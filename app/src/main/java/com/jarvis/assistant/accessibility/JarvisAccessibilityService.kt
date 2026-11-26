package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.app.KeyguardManager
import com.jarvis.assistant.apps.ScreenItem
import com.jarvis.assistant.apps.YouTubeAction
import com.jarvis.assistant.agent.SensitiveActions
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.jarvis.assistant.Settings
import com.jarvis.assistant.service.JarvisService
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The agent's hands and eyes. Android only lets an accessibility service read other apps'
 * screens and inject taps, so every phone action goes through here.
 */
class JarvisAccessibilityService : AccessibilityService() {

    @Volatile private var snapshot: ScreenSnapshot? = null

    private data class ReviewedClick(val node: AccessibilityNodeInfo, val clicked: CountDownLatch = CountDownLatch(1))
    @Volatile private var review: ReviewedClick? = null

    /** The task island shown at the top of the screen while Jarvis works. */
    val island by lazy { com.jarvis.assistant.ui.TaskIsland(this) }

    override fun onServiceConnected() {
        instance = this
        // An enabled accessibility service is a good moment to make sure the listener is running.
        if (Settings(this).wakeWordEnabled) runCatching { JarvisService.startIfSwitchedOn(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pending = review ?: return
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.windowId == pending.node.windowId && event.source == pending.node) pending.clicked.countDown()
    }
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { island.hide() }
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Observation ----

    private fun requireUnlocked() {
        check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the device to continue." }
    }

    fun readScreen(maxElements: Int = 300): ScreenSnapshot {
        requireUnlocked()
        return ScreenReader.capture(this, maxElements).also { snapshot = it }
    }

    private fun element(id: Int): AccessibilityNodeInfo {
        requireUnlocked()
        val seen = snapshot ?: error("Read the screen again.")
        val node = seen.elements[id] ?: error("Element unavailable.")
        check(node.refresh() && node.isVisibleToUser && node.isEnabled &&
            node.windowId == seen.windowId && rootInActiveWindow?.windowId == node.windowId) { "The screen changed." }
        return node
    }

    /** Screenshot as JPEG, scaled so its width is at most [maxWidth]. Returns bytes and the scale used. */
    @RequiresApi(34)
    fun screenshot(maxWidth: Int = 720): Pair<ByteArray, Float> {
        val latch = CountDownLatch(1)
        var result: Pair<ByteArray, Float>? = null
        var error: String? = null
        val before = readScreen()
        val window = before.windowId ?: error("Window unavailable.")
        check(Settings(this).allowScreenshots)
        takeScreenshotOfWindow(window, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                val full = hw?.copy(Bitmap.Config.ARGB_8888, false)
                shot.hardwareBuffer.close()
                if (full == null) { error = "could not decode screenshot"; latch.countDown(); return }
                val scale = (maxWidth.toFloat() / full.width).coerceAtMost(1f)
                val scaled = Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
                val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                result = bytes to scale
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                error = "screenshot failed (code $errorCode)"
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
        val after = readScreen()
        check(before.windowId == after.windowId && before.text == after.text && Settings(this).allowScreenshots) { "Window changed during capture." }
        return result ?: throw IllegalStateException(error ?: "screenshot timed out")
    }

    // ---- Actions ----

    fun tap(id: Int, canAct: () -> Boolean): String {
        val node = element(id)
        val label = node.text?.toString()?.ifBlank { null } ?: node.contentDescription?.toString().orEmpty()
        if (!canAct()) return "Task stopped."
        if (node.isEditable) {
            return if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) "Tapped text field." else "Could not focus field."
        }
        if (!SensitiveActions.canNavigate(label, false) || !node.isClickable) return waitForUserTap(node, label, canAct)
        return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Tapped control." else "Tap failed."
    }

    /** The person performs the final click. A matching event resumes observation exactly once. */
    private fun waitForUserTap(node: AccessibilityNodeInfo, label: String, canAct: () -> Boolean): String {
        var target = node
        while (!target.isClickable) target = target.parent ?: return SensitiveActions.MANUAL
        val pending = ReviewedClick(target)
        review = pending
        try {
            android.os.Handler(mainLooper).post {
                if (canAct() && review === pending) android.widget.Toast.makeText(this,
                    "Review the screen and tap ${label.take(60).ifBlank { "the control" }} to continue.", android.widget.Toast.LENGTH_LONG).show()
            }
            val end = SystemClock.elapsedRealtime() + 30_000
            while (canAct() && SystemClock.elapsedRealtime() < end) {
                requireUnlocked()
                if (pending.clicked.await(150, TimeUnit.MILLISECONDS)) return "Tapped by you."
                if (rootInActiveWindow?.windowId != node.windowId) break
            }
            return SensitiveActions.MANUAL
        } finally {
            review = null
        }
    }

    fun tapVideoResult(id: Int, title: String): String {
        val node = element(id)
        val item = ScreenItem(id, (node.contentDescription ?: node.text)?.toString().orEmpty(), node.isClickable)
        check(node.packageName == YouTubeAction.packageName && YouTubeAction.pickResult(listOf(item)) == item &&
            YouTubeAction.titleOf(item) == title) { "Video result changed." }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Tapped video." else "Tap failed."
    }

    fun typeText(id: Int?, text: String, submit: Boolean): String {
        if (submit) return SensitiveActions.MANUAL
        val seen = snapshot
        val fresh = readScreen()
        check(fresh.foregroundPackage != null && (id == null || seen?.text == fresh.text)) { "Read the field again." }
        val target = id ?: fresh.elements.entries.firstOrNull { it.value.isFocused && it.value.isEditable }?.key
            ?: error("No text field is focused.")
        val node = element(target)
        check(node.isEditable) { "Select an editable field." }
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Could not type into that element (is it a text field?)."
        return "Typed text."
    }

    fun scroll(id: Int?, direction: String): String {
        if (id != null) {
            var target: AccessibilityNodeInfo? = element(id)
            while (target != null && !target.isScrollable) target = target.parent
            val action = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target != null && target.performAction(action)) return "Scrolled $direction."
        }
        val scrollable = readScreen().elements.values.firstOrNull { it.isScrollable }
        val action = if (direction in setOf("down", "right")) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (scrollable?.performAction(action) == true) "Scrolled $direction." else "Scroll unavailable."
    }

    fun pressKey(key: String): String {
        requireUnlocked()
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else return "Screenshots by voice need Android 9 or newer."
            "lock" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return "Locking by voice needs Android 9 or newer."
            else -> return "Unknown key '$key'."
        }
        return if (performGlobalAction(action)) "Pressed $key." else "Could not press $key."
    }

    /** Launches the best-matching installed app by its visible name (or package name). */
    fun openApp(name: String): String {
        requireUnlocked()
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
        val q = name.trim().lowercase()
        val match = apps.firstOrNull { it.first.lowercase() == q || it.second.lowercase() == q }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.first.lowercase().contains(q) || q.contains(it.first.lowercase()) }
            ?: return "No installed app matches '$name'. Installed apps include: " +
                apps.map { it.first }.sorted().take(80).joinToString(", ")
        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return "'${match.first}' has no launch screen."
        // Accessibility services are allowed to start activities from the background.
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return "Opened ${match.first} (${match.second})."
    }

    /** Starts [intent] from the background (accessibility services are allowed to). False if nothing can handle it. */
    fun launch(intent: Intent): Boolean = try {
        requireUnlocked()
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    /**
     * Launches an app. Uses its launcher activity when that is known (works for apps without a default launch intent,
     * cloned apps and some manufacturer apps), otherwise the package's default launch intent.
     * True if Android accepted the launch (see [waitForForeground] to confirm).
     */
    fun openPackage(packageName: String, activity: String? = null): Boolean {
        if (activity != null) {
            val direct = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(packageName, activity).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (launch(direct)) return true
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return launch(intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    /** Waits until [packageName] is the app in front, up to [timeoutMs]. Must not be called on the main thread. */
    fun waitForForeground(packageName: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (rootInActiveWindow?.packageName?.toString() == packageName) return true
            Thread.sleep(150)
        }
        return false
    }

    /** Taps the visible element whose text or description is [text]. */
    fun tapByText(text: String, canAct: () -> Boolean): String {
        val seen = readScreen()
        val matches = seen.elements.filterValues { (it.text ?: it.contentDescription)?.toString().equals(text, ignoreCase = true) }
        val id = matches.keys.singleOrNull() ?: return "Choose the control on your phone."
        return tap(id, canAct)
    }

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set
    }
}

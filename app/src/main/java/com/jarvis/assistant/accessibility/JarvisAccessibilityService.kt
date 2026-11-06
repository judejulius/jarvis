package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
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

    /** The task island shown at the top of the screen while Jarvis works. */
    val island by lazy { com.jarvis.assistant.ui.TaskIsland(this) }

    override fun onServiceConnected() {
        instance = this
        // An enabled accessibility service is a good moment to make sure the listener is running.
        if (Settings(this).wakeWordEnabled) runCatching { JarvisService.startIfSwitchedOn(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { island.hide() }
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---- Observation ----

    fun readScreen(maxElements: Int = 300): ScreenSnapshot = ScreenReader.capture(this, maxElements).also { snapshot = it }

    /** The text or description of element [id] on the last screen read ("" if unknown). */
    fun labelOf(id: Int): String {
        val n = snapshot?.elements?.get(id) ?: return ""
        return (n.text ?: n.contentDescription)?.toString().orEmpty()
    }

    private fun element(id: Int): AccessibilityNodeInfo =
        snapshot?.elements?.get(id)?.also { it.refresh() }
            ?: throw IllegalArgumentException("No element [$id] on the last screen read. Use ids from the latest screen.")

    /** Screenshot as JPEG, scaled so its width is at most [maxWidth]. Returns bytes and the scale used. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun screenshot(maxWidth: Int = 720): Pair<ByteArray, Float> {
        val latch = CountDownLatch(1)
        var result: Pair<ByteArray, Float>? = null
        var error: String? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
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
        return result ?: throw IllegalStateException(error ?: "screenshot timed out")
    }

    // ---- Actions ----

    fun tap(id: Int): String {
        val node = element(id)
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped [$id]."
        val b = Rect().also { node.getBoundsInScreen(it) }
        return if (tapAt(b.centerX().toFloat(), b.centerY().toFloat())) "Tapped [$id] at (${b.centerX()},${b.centerY()})."
        else "Tap on [$id] failed."
    }

    fun longPress(id: Int): String {
        val node = element(id)
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isLongClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "Long-pressed [$id]."
        val b = Rect().also { node.getBoundsInScreen(it) }
        return if (gesture(path(b.exactCenterX(), b.exactCenterY()), 800)) "Long-pressed [$id]." else "Long press failed."
    }

    fun typeText(id: Int?, text: String, submit: Boolean): String {
        val node = when {
            id != null -> element(id)
            else -> findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: throw IllegalStateException("No text field is focused. Pass an element_id.")
        }
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "Could not type into that element (is it a text field?)."
        if (submit) {
            val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return if (ok) "Typed text and pressed enter." else "Typed text, but could not press enter; tap the send/search button instead."
        }
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
        // Fall back to a swipe in the middle of the screen. Content moves opposite to the finger.
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val cy = m.heightPixels / 2f
        val dy = m.heightPixels * 0.3f
        val dx = m.widthPixels * 0.35f
        val ok = when (direction) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy, 350)
            "up" -> swipe(cx, cy - dy, cx, cy + dy, 350)
            "right" -> swipe(cx + dx, cy, cx - dx, cy, 350)
            else -> swipe(cx - dx, cy, cx + dx, cy, 350)
        }
        return if (ok) "Swiped to scroll $direction." else "Scroll failed."
    }

    fun tapAt(x: Float, y: Float): Boolean = gesture(path(x, y), 60)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs)

    fun pressKey(key: String): String {
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
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    /** Launches the app with [packageName]. True if Android accepted the launch (see [waitForForeground] to confirm). */
    fun openPackage(packageName: String): Boolean {
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
    fun tapByText(text: String): String {
        val root = rootInActiveWindow ?: return "I can't read the screen right now."
        val matches = root.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }
        val exact = matches.filter { (it.text ?: it.contentDescription)?.toString().equals(text, ignoreCase = true) }
        val node = (exact.ifEmpty { matches }).firstOrNull() ?: return "I can't see anything labelled $text on this screen."
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped $text."
        val b = Rect().also { node.getBoundsInScreen(it) }
        return if (tapAt(b.centerX().toFloat(), b.centerY().toFloat())) "Tapped $text." else "Tapping $text failed."
    }

    private fun path(x: Float, y: Float) = Path().apply { moveTo(x, y); lineTo(x + 1, y + 1) }

    private fun gesture(path: Path, durationMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchGesture(desc, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (!dispatched) return false
        latch.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set
    }
}

package com.jarvis.assistant.agent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import android.util.Log
import android.view.KeyEvent
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.memory.MemoryManager
import java.util.Calendar
import java.util.Locale

data class FastCommandResult(
    val handled: Boolean,
    val success: Boolean = true,
    val summary: String = ""
)

/**
 * Deterministic fast-command router.
 * Executes simple phone commands, app launches, system toggles, media keys,
 * navigation, alarms, and calls in < 50ms without invoking the LLM or network.
 */
object FastCommandRouter {

    private const val TAG = "FastCommandRouter"

    fun execute(context: Context, rawTask: String): FastCommandResult {
        val task = rawTask.trim()
        val lower = task.lowercase(Locale.ROOT)
        val memory = MemoryManager.get(context)

        // Check for learned command corrections first
        val corrected = memory.findCorrection(lower)
        val effectiveTask = corrected ?: lower

        // 1. Direct Memory / Personalization Commands
        handleMemoryCommands(context, task, lower, memory)?.let { return it }

        // 2. Flashlight / Torch
        handleFlashlight(context, effectiveTask)?.let { return it }

        // 3. Volume & Audio Controls
        handleVolume(context, effectiveTask)?.let { return it }

        // 4. Media Playback Controls
        handleMedia(context, effectiveTask)?.let { return it }

        // 5. Navigation & Accessibility Keys
        handleNavigation(effectiveTask)?.let { return it }

        // 6. Camera
        handleCamera(context, effectiveTask)?.let { return it }

        // 7. System Settings
        handleSettings(context, effectiveTask)?.let { return it }

        // 8. Alarms & Timers
        handleAlarmsAndTimers(context, effectiveTask)?.let { return it }

        // 9. Phone Calls
        handlePhoneCalls(context, effectiveTask)?.let { return it }

        // 10. Web Search
        handleWebSearch(context, effectiveTask)?.let { return it }

        // 11. Fast App Launching (with Hindi, Hinglish, & alias matching)
        handleAppLaunch(context, effectiveTask, memory)?.let { return it }

        return FastCommandResult(handled = false)
    }

    // ---- 1. Memory Commands ----
    private fun handleMemoryCommands(context: Context, raw: String, lower: String, memory: MemoryManager): FastCommandResult? {
        // "remember that [fact]" / "yaad rakhna ki [fact]"
        val rememberRegex = Regex("""^(?:remember\s+(?:that\s+)?|yaad\s+(?:rakhna|rakho)\s+(?:ki\s+)?)(.+)$""")
        rememberRegex.find(lower)?.let { match ->
            val fact = match.groupValues[1].trim()
            val colonIdx = fact.indexOf("is")
            val (key, value) = if (colonIdx > 0 && colonIdx < fact.length - 3) {
                fact.substring(0, colonIdx).trim() to fact.substring(colonIdx + 2).trim()
            } else {
                "fact" to fact
            }
            memory.saveFact(key, value)
            return FastCommandResult(true, true, "Got it, I'll remember that.")
        }

        // "when I say [X] open [Y]" / "jab main [X] bolu tab [Y] kholna"
        val aliasRegex1 = Regex("""^when\s+i\s+say\s+['"]?([^'"]+)['"]?\s+(?:open|launch)\s+['"]?([^'"]+)['"]?$""")
        val aliasRegex2 = Regex("""^jab\s+main\s+['"]?([^'"]+)['"]?\s+bolu\s+tab\s+['"]?([^'"]+)['"]?\s+kholna$""")

        val aliasMatch = aliasRegex1.find(lower) ?: aliasRegex2.find(lower)
        if (aliasMatch != null) {
            val trigger = aliasMatch.groupValues[1].trim()
            val target = aliasMatch.groupValues[2].trim()
            memory.saveAlias(trigger, target)
            return FastCommandResult(true, true, "Got it. When you say '$trigger', I will open '$target'.")
        }

        return null
    }

    // ---- 2. Flashlight ----
    private fun handleFlashlight(context: Context, lower: String): FastCommandResult? {
        val onMatch = Regex("""\b(turn\s+on\s+(?:the\s+)?(?:flashlight|torch)|(?:flashlight|torch)\s+on|torch\s+jalao|torch\s+on\s+karo|batti\s+jalao)\b""").containsMatchIn(lower)
        val offMatch = Regex("""\b(turn\s+off\s+(?:the\s+)?(?:flashlight|torch)|(?:flashlight|torch)\s+off|torch\s+band\s+karo|torch\s+off\s+karo|batti\s+bujhao)\b""").containsMatchIn(lower)

        if (!onMatch && !offMatch) return null

        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            val cameraId = cm?.cameraIdList?.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm?.cameraIdList?.firstOrNull()

            if (cm != null && cameraId != null) {
                cm.setTorchMode(cameraId, onMatch)
                FastCommandResult(true, true, if (onMatch) "Flashlight turned on." else "Flashlight turned off.")
            } else {
                FastCommandResult(true, false, "Flashlight is not available on this device.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggle flashlight failed", e)
            FastCommandResult(true, false, "Could not control the flashlight.")
        }
    }

    // ---- 3. Volume ----
    private fun handleVolume(context: Context, lower: String): FastCommandResult? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null

        val isUp = Regex("""\b(increase\s+volume|volume\s+up|sound\s+badhao|volume\s+badha\s+do|aawaz\s+badhao|louder)\b""").containsMatchIn(lower)
        val isDown = Regex("""\b(decrease\s+volume|volume\s+down|sound\s+kam\s+karo|volume\s+kam\s+karo|aawaz\s+kam\s+karo|lower\s+volume|softer)\b""").containsMatchIn(lower)
        val isMute = Regex("""\b(mute(?:\s+volume)?|silent(?:\s+kar\s+do)?|volume\s+mute\s+karo)\b""").containsMatchIn(lower)
        val isUnmute = Regex("""\b(unmute(?:\s+volume)?|unmute\s+karo)\b""").containsMatchIn(lower)
        val isMax = Regex("""\b(max\s+volume|full\s+volume|volume\s+full(?:\s+kar\s+do)?)\b""").containsMatchIn(lower)

        return when {
            isMax -> {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                FastCommandResult(true, true, "Volume set to maximum.")
            }
            isMute -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                FastCommandResult(true, true, "Muted.")
            }
            isUnmute -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                FastCommandResult(true, true, "Unmuted.")
            }
            isUp -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                FastCommandResult(true, true, "Volume increased.")
            }
            isDown -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                FastCommandResult(true, true, "Volume decreased.")
            }
            else -> null
        }
    }

    // ---- 4. Media Controls ----
    private fun handleMedia(context: Context, lower: String): FastCommandResult? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null

        val isPlay = Regex("""\b(play\s+music|resume\s+music|play\s+song|gana\s+bajao|gana\s+chalao|music\s+chalao)\b""").containsMatchIn(lower)
        val isPause = Regex("""\b(pause\s+music|stop\s+music|pause\s+song|gana\s+roko|gana\s+band\s+karo|music\s+band\s+karo)\b""").containsMatchIn(lower)
        val isNext = Regex("""\b(next\s+song|next\s+track|skip\s+song|agla\s+gana)\b""").containsMatchIn(lower)
        val isPrev = Regex("""\b(previous\s+song|previous\s+track|pichla\s+gana)\b""").containsMatchIn(lower)

        return when {
            isPlay -> {
                sendMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY)
                FastCommandResult(true, true, "Playing music.")
            }
            isPause -> {
                sendMediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
                FastCommandResult(true, true, "Paused music.")
            }
            isNext -> {
                sendMediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT)
                FastCommandResult(true, true, "Next track.")
            }
            isPrev -> {
                sendMediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                FastCommandResult(true, true, "Previous track.")
            }
            else -> null
        }
    }

    private fun sendMediaKey(am: AudioManager, keyCode: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ---- 5. Navigation & Accessibility Keys ----
    private fun handleNavigation(lower: String): FastCommandResult? {
        val a11y = JarvisAccessibilityService.instance ?: return null

        val isBack = Regex("""\b(go\s+back|back\s+jao|piche\s+jao|back\s+karo)\b""").containsMatchIn(lower) || lower == "back"
        val isHome = Regex("""\b(go\s+home|home\s+screen|home\s+par\s+jao)\b""").containsMatchIn(lower) || lower == "home"
        val isRecents = Regex("""\b(recent\s+apps|recent\s+tasks|recents|recent\s+kholo)\b""").containsMatchIn(lower)
        val isNotifs = Regex("""\b(notifications|open\s+notifications|notification\s+panel|notifications\s+dikhao)\b""").containsMatchIn(lower)
        val isQuickSettings = Regex("""\b(quick\s+settings|open\s+quick\s+settings)\b""").containsMatchIn(lower)
        val isScrollDown = Regex("""\b(scroll\s+down|niche\s+scroll\s+karo|niche\s+jao)\b""").containsMatchIn(lower)
        val isScrollUp = Regex("""\b(scroll\s+up|upar\s+scroll\s+karo|upar\s+jao)\b""").containsMatchIn(lower)
        val isScreenshot = Regex("""\b(take\s+screenshot|screenshot\s+lo|screenshot\s+khicho|take\s+a\s+screenshot)\b""").containsMatchIn(lower)

        return when {
            isBack -> {
                a11y.pressKey("back")
                FastCommandResult(true, true, "Went back.")
            }
            isHome -> {
                a11y.pressKey("home")
                FastCommandResult(true, true, "Going home.")
            }
            isRecents -> {
                a11y.pressKey("recents")
                FastCommandResult(true, true, "Showing recent apps.")
            }
            isNotifs -> {
                a11y.pressKey("notifications")
                FastCommandResult(true, true, "Opening notifications.")
            }
            isQuickSettings -> {
                a11y.pressKey("quick_settings")
                FastCommandResult(true, true, "Opening quick settings.")
            }
            isScrollDown -> {
                a11y.scroll(null, "down")
                FastCommandResult(true, true, "Scrolled down.")
            }
            isScrollUp -> {
                a11y.scroll(null, "up")
                FastCommandResult(true, true, "Scrolled up.")
            }
            isScreenshot -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val ok = a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    FastCommandResult(true, ok, if (ok) "Screenshot taken." else "Could not take screenshot.")
                } else {
                    FastCommandResult(true, false, "Screenshot requires Android 11 or higher.")
                }
            }
            else -> null
        }
    }

    // ---- 6. Camera ----
    private fun handleCamera(context: Context, lower: String): FastCommandResult? {
        val isCamera = Regex("""\b(open\s+camera|camera\s+kholo|take\s+photo|take\s+picture|photo\s+khicho)\b""").containsMatchIn(lower)
        if (!isCamera) return null

        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            FastCommandResult(true, true, "Opening camera.")
        } catch (e: Exception) {
            Log.e(TAG, "open camera failed", e)
            FastCommandResult(true, false, "Could not open camera.")
        }
    }

    // ---- 7. System Settings ----
    private fun handleSettings(context: Context, lower: String): FastCommandResult? {
        val isWifi = Regex("""\b(open\s+wi-?fi\s+settings|wi-?fi\s+settings)\b""").containsMatchIn(lower)
        val isBt = Regex("""\b(open\s+bluetooth\s+settings|bluetooth\s+settings)\b""").containsMatchIn(lower)
        val isGeneralSettings = Regex("""\b(open\s+settings|settings\s+kholo)\b""").containsMatchIn(lower)

        return when {
            isWifi -> {
                val intent = Intent(AndroidSettings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                FastCommandResult(true, true, "Opening Wi-Fi settings.")
            }
            isBt -> {
                val intent = Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                FastCommandResult(true, true, "Opening Bluetooth settings.")
            }
            isGeneralSettings -> {
                val intent = Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                FastCommandResult(true, true, "Opening Settings.")
            }
            else -> null
        }
    }

    // ---- 8. Alarms & Timers ----
    private fun handleAlarmsAndTimers(context: Context, lower: String): FastCommandResult? {
        // Alarms: "set alarm for 7:30 am", "set alarm for 6 am", "alarm lagao 7 baje"
        val alarmRegex = Regex("""(?:set\s+(?:an\s+)?alarm\s+for|alarm\s+lagao)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|baje)?""")
        alarmRegex.find(lower)?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase()

            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0

            return try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Alarm")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val displayTime = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
                FastCommandResult(true, true, "Alarm set for $displayTime.")
            } catch (e: Exception) {
                FastCommandResult(true, false, "Could not set alarm.")
            }
        }

        // Timers: "set timer for 10 minutes", "timer lagao 5 minute"
        val timerRegex = Regex("""(?:set\s+(?:a\s+)?timer\s+for|timer\s+lagao)\s+(\d+)\s*(?:minute|minutes|min)""")
        timerRegex.find(lower)?.let { match ->
            val minutes = match.groupValues[1].toIntOrNull() ?: return@let
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Timer")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                FastCommandResult(true, true, "Timer set for $minutes minutes.")
            } catch (e: Exception) {
                FastCommandResult(true, false, "Could not set timer.")
            }
        }

        return null
    }

    // ---- 9. Phone Calls ----
    private fun handlePhoneCalls(context: Context, lower: String): FastCommandResult? {
        val callRegex = Regex("""^(?:call|dial|(?:phone\s+karo))\s+(.+)$""")
        val match = callRegex.find(lower) ?: return null
        val target = match.groupValues[1].replace(Regex("""\b(ko|please)\b"""), "").trim()
        if (target.isEmpty()) return null

        // Check if digits
        val isDigits = target.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
        return if (isDigits) {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${target.filter { it.isDigit() || it == '+' }}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            FastCommandResult(true, true, "Dialing $target.")
        } else {
            // Attempt to look up contact by name
            val number = lookupContactNumber(context, target)
            if (number != null) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                FastCommandResult(true, true, "Calling $target.")
            } else {
                val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
                FastCommandResult(true, true, "Opening phone dialer for $target.")
            }
        }
    }

    private fun lookupContactNumber(context: Context, name: String): String? {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- 10. Web Search ----
    private fun handleWebSearch(context: Context, lower: String): FastCommandResult? {
        val searchRegex = Regex("""^(?:search(?:\s+for)?|search\s+google\s+for|google\s+par\s+search\s+karo|dhoondo)\s+(.+)$""")
        val match = searchRegex.find(lower) ?: return null
        val query = match.groupValues[1].replace(Regex("""\b(on\s+google|ko)\b"""), "").trim()
        if (query.isEmpty()) return null

        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            FastCommandResult(true, true, "Searching Google for $query.")
        } catch (e: Exception) {
            FastCommandResult(true, false, "Could not open web search.")
        }
    }

    // ---- 11. Fast App Launching ----
    private fun handleAppLaunch(context: Context, lower: String, memory: MemoryManager): FastCommandResult? {
        val trimmed = lower.replace(Regex("""^(?:can\s+you\s+|could\s+you\s+|please\s+|just\s+)+"""), "").trim()

        // App launch pattern detection (English, Hindi, Hinglish)
        val patterns = listOf(
            Regex("""^(?:open|launch|start|switch\s+to)\s+(?:the\s+)?(.+?)(?:\s+app)?$"""),
            Regex("""^(.+?)\s+(?:kholo|khol\s+do|open\s+karo|chalao|chala\s+do|start\s+karo)$"""),
            Regex("""^(?:open|launch)\s+(.+)$""")
        )

        var appQuery: String? = null
        for (pattern in patterns) {
            val m = pattern.find(trimmed)
            if (m != null) {
                appQuery = m.groupValues[1].trim()
                break
            }
        }

        // If no explicit prefix/suffix, also check if the whole input is a known app or alias (e.g. "YouTube", "WhatsApp", "YT")
        if (appQuery.isNullOrBlank()) {
            if (trimmed.split(Regex("\\s+")).size <= 2 && trimmed.isNotEmpty()) {
                appQuery = trimmed
            } else {
                return null
            }
        }

        val normalized = normalizeAppName(appQuery)
        val resolvedName = memory.resolveAppAlias(normalized)
        val q = resolvedName.lowercase(Locale.ROOT)

        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }

        val match = apps.firstOrNull { it.first.lowercase(Locale.ROOT) == q || it.second.lowercase(Locale.ROOT) == q }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).startsWith(q) }
            ?: apps.firstOrNull { it.first.lowercase(Locale.ROOT).contains(q) || q.contains(it.first.lowercase(Locale.ROOT)) }

        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.second)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                return FastCommandResult(true, true, "Opening ${match.first}.")
            }
        }

        // Known well-known package fallback in case app label is localized in device language (e.g. Hindi)
        val wellKnownPackage = when (q) {
            "youtube" -> "com.google.android.youtube"
            "whatsapp" -> "com.whatsapp"
            "instagram" -> "com.instagram.android"
            "chrome" -> "com.android.chrome"
            "gmail" -> "com.google.android.gm"
            "maps", "google maps" -> "com.google.android.apps.maps"
            "play store" -> "com.android.vending"
            "spotify" -> "com.spotify.music"
            "telegram" -> "org.telegram.messenger"
            else -> null
        }

        if (wellKnownPackage != null) {
            val intent = pm.getLaunchIntentForPackage(wellKnownPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                val label = q.replaceFirstChar { it.uppercase() }
                return FastCommandResult(true, true, "Opening $label.")
            }
        }

        return null
    }

    private fun normalizeAppName(name: String): String {
        val clean = name.lowercase().trim()
        return when (clean) {
            "yt", "you tube", "utube" -> "youtube"
            "insta", "insta gram" -> "instagram"
            "wp", "whats app", "what's app", "watssap", "watsap" -> "whatsapp"
            "fb" -> "facebook"
            "g mail" -> "gmail"
            "photo", "photos" -> "gallery"
            "message", "messages", "sms" -> "messages"
            "calc" -> "calculator"
            else -> clean
        }
    }
}

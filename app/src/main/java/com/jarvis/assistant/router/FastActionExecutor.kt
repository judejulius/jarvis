package com.jarvis.assistant.router

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import com.jarvis.assistant.Settings
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.agent.SensitiveActions
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.apps.AppAction
import com.jarvis.assistant.apps.ScreenItem
import com.jarvis.assistant.apps.YouTubeAction
import com.jarvis.assistant.contacts.ContactEntry
import com.jarvis.assistant.contacts.ContactMatcher
import com.jarvis.assistant.contacts.ContactResolver
import com.jarvis.assistant.contacts.PermissionActivity
import com.jarvis.assistant.contacts.PhoneNumber
import com.jarvis.assistant.memory.MemoryStore
import com.jarvis.assistant.memory.SensitiveFilter
import com.jarvis.assistant.runtime.EmbeddedRuntime
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import android.provider.Settings as AndroidSettings

/**
 * Runs [FastCommand]s with direct Android APIs: intents, AudioManager, the camera flash, media keys and the
 * accessibility service. No language model is involved. Each action reports what actually happened, and checks the
 * result where Android lets it (app in front, volume level, torch state, music playing) before saying it worked.
 *
 * Call [execute] from a worker thread: some actions wait briefly to verify the outcome.
 */
class FastActionExecutor(
    context: Context,
    private val settings: Settings,
    private val memory: () -> MemoryStore?,
) {
    /**
     * [handled] false means "not something I can do directly": the caller should hand the request to the model.
     * [app] and [how] tell which app was opened and how it was matched.
     */
    data class Result(
        val handled: Boolean,
        val success: Boolean,
        val message: String,
        val app: AppEntry? = null,
        val how: String = "",
        /** True when the action cannot be taken back (a call was placed). */
        val irreversible: Boolean = false,
    )

    private val app = context.applicationContext
    private val a11y get() = JarvisAccessibilityService.instance

    @Volatile private var appCache: Pair<Long, List<AppEntry>>? = null

    @Volatile private var torchOn = false
    @Volatile private var torchWatching = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchOn = enabled
        }
    }

    private val resolver by lazy { ContactResolver(app) }

    /**
     * [asker] speaks a question and returns what the user answers (null if nothing). It is used for "which one?" and
     * "call her?" questions. Without it, actions that need an answer do nothing risky.
     */
    fun execute(cmd: FastCommand, asker: ((String) -> String?)? = null): Result = try {
        run(cmd, asker)
    } catch (e: SecurityException) {
        Log.w(TAG, "${cmd.javaClass.simpleName} not permitted", e)
        Result(true, false, "Android didn't allow that.")
    } catch (e: Exception) {
        Log.w(TAG, "${cmd.javaClass.simpleName} failed", e)
        Result(true, false, "Sorry, that didn't work.")
    }

    private fun run(cmd: FastCommand, asker: ((String) -> String?)?): Result = when (cmd) {
        is FastCommand.OpenApp -> openApp(cmd.query, asker)
        is FastCommand.Press -> press(cmd.key)
        is FastCommand.Scroll -> withPhoneControl { svc ->
            val msg = svc.scroll(null, cmd.direction)
            Result(true, !msg.contains("failed"), if (msg.contains("failed")) "I couldn't scroll." else "Scrolled ${cmd.direction}.")
        }
        is FastCommand.Volume -> volume(cmd)
        is FastCommand.Flashlight -> flashlight(cmd)
        is FastCommand.Media -> media(cmd.action)
        is FastCommand.WebSearch -> webSearch(cmd)
        is FastCommand.OpenSettings -> openSettings(cmd)
        FastCommand.OpenCamera -> {
            val ok = startIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            Result(true, ok, if (ok) "Opening the camera." else "I couldn't open the camera.")
        }
        is FastCommand.SetAlarm -> setAlarm(cmd)
        is FastCommand.SetTimer -> setTimer(cmd)
        is FastCommand.Dial -> {
            val ok = startIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(cmd.number))))
            Result(true, ok, if (ok) "The dialer is open with that number. Tap call to place it." else "I couldn't open the dialer.")
        }
        is FastCommand.CallContact -> callContact(cmd.name, asker)
        is FastCommand.PlayYouTube -> playYouTube(cmd.query)
        is FastCommand.TapText -> withPhoneControl { svc ->
            val msg = svc.tapByText(cmd.text)
            Result(true, msg.startsWith("Tapped"), msg)
        }
        is FastCommand.TypeText -> withPhoneControl { svc ->
            val msg = try { svc.typeText(null, cmd.text, false) } catch (e: IllegalStateException) { e.message ?: "No text field is focused." }
            Result(true, msg.startsWith("Typed"), if (msg.startsWith("Typed")) "Typed it." else msg)
        }
        FastCommand.Screenshot -> withPhoneControl { svc ->
            val msg = svc.pressKey("screenshot")
            Result(true, msg.startsWith("Pressed"), if (msg.startsWith("Pressed")) "Taking a screenshot." else "I couldn't take a screenshot.")
        }
        FastCommand.LockScreen -> withPhoneControl { svc ->
            val msg = svc.pressKey("lock")
            Result(true, msg.startsWith("Pressed"), if (msg.startsWith("Pressed")) "Locking the screen." else "I couldn't lock the screen.")
        }
        is FastCommand.Ask -> ask(cmd.kind)
        is FastCommand.SetMode -> setMode(cmd.mode)
        is FastCommand.SetLanguage -> setLanguage(cmd.lang)
        is FastCommand.RuntimeControl -> runtimeControl(cmd.action)
    }

    // ---- Apps ----

    /** Launchable apps, cached for a short time so repeated commands are instant. */
    fun installedApps(): List<AppEntry> {
        val now = SystemClock.elapsedRealtime()
        appCache?.let { if (now - it.first < APP_CACHE_MS) return it.second }
        val pm = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.packageName }
        appCache = now to list
        return list
    }

    private fun openApp(query: String, asker: ((String) -> String?)?): Result {
        val m = AppMatcher.match(query, installedApps()) { phrase -> runCatching { memory()?.appAlias(phrase) }.getOrNull() }
        val entry = m.app
        if (entry == null) {
            if (m.ambiguous.isNotEmpty()) {
                return Result(true, false, "Which one: ${m.ambiguous.joinToString(" or ") { it.label }}? Please say the full name.")
            }
            m.notInstalled?.let { return Result(true, false, "${it.replaceFirstChar { c -> c.uppercase() }} isn't installed on this phone.") }
            return Result(false, false, "")
        }
        // A match that only "sounds like" the name is confirmed first. A yes teaches Jarvis the name for next time.
        if (m.how == "sound" && asker != null) {
            val answer = asker("Did you mean ${entry.label}?")
            if (!SensitiveActions.isYes(answer)) return Result(true, true, "Okay, I won't open it.")
            learnAlias(query, entry)
        }
        val svc = a11y
        val started = if (svc != null) svc.openPackage(entry.packageName, entry.activity) else startLaunchIntent(entry.packageName)
        if (!started) return Result(true, false, "I couldn't open ${entry.label}.", entry, m.how)
        if (svc == null) {
            return Result(true, false, "I tried to open ${entry.label}. Turn on phone control in Jarvis so I can open apps reliably.", entry, m.how)
        }
        val shown = svc.waitForForeground(entry.packageName, FOREGROUND_WAIT_MS)
        val msg = if (shown) "Opened ${entry.label}." else "I asked Android to open ${entry.label}, but I couldn't confirm it came to the front."
        return Result(true, true, msg, entry, m.how)
    }

    private fun startLaunchIntent(pkg: String): Boolean {
        val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return startIntent(intent)
    }

    // ---- Navigation and screen ----

    private fun press(key: String): Result = withPhoneControl { svc ->
        val msg = svc.pressKey(key)
        val ok = msg.startsWith("Pressed")
        val say = when (key) {
            "back" -> "Going back."
            "home" -> "Home."
            "recents" -> "Recent apps."
            "notifications" -> "Notifications."
            "quick_settings" -> "Quick settings."
            else -> "Done."
        }
        Result(true, ok, if (ok) say else "I couldn't do that.")
    }

    private inline fun withPhoneControl(block: (JarvisAccessibilityService) -> Result): Result {
        val svc = a11y ?: return Result(true, false, "Please turn on phone control in the Jarvis app first.")
        return block(svc)
    }

    // ---- Sound, flashlight, media ----

    private fun volume(cmd: FastCommand.Volume): Result {
        val am = app.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        when (cmd.change) {
            VolumeChange.UP -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            VolumeChange.DOWN -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            VolumeChange.MUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            VolumeChange.UNMUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            VolumeChange.SET -> {
                val level = ((cmd.percent ?: 50) * max / 100f).roundToInt().coerceIn(0, max)
                am.setStreamVolume(stream, level, AudioManager.FLAG_SHOW_UI)
            }
        }
        val percent = (am.getStreamVolume(stream) * 100f / max).roundToInt()
        return Result(true, true, if (am.isStreamMute(stream)) "Muted." else "Volume is $percent percent.")
    }

    private fun flashlight(cmd: FastCommand.Flashlight): Result {
        val cm = app.getSystemService(CameraManager::class.java)
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return Result(true, false, "This phone has no flashlight I can use.")
        if (!torchWatching) {
            cm.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
            torchWatching = true
            Thread.sleep(200) // the callback reports the current state shortly after registering
        }
        val want = cmd.on ?: !torchOn
        cm.setTorchMode(cameraId, want)
        waitUntil(800) { torchOn == want }
        return if (torchOn == want) Result(true, true, if (want) "Flashlight on." else "Flashlight off.")
        else Result(true, false, "I couldn't turn the flashlight ${if (want) "on" else "off"}.")
    }

    private fun media(action: MediaAction): Result {
        val am = app.getSystemService(AudioManager::class.java)
        val playing = am.isMusicActive
        if (!playing && action != MediaAction.PLAY) {
            return Result(true, action == MediaAction.PAUSE, "Nothing is playing right now.")
        }
        val code = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return when (action) {
            MediaAction.PAUSE ->
                if (waitUntil(900) { !am.isMusicActive }) Result(true, true, "Paused.")
                else Result(true, false, "I sent pause, but music is still playing.")
            MediaAction.PLAY ->
                if (playing || waitUntil(1500) { am.isMusicActive }) Result(true, true, "Playing.")
                else Result(true, false, "I sent play, but nothing started. Open your music app first.")
            MediaAction.NEXT -> Result(true, true, "Next track.")
            MediaAction.PREVIOUS -> Result(true, true, "Previous track.")
        }
    }

    private fun waitUntil(ms: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (true) {
            if (condition()) return true
            if (SystemClock.elapsedRealtime() >= end) return false
            Thread.sleep(60)
        }
    }

    // ---- Search, settings, clock ----

    private fun webSearch(cmd: FastCommand.WebSearch): Result {
        val q = URLEncoder.encode(cmd.query, "UTF-8")
        val youtube = cmd.site == "youtube"
        val url = if (youtube) "https://www.youtube.com/results?search_query=$q" else "https://www.google.com/search?q=$q"
        val ok = startIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        val where = if (youtube) "YouTube" else "Google"
        return Result(true, ok, if (ok) "Searching $where for ${cmd.query}." else "I couldn't start the search.")
    }

    private fun openSettings(cmd: FastCommand.OpenSettings): Result {
        val action = when (cmd.page) {
            "wifi" -> AndroidSettings.ACTION_WIFI_SETTINGS
            "bluetooth" -> AndroidSettings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS
            "display" -> AndroidSettings.ACTION_DISPLAY_SETTINGS
            "sound" -> AndroidSettings.ACTION_SOUND_SETTINGS
            "location" -> AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS
            "hotspot", "data" -> AndroidSettings.ACTION_WIRELESS_SETTINGS
            "apps" -> AndroidSettings.ACTION_APPLICATION_SETTINGS
            "storage" -> AndroidSettings.ACTION_INTERNAL_STORAGE_SETTINGS
            "accessibility" -> AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS
            "developer" -> AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "date" -> AndroidSettings.ACTION_DATE_SETTINGS
            "language" -> AndroidSettings.ACTION_LOCALE_SETTINGS
            "notification" -> "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"
            else -> AndroidSettings.ACTION_SETTINGS
        }
        val ok = startIntent(Intent(action)) || startIntent(Intent(AndroidSettings.ACTION_SETTINGS))
        val name = cmd.page.replaceFirstChar { it.uppercase() }
        return when {
            !ok -> Result(true, false, "I couldn't open settings.")
            cmd.toggleRequested -> Result(true, true, "Android doesn't let apps switch $name directly, so I opened its settings.")
            else -> Result(true, true, "Opening $name settings.")
        }
    }

    private fun setAlarm(cmd: FastCommand.SetAlarm): Result {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, cmd.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val ok = startIntent(intent)
        val h12 = if (cmd.hour % 12 == 0) 12 else cmd.hour % 12
        val label = "%d:%02d %s".format(Locale.US, h12, cmd.minute, if (cmd.hour < 12) "AM" else "PM")
        return Result(true, ok, if (ok) "I've asked your clock app to set an alarm for $label." else "I couldn't find a clock app to set the alarm.")
    }

    private fun setTimer(cmd: FastCommand.SetTimer): Result {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val ok = startIntent(intent)
        val minutes = cmd.seconds / 60
        val label = when {
            cmd.seconds % 3600 == 0 -> "${cmd.seconds / 3600} hour"
            minutes > 0 && cmd.seconds % 60 == 0 -> "$minutes minute"
            else -> "${cmd.seconds} second"
        }
        return Result(true, ok, if (ok) "I've asked your clock app to start a $label timer." else "I couldn't find a clock app to start the timer.")
    }

    // ---- Actions inside other apps ----

    /**
     * Searches YouTube in the YouTube app, taps the first real video result and checks whether sound starts. It reports
     * what it could confirm: a result picked, and audio playing. YouTube may show an ad first, ask to sign in, or change
     * its screens; in those cases the answer says so instead of claiming playback.
     */
    private fun playYouTube(rawQuery: String): Result {
        val query = YouTubeAction.cleanQuery(rawQuery)
        val svc = a11y ?: return Result(true, false, "Please turn on phone control in the Jarvis app first.")
        val installed = installedApps().any { it.packageName == YouTubeAction.packageName }
        if (!installed) {
            val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
            val ok = startIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            return Result(true, ok, if (ok) "YouTube isn't installed, so I searched for $query in the browser. I can't start playback there." else "I couldn't open YouTube.")
        }
        val search = Intent(Intent.ACTION_SEARCH).setPackage(YouTubeAction.packageName).putExtra(android.app.SearchManager.QUERY, query)
        if (!svc.launch(search)) return Result(true, false, "I couldn't open YouTube's search.")
        if (!svc.waitForForeground(YouTubeAction.packageName, 6_000)) {
            return Result(true, false, "I asked YouTube to search for $query, but I couldn't confirm that it opened.")
        }
        val pick = waitForResult(svc, YouTubeAction, 8_000)
            ?: return Result(true, false, "YouTube is showing results for $query, but I couldn't pick a video myself. It's on the screen.")
        val tapped = svc.tap(pick.first)
        if (!tapped.startsWith("Tapped")) return Result(true, false, "I found a video but couldn't tap it.")
        val am = app.getSystemService(AudioManager::class.java)
        val title = pick.second
        return if (waitUntil(9_000) { am.isMusicActive }) Result(true, true, "Playing $title.")
        else Result(true, false, "I opened $title, but I can't confirm that it's playing. It may be loading or showing an ad.")
    }

    /** Reads the screen every half second until [action] finds a result to tap. Returns the element id and a title. */
    private fun waitForResult(svc: JarvisAccessibilityService, action: AppAction, timeoutMs: Long): Pair<Int, String>? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val shot = svc.readScreen(150)
            if (shot.foregroundPackage == action.packageName) {
                val items = shot.elements.map { (id, node) ->
                    ScreenItem(id, (node.contentDescription ?: node.text)?.toString().orEmpty(), node.isClickable)
                }
                action.pickResult(items)?.let { return it.id to action.titleOf(it) }
            }
            Thread.sleep(500)
        }
        return null
    }

    // ---- Calling ----

    private fun askForPermissions() {
        startIntent(PermissionActivity.intent(app))
    }

    private fun callContact(name: String, asker: ((String) -> String?)?): Result {
        if (!resolver.hasContactsPermission()) {
            askForPermissions()
            return Result(true, false, "I need permission to read your contacts before I can call $name. I've opened the permission screen.")
        }
        val match = ContactMatcher.find(name, resolver.contacts())
        val contact: ContactEntry = when {
            match.matches.isEmpty() -> return Result(true, false, "I couldn't find anyone called $name in your contacts.")
            match.ambiguous -> {
                val options = match.matches.joinToString(" or ") { it.name }
                if (asker == null) return Result(true, false, "$name matches $options. Please say the full name.")
                val answer = asker("Which one: $options?")
                ContactMatcher.pick(answer.orEmpty(), match.matches)
                    ?: return Result(true, false, "I'm not sure which one you meant, so I haven't called anyone.")
            }
            else -> match.matches.single()
        }
        val number = ContactMatcher.bestNumber(contact) ?: return Result(true, false, "${contact.name} has no phone number saved.")

        // A call is never placed on a guess: sound-alike matches are always confirmed, and so is everything else unless
        // the user turned confirmations off. Without a way to ask, the dialer is opened instead of calling.
        val weak = match.how in setOf("fuzzy", "sound", "prefix")
        if (settings.confirmSensitive || weak) {
            if (asker == null) return dialInstead(contact, number)
            val answer = asker("Call ${ContactMatcher.describe(contact, number)}?")
            if (!SensitiveActions.isYes(answer)) return Result(true, true, "Okay, I won't call.")
        }
        return placeCall(contact, number)
    }

    private fun uriFor(n: PhoneNumber) = Uri.parse("tel:" + Uri.encode(n.number.filter { it.isDigit() || it == '+' }))

    private fun dialInstead(contact: ContactEntry, number: PhoneNumber): Result {
        val ok = startIntent(Intent(Intent.ACTION_DIAL, uriFor(number)))
        return Result(true, ok, if (ok) "I opened the dialer with ${contact.name}'s number. Tap call to place it." else "I couldn't open the dialer.")
    }

    // CALL_PHONE is checked through ContactResolver.hasCallPermission() just below; lint cannot see through that.
    @SuppressLint("MissingPermission")
    private fun placeCall(contact: ContactEntry, number: PhoneNumber): Result {
        if (!resolver.hasCallPermission()) {
            askForPermissions()
            val r = dialInstead(contact, number)
            return r.copy(message = if (r.success) "I'm not allowed to place calls yet, so I opened the dialer with ${contact.name}'s number. Tap call." else r.message)
        }
        if (!startIntent(Intent(Intent.ACTION_CALL, uriFor(number)))) return Result(true, false, "I couldn't start the call to ${contact.name}.")
        if (!resolver.hasPhoneStatePermission()) {
            return Result(true, true, "I've asked Android to call ${contact.name}. I can't confirm that it connected.", irreversible = true)
        }
        return if (waitUntil(7_000) { resolver.inCall() }) Result(true, true, "Calling ${contact.name}.", irreversible = true)
        else Result(true, false, "I asked Android to call ${contact.name}, but the call didn't start.", irreversible = true)
    }

    // ---- Things Jarvis can answer itself ----

    private fun ask(kind: QueryKind): Result = when (kind) {
        QueryKind.TIME -> Result(true, true, "It's ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}.")
        QueryKind.DATE -> Result(true, true, "Today is ${SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())}.")
        QueryKind.BATTERY -> {
            val bm = app.getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            Result(true, true, "Battery is at $level percent${if (bm.isCharging) " and charging" else ""}.")
        }
    }

    private fun setMode(mode: AiMode): Result {
        settings.aiMode = mode
        val msg = when (mode) {
            AiMode.AUTO -> "Automatic mode. I'll pick the local or cloud model for each request."
            AiMode.ONLINE -> "Online mode. I'll prefer your cloud models."
            AiMode.OFFLINE -> "Offline mode. Only the local model will answer."
        }
        return Result(true, true, msg)
    }

    private fun runtimeControl(action: RuntimeAction): Result {
        val rt = EmbeddedRuntime.get(app)
        if (!rt.isSupported()) return Result(true, false, "This build doesn't include the on-device model runtime.")
        val s = rt.status
        return when (action) {
            RuntimeAction.DOWNLOAD -> when {
                rt.hasModel() -> Result(true, true, "The on-device model is already installed.")
                s.phase == EmbeddedRuntime.Phase.DOWNLOADING -> Result(true, true, "It's already downloading${if (s.percent >= 0) ", ${s.percent} percent done" else ""}.")
                else -> {
                    rt.startDownloadIfNeeded(allowMobileData = true) // the user asked for it explicitly
                    Result(true, true, "Starting the download of the on-device model, about 1 gigabyte. I'll tell you when it's ready.")
                }
            }
            RuntimeAction.STATUS -> Result(true, true, when (s.phase) {
                EmbeddedRuntime.Phase.RUNNING -> "The on-device model is running."
                EmbeddedRuntime.Phase.STARTING -> "The on-device model is starting."
                EmbeddedRuntime.Phase.DOWNLOADING -> "The on-device model is downloading${if (s.percent >= 0) ", ${s.percent} percent" else ""}."
                EmbeddedRuntime.Phase.NO_MODEL -> "The on-device model isn't downloaded yet. Say download the offline model."
                EmbeddedRuntime.Phase.FAILED -> "The on-device model has a problem. ${s.detail}"
                EmbeddedRuntime.Phase.STOPPED -> "The on-device model is installed and will start when needed."
                EmbeddedRuntime.Phase.NO_BINARY -> "This build doesn't include the on-device model runtime."
            })
        }
    }

    private fun setLanguage(lang: String): Result {
        settings.speechLanguage = lang
        return if (lang == "hi") {
            Result(true, true, "Okay. I'll listen for Hindi and Hinglish with Google speech recognition, and answer in English.")
        } else {
            Result(true, true, "Okay. English with offline recognition.")
        }
    }

    // ---- Memory ----

    /** Carries out a memory request. Only called for sentences the user spoke. */
    fun handleMemory(intent: MemoryIntent): Result {
        val store = memory() ?: return Result(true, false, "My memory isn't available right now.")
        return when (intent) {
            is MemoryIntent.TeachApp -> teach(store, intent)
            is MemoryIntent.TeachPhrase -> {
                if (SensitiveFilter.isSensitive(intent.phrase) || SensitiveFilter.isSensitive(intent.meaning)) Result(true, false, SensitiveFilter.REFUSAL)
                else {
                    store.addPhraseCorrection(intent.phrase, intent.meaning)
                    Result(true, true, "Got it. When I hear \"${intent.phrase}\", I'll understand \"${intent.meaning}\".")
                }
            }
            is MemoryIntent.Remember -> {
                if (SensitiveFilter.isSensitive(intent.text)) return Result(true, false, SensitiveFilter.REFUSAL)
                val id = store.addFact(intent.text, intent.style)
                if (id >= 0) Result(true, true, if (intent.style) "Okay, I'll keep that in mind." else "Okay, I'll remember that.")
                else Result(true, false, "I couldn't save that.")
            }
            is MemoryIntent.Forget -> {
                val n = store.forget(intent.what)
                if (n > 0) Result(true, true, "Okay, I forgot $n ${if (n == 1) "thing" else "things"}.")
                else Result(true, true, "I don't have anything saved about that.")
            }
            MemoryIntent.ListAll -> {
                val items = store.all()
                if (items.isEmpty()) Result(true, true, "I haven't saved anything yet.")
                else Result(true, true, "I remember ${items.size} ${if (items.size == 1) "thing" else "things"}. " +
                    "The latest: ${items.take(3).joinToString("; ") { it.title }}. Open Memories in Jarvis to see or change them.")
            }
        }
    }

    private fun teach(store: MemoryStore, t: MemoryIntent.TeachApp): Result {
        val apps = installedApps()
        val target = AppMatcher.match(t.target, apps)
        val entry = target.app
        if (entry == null) {
            return if (target.ambiguous.isNotEmpty()) {
                Result(true, false, "${t.target} matches ${target.ambiguous.joinToString(" and ") { it.label }}. Please say the full app name.")
            } else {
                Result(true, false, "I couldn't find an app called ${t.target}, so I didn't save that.")
            }
        }
        val already = AppMatcher.match(t.phrase, apps) { runCatching { store.appAlias(it) }.getOrNull() }.app
        if (already?.packageName == entry.packageName) {
            return Result(true, true, "I already understand \"${t.phrase}\" as ${entry.label}.")
        }
        store.addAlias(t.phrase, entry.packageName, entry.label)
        return Result(true, true, "Got it. When you say \"${t.phrase}\", I'll open ${entry.label}.")
    }

    /** Saves "[phrase] -> [entry]" after the user repeated a failed command with the right name. */
    fun learnAlias(phrase: String, entry: AppEntry) {
        runCatching { memory()?.addAlias(phrase, entry.packageName, entry.label) }
    }

    // ---- helpers ----

    private fun startIntent(intent: Intent): Boolean {
        a11y?.let { return it.launch(intent) }
        return try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        private const val TAG = "FastAction"
        private const val APP_CACHE_MS = 30_000L
        private const val FOREGROUND_WAIT_MS = 3_000L
    }
}

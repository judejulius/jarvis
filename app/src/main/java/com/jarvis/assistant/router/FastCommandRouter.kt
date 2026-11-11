package com.jarvis.assistant.router

import com.jarvis.assistant.ai.AiMode

/**
 * The fast path: recognises simple phone commands in English, Hinglish and Devanagari Hindi so they run in
 * milliseconds without a language model. Anything it does not recognise returns null and goes to the model as before.
 */
object FastCommandRouter {
    private const val MAX_WORDS = 12

    private val MODE = Regex("""^(?:go |switch to |switch |use |set |turn on )?(offline|online|auto|automatic)(?: mode)?(?: on| karo| kar do| kar de| chalu karo)?$""")
    private val LANG = Regex("""^(?:please )?(?:(?:speak|listen|talk|understand|reply|bolo|suno|samjho)(?: to me)?(?: in| me| mein)? )?(hindi|hinglish|english)(?: mode)?(?: me| mein| on| karo| kar do| suno| samjho| bolo)*$""")

    private val BACK = Regex("""^(?:go |press |navigate |take me )?back(?: button| karo| kar do| jao| chalo| ja)?$|^(?:peeche|piche|wapas|vapas)(?: jao| ja| chalo| karo| kar do)?$""")
    private val HOME = Regex("""^(?:go |press |take me |navigate |show )?(?:to )?(?:the )?home(?: screen| button| page)?(?: par| pe)?(?: jao| ja| chalo| karo| kar do| dikhao)?$""")
    private val RECENTS = Regex("""^(?:open |show |go to )?(?:the )?(?:recent apps?|recents?|app switcher)(?: kholo| dikhao| karo)?$""")
    private val NOTIFICATIONS = Regex("""^(?:open |show |pull down |check )?(?:the )?(?:my )?notifications?(?: panel| shade| bar| drawer)?(?: kholo| dikhao| dikha do| karo)?$""")
    private val QUICK_SETTINGS = Regex("""^(?:open |show |pull down )?(?:the )?quick settings?(?: panel)?(?: kholo| dikhao)?$""")
    private val SCREENSHOT = Regex("""^(?:take |capture |grab )?(?:a )?(?:screenshot|screen shot|screen capture)(?: lo| le| karo| kar do| lena)?$""")
    private val LOCK = Regex("""^lock(?: the)?(?: phone| screen| device| mobile)?(?: karo| kar do)?$|^(?:phone|screen) lock(?: karo| kar do)?$""")

    private val SCROLL_OK = setOf("scroll", "swipe", "down", "up", "left", "right", "neeche", "niche", "upar", "oopar", "karo", "kar", "do", "the", "page", "screen", "a", "little", "bit", "thoda", "aur", "more", "ko")

    private val VOLUME_WORDS = setOf("volume", "awaaz", "sound", "aawaz", "awaz")
    private val UP_WORDS = setOf("up", "increase", "raise", "louder", "higher", "badhao", "badha", "zyada", "jyada", "full", "max", "maximum")
    private val DOWN_WORDS = setOf("down", "decrease", "lower", "reduce", "quieter", "softer", "kam", "ghatao", "ghata", "low", "min", "minimum")

    private val TORCH_WORDS = setOf("flashlight", "torch", "flash", "tourch", "tarch", "flashlite")
    private val ON_WORDS = setOf("on", "enable", "jalao", "jala", "chalu", "start", "shuru", "laga", "lagao", "activate")
    private val OFF_WORDS = setOf("off", "disable", "band", "bujhao", "bujha", "stop", "shut", "deactivate")

    private val MUSIC_WORDS = setOf("music", "song", "songs", "gaana", "gana", "gaane", "audio", "track", "tune")
    private val MEDIA_FILLER = setOf("the", "some", "my", "karo", "kar", "do", "de", "ek", "ko")
    private val PLAY_WORDS = setOf("play", "resume", "start", "bajao", "baja", "chalao", "chala", "laga", "lagao", "shuru")
    private val PAUSE_WORDS = setOf("pause", "stop", "roko", "ruko", "band", "rok")
    private val NEXT_WORDS = setOf("next", "skip", "agla", "agle", "aage")
    private val PREV_WORDS = setOf("previous", "prev", "pichla", "pichle", "pichhla", "last")

    private val TIME = Regex("""^(?:what(?: s|s| is)? the time(?: now| right now)?|what time is it(?: now| right now)?|tell me the time|current time|time kya hai|time kya hua hai|time kitna hua hai|kitne baje hain?|time batao|time bata do)$""")
    private val DATE = Regex("""^(?:what(?: s|s| is)? (?:the )?(?:date|day)(?: today| is it)?|what day is it(?: today)?|today s date|todays date|aaj (?:ki )?date(?: kya hai)?|aaj kya date hai|aaj kaun sa din hai|aaj konsa din hai|date kya hai|which day is it)$""")
    private val BATTERY = Regex("""^(?:what(?: s|s| is)? (?:the )?battery(?: level| percentage| percent)?|how much battery(?: is left| do i have| left)?|battery (?:level|percentage|percent|status)|battery kitni (?:hai|hain|bachi hai)|battery kitna hai|battery kitne percent hai)$""")

    private val DURATION = Regex("""(\d+)\s*(seconds?|secs?|minutes?|mins?|minat|hours?|hrs?|ghanta|ghante|ghantey)""")
    private val CLOCK = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm|baje|bje)?(?=\s|$)""")
    private val DIAL_A = Regex("""^(?:call|dial|phone)\s+(\+?[\d\s]{6,18})(?: karo| kar do)?$""")
    private val DIAL_B = Regex("""^(\+?[\d\s]{6,18}) ko (?:call|phone|dial)(?: karo| kar do)?$""")

    private val CALL_A = Regex("""^(?:make a call to|place a call to|give a call to|call|phone|ring|dial) (.+)$""")
    private val CALL_B = Regex("""^(.+?) ko (?:call|phone|dial)(?: karo| kar do| lagao| laga do| kijiye| kar de)?$|^(.+?) ko (?:phone|call) (?:lagao|laga do|karo|kar do)$""")
    private val NOT_A_NAME = setOf("me", "him", "her", "them", "us", "back", "again", "it", "someone", "somebody", "anyone", "a", "the", "number", "now", "later")

    private val PAGES = linkedMapOf(
        "wifi" to "wifi", "wi fi" to "wifi", "bluetooth" to "bluetooth", "battery" to "battery", "display" to "display",
        "brightness" to "display", "sound" to "sound", "location" to "location", "gps" to "location", "airplane" to "airplane",
        "flight" to "airplane", "hotspot" to "hotspot", "mobile data" to "data", "data" to "data", "apps" to "apps",
        "storage" to "storage", "accessibility" to "accessibility", "developer" to "developer", "notification" to "notification",
        "date" to "date", "language" to "language",
    )
    private val TOGGLE_PAGES = setOf("wifi", "bluetooth", "airplane", "hotspot", "data", "location")
    private val TOGGLE_OK = setOf("turn", "switch", "on", "off", "enable", "disable", "the", "wifi", "wi", "fi", "bluetooth", "airplane", "flight", "mode", "hotspot", "mobile", "data", "location", "gps", "karo", "kar", "do", "chalu", "band", "shuru", "kholo")

    private val RUNTIME_DOWNLOAD = Regex("""^(?:download|install|get|set up|setup) (?:the )?(?:offline|local|on device|ondevice) (?:model|ai|brain)$""")
    private val RUNTIME_STATUS = Regex("""^(?:is )?(?:the )?(?:offline|local|on device|ondevice) (?:model|ai|brain) (?:status|running|ready|working)$|^(?:offline|local|on device|ondevice) (?:model|ai|brain) status$""")

    private val CAMERA = Regex("""^(?:open |launch |start )?(?:the )?camera(?: app)?(?: kholo| open karo| chalao| chalu karo)?$""")

    private val YT_A = Regex("""^(?:open |launch |start )?youtube(?: app)? (?:and |then |aur )?(?:play|watch|chalao|bajao) (.+)$""")
    private val YT_B = Regex("""^(?:play|watch) (.+?) (?:on|in|from) youtube$""")
    private val YT_C = Regex("""^youtube (?:par|pe|me|mein) (.+?) (?:chalao|bajao|play karo|laga do|chala do|baja do|lagao|dikhao)$""")
    private val YT_D = Regex("""^(.+?) youtube (?:par|pe|me|mein) (?:chalao|bajao|play karo|laga do|chala do|baja do|lagao)$|^(.+?) (?:chalao|bajao) youtube (?:par|pe|me|mein)$""")

    private val SEARCH_PREFIX_SITE = Regex("""^(youtube|google) (?:par|pe|me|mein|on|in) (?:search )?(.+?)(?: search karo| search kar do| dhundo| dhoondo)?$""")
    private val SEARCH_A = Regex("""^(?:search|google|look up|dhundo|dhoondo|khojo)(?: for)? (.+)$""")
    private val SEARCH_B = Regex("""^(.+?) (?:search karo|search kar do|search kar|dhundo|dhoondo|khojo|google karo|google kar do)$""")
    private val SEARCH_SITE = Regex("""^(.+?) (?:on|in|par|pe|me|mein) (google|youtube)(?: search)?$""")
    private val IN_APP_WORDS = setOf("spotify", "amazon", "flipkart", "zomato", "swiggy", "instagram", "whatsapp", "telegram", "facebook", "twitter", "netflix", "hotstar", "gmail", "maps", "paytm", "phonepe", "uber", "ola")

    private val TYPE_RAW = Regex("""^\W*(?:(?:hey|ok|okay)\s+jarvis\W*)?(?:please\s+)?type\s+(.+?)[\s.]*$""", RegexOption.IGNORE_CASE)
    private val TAP_A = Regex("""^(?:tap|click|press|touch|select|dabao|dabana)(?: on)?(?: the)? (.{1,30}?)(?: button| option| icon| tab)?$""")
    private val TAP_B = Regex("""^(.{1,30}?) (?:dabao|dabana|dabaiye|click karo|tap karo|press karo)$""")
    private val TAP_GENERIC = setOf("it", "this", "that", "screen", "anywhere", "here", "there", "something")

    private val OPEN_A = Regex("""^(?:open|launch|start|run|go to|switch to|kholo)(?: karo| up)?(?: the)? (.+?)(?: app| application| ko)?$""")
    private val OPEN_B = Regex("""^(.+?)(?: app| application)? (?:kholo|khol do|khol de|kholna|khol dena|chalao|chala do|chalu karo|chalu kar do|open karo|open kar do|launch karo|start karo|dikhao|dikha do)$""")
    private val OPEN_BLOCKED = setOf("it", "this", "that", "something", "anything", "a", "an", "one", "up")

    /** Returns the command if [raw] is a complete simple command, otherwise null. */
    fun parse(raw: String): FastCommand? {
        val t = Lexicon.clean(raw)
        if (t.isEmpty()) return null
        val words = t.split(' ')
        if (words.size > MAX_WORDS) return null
        return mode(t) ?: language(t, words) ?: runtime(t)
            ?: press(t) ?: screenshotOrLock(t) ?: scroll(words)
            ?: volume(t, words) ?: flashlight(words) ?: media(words)
            ?: query(t) ?: timerOrAlarm(t, words) ?: dial(t) ?: callContact(t)
            ?: settingsPage(t, words) ?: camera(t)
            ?: playYouTube(t)
            ?: open(t)
            ?: search(t)
            ?: typeText(raw) ?: tap(t)
    }

    /**
     * How long (ms) the user must stay quiet before the command can be run without waiting for the full end-of-speech
     * timeout, or null when the command could still be continuing (searches, numbers, typed text).
     */
    fun earlyAcceptDelayMs(raw: String): Long? = when (parse(raw)) {
        null -> null
        is FastCommand.OpenApp -> 800L
        is FastCommand.Press, is FastCommand.Scroll, is FastCommand.Flashlight, is FastCommand.Media,
        is FastCommand.Ask, is FastCommand.SetMode, is FastCommand.SetLanguage, is FastCommand.OpenSettings, is FastCommand.RuntimeControl,
        FastCommand.Screenshot, FastCommand.LockScreen, FastCommand.OpenCamera,
        -> 600L
        is FastCommand.Volume -> 600L
        else -> null
    }

    private fun mode(t: String): FastCommand? {
        val m = MODE.matchEntire(t) ?: return null
        val explicit = "mode" in t || t.startsWith("go ") || t.startsWith("switch") || t.startsWith("use ") ||
            t.startsWith("set ") || "karo" in t || "kar do" in t
        if (!explicit) return null
        return FastCommand.SetMode(if (m.groupValues[1].startsWith("auto")) AiMode.AUTO else AiMode.parse(m.groupValues[1]))
    }

    private fun runtime(t: String): FastCommand? = when {
        RUNTIME_DOWNLOAD.matches(t) -> FastCommand.RuntimeControl(RuntimeAction.DOWNLOAD)
        RUNTIME_STATUS.matches(t) -> FastCommand.RuntimeControl(RuntimeAction.STATUS)
        else -> null
    }

    private fun language(t: String, words: List<String>): FastCommand? {
        val m = LANG.matchEntire(t) ?: return null
        if (words.size == 1) return null
        return FastCommand.SetLanguage(if (m.groupValues[1] == "english") "en" else "hi")
    }

    private fun press(t: String): FastCommand? = when {
        BACK.matches(t) -> FastCommand.Press("back")
        HOME.matches(t) -> FastCommand.Press("home")
        RECENTS.matches(t) -> FastCommand.Press("recents")
        NOTIFICATIONS.matches(t) -> FastCommand.Press("notifications")
        QUICK_SETTINGS.matches(t) -> FastCommand.Press("quick_settings")
        else -> null
    }

    private fun screenshotOrLock(t: String): FastCommand? = when {
        SCREENSHOT.matches(t) -> FastCommand.Screenshot
        LOCK.matches(t) -> FastCommand.LockScreen
        else -> null
    }

    private fun scroll(words: List<String>): FastCommand? {
        if (words.size > 6 || ("scroll" !in words && "swipe" !in words)) return null
        if (words.any { it !in SCROLL_OK }) return null
        val dir = when {
            "up" in words || "upar" in words || "oopar" in words -> "up"
            "left" in words -> "left"
            "right" in words -> "right"
            else -> "down"
        }
        return FastCommand.Scroll(dir)
    }

    private fun volume(t: String, words: List<String>): FastCommand? {
        when (t) {
            "mute", "mute karo", "mute kar do", "silent", "silent karo", "silent kar do", "mute phone", "mute the phone" ->
                return FastCommand.Volume(VolumeChange.MUTE)
            "unmute", "unmute karo", "unmute kar do" -> return FastCommand.Volume(VolumeChange.UNMUTE)
            "louder" -> return FastCommand.Volume(VolumeChange.UP)
            "quieter" -> return FastCommand.Volume(VolumeChange.DOWN)
        }
        if (words.none { it in VOLUME_WORDS }) return null
        val number = Regex("""\b(\d{1,3})\b""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        if (number != null) return FastCommand.Volume(VolumeChange.SET, number.coerceIn(0, 100))
        return when {
            words.any { it in UP_WORDS } -> FastCommand.Volume(VolumeChange.UP)
            words.any { it in DOWN_WORDS } -> FastCommand.Volume(VolumeChange.DOWN)
            else -> null
        }
    }

    private fun flashlight(words: List<String>): FastCommand? {
        if (words.size > 6 || words.none { it in TORCH_WORDS }) return null
        val on = words.any { it in ON_WORDS }
        val off = words.any { it in OFF_WORDS }
        return when {
            on && !off -> FastCommand.Flashlight(true)
            off && !on -> FastCommand.Flashlight(false)
            !on && !off -> FastCommand.Flashlight(null)
            else -> null
        }
    }

    private fun media(words: List<String>): FastCommand? {
        if (words.size > 5) return null
        val hasMusic = words.any { it in MUSIC_WORDS }
        val rest = words.filterNot { it in MUSIC_WORDS || it in MEDIA_FILLER }
        if (rest.isEmpty()) return null
        if (rest.all { it in NEXT_WORDS } && (hasMusic || words.size <= 2)) return FastCommand.Media(MediaAction.NEXT)
        if (rest.all { it in PREV_WORDS } && (hasMusic || words.size <= 2)) return FastCommand.Media(MediaAction.PREVIOUS)
        if (!hasMusic) return null
        if (rest.all { it in PAUSE_WORDS }) return FastCommand.Media(MediaAction.PAUSE)
        if (rest.all { it in PLAY_WORDS }) return FastCommand.Media(MediaAction.PLAY)
        return null
    }

    private fun query(t: String): FastCommand? = when {
        TIME.matches(t) -> FastCommand.Ask(QueryKind.TIME)
        DATE.matches(t) -> FastCommand.Ask(QueryKind.DATE)
        BATTERY.matches(t) -> FastCommand.Ask(QueryKind.BATTERY)
        else -> null
    }

    private fun durationSeconds(t: String): Int {
        var total = 0L
        for (m in DURATION.findAll(t)) {
            val n = m.groupValues[1].toLongOrNull() ?: continue
            val unit = m.groupValues[2]
            total += n * when {
                unit.startsWith("sec") -> 1
                unit.startsWith("min") -> 60
                else -> 3600
            }
        }
        return total.coerceAtMost(24L * 3600).toInt()
    }

    private fun timerOrAlarm(t: String, words: List<String>): FastCommand? {
        val isTimer = "timer" in words
        val isAlarm = "alarm" in words || ("wake" in words && "me" in words) || words.any { it.startsWith("jaga") }
        if (!isTimer && !isAlarm) return null
        val seconds = durationSeconds(t)
        if (seconds > 0) return FastCommand.SetTimer(seconds)
        if (isTimer) return null
        val m = CLOCK.find(t) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        if (minute !in 0..59 || hour !in 0..23) return null
        val explicit = m.groupValues[3]
        val afternoon = words.any { it in setOf("shaam", "evening", "dopahar", "afternoon", "sham") }
        val night = words.any { it in setOf("raat", "night") }
        val pm = explicit == "pm" || (explicit != "am" && (afternoon || night))
        if (hour <= 12) {
            if (pm && hour < 12) hour += 12
            if (!pm && hour == 12 && (explicit == "am" || night || words.any { it in setOf("subah", "morning") })) hour = 0
            if (night && !pm && hour == 12) hour = 0
        }
        return FastCommand.SetAlarm(hour, minute)
    }

    private fun dial(t: String): FastCommand? {
        val m = DIAL_A.matchEntire(t) ?: DIAL_B.matchEntire(t) ?: return null
        val number = m.groupValues[1].filter { it.isDigit() || it == '+' }
        return if (number.count { it.isDigit() } >= 6) FastCommand.Dial(number) else null
    }

    private fun callContact(t: String): FastCommand? {
        val m = CALL_A.matchEntire(t) ?: CALL_B.matchEntire(t) ?: return null
        val name = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim().orEmpty()
        val nw = name.split(' ')
        if (name.isEmpty() || nw.size > 3 || nw.first() in NOT_A_NAME || nw.first() == "me") return null
        if (name.any { it.isDigit() }) return null
        return FastCommand.CallContact(name)
    }

    private fun settingsPage(t: String, words: List<String>): FastCommand? {
        if (words.size > 6) return null
        val page = PAGES.entries.firstOrNull { (k, _) -> " $t ".contains(" $k ") || " $t ".contains(" ${k}s ") }?.value ?: return null
        val mentionsSettings = "settings" in words || "setting" in words
        if (mentionsSettings) return FastCommand.OpenSettings(page)
        if (page in TOGGLE_PAGES && words.all { it in TOGGLE_OK } && words.any { it in ON_WORDS || it in OFF_WORDS || it == "enable" || it == "disable" }) {
            return FastCommand.OpenSettings(page, toggleRequested = true)
        }
        return null
    }

    private fun camera(t: String): FastCommand? = if (CAMERA.matches(t)) FastCommand.OpenCamera else null

    private fun playYouTube(t: String): FastCommand? {
        val m = YT_A.matchEntire(t) ?: YT_B.matchEntire(t) ?: YT_C.matchEntire(t) ?: YT_D.matchEntire(t) ?: return null
        val q = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim().orEmpty()
        if (q.isEmpty() || q.split(' ').size > 8) return null
        return FastCommand.PlayYouTube(q)
    }

    private fun open(t: String): FastCommand? {
        val target = (OPEN_A.matchEntire(t) ?: OPEN_B.matchEntire(t))?.groupValues?.get(1)?.trim() ?: return null
        val tw = target.split(' ')
        if (target.isEmpty() || tw.size > 4 || target in OPEN_BLOCKED) return null
        if (tw.any { it.all(Char::isDigit) }) return null
        if (" and " in " $target " || " aur " in " $target ") return null
        return FastCommand.OpenApp(target)
    }

    private fun search(t: String): FastCommand? {
        SEARCH_PREFIX_SITE.matchEntire(t)?.let { m ->
            return searchOf(m.groupValues[2], m.groupValues[1])
        }
        val q = (SEARCH_A.matchEntire(t) ?: SEARCH_B.matchEntire(t))?.groupValues?.get(1)?.trim() ?: return null
        SEARCH_SITE.matchEntire(q)?.let { m -> return searchOf(m.groupValues[1], m.groupValues[2]) }
        val lastWord = q.substringAfterLast(' ')
        if (lastWord in IN_APP_WORDS && q.contains(' ')) return null
        return searchOf(q, "google")
    }

    private fun searchOf(query: String, site: String): FastCommand? {
        val q = query.trim()
        if (q.isEmpty() || q.split(' ').size > 8) return null
        return FastCommand.WebSearch(q, site)
    }

    private fun typeText(raw: String): FastCommand? {
        val text = TYPE_RAW.matchEntire(raw.trim())?.groupValues?.get(1)?.trim().orEmpty()
        return if (text.isNotEmpty() && text.length <= 300) FastCommand.TypeText(text) else null
    }

    private fun tap(t: String): FastCommand? {
        val label = (TAP_A.matchEntire(t) ?: TAP_B.matchEntire(t))?.groupValues?.get(1)?.trim() ?: return null
        if (label.isEmpty() || label in TAP_GENERIC) return null
        return FastCommand.TapText(label)
    }
}

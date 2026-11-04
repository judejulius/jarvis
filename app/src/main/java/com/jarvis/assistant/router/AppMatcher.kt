package com.jarvis.assistant.router

data class AppEntry(val label: String, val packageName: String)

/** [app] is the match. If [ambiguous] is not empty, several installed apps fit equally well and none was chosen. */
data class AppMatch(val app: AppEntry?, val ambiguous: List<AppEntry> = emptyList(), val how: String = "")

/**
 * Finds the installed app a user means, from a spoken name. Order: what the user taught Jarvis, well-known apps,
 * exact label, label prefix/contains, small spelling differences, and finally "sounds alike".
 */
object AppMatcher {
    /** Spoken names -> package names, in priority order. Only installed packages are ever used. */
    private val BUILT_IN: List<Pair<List<String>, List<String>>> = listOf(
        listOf("youtube", "you tube", "yutub", "utube", "youtub") to listOf("com.google.android.youtube"),
        listOf("youtube music", "yt music") to listOf("com.google.android.apps.youtube.music"),
        listOf("whatsapp", "whats app", "watsapp", "whatsap", "whatsup", "vhatsapp") to listOf("com.whatsapp"),
        listOf("whatsapp business") to listOf("com.whatsapp.w4b"),
        listOf("instagram", "insta", "insta gram") to listOf("com.instagram.android"),
        listOf("facebook", "fb", "face book") to listOf("com.facebook.katana"),
        listOf("messenger") to listOf("com.facebook.orca"),
        listOf("chrome", "google chrome", "browser", "crome") to listOf("com.android.chrome"),
        listOf("gmail", "mail", "email", "g mail") to listOf("com.google.android.gm"),
        listOf("maps", "google maps", "map") to listOf("com.google.android.apps.maps"),
        listOf("photos", "google photos") to listOf("com.google.android.apps.photos"),
        listOf("gallery") to listOf("com.coloros.gallery3d", "com.oplus.gallery", "com.google.android.apps.photos", "com.android.gallery3d"),
        listOf("camera") to listOf("com.oplus.camera", "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
        listOf("settings", "setting") to listOf("com.android.settings"),
        listOf("phone", "dialer", "dial pad", "call") to listOf("com.google.android.dialer", "com.android.dialer", "com.oplus.dialer", "com.android.contacts"),
        listOf("contacts", "contact") to listOf("com.google.android.contacts", "com.android.contacts"),
        listOf("messages", "message", "sms", "texts") to listOf("com.google.android.apps.messaging", "com.android.mms", "com.oplus.mms"),
        listOf("play store", "playstore", "google play", "app store") to listOf("com.android.vending"),
        listOf("spotify") to listOf("com.spotify.music"),
        listOf("telegram") to listOf("org.telegram.messenger"),
        listOf("twitter", "x") to listOf("com.twitter.android"),
        listOf("snapchat", "snap") to listOf("com.snapchat.android"),
        listOf("netflix") to listOf("com.netflix.mediaclient"),
        listOf("amazon") to listOf("com.amazon.mShop.android.shopping"),
        listOf("paytm") to listOf("net.one97.paytm"),
        listOf("phonepe", "phone pe") to listOf("com.phonepe.app"),
        listOf("google pay", "gpay", "g pay") to listOf("com.google.android.apps.nbu.paisa.user"),
        listOf("calculator", "calc") to listOf("com.google.android.calculator", "com.coloros.calculator", "com.oplus.calculator", "com.android.calculator2"),
        listOf("clock", "alarm clock") to listOf("com.google.android.deskclock", "com.coloros.alarmclock", "com.oplus.alarmclock", "com.android.deskclock"),
        listOf("files", "file manager") to listOf("com.google.android.apps.nbu.files", "com.coloros.filemanager", "com.oplus.filemanager", "com.android.documentsui"),
        listOf("calendar") to listOf("com.google.android.calendar", "com.android.calendar"),
        listOf("google", "google app") to listOf("com.google.android.googlequicksearchbox"),
        listOf("drive", "google drive") to listOf("com.google.android.apps.docs"),
        listOf("meet", "google meet") to listOf("com.google.android.apps.tachyon"),
        listOf("zoom") to listOf("us.zoom.videomeetings"),
        listOf("linkedin") to listOf("com.linkedin.android"),
        listOf("reddit") to listOf("com.reddit.frontpage"),
    )
    private val NOISE = setOf("app", "application", "the", "my")

    fun cleanQuery(query: String): String =
        Lexicon.clean(query).split(' ').filter { it.isNotEmpty() && it !in NOISE }.joinToString(" ")

    private fun norm(label: String): String =
        label.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun match(query: String, installed: List<AppEntry>, learned: (String) -> String? = { null }): AppMatch {
        val q = cleanQuery(query)
        if (q.isEmpty() || installed.isEmpty()) return AppMatch(null)
        val byPackage = installed.associateBy { it.packageName }

        learned(q)?.let { pkg -> byPackage[pkg]?.let { return AppMatch(it, how = "learned") } }

        for ((names, packages) in BUILT_IN) {
            if (q in names) {
                packages.firstNotNullOfOrNull { byPackage[it] }?.let { return AppMatch(it, how = "builtin") }
            }
        }

        val qc = q.replace(" ", "")
        val labels = installed.map { it to norm(it.label) }
        pick(labels.filter { (_, l) -> l == q || l.replace(" ", "") == qc }, "exact")?.let { return it }
        if (qc.length >= 3) {
            pick(labels.filter { (_, l) -> l.startsWith(q) }, "prefix")?.let { return it }
        }
        if (qc.length >= 4) {
            pick(labels.filter { (_, l) -> l.contains(q) }, "contains")?.let { return it }
            val maxEdits = if (qc.length >= 8) 2 else 1
            pick(labels.filter { (_, l) -> editDistance(l.replace(" ", ""), qc) <= maxEdits }, "fuzzy")?.let { return it }
            val qs = skeleton(qc)
            if (qs.length >= 3) {
                pick(labels.filter { (_, l) -> skeleton(l.replace(" ", "")) == qs }, "sound")?.let { return it }
            }
        }
        return AppMatch(null)
    }

    private fun pick(hits: List<Pair<AppEntry, String>>, how: String): AppMatch? = when {
        hits.isEmpty() -> null
        hits.size == 1 -> AppMatch(hits[0].first, how = how)
        else -> AppMatch(null, hits.map { it.first }.take(3), how)
    }

    /**
     * True when a spoken phrase that failed earlier is plausibly a mis-hearing of this app's name, so it is safe to
     * learn "phrase -> app" automatically after the user repeated the command with the right name.
     */
    fun similar(phrase: String, appName: String): Boolean {
        val a = cleanQuery(phrase).replace(" ", "")
        val b = norm(appName).replace(" ", "")
        if (a.length < 3 || b.length < 3) return false
        if (a == b) return false
        return editDistance(a, b) <= maxOf(2, b.length / 3) || skeleton(a) == skeleton(b)
    }

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    /** A rough "how it sounds" key: later vowels and 'h' dropped, v/w and c/k/q/z merged, repeats collapsed. */
    fun skeleton(s: String): String {
        val sb = StringBuilder()
        var prev = ' '
        for ((i, original) in s.withIndex()) {
            val ch = when (original) {
                'v' -> 'w'
                'c', 'q' -> 'k'
                'z' -> 's'
                else -> original
            }
            if (i > 0 && ch in "aeiouyh") continue
            if (ch == prev) continue
            sb.append(ch)
            prev = ch
        }
        return sb.toString()
    }
}

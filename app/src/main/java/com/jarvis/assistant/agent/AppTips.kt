package com.jarvis.assistant.agent

/**
 * Short notes about how particular apps are laid out, given to the model the first time an app is in front during a
 * task. A few common apps have built-in tips; the user can add their own in Settings, one per line as
 * "App name: tip". The user's tips come first, since they describe the user's own phone.
 *
 * [labelOf] returns the app name shown on the home screen for a package, or null if unknown.
 */
class AppTips(userText: String, private val labelOf: (String) -> String?) {
    private val user = parse(userText)
    private val given = HashSet<String>()

    /** The tips for [pkg] as a block for the model, or null if there are none or they were already given. */
    fun forForeground(pkg: String?): String? {
        if (pkg.isNullOrBlank() || pkg in given) return null
        val label = labelOf(pkg)
        val tips = tipsFor(pkg, label)
        if (tips.isEmpty()) return null
        given += pkg
        return "Tips for ${label ?: pkg}:\n" + tips.joinToString("\n") { "- $it" }
    }

    fun tipsFor(pkg: String, label: String?): List<String> {
        val mine = user.filter { (app, _) -> app == key(pkg) || (label != null && app == key(label)) }.map { it.second }
        return mine + BUILT_IN[pkg].orEmpty()
    }

    companion object {
        private const val MAX_USER_TIPS = 50
        private const val MAX_TIP_CHARS = 300

        /** "WhatsApp: the search icon is at the top" lines, as (normalised app name, tip). Other lines are ignored. */
        fun parse(text: String): List<Pair<String, String>> = text.lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val app = key(line.substring(0, colon))
                val tip = line.substring(colon + 1).trim().take(MAX_TIP_CHARS)
                if (app.isEmpty() || tip.isEmpty()) null else app to tip
            }
            .take(MAX_USER_TIPS)
            .toList()

        private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() || it == '.' }

        private val WHATSAPP = listOf(
            "To find a chat, tap the search icon or search bar at the top of the Chats tab and type the name.",
            "The message box is usually labelled \"Message\". The Send button only appears after typing; when the box is empty that spot is the voice note button, so don't hold it.",
        )
        private val SETTINGS = listOf(
            "Use the search field at the top (\"Search settings\") instead of scrolling through menus.",
        )

        /** Built-in tips by package. Kept general so they stay true across app updates. */
        val BUILT_IN: Map<String, List<String>> = mapOf(
            "com.whatsapp" to WHATSAPP,
            "com.whatsapp.w4b" to WHATSAPP,
            "com.google.android.youtube" to listOf(
                "Search with the magnifying glass button at the top, type the query and press enter.",
                "An ad may play before a video; a \"Skip\" button usually appears after a few seconds.",
            ),
            "com.android.chrome" to listOf(
                "The address bar at the top (\"Search or type URL\") accepts both web addresses and searches; type, then press enter.",
            ),
            "com.google.android.gm" to listOf(
                "Start a new email with the \"Compose\" button at the bottom right. Fill in To, Subject and the body, then tap the Send icon at the top.",
            ),
            "com.google.android.apps.maps" to listOf(
                "Use the \"Search here\" box at the top. After choosing a place, the \"Directions\" button starts a route.",
            ),
            "com.android.settings" to SETTINGS,
            "com.google.android.apps.messaging" to listOf(
                "Start a new conversation with \"Start chat\" at the bottom right, then type the name or number in the To field.",
            ),
            "org.telegram.messenger" to listOf(
                "To find a chat, tap the search icon at the top right of the chat list and type the name.",
            ),
            "com.spotify.music" to listOf(
                "Open the Search tab at the bottom, then tap the search field at the top and type.",
            ),
            "com.android.vending" to listOf(
                "Search with the bar at the top (\"Search apps & games\"). Ask the user before tapping Install on a paid app.",
            ),
        )
    }
}

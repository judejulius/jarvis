package com.jarvis.assistant.apps

/** One thing on the screen that could be tapped, reduced to what the choosing logic needs. */
data class ScreenItem(val id: Int, val label: String, val clickable: Boolean)

/**
 * The framework for actions inside other apps ("play X on YouTube"). Each app gets one [AppAction]: it knows which
 * package it is for, and how to recognise the right thing to tap on that app's screens. The choosing logic is pure
 * and tested; running it on a real screen needs the accessibility service (see FastActionExecutor).
 */
interface AppAction {
    val packageName: String
    val appName: String

    /** The item to tap on a results screen, or null if the screen does not show results yet. */
    fun pickResult(items: List<ScreenItem>): ScreenItem?

    /** A short spoken name for the chosen item (a video title), without private details. */
    fun titleOf(item: ScreenItem): String
}

/**
 * YouTube. Search results are announced to accessibility as one description per video, something like
 * "Title - 3 minutes, 4 seconds - Channel - 1M views - 2 years ago - play video". The first organic video is chosen;
 * ads, Shorts shelves, playlists and channel rows are skipped. If YouTube changes how it describes results, nothing
 * is chosen and Jarvis says so, instead of tapping something unrelated.
 */
object YouTubeAction : AppAction {
    override val packageName = "com.google.android.youtube"
    override val appName = "YouTube"

    private val DURATION = Regex("""\b\d+\s*(?:hours?|minutes?|seconds?)\b""", RegexOption.IGNORE_CASE)

    private fun score(label: String): Int {
        if (label.isBlank()) return 0
        var s = 0
        if (label.trim().endsWith("play video", ignoreCase = true)) s += 3
        if (DURATION.containsMatchIn(label)) s += 2
        if (label.count { it == '-' } >= 2) s += 1
        return s
    }

    override fun pickResult(items: List<ScreenItem>): ScreenItem? =
        items.firstOrNull { it.clickable && score(it.label) >= 3 && !isSkipped(it.label) }

    /** A row we must not tap even though it looks like a video: an ad, a Shorts shelf, a playlist. */
    private fun isSkipped(label: String): Boolean =
        label.split(" - ").map { it.trim() }.any { part ->
            part.equals("Sponsored", true) || part.equals("Ad", true) || part.equals("Shorts", true) ||
                part.equals("Playlist", true) || part.startsWith("Sponsored", true)
        }

    override fun titleOf(item: ScreenItem): String = item.label.substringBefore(" - ").trim().take(80)

    /** The words after "play", cleaned: "despacito song" -> "despacito". */
    fun cleanQuery(q: String): String =
        q.trim().replace(Regex("""\s+(?:song|video|gaana|gana|on youtube|youtube par|youtube pe)$""", RegexOption.IGNORE_CASE), "").trim()
}

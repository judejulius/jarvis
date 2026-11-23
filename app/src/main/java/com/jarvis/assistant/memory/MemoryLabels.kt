package com.jarvis.assistant.memory

import android.content.Context
import com.jarvis.assistant.R

/**
 * Shows the labels [MemoryStore] saves (task states, kinds of memory) in the app's language. The database keeps them
 * in English, so changing the phone's language never changes what is stored.
 */
object MemoryLabels {
    private val WORDS = mapOf(
        "Completed" to R.string.state_completed,
        "Failed" to R.string.state_failed_task,
        "Running" to R.string.state_running,
        "Interrupted" to R.string.state_interrupted,
        "Cancelled" to R.string.state_cancelled,
        "App name" to R.string.detail_app_name,
        "How to answer" to R.string.detail_how_to_answer,
        "Note" to R.string.detail_note,
    )
    private val ALIAS = Regex("^\"(.*)\" opens (.*)$")

    /** A stored word such as "Completed", translated; any other text unchanged. */
    fun word(context: Context, text: String): String = WORDS[text]?.let(context::getString) ?: text

    /** "Completed · 23/11/2026 08:00" with the state translated. */
    fun detail(context: Context, item: MemoryStore.Item): String {
        val parts = item.detail.split(" · ", limit = 2)
        return (listOf(word(context, parts[0])) + parts.drop(1)).joinToString(" · ")
    }

    fun title(context: Context, item: MemoryStore.Item): String {
        if (item.kind != MemoryStore.Kind.ALIAS) return item.title
        val m = ALIAS.matchEntire(item.title) ?: return item.title
        return context.getString(R.string.alias_opens, m.groupValues[1], m.groupValues[2])
    }
}

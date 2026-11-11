package com.jarvis.assistant.router

/**
 * Applies what the user taught about how they sound: "when I say you too, I mean youtube". Whole words only, longest
 * phrase first, so "open you too" becomes "open youtube". Pure logic.
 */
object PhraseCorrector {
    fun apply(text: String, corrections: Map<String, String>): String {
        if (corrections.isEmpty() || text.isBlank()) return text
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        fun norm(w: String) = w.lowercase().trim(',', '.', '!', '?', ';', ':', '"', '\'')
        val ordered = corrections.entries.sortedByDescending { it.key.split(' ').size }
        var changed = false
        for ((phrase, meaning) in ordered) {
            val p = phrase.split(' ').filter { it.isNotEmpty() }
            if (p.isEmpty()) continue
            var i = 0
            while (i + p.size <= words.size) {
                if (p.indices.all { norm(words[i + it]) == p[it] }) {
                    repeat(p.size) { words.removeAt(i) }
                    words.add(i, meaning)
                    changed = true
                    i += 1
                } else {
                    i++
                }
            }
        }
        return if (changed) words.joinToString(" ") else text
    }
}

/** Chooses among the recognizer's alternative transcriptions: the first one Jarvis can act on, otherwise the best guess. */
object AlternativePicker {
    fun pick(alternatives: List<String>, isCommand: (String) -> Boolean): String? {
        val cleaned = alternatives.map { it.trim() }.filter { it.isNotEmpty() }
        return cleaned.firstOrNull(isCommand) ?: cleaned.firstOrNull()
    }
}

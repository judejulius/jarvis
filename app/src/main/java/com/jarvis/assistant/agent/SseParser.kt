package com.jarvis.assistant.agent

/** Reads one line of a streamed chat response ("data: {...}"). Pure, so it is unit-tested. */
object SseParser {
    sealed interface Event {
        data class Text(val text: String) : Event
        data object Done : Event
        data object Other : Event
    }

    fun parseLine(line: String): Event {
        if (!line.startsWith("data:")) return Event.Other
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") return Event.Done
        val deltaAt = payload.indexOf("\"delta\"")
        if (deltaAt < 0) return Event.Other
        val keyAt = payload.indexOf("\"content\"", deltaAt)
        if (keyAt < 0) return Event.Other
        var i = payload.indexOf(':', keyAt + 9)
        if (i < 0) return Event.Other
        i++
        while (i < payload.length && payload[i] == ' ') i++
        if (i >= payload.length || payload[i] != '"') return Event.Other // null or missing
        return Event.Text(readJsonString(payload, i + 1) ?: return Event.Other)
    }

    /** Reads a JSON string body starting right after the opening quote, handling escapes and \uXXXX. */
    private fun readJsonString(s: String, start: Int): String? {
        val sb = StringBuilder()
        var i = start
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> return sb.toString()
                '\\' -> {
                    i++
                    if (i >= s.length) return null
                    when (val e = s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C'); '/' -> sb.append('/'); '\\' -> sb.append('\\'); '"' -> sb.append('"')
                        'u' -> {
                            if (i + 4 >= s.length) return null
                            sb.append(s.substring(i + 1, i + 5).toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }
}

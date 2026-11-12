package com.jarvis.assistant.voice

/**
 * Turns streamed text into pieces that are good to speak: whole sentences, never half of one, and not so short that the
 * voice stutters. Markdown marks are removed because they would be read aloud. Pure logic.
 */
class SentenceChunker(private val minLength: Int = 24, private val maxLength: Int = 240) {
    private val buf = StringBuilder()

    /** Adds streamed text; returns the complete pieces that are ready to speak now. */
    fun feed(delta: String): List<String> {
        buf.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val cut = nextCut() ?: break
            val piece = clean(buf.substring(0, cut))
            buf.delete(0, cut)
            if (piece.isNotEmpty()) out += piece
        }
        return out
    }

    /** The unfinished rest at the end of the stream. */
    fun flush(): String? {
        val piece = clean(buf.toString())
        buf.setLength(0)
        return piece.ifEmpty { null }
    }

    private fun isEnd(c: Char) = c == '.' || c == '!' || c == '?' || c == '।' || c == '\n'

    private fun nextCut(): Int? {
        // a sentence end followed by whitespace (so "3.5" and "e.g." are not cut), once the piece is long enough
        for (i in buf.indices) {
            if (!isEnd(buf[i])) continue
            val after = buf.getOrNull(i + 1) ?: break // the next character has not arrived yet
            if (!after.isWhitespace() && buf[i] != '\n') continue
            if (clean(buf.substring(0, i + 1)).length >= minLength || buf[i] == '\n' && i + 1 >= minLength) return i + 1
        }
        if (buf.length > maxLength) {
            // a very long sentence: cut at the last comma or space instead of making the user wait
            val at = (maxLength downTo minLength).firstOrNull { buf[it] == ',' || buf[it] == ';' } ?: (maxLength downTo minLength).firstOrNull { buf[it] == ' ' }
            if (at != null) return at + 1
        }
        return null
    }

    private fun clean(s: String): String =
        s.replace(Regex("""[*_`#>]+"""), "").replace(Regex("\\s+"), " ").trim()
}

/**
 * Speaks streamed replies one sentence after another. [runOnMain] runs a block on the thread that talks to the voice;
 * [speak] speaks one piece and calls back when it is finished. While a piece is being spoken, [prepare] is told
 * which piece comes next, so the voice can make it meanwhile and there is no silence between the two.
 */
class ReplyStreamer(
    private val runOnMain: (() -> Unit) -> Unit,
    private val prepare: (String) -> Unit = {},
    private val speak: (String, () -> Unit) -> Unit,
) {
    private val chunker = SentenceChunker()
    private val queue = ArrayDeque<String>()
    private var speaking = false
    private var finished = false
    private var cancelled = false
    private var done: (() -> Unit)? = null

    /** Number of pieces handed to the voice so far (main thread). */
    var spoken = 0
        private set

    fun onDelta(delta: String) = runOnMain {
        if (!cancelled) { queue.addAll(chunker.feed(delta)); pump() }
    }

    /** The stream ended: whatever is left is spoken too. */
    fun finish() = runOnMain {
        if (!cancelled) { chunker.flush()?.let { queue.addLast(it) }; finished = true; pump() }
    }

    /** Calls [callback] once everything has been spoken (immediately if it already has). Call after [finish]. */
    fun whenDone(callback: () -> Unit) = runOnMain {
        if (!cancelled) { done = callback; pump() }
    }

    /** Drops what has not been spoken yet. Main thread. */
    fun cancel() {
        cancelled = true
        queue.clear()
        done = null
    }

    private var preparedPiece: String? = null

    /** Tells the voice what comes after the piece being spoken, once per piece. */
    private fun prepareNext() {
        val next = queue.firstOrNull() ?: return
        if (!speaking || cancelled || next === preparedPiece) return
        preparedPiece = next
        prepare(next)
    }

    private fun pump() {
        if (cancelled) return
        if (speaking) {
            prepareNext()
            return
        }
        val next = queue.removeFirstOrNull()
        if (next != null) {
            speaking = true
            spoken++
            speak(next) { speaking = false; pump() }
            prepareNext() // after speak, which drops anything prepared earlier
            return
        }
        if (finished) {
            val cb = done
            done = null
            cb?.invoke()
        }
    }
}

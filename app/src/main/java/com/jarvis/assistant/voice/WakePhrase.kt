package com.jarvis.assistant.voice

import android.content.Context
import java.util.Locale

/** English unigram pieces and scores extracted from the pinned GigaSpeech keyword model's bpe.model. */
object WakePhrase {
    const val DEFAULT = "Hey Jarvis"
    private val valid = Regex("[A-Za-z]+(?: [A-Za-z]+)*")

    fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")
    fun isValid(text: String) = text.length in 1..40 && valid.matches(text)

    /** Highest-scoring segmentation, as used by the model's SentencePiece unigram tokenizer. */
    fun keyword(context: Context, phrase: String): String {
        require(isValid(phrase))
        val scores = context.assets.open("wake-pieces.txt").bufferedReader().useLines { lines ->
            lines.associate { it.substringBefore(' ') to it.substringAfter(' ').toFloat() }
        }
        val pieces = phrase.uppercase(Locale.ROOT).split(' ').flatMap { word ->
            val input = "▁$word"
            val best = FloatArray(input.length + 1) { Float.NEGATIVE_INFINITY }
            val previous = IntArray(input.length + 1)
            best[0] = 0f
            for (end in 1..input.length) {
                for (start in 0 until end) {
                    val score = scores[input.substring(start, end)] ?: continue
                    if (best[start] + score > best[end]) {
                        best[end] = best[start] + score
                        previous[end] = start
                    }
                }
            }
            check(best.last().isFinite())
            val tokens = mutableListOf<String>()
            var end = input.length
            while (end > 0) {
                val start = previous[end]
                tokens += input.substring(start, end)
                end = start
            }
            tokens.asReversed()
        }
        return pieces.joinToString(" ") + " :2.0 #0.1 @CUSTOM"
    }
}

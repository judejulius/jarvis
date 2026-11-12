package com.jarvis.assistant.voice

private fun nowMs() = System.nanoTime() / 1_000_000

/** How fast the voice makes speech on this phone. Learnt while it speaks and kept between replies. */
internal class VoiceSpeed {
    /** Time to make one character of text, in milliseconds. 0 until it has been measured. */
    @Volatile var msPerChar = 0f
        private set

    /** Time to make speech divided by how long that speech lasts. Above 1, the voice can't keep up. 0 until measured. */
    @Volatile var realTimeFactor = 0f
        private set

    fun record(chars: Int, computeMs: Long, audioMs: Long) {
        // Very short pieces are mostly fixed cost and say little about longer ones.
        if (chars < MIN_CHARS || audioMs <= 0) return
        msPerChar = blend(msPerChar, computeMs.toFloat() / chars)
        realTimeFactor = blend(realTimeFactor, computeMs.toFloat() / audioMs)
    }

    private fun blend(old: Float, new: Float) = if (old == 0f) new else old * 0.6f + new * 0.4f

    private companion object {
        const val MIN_CHARS = 15
    }
}

/**
 * Decides how much text to give the voice next, for one utterance.
 *
 * The first piece is short, so Jarvis starts talking soon. After that a piece may only be as long
 * as the voice can make before the speech already waiting runs out: a longer one would leave a
 * silence while it is made. On a fast phone the pieces grow to [max] within a sentence or two. On
 * a phone that makes speech slower than it plays, they stay short, so the pauses that can't be
 * avoided are brief and fall at commas and full stops rather than one long silence mid-reply.
 *
 * An utterance can be made before it is played (the next sentence of a streamed reply, made while
 * the current one plays). Until [startPlaying], everything made so far is still waiting.
 */
internal class SpeechPacer(
    private val speed: VoiceSpeed,
    private val first: Int,
    private val max: Int,
    val now: () -> Long = ::nowMs,
) {
    private var playing = false
    private var playEnd = 0L
    private var waitingMs = 0L // speech made before playback began
    private var started = false

    /** Call when the speaker begins to play this utterance. */
    @Synchronized fun startPlaying() {
        if (playing) return
        playing = true
        playEnd = now() + waitingMs
    }

    /** The most characters the next piece should have. */
    @Synchronized fun nextLimit(): Int {
        if (!started) {
            started = true
            return first
        }
        val floor = minOf(SHORT_PIECE, max)
        val cost = speed.msPerChar
        if (cost <= 0f) return floor
        val ahead = if (playing) playEnd - now() else waitingMs // how much speech is made but not yet heard
        return (ahead * HEADROOM / cost).toInt().coerceIn(floor, max)
    }

    /** Call when [audioMs] of speech has been handed to the speaker. */
    @Synchronized fun emitted(audioMs: Long) {
        if (playing) playEnd = maxOf(playEnd, now()) + audioMs else waitingMs += audioMs
    }

    /** Call when a piece of [chars] characters took [computeMs] of work and gave [audioMs] of speech. */
    fun made(chars: Int, computeMs: Long, audioMs: Long) = speed.record(chars, computeMs, audioMs)

    private companion object {
        /** The piece size when the voice is behind: long enough to reach a comma in most sentences. */
        const val SHORT_PIECE = 60
        /** Use this share of the time available, as how long a piece takes varies. */
        const val HEADROOM = 0.8f
    }
}

/**
 * Makes the speech for [text] piece by piece, each piece sized by [pacer] and made by [make].
 * [make] hands its audio (at [sampleRate]) to the function it is given, which passes it to [out];
 * both return false once the speech is no longer wanted, and making then stops.
 */
internal fun makePaced(
    text: String,
    pacer: SpeechPacer,
    sampleRate: Int,
    out: (FloatArray) -> Boolean,
    make: (piece: String, emit: (FloatArray) -> Boolean) -> Unit,
) {
    var rest = text.trim()
    var wanted = true
    while (wanted && rest.isNotEmpty()) {
        val (piece, after) = SpeechChunks.take(rest, pacer.nextLimit())
        rest = after
        var waitedMs = 0L // time spent inside [out], which isn't the voice's work
        var audioMs = 0L
        val t0 = pacer.now()
        make(piece) { samples ->
            val t = pacer.now()
            val ms = samples.size * 1000L / sampleRate
            audioMs += ms
            pacer.emitted(ms)
            wanted = out(samples)
            waitedMs += pacer.now() - t
            wanted
        }
        pacer.made(piece.length, pacer.now() - t0 - waitedMs, audioMs)
    }
}

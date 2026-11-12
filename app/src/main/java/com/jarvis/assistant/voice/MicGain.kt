package com.jarvis.assistant.voice

import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Brings what the microphone hears to the loudness the wake word and speech models expect.
 *
 * The plain recognition microphone has no volume control of its own: someone speaking normally a
 * metre away arrives 20 to 30 dB quieter than the speech the models were trained on, and only a
 * shout is loud enough. So the gain rests as high as the room's background noise allows, which
 * means a quiet "Hey Jarvis" is already lifted from its first sound, and it is turned down at
 * once when something loud arrives.
 *
 * Feed it the microphone in order, one block at a time, from one thread.
 */
internal class MicGain {
    /** Background noise level (RMS), learnt from the quietest moments. */
    var noiseFloor = 0.0005f
        private set

    /** The gain applied to the last block. */
    @Volatile var gain = 1f
        private set

    /** Loudness of recent speech before gain: jumps up with a loud block, then fades over a second or two. */
    private var envelope = 0f

    /**
     * Scales [x] in place and returns its loudness (RMS) before scaling. With [learn] false the
     * noise level is left alone (Jarvis is speaking, and its own voice is not the room's noise).
     */
    fun process(x: FloatArray, learn: Boolean = true): Float {
        val raw = rms(x)
        if (learn) {
            // The noise floor follows the quietest blocks: down quickly, up only slowly, so speech doesn't raise it.
            noiseFloor = if (raw < noiseFloor) noiseFloor * 0.7f + raw * 0.3f else noiseFloor * NOISE_RISE
            noiseFloor = noiseFloor.coerceIn(MIN_NOISE, MAX_NOISE)
        }
        envelope = maxOf(raw, envelope * ENVELOPE_FADE)

        val wanted = (TARGET_RMS / envelope.coerceAtLeast(1e-6f)).coerceIn(1f, MAX_GAIN)
        val noiseCap = (MAX_NOISE_AFTER_GAIN / noiseFloor).coerceAtLeast(1f) // never turn the room's hiss into a roar
        val target = minOf(wanted, noiseCap)
        val before = gain
        // Down at once, so loud speech is never clipped; up gradually, so the level doesn't pump between words.
        gain = if (target < before) target else before + (target - before) * RISE
        if (gain <= 1.01f && before <= 1.01f) return raw
        if (gain < before) {
            for (i in x.indices) x[i] = tanh(x[i] * gain)
        } else {
            val step = (gain - before) / x.size
            for (i in x.indices) x[i] = tanh(x[i] * (before + step * i)) // soft limit: peaks never clip harshly
        }
        return raw
    }

    companion object {
        /** The loudness speech is brought to. */
        const val TARGET_RMS = 0.05f
        const val MAX_GAIN = 32f
        /** Background noise is never amplified beyond this. */
        const val MAX_NOISE_AFTER_GAIN = 0.01f
        private const val MIN_NOISE = 0.00005f
        private const val MAX_NOISE = 0.05f
        private const val NOISE_RISE = 1.004f   // per 100 ms block: doubles in about 17 s
        private const val ENVELOPE_FADE = 0.93f // per 100 ms block: halves in about 1 s
        private const val RISE = 0.2f

        fun rms(x: FloatArray): Float {
            var sum = 0.0
            for (v in x) sum += v * v
            return sqrt(sum / x.size.coerceAtLeast(1)).toFloat()
        }
    }
}

package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin

class MicGainTest {
    private val random = Random(3)

    /** 100 ms of room hiss at [level] (RMS). */
    private fun hiss(level: Float) = FloatArray(1600) { (random.nextGaussian() * level).toFloat() }

    /** 100 ms of a voice-like tone at [level] (RMS), over hiss. */
    private fun voice(level: Float, noise: Float = 0.0003f) =
        FloatArray(1600) { (sin(it * 0.12) * level * 1.414 + random.nextGaussian() * noise).toFloat() }

    private fun MicGain.quiet(seconds: Int, level: Float = 0.0003f) = repeat(seconds * 10) { process(hiss(level)) }

    /** Loudness after gain of each 100 ms block of a 0.7 s phrase (about as long as "Hey Jarvis"). */
    private fun MicGain.phrase(level: Float, noise: Float = 0.0003f) = List(7) { voice(level, noise).also { process(it) }.let(MicGain::rms) }

    @Test
    fun aNormalVoiceIsLiftedFromItsFirstSound() {
        val gain = MicGain().apply { quiet(5) }
        val heard = gain.phrase(0.006f) // someone speaking normally at arm's length
        assertTrue("first 100 ms already lifted: ${heard[0]}", heard[0] > 0.03f)
        heard.forEach { assertTrue("near the level the models expect: $it", it in 0.03f..0.09f) }
    }

    @Test
    fun aVoiceFromAcrossTheRoomIsLiftedAsFarAsTheGainGoes() {
        val gain = MicGain().apply { quiet(5) }
        val heard = gain.phrase(0.002f)
        heard.forEach { assertTrue("lifted about 30 times: $it", it > 0.04f) }
    }

    @Test
    fun aLoudVoiceIsLeftAloneAndNotClipped() {
        val gain = MicGain().apply { quiet(5) }
        val block = voice(0.07f)
        gain.process(block)
        assertEquals("gain dropped for the very first loud block", 1f, gain.gain, 0.01f)
        assertTrue("no clipping", block.maxOf { abs(it) } < 0.2f)
    }

    @Test
    fun aNoisyRoomIsNotTurnedIntoARoar() {
        val gain = MicGain().apply { quiet(90, level = 0.004f) } // a fan, a car: learnt as noise within about a minute
        val block = hiss(0.004f)
        gain.process(block)
        assertTrue("noise after gain stays low: ${MicGain.rms(block)}", MicGain.rms(block) < MicGain.MAX_NOISE_AFTER_GAIN * 1.5f)
    }

    @Test
    fun sensitivityComesBackWithinSecondsOfALoudSound() {
        val gain = MicGain().apply { quiet(5) }
        repeat(10) { gain.process(voice(0.07f)) } // a second of loud speech
        gain.quiet(4)
        assertTrue("a normal voice is lifted again: ${gain.phrase(0.006f)}", gain.phrase(0.006f).all { it > 0.025f })
    }

    @Test
    fun speechDoesNotRaiseTheNoiseFloor() {
        val gain = MicGain().apply { quiet(5) }
        val before = gain.noiseFloor
        repeat(50) { gain.process(voice(0.006f)) } // five seconds of talking
        assertTrue("noise floor ${gain.noiseFloor} was $before", gain.noiseFloor < before * 1.5f)
    }

    @Test
    fun jarvissOwnVoiceIsNotLearntAsNoise() {
        val gain = MicGain().apply { quiet(5) }
        val before = gain.noiseFloor
        repeat(600) { gain.process(voice(0.03f), learn = false) } // a minute of Jarvis talking
        assertEquals(before, gain.noiseFloor, 0f)
    }
}

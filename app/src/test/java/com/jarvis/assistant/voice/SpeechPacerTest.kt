package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPacerTest {
    private var clock = 0L
    private val reply = "I have checked your calendar and you have three meetings today, the first is at nine with the design team, " +
        "the second is a lunch with Sarah at noon, and the last is a call with the client at four. Shall I tell them you are on your way? " +
        "I can also move the lunch to one if you would like more time to get there, or cancel the call."

    /**
     * Speaks [reply] with a pretend voice that takes [msPerChar] to make a character lasting 50 ms,
     * on a pretend clock. Returns the pieces and the total silence between them.
     */
    private fun speak(msPerChar: Long, speed: VoiceSpeed = VoiceSpeed(), first: Int = 40, max: Int = 150, playing: Boolean = true): Pair<List<String>, Long> {
        val pieces = mutableListOf<String>()
        var playEnd = 0L
        var silence = 0L
        val pacer = SpeechPacer(speed, first, max) { clock }.also { if (playing) it.startPlaying() }
        makePaced(reply, pacer, AudioOut.SAMPLE_RATE, out = { true }) { piece, emit ->
            pieces += piece
            clock += piece.length * msPerChar
            if (pieces.size > 1 && clock > playEnd) silence += clock - playEnd
            playEnd = maxOf(playEnd, clock) + piece.length * 50L
            emit(FloatArray(piece.length * 50 * AudioOut.SAMPLE_RATE / 1000))
        }
        return pieces to silence
    }

    @Test
    fun nothingIsLostAndTheFirstPieceIsShort() {
        for (cost in listOf(5L, 40L, 120L)) {
            val (pieces, _) = speak(cost)
            assertTrue("first piece: ${pieces[0]}", pieces[0].length <= 40)
            assertEquals(reply, pieces.joinToString(" "))
        }
    }

    @Test
    fun aFastVoiceMovesToLongPiecesAndNeverFallsSilent() {
        val (pieces, silence) = speak(msPerChar = 10) // five times faster than it plays
        assertEquals("no silence between pieces", 0L, silence)
        assertTrue("grew to long pieces: ${pieces.map { it.length }}", pieces.drop(1).any { it.length > 100 })
    }

    @Test
    fun aVoiceJustFasterThanSpeechGrowsItsPiecesGraduallyWithoutSilence() {
        val (pieces, silence) = speak(msPerChar = 35)
        assertEquals("no silence between pieces: ${pieces.map { it.length }}", 0L, silence)
        // Made in one go, the second piece would be 150 characters: 5 s of work with 2 s of speech waiting.
        assertTrue("second piece is modest: ${pieces.map { it.length }}", pieces[1].length <= 80)
    }

    @Test
    fun aVoiceSlowerThanSpeechKeepsItsPiecesShortSoNoPauseIsLong() {
        val (pieces, _) = speak(msPerChar = 100) // takes twice as long to make speech as to play it
        pieces.forEach { assertTrue("short piece: $it", it.length <= 60) }
        val atPunctuation = pieces.dropLast(1).count { it.last() in ",.?" }
        assertTrue("most pauses fall at punctuation: $pieces", atPunctuation * 3 >= (pieces.size - 1) * 2)
    }

    @Test
    fun whatWasLearntInOneReplyIsUsedInTheNext() {
        val speed = VoiceSpeed()
        speak(msPerChar = 10, speed = speed)
        assertEquals(10f, speed.msPerChar, 0.5f)
        assertEquals(0.2f, speed.realTimeFactor, 0.02f)
    }

    @Test
    fun veryShortPiecesDoNotCountAsAMeasurement() {
        val speed = VoiceSpeed()
        speed.record(chars = 4, computeMs = 900, audioMs = 700)
        assertEquals(0f, speed.msPerChar, 0f)
    }

    @Test
    fun speechMadeAheadOfBeingPlayedGrowsItsPiecesEvenOnASlowPhone() {
        // Nothing is playing yet, so everything made so far is still waiting and there is time for longer pieces.
        val (pieces, _) = speak(msPerChar = 100, first = 80, playing = false)
        assertEquals(reply, pieces.joinToString(" "))
        assertTrue("grew past the short size: ${pieces.map { it.length }}", pieces.drop(1).any { it.length > 60 })
    }

    @Test
    fun aLowMemoryLimitIsNeverExceeded() {
        val (pieces, _) = speak(msPerChar = 5, max = 90)
        pieces.forEach { assertTrue("piece ${it.length} > 90", it.length <= 90) }
    }
}

package com.jarvis.assistant.router

import com.jarvis.assistant.memory.SensitiveFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseCorrectorTest {
    private val map = mapOf("you too" to "youtube", "what sap" to "whatsapp", "play list" to "playlist")

    @Test fun replacesTaughtPhrasesInsideASentence() {
        assertEquals("open youtube", PhraseCorrector.apply("open you too", map))
        assertEquals("Open youtube please", PhraseCorrector.apply("Open You Too please", map))
        assertEquals("open whatsapp and youtube", PhraseCorrector.apply("open what sap and you too", map))
    }

    @Test fun leavesOtherSentencesAlone() {
        assertEquals("open chrome", PhraseCorrector.apply("open chrome", map))
        assertEquals("open you", PhraseCorrector.apply("open you", map))
        assertEquals("", PhraseCorrector.apply("", map))
        assertEquals("open you too", PhraseCorrector.apply("open you too", emptyMap()))
    }

    @Test fun usesTheFirstAlternativeJarvisCanActOn() {
        val alts = listOf("open you tube", "open youtube", "open u two")
        assertEquals("open youtube", AlternativePicker.pick(alts) { FastCommandRouter.parse(it) == FastCommand.OpenApp("youtube") })
        assertEquals("what is the sky", AlternativePicker.pick(listOf("what is the sky", "what is the skye")) { false })
        assertEquals(null, AlternativePicker.pick(emptyList()) { true })
        assertEquals("hello", AlternativePicker.pick(listOf(" ", "hello")) { false })
    }

    @Test fun secretsAreNeverStored() {
        for (t in listOf("my wifi password is hunter2", "my otp is 482913", "card number 4111 1111 1111 1111", "my aadhaar is 1234 5678 9012", "api key is abc", "my pin is 4321")) {
            assertTrue(t, SensitiveFilter.isSensitive(t))
        }
        for (t in listOf("I prefer short answers", "my brother lives in Pune", "call me boss", "my number is 9876543210", "I like spinning")) {
            assertFalse(t, SensitiveFilter.isSensitive(t))
        }
    }
}

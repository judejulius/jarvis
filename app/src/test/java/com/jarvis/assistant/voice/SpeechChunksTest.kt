package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {
    private val reply =
        "Good evening. I have checked your calendar and you have three meetings today. The first is at nine with the design team, " +
            "the second is a lunch with Sarah, and the last is a call with the client at four. Shall I send a message to let them know you are on your way?"

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()

    @Test
    fun noPieceIsTooLongAndNothingIsLost() {
        for ((first, max) in listOf(80 to 150, 40 to 150, 40 to 90, 20 to 30)) {
            val pieces = SpeechChunks.split(reply, first, max)
            assertTrue("first piece ${pieces[0].length} > $first", pieces[0].length <= first)
            pieces.forEach { assertTrue("piece ${it.length} > $max: $it", it.length <= max) }
            assertEquals(squash(reply), pieces.joinToString(" "))
        }
    }

    @Test
    fun aLongSentenceIsCutAtACommaSoSpeechStartsSooner() {
        val one = "I have opened the messaging app and found the chat with your mother, then I typed the message you asked for, and I checked it twice"
        val pieces = SpeechChunks.split(one, first = 80)
        assertTrue(pieces.size > 1)
        assertTrue("cut at a comma: ${pieces[0]}", pieces[0].endsWith(","))
        assertEquals(one, pieces.joinToString(" "))
    }

    @Test
    fun aLongRunWithNoPunctuationIsCutAtSpaces() {
        val words = "word ".repeat(100).trim()
        val pieces = SpeechChunks.split(words, first = 40, max = 100)
        assertTrue(pieces.all { it.length <= 100 && !it.startsWith(" ") && !it.endsWith(" ") })
        assertEquals(words, pieces.joinToString(" "))
        assertEquals("a word is never cut in half", true, pieces.all { p -> p.split(" ").all { it == "word" } })
    }

    @Test
    fun decimalsAndShortRepliesStayWhole() {
        assertEquals(listOf("It is 0.5 of the whole."), SpeechChunks.split("It is 0.5 of the whole."))
        assertEquals(listOf("Yes?"), SpeechChunks.split("Yes?"))
    }

    @Test
    fun shortSentencesArePackedTogether() {
        val pieces = SpeechChunks.split("One. Two. Three. Four. Five. Six. Seven. Eight. Nine. Ten.", first = 20, max = 40)
        assertTrue("packed, not one piece per sentence: $pieces", pieces.size in 2..4)
    }

    @Test
    fun takeGivesWholeSentencesThatFitAndLeavesTheRest() {
        val (piece, rest) = SpeechChunks.take("One two. Three four. Five six seven eight.", 22)
        assertEquals("One two. Three four.", piece)
        assertEquals("Five six seven eight.", rest)
        assertEquals("all of it when it fits", "Yes?" to "", SpeechChunks.take(" Yes? ", 40))
    }

    @Test
    fun takeCutsASentenceThatDoesNotFitAtAComma() {
        val (piece, rest) = SpeechChunks.take("The first is at nine with the design team, the second is a lunch with Sarah.", 60)
        assertEquals("The first is at nine with the design team,", piece)
        assertEquals("the second is a lunch with Sarah.", rest)
    }

    @Test
    fun blankTextGivesNothing() {
        assertTrue(SpeechChunks.split("   ").isEmpty())
        assertTrue(SpeechChunks.split("").isEmpty())
    }

    @Test
    fun aSingleUnbrokenWordIsStillCut() {
        val pieces = SpeechChunks.split("a".repeat(500), first = 40, max = 150)
        assertTrue(pieces.all { it.length <= 150 })
        assertEquals(500, pieces.sumOf { it.length })
    }
}

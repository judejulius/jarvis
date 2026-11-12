package com.jarvis.assistant.voice

import com.jarvis.assistant.agent.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {
    private fun stream(c: SentenceChunker, text: String, step: Int = 3): List<String> {
        val out = ArrayList<String>()
        text.chunked(step).forEach { out += c.feed(it) }
        c.flush()?.let { out += it }
        return out
    }

    @Test fun speaksWholeSentencesAsTheyComplete() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.feed("The capital of France is Par"))
        assertEquals(listOf("The capital of France is Paris."), c.feed("is. It lies on the Se")) // complete once the next word starts
        val out = stream(SentenceChunker(), "The capital of France is Paris. It lies on the river Seine. It is large!")
        assertEquals(listOf("The capital of France is Paris.", "It lies on the river Seine.", "It is large!"), out)
    }

    @Test fun neverCutsDecimalsOrAbbreviationsInTheMiddle() {
        val out = stream(SentenceChunker(), "The price is 3.5 dollars for each item today. Dr. Rao will call you back later.")
        assertEquals("The price is 3.5 dollars for each item today.", out.first())
        assertTrue(out.joinToString(" ").contains("Dr. Rao"))
    }

    @Test fun shortSentencesAreMergedSoTheVoiceDoesNotStutter() {
        val out = stream(SentenceChunker(), "Yes. Sure. I can do that for you right now, no problem at all.")
        assertEquals(1, out.size)
        assertEquals("Yes. Sure. I can do that for you right now, no problem at all.", out[0])
    }

    @Test fun removesMarkdownMarks() {
        val out = stream(SentenceChunker(), "**Here** is a `short` answer for you to read now. # Next\nBullet line that is long enough to speak.")
        assertTrue(out.none { it.contains('*') || it.contains('`') || it.contains('#') })
    }

    @Test fun hindiSentenceEndsAreRespected() {
        val out = stream(SentenceChunker(minLength = 10), "आज मौसम बहुत अच्छा है। कल बारिश हो सकती है।")
        assertEquals(listOf("आज मौसम बहुत अच्छा है।", "कल बारिश हो सकती है।"), out)
    }

    @Test fun aVeryLongSentenceIsCutAtACommaInsteadOfMakingTheUserWait() {
        val long = ("word ".repeat(30) + "and then, ") + "word ".repeat(40) + "end."
        val c = SentenceChunker(minLength = 24, maxLength = 100)
        val pieces = c.feed(long)
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.all { it.length <= 110 })
    }

    @Test fun sseLines() {
        assertEquals(SseParser.Event.Text("Hello"), SseParser.parseLine("""data: {"choices":[{"delta":{"content":"Hello"}}]}"""))
        assertEquals(SseParser.Event.Text(" wor\"ld\n"), SseParser.parseLine("""data: {"choices":[{"index":0,"delta":{"role":"assistant","content":" wor\"ld\n"}}]}"""))
        assertEquals(SseParser.Event.Text("नमस्ते"), SseParser.parseLine("""data: {"choices":[{"delta":{"content":"\u0928\u092e\u0938\u094d\u0924\u0947"}}]}"""))
        assertEquals(SseParser.Event.Done, SseParser.parseLine("data: [DONE]"))
        assertEquals(SseParser.Event.Other, SseParser.parseLine("""data: {"choices":[{"delta":{"content":null}}]}"""))
        assertEquals(SseParser.Event.Other, SseParser.parseLine("""data: {"choices":[{"delta":{}}]}"""))
        assertEquals(SseParser.Event.Other, SseParser.parseLine(": keep-alive"))
        assertEquals(SseParser.Event.Other, SseParser.parseLine(""))
    }

    @Test fun streamerSpeaksPiecesInOrderAndReportsWhenDone() {
        val spoken = ArrayList<String>()
        var finishSpeaking: (() -> Unit)? = null
        val streamer = ReplyStreamer({ it() }) { text, done -> spoken += text; finishSpeaking = done }
        streamer.onDelta("The capital of France is Paris. It lies on the river Seine. ")
        assertEquals(listOf("The capital of France is Paris."), spoken) // second waits for the first to finish
        var doneCalled = false
        streamer.finish()
        streamer.whenDone { doneCalled = true }
        assertFalse(doneCalled)
        finishSpeaking!!.invoke()
        assertEquals(listOf("The capital of France is Paris.", "It lies on the river Seine."), spoken)
        assertFalse(doneCalled)
        finishSpeaking!!.invoke()
        assertTrue(doneCalled)
        assertEquals(2, streamer.spoken)
    }

    @Test fun theNextPieceIsPreparedWhileTheCurrentOneIsSpoken() {
        val events = ArrayList<String>()
        var finishSpeaking: (() -> Unit)? = null
        val streamer = ReplyStreamer({ it() }, prepare = { events += "prepare: $it" }) { text, done -> events += "speak: $text"; finishSpeaking = done }
        streamer.onDelta("The capital of France is Paris. ")
        assertEquals(listOf("speak: The capital of France is Paris."), events) // nothing to prepare yet
        streamer.onDelta("It lies on the river Seine. It is a very large city indeed. ")
        streamer.onDelta("And more text is still arriving")
        // Only the piece that comes next is prepared, and only once.
        assertEquals(listOf("speak: The capital of France is Paris.", "prepare: It lies on the river Seine."), events)
        finishSpeaking!!.invoke()
        assertEquals(
            listOf("speak: It lies on the river Seine.", "prepare: It is a very large city indeed."),
            events.drop(2),
        )
        streamer.cancel()
        finishSpeaking!!.invoke()
        assertEquals("nothing is prepared after a cancel", 4, events.size)
    }

    @Test fun cancelDropsWhatIsLeft() {
        val spoken = ArrayList<String>()
        var finishSpeaking: (() -> Unit)? = null
        val streamer = ReplyStreamer({ it() }) { text, done -> spoken += text; finishSpeaking = done }
        streamer.onDelta("First sentence is long enough to speak. Second sentence is also long enough. ")
        streamer.cancel()
        finishSpeaking!!.invoke()
        streamer.onDelta("More text that arrives late and is long enough. ")
        assertEquals(1, spoken.size)
        var doneCalled = false
        streamer.whenDone { doneCalled = true }
        assertFalse(doneCalled)
    }

    @Test fun doneIsCalledImmediatelyWhenNothingIsLeft() {
        val streamer = ReplyStreamer({ it() }) { _, done -> done() }
        streamer.onDelta("A complete sentence that is long enough. ")
        streamer.finish()
        var doneCalled = false
        streamer.whenDone { doneCalled = true }
        assertTrue(doneCalled)
        assertNull(null)
    }
}

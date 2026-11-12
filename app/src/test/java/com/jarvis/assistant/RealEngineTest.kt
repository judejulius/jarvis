package com.jarvis.assistant

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.voice.Kokoro
import com.jarvis.assistant.voice.SpeechModel
import com.jarvis.assistant.voice.VoiceInput
import com.jarvis.assistant.voice.WakeModel
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowAudioRecord
import com.jarvis.assistant.voice.ModelDownloader
import com.jarvis.assistant.voice.Speaker
import com.jarvis.assistant.voice.SpeechPacer
import com.jarvis.assistant.voice.VoiceSpeed
import com.jarvis.assistant.voice.makePaced
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.io.File
import java.nio.ByteBuffer
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Runs the app's real voice code against the real sherpa-onnx engine (Linux build) and real models.
 * Skipped unless test-native/ is set up (see test-native/README.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealEngineTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val native = File(System.getProperty("jarvis.nativeDir") ?: "test-native")
    private val models = File(native, "models")

    @Before
    fun loadNative() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val lib = File(native, "sherpa-onnx-v1.13.8-linux-x64-jni/lib")
        assumeTrue("real engine not set up", File(lib, "libsherpa-onnx-jni.so").exists() && File(models, "kokoro.tar.bz2").exists())
        System.load(File(lib, "libonnxruntime.so").absolutePath)
        System.load(File(lib, "libsherpa-onnx-jni.so").absolutePath)
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().clear().commit()
        org.robolectric.shadows.ShadowLog.stream = System.out
        JarvisService.resetForTest()
        seenListening = false
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (JarvisService.status == "Listening…") seenListening = true
            if (cond()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun installAll() {
        ModelDownloader.unpack(File(models, "kokoro.tar.bz2"), Kokoro.modelDir(ctx), ModelDownloader::openTarBz2)
        ModelDownloader.unpack(File(models, "kws.tar.bz2"), WakeModel.dir(ctx), ModelDownloader::openTarBz2)
        ModelDownloader.prune(WakeModel.dir(ctx), WakeModel.KEEP_FILES)
        ModelDownloader.unpack(File(models, "asr.tar.bz2"), SpeechModel.dir(ctx), ModelDownloader::openTarBz2)
        ModelDownloader.prune(SpeechModel.dir(ctx), SpeechModel.KEEP_FILES)
        assertTrue(Kokoro.isInstalled(ctx) && WakeModel.isInstalled(ctx) && SpeechModel.isInstalled(ctx))
    }

    /** Kokoro speech resampled to the microphone's 16 kHz, for feeding the simulated mic. */
    private val tts by lazy {
        val dir = Kokoro.modelDir(ctx)
        OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = File(dir, "model.int8.onnx").path, voices = File(dir, "voices.bin").path,
                tokens = File(dir, "tokens.txt").path, dataDir = File(dir, "espeak-ng-data").path,
            ), numThreads = 4)))
    }

    private fun say(text: String, voice: Int = 5): FloatArray {
        val a = tts.generate(text, voice, 1.0f)
        val ratio = a.sampleRate / 16000.0
        return FloatArray((a.samples.size / ratio).toInt()) { i ->
            val x = i * ratio; val j = x.toInt(); val f = (x - j).toFloat()
            a.samples[j] * (1 - f) + a.samples[minOf(j + 1, a.samples.size - 1)] * f
        }
    }

    private fun silence(seconds: Double) = FloatArray((seconds * 16000).toInt())

    /** A simulated microphone that plays [script] in real time, then silence. */
    private fun playIntoMic(vararg parts: FloatArray) {
        val script = parts.reduce { a, b -> a + b }
        var pos = 0
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                Thread.sleep(sizeInShorts * 1000L / 16000) // real time
                for (i in 0 until sizeInShorts) {
                    val v = if (pos < script.size) script[pos] else 0f
                    audioData[offsetInShorts + i] = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    pos++
                }
                return sizeInShorts
            }
        })
    }

    /** Status at the moment the scenario ended (before the service is shut down). */
    private var finalStatus = ""

    private fun runService(timeoutMs: Long, until: () -> Boolean): Boolean {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildService(JarvisService::class.java).create().startCommand(0, 1)
        try {
            return waitFor(timeoutMs, until)
        } finally {
            finalStatus = JarvisService.status
            println("STATUS: $finalStatus | task=${JarvisService.lastTask}")
            controller.destroy()
        }
    }

    @Test
    fun turningOffWhileModelsLoadKeepsTheMicClosed() {
        installAll()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                reads.incrementAndGet()
                Thread.sleep(sizeInShorts * 1000L / 16000)
                return sizeInShorts
            }
        })
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        // Turn on, then off straight away while the models are still loading.
        Robolectric.buildService(JarvisService::class.java).create().startCommand(0, 1).destroy()
        waitFor(5000) { false } // plenty of time for the models to finish loading
        val before = reads.get()
        waitFor(1000) { false }
        assertEquals("the microphone must stay closed after turning off", before, reads.get())
        assertEquals(0, before)
    }

    @Test
    fun oneBreathCommandIsHeardAndStarted() {
        installAll()
        playIntoMic(silence(1.5), say("Hey Jarvis, send my wife a message on WhatsApp and tell her I love her."), silence(6.0))
        val started = runService(40_000) { JarvisService.lastTask != null }
        assertTrue("command reached the agent: ${JarvisService.lastTask}", started)
        val task = JarvisService.lastTask!!.lowercase()
        assertTrue(task, "send my wife" in task && "app" in task && "love her" in task)
        assertFalse("wake word stripped: $task", task.startsWith("hey"))
    }

    @Test
    fun pauseAfterWakeWordStillGetsTheCommand() {
        installAll()
        playIntoMic(silence(1.0), say("Hey Jarvis."), silence(2.5), say("Turn on the flashlight."), silence(6.0))
        assertTrue(runService(40_000) { JarvisService.lastTask != null })
        assertTrue(JarvisService.lastTask!!, "turn on the flashlight" in JarvisService.lastTask!!.lowercase())
    }

    @Test
    fun saysSorryWhenNothingFollowsTheWakeWord() {
        installAll()
        playIntoMic(silence(1.0), say("Hey Jarvis."), silence(14.0))
        assertTrue(runService(40_000) { JarvisService.status == "Didn't catch that" })
        assertTrue("no task started", JarvisService.lastTask == null)
    }

    private var seenListening = false

    @Test
    fun soundAlikesDoNotWakeJarvis() {
        installAll()
        playIntoMic(silence(1.0), say("Hey Travis, how are you?"), silence(1.0), say("The harvest is almost ready."),
            silence(1.0), say("Have you seen my car keys?"), silence(2.0))
        runService(16_000) { JarvisService.status == "Listening…" }
        assertTrue("never woke: $finalStatus", finalStatus.startsWith("Say") && !seenListening)
    }

    @Test
    fun kokoroSpeaksWithTheRealModel() {
        installAll()
        val speaker = Speaker(ctx)
        assertTrue(speaker.usingKokoro)
        var done = false
        val t0 = System.currentTimeMillis()
        speaker.speak("Good evening. All systems are online.") { done = true }
        val finished = waitFor(60_000) { done }
        val ms = System.currentTimeMillis() - t0
        println("KOKORO finished=$finished samples=${Kokoro.lastSamplesPlayed} error=${Kokoro.lastError} ms=$ms")
        assertFalse("Kokoro failed to load: ${Kokoro.lastError}", Kokoro.failed)
        assertTrue("speech finished", finished)
        assertTrue("no error: ${Kokoro.lastError}", Kokoro.lastError == null)
        assertTrue("about 2+ seconds of speech played", Kokoro.lastSamplesPlayed > Kokoro.SAMPLE_RATE * 2)
        assertTrue("waited for playback to finish", ms >= Kokoro.lastSamplesPlayed * 1000 / Kokoro.SAMPLE_RATE)

        // Stock phrases are cached on disk after the first time and play from the cache.
        done = false
        speaker.speak("Yes?") { done = true }
        assertTrue(waitFor(30_000) { done })
        val firstYes = Kokoro.lastSamplesPlayed
        assertTrue("'Yes?' played", firstYes > Kokoro.SAMPLE_RATE / 4)
        val cache = File(ctx.cacheDir, "kokoro-phrases")
        assertTrue("'Yes?' cached", cache.listFiles().orEmpty().isNotEmpty())
        done = false
        speaker.speak("Yes?") { done = true }
        assertTrue(waitFor(10_000) { done })
        assertTrue("cached copy is identical", Kokoro.lastSamplesPlayed == firstYes)

        // New speech interrupts old speech; only the newest reports done.
        var firstDone = false
        var secondDone = false
        speaker.speak("This is a long sentence that will be interrupted before it finishes playing.") { firstDone = true }
        Thread.sleep(300)
        speaker.speak("Stop.") { secondDone = true }
        assertTrue(waitFor(30_000) { secondDone })
        assertFalse("interrupted speech doesn't report done", firstDone)
    }

    @Test
    fun theNextSentenceOfAStreamedReplyIsMadeWhileTheCurrentOnePlays() {
        installAll()
        val speaker = Speaker(ctx)
        val first = "I have checked your calendar and you have three meetings today."
        val second = "The first one is at nine with the design team in the main office."
        fun speakAndTime(text: String, during: () -> Unit = {}): Long {
            var done = false
            val t0 = System.currentTimeMillis()
            speaker.speak(text) { done = true }
            during()
            assertTrue("spoke: $text", waitFor(60_000) { done })
            return System.currentTimeMillis() - t0
        }
        fun audioMs() = Kokoro.lastSamplesPlayed * 1000 / Kokoro.SAMPLE_RATE

        // Before: the second sentence is only started once the first has finished, so it is made in silence.
        speakAndTime(first)
        val unpreparedMs = speakAndTime(second)
        val unpreparedWait = unpreparedMs - audioMs()

        // Now: it is made while the first plays, and starts at once.
        speakAndTime(first) { speaker.prepare(second) }
        val preparedMs = speakAndTime(second)
        val preparedWait = preparedMs - audioMs()

        println("KOKORO second sentence: ${unpreparedWait} ms of waiting when made after the first, ${preparedWait} ms when made during it")
        assertTrue("no error: ${Kokoro.lastError}", Kokoro.lastError == null)
        assertTrue("the whole sentence was played", audioMs() > 2500)
        // How much sooner depends on the computer: where Kokoro is slower than its own speech, the second sentence can't be ready in time.
        assertTrue("starts sooner: $unpreparedWait ms before, $preparedWait ms now", preparedWait < unpreparedWait - 500)

        // Something else spoken instead drops what was made ahead, and speech still works.
        speakAndTime(first) { speaker.prepare(second) }
        speakAndTime("Never mind, that is everything for now.")
        assertTrue(audioMs() > 1000)
        assertTrue("no error: ${Kokoro.lastError}", Kokoro.lastError == null)
    }

    @Test
    fun pacedPiecesLeaveShorterSilencesInLongSentencesThanFixedPieces() {
        installAll()
        // Long sentences, as a model's summary of a task often has. On this computer Kokoro makes speech
        // about as fast as it plays, like a phone that only just keeps up.
        val reply = "I have checked your calendar and you have three meetings today, the first is at nine with the design team in the main office, " +
            "the second is a lunch with Sarah at noon, and the last is a call with the client at four. " +
            "I also looked at the traffic on your usual route and it is heavier than normal this morning, so leaving ten minutes earlier would be sensible."
        tts.generate("Ready.", 5, 1f) // load the model first, so neither run pays for it

        // Before: the whole reply was handed to the voice, which plays each sentence from the thread that makes it.
        val before = RealtimeSpeaker()
        tts.generateWithCallback(reply, 5, 1f, Streamed { before.write(it); true })
        before.finish()

        // Now: paced pieces, made on one thread while another plays them.
        val after = RealtimeSpeaker()
        val queue = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
        val end = FloatArray(0)
        val player = Thread { while (true) { val c = queue.take(); if (c === end) break; after.write(c) }; after.finish() }.apply { start() }
        val speed = VoiceSpeed()
        val pacer = SpeechPacer(speed, 40, 150).also { it.startPlaying() }
        makePaced(reply, pacer, Kokoro.SAMPLE_RATE, out = { queue += it; true }) { piece, emit -> tts.generateWithCallback(piece, 5, 1f, Streamed(emit)) }
        queue += end
        player.join()

        println("KOKORO whole reply:  first sound ${before.firstSoundAt} ms, longest silence ${before.longestSilenceMs} ms, all silence ${before.silenceMs} ms, done ${before.doneAt} ms")
        println("KOKORO paced pieces: first sound ${after.firstSoundAt} ms, longest silence ${after.longestSilenceMs} ms, all silence ${after.silenceMs} ms, done ${after.doneAt} ms, speed ${speed.realTimeFactor}")
        assertTrue("starts sooner: ${before.firstSoundAt} ms before, ${after.firstSoundAt} ms now", after.firstSoundAt < before.firstSoundAt)
        assertTrue("less silence: ${before.silenceMs} ms before, ${after.silenceMs} ms now", after.silenceMs < before.silenceMs)
    }

    /** A speaker that plays in real time, like an AudioTrack: a write returns when its audio is almost over. */
    private class RealtimeSpeaker {
        private val bufferMs = 250L
        private var endAt = 0L
        var silenceMs = 0L
        var longestSilenceMs = 0L
        var firstSoundAt = 0L
        var doneAt = 0L
        private val started = System.currentTimeMillis()
        fun write(samples: FloatArray) {
            val now = System.currentTimeMillis()
            if (endAt == 0L) firstSoundAt = now - started
            else if (now > endAt) { silenceMs += now - endAt; longestSilenceMs = maxOf(longestSilenceMs, now - endAt) }
            endAt = maxOf(now, endAt) + samples.size * 1000L / Kokoro.SAMPLE_RATE
            val wake = endAt - bufferMs
            if (wake > now) Thread.sleep(wake - now)
        }
        fun finish() {
            val wait = endAt - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            doneAt = System.currentTimeMillis() - started
        }
    }

    private class Streamed(private val onChunk: (FloatArray) -> Boolean) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (onChunk(samples)) 1 else 0
    }

    // ---- Conversation ----

    /** A microphone that plays whatever is queued (in real time), and silence otherwise. */
    private val live = java.util.concurrent.LinkedBlockingDeque<Float>()
    private fun liveMic() {
        live.clear()
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                Thread.sleep(sizeInShorts * 1000L / 16000)
                for (i in 0 until sizeInShorts) {
                    val v = live.pollFirst() ?: 0f
                    audioData[offsetInShorts + i] = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                }
                return sizeInShorts
            }
        })
    }
    private fun speakIntoMic(vararg parts: FloatArray) = parts.forEach { p -> p.forEach { live.addLast(it) } }

    private fun mix(a: FloatArray, b: FloatArray, bStartsAt: Double): FloatArray {
        val offset = (bStartsAt * 16000).toInt()
        return FloatArray(maxOf(a.size, offset + b.size)) { i -> (if (i < a.size) a[i] else 0f) + (if (i >= offset && i - offset < b.size) b[i - offset] else 0f) }
    }
    private fun scaled(x: FloatArray, k: Float) = FloatArray(x.size) { x[it] * k }

    private fun loadedVoice(): VoiceInput {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val voice = VoiceInput(ctx) {}
        voice.echoCancellation = false // worst case: the phone has no echo cancellation at all
        var loaded = false
        voice.load { loaded = true }
        waitFor(30_000) { loaded }
        return voice
    }

    @Test
    fun ownEchoNeverInterruptsButTheUserDoes() {
        installAll()
        liveMic()
        val reply = "Done. I sent your wife a message on WhatsApp saying I love you. Is there anything else?"
        val echo = scaled(say(reply, voice = 9), 0.35f) // loud: Jarvis's voice coming straight back into the mic
        val voice = loadedVoice()
        voice.start()
        try {

        // 1. Only Jarvis's own echo: no interruption.
        var bargedIn = false
        voice.listenWhileSpeaking(reply) { bargedIn = true }
        speakIntoMic(silence(0.3), echo, silence(0.8))
        waitFor((echo.size / 16 + 1500).toLong()) { bargedIn }
        println("ECHO-ONLY bargedIn=$bargedIn heard=${voice.lastEchoHeard}")
        assertFalse("Jarvis interrupted itself", bargedIn)
        voice.stopListeningWhileSpeaking()

        // 2. The user talks over the same echo.
        voice.listenWhileSpeaking(reply) { bargedIn = true }
        speakIntoMic(silence(0.3), mix(echo, say("No, send it to my brother instead.", voice = 6), 1.5), silence(1.0))
        assertTrue("talking over Jarvis was noticed", waitFor(15_000) { bargedIn })
        var heard: String? = null
        var finished = false
        val t0 = System.currentTimeMillis()
        voice.listenForCommand { heard = it; finished = true }
        var lastPartial = ""
        waitFor(15_000) {
            val p = voice.partialText
            if (p != lastPartial) { println("PARTIAL +${System.currentTimeMillis() - t0}ms: $p"); lastPartial = p }
            finished
        }
        println("BARGE-IN heard=$heard after ${System.currentTimeMillis() - t0}ms, queued audio left=${live.size / 16}ms")
        assertTrue("$heard", heard != null && "brother" in heard!!.lowercase())
        } finally {
            voice.release()
        }
    }

    @Test
    fun soundAlikesInJarvissOwnSpeechDontStopIt() {
        installAll()
        liveMic()
        val replies = listOf(
            "The top story today is about the stock market, which dropped at the start of trading.",
            "Your next stop is Oxford Street. The shop closes at six, so there is plenty of time.",
            "I set a stopwatch for ten minutes. Tap the screen to pause it.",
        )
        val voice = loadedVoice()
        voice.start()
        try {
            val falseStops = mutableListOf<String>()
            for (reply in replies) for (jarvis in listOf(9, 5)) for (echo in listOf(0.15f, 0.35f)) {
                var interrupted = false
                voice.listenWhileSpeaking(reply) { interrupted = true }
                speakIntoMic(silence(0.3), scaled(say(reply, voice = jarvis), echo), silence(0.8))
                waitFor(12_000) { interrupted || live.isEmpty() }
                waitFor(800) { interrupted }
                if (interrupted) falseStops += "voice $jarvis echo $echo: ${reply.take(30)}"
                voice.stopListeningWhileSpeaking()
                live.clear()
            }
            println("SOUND-ALIKE false stops=$falseStops log=${voice.echoLog}")
            assertTrue(falseStops.toString(), falseStops.isEmpty())
        } finally {
            voice.release()
        }
    }

    @Test
    fun sayingStopOverJarvisStopsIt() {
        installAll()
        liveMic()
        val reply = "Here is what I found on the weather today. It will be sunny with a high of twenty two degrees."
        val echo = scaled(say(reply, voice = 9), 0.15f) // a phone's echo cancellation normally leaves far less
        val voice = loadedVoice()
        voice.start()
        try {
            val results = mutableListOf<String>()
            for (user in listOf(6, 1)) for (at in listOf(1.0, 2.0, 3.0)) {
                var bargedIn = false
                var stopped = false
                voice.listenWhileSpeaking(reply) { stop -> bargedIn = true; stopped = stop }
                speakIntoMic(silence(0.3), mix(echo, say("Stop.", voice = user), at), silence(1.0))
                val noticed = waitFor(15_000) { bargedIn }
                live.clear() // Jarvis stops talking, so its echo stops too
                var how = if (stopped) "keyword" else "transcript"
                if (noticed && !stopped) {
                    var heard: String? = null
                    var finished = false
                    voice.listenForCommand { heard = it; finished = true }
                    waitFor(10_000) { finished }
                    // Jarvis has stopped either way; what matters is that no misheard task starts.
                    // Null, "stop", or a single word (which the service treats as "stop") are all fine.
                    val h = heard
                    if (h != null && !VoiceInput.isStopCommand(h) && VoiceInput.words(h).size > 1) how = "misheard: $h"
                } else if (!noticed) how = "missed"
                voice.stopListeningWhileSpeaking()
                voice.cancelCommand()
                live.clear()
                waitFor(500) { false }
                results += "voice $user at ${at}s: $how"
            }
            println("STOP results=$results log=${voice.echoLog}")
            // Two voices at once with heavy echo is hard; across many runs about 9 in 10 are caught.
            val caught = results.count { it.endsWith("keyword") || it.endsWith("transcript") }
            assertTrue(results.toString(), caught >= 5)
        } finally {
            voice.release()
        }
    }

    @Test
    fun followUpNeedsNoWakeWord() {
        installAll()
        liveMic()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildService(JarvisService::class.java).create().startCommand(0, 1)
        try {
            assertTrue(waitFor(30_000) { JarvisService.status.startsWith("Say") })
            speakIntoMic(say("Hey Jarvis, turn on the flashlight."))
            assertTrue(waitFor(30_000) { JarvisService.lastTask != null })
            // No phone control in tests, so the task ends straight away with a spoken explanation.
            assertTrue(waitFor(60_000) { JarvisService.lastResult != null })
            val first = JarvisService.lastTask
            // Wait until Jarvis has finished replying and is listening for a follow-up.
            assertTrue("listening for a follow-up", waitFor(60_000) { JarvisService.phase == JarvisService.Companion.Phase.LISTENING })
            speakIntoMic(say("Now turn it off again."))
            assertTrue(waitFor(30_000) { JarvisService.lastTask != first })
            println("FOLLOW-UP task=${JarvisService.lastTask}")
            assertTrue(JarvisService.lastTask!!, "turn it off" in JarvisService.lastTask!!.lowercase())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun talkingOverTheReplyStartsTheNextCommand() {
        installAll()
        liveMic()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildService(JarvisService::class.java).create().startCommand(0, 1)
        try {
            assertTrue(waitFor(30_000) { JarvisService.status.startsWith("Say") })
            speakIntoMic(say("Hey Jarvis, turn on the flashlight."))
            assertTrue(waitFor(60_000) { JarvisService.lastResult != null })
            // Jarvis is now saying the result; interrupt it straight away.
            speakIntoMic(say("Never mind, open the camera instead."))
            assertTrue(waitFor(30_000) { JarvisService.lastTask?.lowercase()?.contains("camera") == true })
            println("BARGE-IN task=${JarvisService.lastTask}")
        } finally {
            controller.destroy()
        }
    }
}

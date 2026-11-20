package com.jarvis.assistant.voice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.CloudVoiceConfig
import com.jarvis.assistant.Settings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The cloud voices against a fake server: request format per provider, streaming, and errors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudVoiceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().clear().commit()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** 0.5 s of a 16-bit tone at 24 kHz, as the providers send it. */
    private fun pcm(): Buffer {
        val b = Buffer()
        for (i in 0 until 12000) {
            val v = (Math.sin(i * 0.05) * 8000).toInt()
            b.writeByte(v and 0xFF); b.writeByte((v shr 8) and 0xFF)
        }
        return b
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(20)
        }
    }

    private fun speak(config: CloudVoiceConfig): Pair<Boolean, String?> {
        var done = false
        var failed: String? = null
        CloudVoice().speak(config, "Good evening.", 1.0f, { done = true }) { failed = it }
        waitFor { done || failed != null }
        return done to failed
    }

    private fun url() = server.url("/v1").toString().trimEnd('/')

    @Test
    fun openAiAsksForStreamedPcmInJarvissStyle() {
        server.enqueue(MockResponse().setBody(pcm()))
        val (done, failed) = speak(CloudVoiceConfig("openai", "sk-test", "gpt-4o-mini-tts", "onyx", url()))
        assertNull(failed); assertTrue(done)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/audio/speech", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("pcm", body.getString("response_format"))
        assertEquals("onyx", body.getString("voice"))
        assertEquals("Good evening.", body.getString("input"))
        assertTrue(body.has("instructions"))
        assertTrue("audio was played", AudioOut.lastSamplesPlayed >= 11000)
    }

    @Test
    fun elevenLabsUsesItsStreamEndpoint() {
        server.enqueue(MockResponse().setBody(pcm()))
        val (done, _) = speak(CloudVoiceConfig("elevenlabs", "xi-key", "eleven_flash_v2_5", "VOICE123", url()))
        assertTrue(done)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/text-to-speech/VOICE123/stream?output_format=pcm_24000", req.path)
        assertEquals("xi-key", req.getHeader("xi-api-key"))
        assertEquals("eleven_flash_v2_5", JSONObject(req.body.readUtf8()).getString("model_id"))
    }

    @Test
    fun fishAudioSendsModelHeaderAndVoiceId() {
        server.enqueue(MockResponse().setBody(pcm()))
        val (done, _) = speak(CloudVoiceConfig("fish", "fish-key", "s2.1-pro", "abc123", url()))
        assertTrue(done)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/tts", req.path)
        assertEquals("Bearer fish-key", req.getHeader("Authorization"))
        assertEquals("s2.1-pro", req.getHeader("model"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("pcm", body.getString("format"))
        assertEquals(24000, body.getInt("sample_rate"))
        assertEquals("abc123", body.getString("reference_id"))
    }

    @Test
    fun customServerNeedsNoKey() {
        server.enqueue(MockResponse().setBody(pcm()))
        val (done, _) = speak(CloudVoiceConfig("custom", "", "kokoro", "bm_george", url()))
        assertTrue(done)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull(req.getHeader("Authorization"))
        assertFalse(JSONObject(req.body.readUtf8()).has("instructions"))
    }

    @Test
    fun badKeyIsReportedClearly() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided"}}"""))
        val (done, failed) = speak(CloudVoiceConfig("openai", "wrong", "gpt-4o-mini-tts", "onyx", url()))
        assertFalse(done)
        assertTrue(failed!!, "check the API key" in failed && "Incorrect API key" in failed)
    }

    @Test
    fun stalledRequestFallsBackOnceEvenIfTheProviderNeverSendsHeaders() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val voice = CloudVoice()
        val config = CloudVoiceConfig("custom", "", "tts-1", "alloy", url())
        val failures = mutableListOf<String>()
        var completions = 0
        voice.speak(config, "Hello.", 1f, { completions++ }) { failures += it }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600))

        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("didn't send audio"))
        assertEquals(voice.lastError, failures.single())
        // A fresh request can use the worker immediately after cancellation.
        server.enqueue(MockResponse().setBody(pcm()))
        voice.speak(config, "Next.", 1f, { completions++ }) { failures += it }
        waitFor { completions > 0 || failures.size > 1 }
        assertEquals(1, completions)
        assertEquals(1, failures.size)
        assertNull(voice.lastError)
    }

    @Test
    fun oneByteIsNotEnoughToStartPlayback() {
        server.enqueue(MockResponse().setBody(Buffer().writeByte(1)))
        val (done, error) = speak(CloudVoiceConfig("custom", "", "tts-1", "alloy", url()))
        assertFalse(done)
        assertTrue(error.orEmpty().contains("returned no audio"))
    }

    @Test
    fun slowStreamCanFinishAfterTheFirstAudioDeadline() {
        server.enqueue(MockResponse().setBody(pcm()).throttleBody(4800, 500, TimeUnit.MILLISECONDS))
        val (done, error) = speak(CloudVoiceConfig("custom", "", "tts-1", "alloy", url()))
        assertNull(error)
        assertTrue(done)
        assertEquals(12000L, AudioOut.lastSamplesPlayed)
    }

    @Test
    fun stoppingAStalledRequestSuppressesFallback() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val voice = CloudVoice()
        val config = CloudVoiceConfig("custom", "", "tts-1", "alloy", url())
        var callbacks = 0
        voice.speak(config, "Hello.", 1f, { callbacks++ }) { callbacks++ }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)
        voice.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, callbacks)
        assertNull(voice.lastError)
    }

    @Test
    fun speakerFallsBackToALocalVoiceWhenTheCloudFails() {
        server.enqueue(MockResponse().setResponseCode(500))
        val settings = Settings(ctx)
        settings.voiceEngine = "custom"
        settings.saveCloudVoice(CloudVoiceConfig("custom", "", "tts-1", "alloy", url()))
        var done = false
        val speaker = Speaker(ctx)
        speaker.speak("Hello there.") { done = true }
        waitFor { speaker.lastError != null }
        assertTrue(speaker.lastError!!, "error 500" in speaker.lastError!!)
        // Android's voice takes over and says the same sentence.
        waitFor { org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance() != null }
        val tts = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()!!
        shadowOf(tts).onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS)
        waitFor { shadowOf(tts).lastSpokenText != null }
        assertEquals("Hello there.", shadowOf(tts).lastSpokenText)
        waitFor { done }
        assertTrue("finishes once the backup voice is done", done)
    }

    @Test
    fun openAiReusesTheKeyFromAnOpenAiModel() {
        val settings = Settings(ctx)
        settings.saveProfile(com.jarvis.assistant.ModelProfile(id = "m1", name = "GPT", providerId = "openai", baseUrl = "https://api.openai.com/v1", apiKey = "sk-from-model", model = "gpt-5", effort = ""))
        assertEquals("sk-from-model", settings.cloudVoice("openai").apiKey)
    }
}

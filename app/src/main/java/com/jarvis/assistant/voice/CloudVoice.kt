package com.jarvis.assistant.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.CloudVoiceConfig
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Text-to-speech from a cloud provider (OpenAI, ElevenLabs, Fish Audio, or any OpenAI-compatible
 * server). Asks for raw PCM and plays it as it streams in, so Jarvis starts talking within a moment
 * instead of waiting for the whole clip.
 */
class CloudVoice {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cloud-voice") }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    @Volatile private var call: Call? = null

    /** The last problem, shown in Settings so a bad key or voice ID isn't a silent failure. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Speaks [text]. [onDone] runs on the main thread when playback ends; [onFail] instead if the
     * provider couldn't be reached or refused, so the caller can fall back to a local voice.
     * Neither runs if the speech is interrupted.
     */
    fun speak(config: CloudVoiceConfig, text: String, speed: Float, onDone: () -> Unit, onFail: (String) -> Unit) {
        val ticket = AudioOut.interrupt()
        call?.cancel()
        val (request, rate) = try {
            request(config, text, speed)
        } catch (e: Exception) {
            main.post {
                if (AudioOut.isCurrent(ticket)) {
                    val error = "Couldn't request speech from ${name(config)}: ${e.message}"
                    lastError = error
                    onFail(error)
                }
            }
            return
        }
        val c = http.newCall(request)
        call = c
        val waitingForAudio = AtomicBoolean(true)
        val timeout = Runnable {
            if (AudioOut.isCurrent(ticket) && waitingForAudio.compareAndSet(true, false)) {
                c.cancel()
                val error = "${name(config)} didn't send audio in time."
                lastError = error
                onFail(error)
            }
        }
        main.postDelayed(timeout, FIRST_AUDIO_TIMEOUT_MS)
        worker.execute {
            val failure = try {
                if (!AudioOut.isCurrent(ticket) || c.isCanceled()) return@execute
                c.execute().use { response ->
                    if (!response.isSuccessful) {
                        problem(config, response.code, response.body?.string().orEmpty())
                    } else if (response.body?.source()?.request(2) != true) {
                        "${name(config)} returned no audio."
                    } else {
                        // One complete 16-bit PCM sample is buffered. The rest can arrive at playback speed.
                        if (!waitingForAudio.compareAndSet(true, false) || !AudioOut.isCurrent(ticket)) return@execute
                        play(response.body!!.byteStream(), rate, ticket, c)
                        null
                    }
                }
            } catch (e: Exception) {
                if (!AudioOut.isCurrent(ticket) || c.isCanceled()) return@execute
                Log.w(TAG, "cloud voice failed", e)
                "Couldn't reach ${name(config)}: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                waitingForAudio.set(false)
                main.removeCallbacks(timeout)
            }
            main.post {
                if (AudioOut.isCurrent(ticket) && !c.isCanceled()) {
                    lastError = failure
                    if (failure == null) onDone() else onFail(failure)
                }
            }
        }
    }

    fun stop() {
        AudioOut.stop()
        call?.cancel()
    }

    private fun play(input: java.io.InputStream, rate: Int, ticket: Int, c: Call) {
        val player = AudioOut.Playback(ticket)
        val buf = ByteArray(9600) // 200 ms at 24 kHz
        var carry = -1 // an odd byte left over from the previous read
        while (true) {
            var n = input.read(buf, if (carry >= 0) 1 else 0, buf.size - 1)
            if (n < 0) break
            if (carry >= 0) { buf[0] = carry.toByte(); n += 1; carry = -1 }
            if (n % 2 == 1) { carry = buf[n - 1].toInt() and 0xFF; n -= 1 }
            if (n > 0 && !player.writePcm16(buf, n, rate)) { c.cancel(); return }
        }
        player.finish()
    }

    /** Builds the provider's request for raw 16-bit mono PCM. Returns the request and its sample rate. */
    private fun request(c: CloudVoiceConfig, text: String, speed: Float): Pair<Request, Int> {
        val json = "application/json".toMediaType()
        return when (c.id) {
            "elevenlabs" -> {
                val body = JSONObject()
                    .put("text", text)
                    .put("model_id", c.model)
                    .put("voice_settings", JSONObject().put("speed", speed.coerceIn(0.7f, 1.2f).toDouble()))
                Request.Builder()
                    .url("${c.baseUrl}/text-to-speech/${c.voice}/stream?output_format=pcm_24000")
                    .header("xi-api-key", c.apiKey)
                    .post(body.toString().toRequestBody(json))
                    .build() to 24000
            }
            "fish" -> {
                val body = JSONObject()
                    .put("text", text)
                    .put("format", "pcm")
                    .put("sample_rate", 24000)
                    .put("latency", "balanced")
                    .put("prosody", JSONObject().put("speed", speed.coerceIn(0.5f, 2f).toDouble()))
                if (c.voice.isNotBlank()) body.put("reference_id", c.voice)
                Request.Builder()
                    .url("${c.baseUrl}/tts")
                    .header("Authorization", "Bearer ${c.apiKey}")
                    .header("model", c.model)
                    .post(body.toString().toRequestBody(json))
                    .build() to 24000
            }
            else -> { // OpenAI and OpenAI-compatible servers
                val body = JSONObject()
                    .put("model", c.model)
                    .put("input", text)
                    .put("voice", c.voice)
                    .put("response_format", "pcm")
                    .put("speed", speed.coerceIn(0.25f, 4f).toDouble())
                if (c.model.startsWith("gpt-4o")) body.put("instructions", JARVIS_STYLE)
                val builder = Request.Builder()
                    .url("${c.baseUrl}/audio/speech")
                    .post(body.toString().toRequestBody(json))
                if (c.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${c.apiKey}")
                builder.build() to 24000
            }
        }
    }

    private fun problem(c: CloudVoiceConfig, code: Int, body: String): String {
        val detail = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optJSONObject("detail")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
                ?: o.optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull() ?: body.take(160)
        val hint = when (code) {
            401, 403 -> "check the API key"
            402 -> "the account is out of credit"
            404 -> "check the voice ID and model"
            429 -> "too many requests; try again shortly"
            else -> "error $code"
        }
        return "${name(c)}: $hint. $detail".trim()
    }

    private fun name(c: CloudVoiceConfig) = com.jarvis.assistant.CLOUD_VOICES.firstOrNull { it.id == c.id }?.name ?: c.id

    companion object {
        private const val FIRST_AUDIO_TIMEOUT_MS = 1500L
        private const val TAG = "CloudVoice"
        /** How gpt-4o voices are asked to sound. */
        const val JARVIS_STYLE = "Speak like a calm, composed British butler: warm, clear and concise, with a hint of dry wit."
    }
}

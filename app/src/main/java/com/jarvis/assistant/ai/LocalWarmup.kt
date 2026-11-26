package com.jarvis.assistant.ai

import android.os.SystemClock
import android.util.Log
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends a one-token request to the local model the moment "Hey Jarvis" is heard. That brings the model's weights back
 * into memory (Android may have paged them out while idle) and lets llama-server cache the fixed system prompt, so the
 * real request, a second or two later, does not pay for them. It never starts the server; if the server is not
 * answering it does nothing. A tool list can be passed, but Jarvis only warms the short chat prompt: a long prompt
 * would keep a one-slot server busy and delay the real question.
 */
object LocalWarmup {
    private const val MIN_GAP_MS = 30_000L
    private val inFlight = AtomicBoolean(false)

    @Volatile private var lastAt = -MIN_GAP_MS

    fun warmAsync(runtime: com.jarvis.assistant.runtime.EmbeddedRuntime, model: String, system: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < MIN_GAP_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastAt = now
        Thread({
            try {
                if (runtime.health() == ServerHealth.State.OK) {
                    val body = JSONObject().put("model", model).put("max_tokens", 1)
                        .put("messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", system))
                            .put(JSONObject().put("role", "user").put("content", "Hi")))
                    val request = Request.Builder().url(runtime.baseUrl() + "/chat/completions")
                        .header("Authorization", "Bearer ${runtime.apiKey()}")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                    runtime.client.newCall(request).execute().close()
                }
            } catch (e: java.io.IOException) {
                Log.i("JarvisTiming", "Warm-up unavailable")
            } finally {
                inFlight.set(false)
            }
        }, "jarvis-warmup").apply { isDaemon = true }.start()
    }

}

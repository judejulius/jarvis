package com.jarvis.assistant.ai

import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.agent.OpenAiCompatibleSession
import com.jarvis.assistant.agent.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

    fun warmAsync(baseUrl: String, model: String, system: String, tools: List<ToolSpec>, apiKey: String = "") {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < MIN_GAP_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastAt = now
        Thread({
            try {
                warm(baseUrl, model, system, tools, apiKey)
            } finally {
                inFlight.set(false)
            }
        }, "jarvis-warmup").apply { isDaemon = true }.start()
    }

    private fun warm(baseUrl: String, model: String, system: String, tools: List<ToolSpec>, apiKey: String) {
        val origin = Endpoints.origin(baseUrl) ?: return
        if (ServerHealth.check(origin, 400) != ServerHealth.State.OK) return
        val started = SystemClock.elapsedRealtime()
        var conn: HttpURLConnection? = null
        try {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 1)
                .put("temperature", 0)
                .put("cache_prompt", true)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", "Hi")),
                )
            if (tools.isNotEmpty()) body.put("tools", OpenAiCompatibleSession.toolsJson(tools))
            val c = URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 2000
            c.readTimeout = 120_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            c.inputStream.use { it.readBytes() }
            Log.i("JarvisTiming", "warmup code=$code total=${SystemClock.elapsedRealtime() - started}ms")
        } catch (e: Exception) {
            Log.i("JarvisTiming", "warmup skipped: ${e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }
}

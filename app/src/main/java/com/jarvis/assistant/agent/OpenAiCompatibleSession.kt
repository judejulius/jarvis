package com.jarvis.assistant.agent

import android.util.Base64
import android.util.Log
import android.os.SystemClock
import com.jarvis.assistant.ProviderConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Any server that speaks the OpenAI Chat Completions API with tool calling:
 * OpenAI, LM Studio, Ollama, OpenRouter, Gemini, Groq, vLLM, llama.cpp server, ...
 */
class OpenAiCompatibleSession(
    private val config: ProviderConfig,
    system: String,
    toolSpecs: List<ToolSpec>,
) : ChatSession {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS) // local models can be slow
        .build()

    private val cancelled = AtomicBoolean(false)
    private val activeCall = AtomicReference<Call?>(null)

    override fun cancel() {
        cancelled.set(true)
        activeCall.getAndSet(null)?.cancel()
    }

    private val tools = JSONArray().apply {
        toolSpecs.forEach { spec ->
            put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", spec.name).put("description", spec.description).put("parameters", spec.parameters)
                )
            )
        }
    }

    private val messages = mutableListOf(JSONObject().put("role", "system").put("content", system))

    override fun seedHistory(exchanges: List<Pair<String, String>>) {
        if (messages.size != 1) return
        exchanges.takeLast(3).forEach { (user, assistant) ->
            messages += JSONObject().put("role", "user").put("content", user)
            messages += JSONObject().put("role", "assistant").put("content", assistant)
        }
    }

    override fun send(userText: String?, toolOutputs: List<ToolOutput>): AssistantTurn {
        val images = mutableListOf<ByteArray>()
        for (out in toolOutputs) {
            messages += JSONObject().put("role", "tool").put("tool_call_id", out.callId)
                .put("content", if (out.isError) "ERROR: ${out.text}" else out.text)
            out.imageJpeg?.let { images += it }
        }
        if (userText != null || images.isNotEmpty()) {
            // Tool messages can't carry images, so screenshots go in a follow-up user message.
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", userText ?: "Screenshot of the current screen:"))
            images.forEach { jpeg ->
                parts.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    )
                )
            }
            messages += JSONObject().put("role", "user").put("content", if (images.isEmpty()) userText else parts)
        }

        val hasTools = tools.length() > 0
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray(trimmedHistory()))
            .put("max_tokens", if (hasTools) 512 else 192)
            .put("temperature", if (hasTools) 0.2 else 0.7)
            .put("top_p", 0.9)
            .apply {
                if (hasTools) {
                    put("tools", tools)
                    put("tool_choice", "auto")
                }
            }

        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        if (cancelled.get()) throw IOException("Cancelled")
        val call = http.newCall(request)
        activeCall.set(call)
        if (cancelled.get()) call.cancel()

        val requestStartedAt = SystemClock.elapsedRealtime()
        val json = try {
            val response = call.execute()
            val headersAt = SystemClock.elapsedRealtime()
            response.use { resp ->
                val text = resp.body?.string().orEmpty()
                val bodyReadAt = SystemClock.elapsedRealtime()
                if (!resp.isSuccessful) {
                    Log.e("JarvisAPI", "HTTP ${resp.code}: $text")
                    throw IOException("HTTP ${resp.code} from ${config.preset.label}: ${text.take(500)}")
                }
                val parsed = JSONObject(text)
                val parsedAt = SystemClock.elapsedRealtime()
                Log.i(
                    "JarvisTiming",
                    "provider=${config.preset.label} model=${config.model} " +
                        "headers=${headersAt - requestStartedAt}ms " +
                        "body=${bodyReadAt - headersAt}ms " +
                        "json=${parsedAt - bodyReadAt}ms " +
                        "total=${parsedAt - requestStartedAt}ms"
                )
                parsed
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }

        val choice = json.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")
        val calls = mutableListOf<ToolCall>()
        val rawCalls = message.optJSONArray("tool_calls")
        if (rawCalls != null) {
            for (i in 0 until rawCalls.length()) {
                val c = rawCalls.getJSONObject(i)
                // Some local servers omit ids; the follow-up tool message needs one either way.
                if (c.optString("id").isBlank()) c.put("id", "call_" + UUID.randomUUID().toString().take(8))
                val fn = c.getJSONObject("function")
                val args = when (val a = fn.opt("arguments")) {
                    is JSONObject -> a
                    is String -> runCatching { JSONObject(a.ifBlank { "{}" }) }.getOrElse { JSONObject() }
                    else -> JSONObject()
                }
                calls += ToolCall(c.getString("id"), fn.getString("name"), args)
            }
        }
        messages += JSONObject().put("role", "assistant")
            .put("content", message.optString("content", "").takeUnless { message.isNull("content") } ?: "")
            .apply { if (rawCalls != null && rawCalls.length() > 0) put("tool_calls", rawCalls) }

        val finish = choice.optString("finish_reason")
        val note = if (finish == "length") "Response was cut off (max tokens)." else null
        return AssistantTurn(message.optString("content", "").takeUnless { message.isNull("content") } ?: "", calls, note)
    }

    /**
     * Screen dumps are large and only the latest one matters, so older ones are shortened before
     * sending. This keeps long tasks inside the small context windows local models often have.
     */
    private fun trimmedHistory(): List<JSONObject> {
        val observationIdx = messages.indices.filter { i ->
            val m = messages[i]
            m.optString("role") == "tool" || (m.optString("role") == "user" && m.opt("content") is JSONArray)
        }
        val keep = observationIdx.takeLast(KEEP_FULL_OBSERVATIONS).toSet()
        return messages.mapIndexed { i, m ->
            if (i !in observationIdx || i in keep) return@mapIndexed m
            val copy = JSONObject(m.toString())
            when (val c = copy.opt("content")) {
                is String -> copy.put("content", c.substringBefore(SCREEN_MARKER).trim() + "\n(older screen omitted)")
                is JSONArray -> copy.put("content", "(older screenshot omitted)")
            }
            copy
        }
    }

    companion object {
        private const val KEEP_FULL_OBSERVATIONS = 1
        const val SCREEN_MARKER = "=== SCREEN"
    }
}

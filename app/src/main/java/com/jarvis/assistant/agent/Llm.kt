package com.jarvis.assistant.agent

import com.jarvis.assistant.ApiKind
import com.jarvis.assistant.ProviderConfig
import org.json.JSONObject

/** A tool the model can call, described with a JSON schema. Provider-neutral. */
data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

data class ToolCall(val id: String, val name: String, val arguments: JSONObject)

/** What the agent sends back after running a tool. */
data class ToolOutput(
    val callId: String,
    val text: String,
    val isError: Boolean = false,
    /** Optional JPEG screenshot to show the model. */
    val imageJpeg: ByteArray? = null,
)

data class AssistantTurn(
    val text: String,
    val toolCalls: List<ToolCall>,
    /** Set when the provider stopped for a reason the agent should surface (e.g. a refusal). */
    val stopNote: String? = null,
)

/**
 * One agent conversation. Each implementation keeps its history in the provider's native
 * format, which matters for providers (like Claude) whose responses must be replayed verbatim.
 */
interface ChatSession {
    /** Cancel an in-flight request when supported by the provider. */
    fun cancel() {}

    /** Seed earlier plain-text conversation exchanges before the first request. */
    fun seedHistory(exchanges: List<Pair<String, String>>) {}

    /** Send the initial user message, or tool outputs for the previous turn's tool calls. */
    fun send(userText: String?, toolOutputs: List<ToolOutput>): AssistantTurn
}

object Llm {
    /** Sends a tiny request to check the address, key and model. Returns the reply text; throws on failure. */
    fun test(config: ProviderConfig): String {
        val ping = ToolSpec("ping", "Reply received.", obj("ok" to bool("Always true."), required = listOf("ok")))
        val turn = newSession(config, "You are being tested. Reply with the single word OK.", listOf(ping)).send("Say OK.", emptyList())
        return turn.text.ifBlank { if (turn.toolCalls.isNotEmpty()) "OK (tool call)" else turn.stopNote ?: "OK" }
    }

    fun newSession(config: ProviderConfig, system: String, tools: List<ToolSpec>): ChatSession =
        when (config.preset.kind) {
            ApiKind.ANTHROPIC -> AnthropicSession(config, system, tools)
            ApiKind.OPENAI_COMPATIBLE -> OpenAiCompatibleSession(config, system, tools)
        }
}

package com.jarvis.assistant.agent

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.jarvis.assistant.ProviderConfig
import org.json.JSONObject
import kotlin.jvm.optionals.getOrNull

/** Claude via the official Anthropic Java SDK. History is append-only so thinking blocks replay intact. */
class AnthropicSession(
    private val config: ProviderConfig,
    private val system: String,
    toolSpecs: List<ToolSpec>,
) : ChatSession {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(config.apiKey)
        .apply { if (config.baseUrl.isNotBlank()) baseUrl(config.baseUrl.trimEnd('/')) }
        .build()

    private val tools: List<Tool> = toolSpecs.map { spec ->
        val props = spec.parameters.optJSONObject("properties") ?: JSONObject()
        val required = spec.parameters.optJSONArray("required")
        Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder().apply {
                            props.keys().forEach { putAdditionalProperty(it, JsonValue.from(jsonToPlain(props.get(it)))) }
                        }.build()
                    )
                    .required((0 until (required?.length() ?: 0)).map { required!!.getString(it) })
                    .build()
            )
            .build()
    }

    private val history = mutableListOf<MessageParam>()

    override fun seedHistory(exchanges: List<Pair<String, String>>) {
        if (history.isNotEmpty()) return
        exchanges.takeLast(3).forEach { (user, assistant) ->
            history += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(user)
                .build()
            history += MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .content(assistant)
                .build()
        }
    }

    override fun send(userText: String?, toolOutputs: List<ToolOutput>): AssistantTurn {
        val blocks = mutableListOf<ContentBlockParam>()
        // All tool results for one assistant turn go back in a single user message.
        for (out in toolOutputs) {
            val content = mutableListOf(
                ToolResultBlockParam.Content.Block.ofText(TextBlockParam.builder().text(out.text).build())
            )
            out.imageJpeg?.let { content += ToolResultBlockParam.Content.Block.ofImage(image(it)) }
            blocks += ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                    .toolUseId(out.callId)
                    .contentOfBlocks(content)
                    .isError(out.isError)
                    .build()
            )
        }
        if (userText != null) blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(userText).build())
        history += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build()

        val params = MessageCreateParams.builder()
            .model(config.model)
            .maxTokens(16000L)
            .system(system)
            .messages(history)
            // Top-level auto caching: the growing history is re-sent every step, so cache it.
            .cacheControl(CacheControlEphemeral.builder().build())
            .apply {
                tools.forEach { addTool(it) }
                if (config.effort.isNotBlank()) {
                    outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(config.effort)).build())
                }
                if (config.model in SERVER_FALLBACK_MODELS) {
                    // If a safety classifier declines the request, let the API retry it on a
                    // suitable model instead of stopping the task.
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

        val response = client.messages().create(params)
        history += response.toParam()

        if (response.stopReason().getOrNull() == StopReason.REFUSAL) {
            val why = response.stopDetails().getOrNull()?.explanation()?.getOrNull()
            return AssistantTurn("", emptyList(), stopNote = "The model declined this request${why?.let { ": $it" } ?: "."}")
        }

        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (block in response.content()) {
            block.text().getOrNull()?.let { text.append(it.text()) }
            block.toolUse().getOrNull()?.let { use ->
                val input = runCatching { JSONObject(use._input().convert(Map::class.java)) }.getOrElse { JSONObject() }
                calls += ToolCall(use.id(), use.name(), input)
            }
        }
        val note = if (response.stopReason().getOrNull() == StopReason.MAX_TOKENS) "Response was cut off (max tokens)." else null
        return AssistantTurn(text.toString(), calls, note)
    }

    private fun image(jpeg: ByteArray): ImageBlockParam = ImageBlockParam.builder()
        .source(
            Base64ImageSource.builder()
                .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                .data(Base64.encodeToString(jpeg, Base64.NO_WRAP))
                .build()
        )
        .build()

    companion object {
        /** Models that accept the server-side `fallbacks: "default"` parameter. */
        private val SERVER_FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    }
}

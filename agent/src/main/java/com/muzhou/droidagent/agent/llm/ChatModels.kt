package com.muzhou.droidagent.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class FunctionCall(
    val name: String = "",
    val arguments: String = "",
)

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall = FunctionCall(),
)

/**
 * OpenAI 兼容的聊天消息。content 可以是字符串，也可以是内容分片数组
 * （文本 / image_url），因此保留原始 JsonElement。
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    fun plainText(): String = when (val value = content) {
        null, is JsonNull -> ""
        is JsonPrimitive -> value.contentOrNull ?: value.toString()
        is JsonArray -> value.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> (element["text"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        }.joinToString("\n")
        is JsonObject -> (value["text"] as? JsonPrimitive)?.contentOrNull ?: ""
        else -> ""
    }

    fun imageParts(): List<String> = when (val value = content) {
        is JsonArray -> value.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "image_url") return@mapNotNull null
            val url = obj["image_url"] as? JsonObject ?: return@mapNotNull null
            (url["url"] as? JsonPrimitive)?.contentOrNull
        }
        else -> emptyList()
    }

    fun copyWithContent(text: String): ChatMessage = copy(content = JsonPrimitive(text))

    companion object {
        fun system(text: String) = ChatMessage("system", JsonPrimitive(text))
        fun user(text: String) = ChatMessage("user", JsonPrimitive(text))
        fun assistant(
            text: String,
            reasoning: String? = null,
            toolCalls: List<ToolCall>? = null,
        ) = ChatMessage(
            role = "assistant",
            content = if (text.isEmpty()) JsonNull else JsonPrimitive(text),
            reasoningContent = reasoning?.takeIf { it.isNotBlank() },
            toolCalls = toolCalls?.takeIf { it.isNotEmpty() },
        )
        fun tool(toolCallId: String, name: String, content: String) = ChatMessage(
            role = "tool",
            content = JsonPrimitive(content),
            toolCallId = toolCallId,
            name = name,
        )
        fun note(text: String, error: Boolean = false) = ChatMessage(
            role = if (error) "note_error" else "note",
            content = JsonPrimitive(text),
        )
    }
}

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

@Serializable
data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true,
)

@Serializable
data class PromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
    @SerialName("cache_creation_input_tokens") val cacheWriteInputTokens: Int? = null,
) {
    val cachedTokens: Int
        get() = promptCacheHitTokens
            ?: promptTokensDetails?.cachedTokens
            ?: cacheReadInputTokens
            ?: 0

    val uncachedTokens: Int
        get() = promptCacheMissTokens
            ?: (promptTokens - cachedTokens).coerceAtLeast(0)

    val cacheReadTokens: Int
        get() = promptTokensDetails?.cachedTokens ?: cacheReadInputTokens ?: cachedTokens

    val cacheWriteTokens: Int
        get() = promptTokensDetails?.cacheWriteTokens ?: cacheWriteInputTokens ?: 0

    val cacheHitPercent: Double
        get() = if (promptTokens > 0) cachedTokens * 100.0 / promptTokens else 0.0

    val effectiveTotal: Int
        get() = if (totalTokens > 0) totalTokens else promptTokens + completionTokens
}

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null,
)

@Serializable
data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionCallDelta? = null,
)

@Serializable
data class FunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatChunk(
    val id: String? = null,
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null,
)

@Serializable
data class CompletionChoice(
    val index: Int = 0,
    val message: ChatMessage = ChatMessage("assistant"),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class CompletionResponse(
    val id: String? = null,
    val choices: List<CompletionChoice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null,
)

package com.muzhou.droidagent.agent.llm

import com.muzhou.droidagent.agent.util.AgentJson
import com.muzhou.droidagent.agent.util.truncateTail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface LlmEvent {
    data class Content(val text: String) : LlmEvent
    data class Reasoning(val text: String) : LlmEvent
    data class ToolDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String?,
    ) : LlmEvent
    data class Finished(val reason: String?) : LlmEvent
    data class Used(val usage: Usage) : LlmEvent
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 极简但健壮的 OpenAI 兼容 Chat Completions 客户端。
 * 支持 SSE 流式、非流式、工具调用增量、reasoning_content。
 */
class OpenAiClient(
    private val httpClient: OkHttpClient = defaultClient(),
) {
    suspend fun streamChat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition> = emptyList(),
        onEvent: suspend (LlmEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val body = ChatRequest(
            model = config.model,
            messages = messages.withoutReasoning(),
            stream = true,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            reasoningEffort = config.reasoningEffort,
            tools = tools.takeIf { it.isNotEmpty() },
            toolChoice = if (tools.isNotEmpty()) "auto" else null,
            streamOptions = StreamOptions(includeUsage = true),
        )
        val request = buildRequest(config, body)
        val call = clientFor(config).newCall(request)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw LlmException(httpErrorMessage(resp.code, resp.body?.string()))
                }
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val source = resp.body?.source() ?: throw LlmException("响应体为空")
                if (contentType.contains("application/json") && !contentType.contains("event-stream")) {
                    val text = source.readUtf8()
                    emitFullCompletion(text, onEvent)
                    return@use
                }
                var dataBuffer = StringBuilder()
                var sawDone = false
                var finishReason: String? = null
                var emittedFinished = false

                suspend fun dispatch() {
                    val raw = dataBuffer.toString().trim()
                    dataBuffer = StringBuilder()
                    if (raw.isEmpty()) return
                    if (raw == "[DONE]") {
                        sawDone = true
                        return
                    }
                    val chunk = try {
                        AgentJson.json.decodeFromString(ChatChunk.serializer(), raw)
                    } catch (_: Exception) {
                        // 某些服务会发送 SSE 注释或非 JSON 心跳
                        return
                    }
                    chunk.error?.let { error ->
                        throw LlmException(error.message ?: "API 返回错误")
                    }
                    chunk.usage?.let { onEvent(LlmEvent.Used(it)) }
                    for (choice in chunk.choices) {
                        choice.delta.content?.takeIf { it.isNotEmpty() }?.let {
                            onEvent(LlmEvent.Content(it))
                        }
                        (choice.delta.reasoningContent ?: choice.delta.reasoning)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { onEvent(LlmEvent.Reasoning(it)) }
                        choice.delta.toolCalls?.forEach { delta ->
                            onEvent(
                                LlmEvent.ToolDelta(
                                    index = delta.index,
                                    id = delta.id,
                                    name = delta.function?.name,
                                    arguments = delta.function?.arguments,
                                ),
                            )
                        }
                        choice.finishReason?.let {
                            finishReason = it
                            emittedFinished = true
                        }
                    }
                }

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> dispatch()
                        line.startsWith("data:") -> {
                            dataBuffer.append(line.removePrefix("data:").trimStart()).append('\n')
                        }
                        line.startsWith("event:") || line.startsWith(":") || line.startsWith("id:") -> Unit
                        line.startsWith("{") -> dataBuffer.append(line).append('\n')
                        else -> Unit
                    }
                    if (sawDone) break
                }
                dispatch()
                onEvent(LlmEvent.Finished(finishReason))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw LlmException("网络错误：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            cancelHandle.dispose()
        }
    }

    suspend fun complete(
        config: LlmConfig,
        messages: List<ChatMessage>,
        maxTokens: Int = 1500,
        temperature: Double = 0.2,
    ): String = withContext(Dispatchers.IO) {
        val body = ChatRequest(
            model = config.model,
            messages = messages.withoutReasoning(),
            stream = false,
            temperature = temperature,
            maxTokens = maxTokens,
        )
        val request = buildRequest(config, body)
        val call = clientFor(config).newCall(request)
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw LlmException(httpErrorMessage(resp.code, text))
                }
                val parsed = try {
                    AgentJson.json.decodeFromString(CompletionResponse.serializer(), text)
                } catch (e: Exception) {
                    throw LlmException("无法解析 API 响应：${e.message}")
                }
                parsed.error?.let { throw LlmException(it.message ?: "API 返回错误") }
                parsed.choices.firstOrNull()?.message?.plainText()
                    ?: throw LlmException("API 未返回内容")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw LlmException("网络错误：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            cancelHandle.dispose()
        }
    }

    private suspend fun emitFullCompletion(
        text: String,
        onEvent: suspend (LlmEvent) -> Unit,
    ) {
        val parsed = try {
            AgentJson.json.decodeFromString(CompletionResponse.serializer(), text)
        } catch (e: Exception) {
            throw LlmException("无法解析 API 响应：${e.message}")
        }
        parsed.error?.let { throw LlmException(it.message ?: "API 返回错误") }
        val choice = parsed.choices.firstOrNull() ?: throw LlmException("API 未返回内容")
        parsed.usage?.let { onEvent(LlmEvent.Used(it)) }
        choice.message.reasoningContent?.takeIf { it.isNotBlank() }?.let {
            onEvent(LlmEvent.Reasoning(it))
        }
        choice.message.plainText().takeIf { it.isNotEmpty() }?.let {
            onEvent(LlmEvent.Content(it))
        }
        choice.message.toolCalls?.forEachIndexed { index, call ->
            onEvent(LlmEvent.ToolDelta(index, call.id, call.function.name, call.function.arguments))
        }
        onEvent(LlmEvent.Finished(choice.finishReason))
    }

    private fun buildRequest(config: LlmConfig, body: ChatRequest): Request {
        val payload = AgentJson.json.encodeToString(ChatRequest.serializer(), body)
        val url = endpoint(config.baseUrl).toHttpUrlOrNull()
            ?: throw LlmException("无效的 API 地址：${config.baseUrl}")
        val builder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA))
            .header("Accept", "text/event-stream, application/json")
            .header("User-Agent", "DroidAgent/1.0")
        if (config.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        config.extraHeaders.forEach { (key, value) ->
            if (key.isNotBlank()) builder.header(key.trim(), value)
        }
        return builder.build()
    }

    private fun endpoint(rawBase: String): String {
        val base = rawBase.trim().trimEnd('/')
        if (base.isEmpty()) throw LlmException("API 地址为空，请在设置中填写 Base URL")
        val url = base.toHttpUrlOrNull()
            ?: throw LlmException("无效的 API 地址：$rawBase")
        if (url.encodedPath.endsWith("/chat/completions")) return base
        val root = if (url.encodedPath.isBlank() || url.encodedPath == "/") "$base/v1" else base
        return "$root/chat/completions"
    }

    private fun httpErrorMessage(code: Int, body: String?): String {
        val raw = body.orEmpty()
        val parsed = AgentJson.parseObjectOrNull(raw)
        val error = parsed?.get("error")
        val message = when (error) {
            is kotlinx.serialization.json.JsonObject ->
                (error["message"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
        val detail = message ?: raw.takeIf { it.isNotBlank() }?.let { truncateTail(it, 800) } ?: "无响应内容"
        return "API 请求失败（HTTP $code）：$detail"
    }

    private fun clientFor(config: LlmConfig): OkHttpClient {
        val total = config.timeoutSeconds.coerceIn(30L, 1_800L)
        return httpClient.newBuilder()
            .callTimeout(total, TimeUnit.SECONDS)
            .readTimeout(minOf(total, 120L), TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}

private fun List<ChatMessage>.withoutReasoning(): List<ChatMessage> =
    if (none { it.reasoningContent != null }) this
    else map { if (it.reasoningContent != null) it.copy(reasoningContent = null) else it }

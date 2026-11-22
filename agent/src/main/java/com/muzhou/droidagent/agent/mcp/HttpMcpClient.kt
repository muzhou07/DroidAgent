package com.muzhou.droidagent.agent.mcp

import com.muzhou.droidagent.agent.util.AgentJson
import com.muzhou.droidagent.agent.util.truncateTail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP Streamable HTTP 客户端（MCP 2025-03-26）。
 * 同时兼容直接返回 application/json 的服务器。
 */
class HttpMcpClient(
    private val config: McpServerConfig,
    private val http: OkHttpClient = defaultHttp(),
) {
    private val mutex = Mutex()
    private val nextId = AtomicLong(1)
    private var sessionId: String? = null
    private var protocolVersion: String? = null
    private var connected = false

    fun matches(other: McpServerConfig): Boolean =
        other.url == config.url && other.headers == config.headers

    suspend fun connect() = mutex.withLock {
        if (connected) return@withLock
        if (!config.url.startsWith("http://") && !config.url.startsWith("https://")) {
            throw McpException("MCP 地址必须是 http(s) URL")
        }
        val params = buildJsonObject {
            put("protocolVersion", LATEST_PROTOCOL)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject {
                put("name", "DroidAgent")
                put("version", "1.0.0")
            })
        }
        val result = rpc("initialize", params, isNotification = false, force = true)
        protocolVersion = (result?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull ?: LATEST_PROTOCOL
        notify("notifications/initialized", null, force = true)
        connected = true
    }

    suspend fun listTools(): List<McpToolInfo> {
        connect()
        return mutex.withLock {
            val tools = ArrayList<McpToolInfo>()
            var cursor: String? = null
            var guard = 0
            do {
                val params = if (cursor == null) null else buildJsonObject { put("cursor", cursor) }
                val result = rpcLocked("tools/list", params)
                val array = result?.get("tools") as? JsonArray ?: break
                array.forEach { element ->
                    runCatching {
                        AgentJson.json.decodeFromString(McpToolInfo.serializer(), element.toString())
                    }.getOrNull()?.let(tools::add)
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
                guard++
            } while (cursor != null && guard < 20)
            tools
        }
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
        connect()
        val result = mutex.withLock {
            rpcLocked(
                "tools/call",
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
        } ?: throw McpException("MCP 工具调用未返回结果")
        return runCatching {
            AgentJson.json.decodeFromString(McpToolResult.serializer(), result.toString())
        }.getOrElse {
            val text = result["content"]?.toString() ?: result.toString()
            McpToolResult(content = listOf(McpContent(type = "text", text = text)))
        }
    }

    suspend fun close() = mutex.withLock {
        if (sessionId != null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(config.url)
                        .delete()
                        .header("Mcp-Session-Id", sessionId!!)
                        .apply { config.headers.forEach { (k, v) -> header(k, v) } }
                        .build()
                    http.newCall(request).execute().close()
                }
            }
        }
        connected = false
        sessionId = null
    }

    private suspend fun rpc(
        method: String,
        params: JsonObject?,
        isNotification: Boolean,
        force: Boolean = false,
    ): JsonObject? {
        if (!force) {
            connect()
            return mutex.withLock { rpcLocked(method, params, isNotification) }
        }
        return rpcLocked(method, params, isNotification)    }

    private suspend fun notify(
        method: String,
        params: JsonObject?,
        force: Boolean = false,
    ) {
        rpc(method, params, isNotification = true, force = force)
    }

    private suspend fun rpcLocked(
        method: String,
        params: JsonObject?,
        isNotification: Boolean = false,
    ): JsonObject? = withContext(Dispatchers.IO) {
        val id = if (isNotification) null else nextId.getAndIncrement()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val requestBuilder = Request.Builder()
            .url(config.url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        config.headers.forEach { (key, value) -> if (key.isNotBlank()) requestBuilder.header(key, value) }
        sessionId?.let { requestBuilder.header("Mcp-Session-Id", it) }
        protocolVersion?.let { requestBuilder.header("MCP-Protocol-Version", it) }
        val response: Response = try {
            http.newCall(requestBuilder.build()).execute()
        } catch (e: Exception) {
            throw McpException("无法连接 MCP 服务器：${e.message}")
        }
        response.use { resp ->
            resp.header("Mcp-Session-Id")?.let { sessionId = it }
            if (!resp.isSuccessful) {
                val body = runCatching { readBodyLimited(resp.body) }.getOrDefault("")
                throw McpException("MCP 请求失败 HTTP ${resp.code}：${truncateTail(body, 400)}")
            }
            if (isNotification || resp.code == 202 || resp.code == 204) return@withContext null
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            val body = resp.body ?: throw McpException("MCP 响应为空")
            if (contentType.contains("text/event-stream")) {
                val result = readSseForId(body.source(), id)
                result ?: throw McpException("MCP SSE 响应中未找到匹配的 id")
            } else {
                parseRpcResult(readBodyLimited(body))
            }
        }
    }

    private fun readSseForId(
        source: okio.BufferedSource,
        expectedId: Long?,
    ): JsonObject? {
        var data = StringBuilder()
        var total = 0L
        var lines = 0
        while (true) {
            val line = source.readUtf8Line() ?: break
            total += line.length
            if (++lines > MAX_SSE_LINES || total > MAX_RESPONSE_BYTES) {
                throw McpException("MCP SSE 响应过大，已中止")
            }
            if (line.isEmpty()) {
                val raw = data.toString().trim()
                data = StringBuilder()
                if (raw.isEmpty()) continue
                val obj = AgentJson.parseObjectOrNull(raw) ?: continue
                if (expectedId == null) continue
                val idElement = obj["id"]
                val idValue = (idElement as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                if (idValue == expectedId) return unwrapRpc(obj)
                continue
            }
            if (line.startsWith("data:")) {
                data.append(line.removePrefix("data:").trimStart()).append('\n')
            }
        }
        return null
    }

    private fun readBodyLimited(body: ResponseBody?): String {
        if (body == null) return ""
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) {
            throw McpException("MCP 响应过大（${declared} 字节），已拒绝读取")
        }
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = body.source().read(buffer, 64L * 1024L)
            if (read == -1L) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw McpException("MCP 响应超过 ${MAX_RESPONSE_BYTES / 1024 / 1024}MB，已拒绝读取")
            }
        }
        return buffer.readUtf8()
    }

    private fun parseRpcResult(text: String): JsonObject {
        val obj = AgentJson.parseObjectOrNull(text)
            ?: throw McpException("MCP 返回了无法解析的响应：${truncateTail(text, 300)}")
        return unwrapRpc(obj) ?: JsonObject(emptyMap())
    }

    private fun unwrapRpc(obj: JsonObject): JsonObject? {
        (obj["error"] as? JsonObject)?.let { error ->
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull
                ?: error.toString()
            throw McpException("MCP 错误：$message")
        }
        return when (val result = obj["result"]) {
            null -> null
            is JsonObject -> result
            else -> buildJsonObject { put("value", result) }
        }
    }

    companion object {
        const val LATEST_PROTOCOL = "2025-03-26"
        private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        private const val MAX_SSE_LINES = 200_000

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            // 不跟随重定向，避免把自定义 Authorization 头转发到其他主机。
            .followRedirects(false)
            .build()
    }
}

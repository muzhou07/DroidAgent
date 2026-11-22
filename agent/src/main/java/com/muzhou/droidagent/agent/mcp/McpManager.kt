package com.muzhou.droidagent.agent.mcp

import com.muzhou.droidagent.agent.tool.AgentTool
import com.muzhou.droidagent.agent.tool.ToolContext
import com.muzhou.droidagent.agent.tool.ToolResult
import com.muzhou.droidagent.agent.tool.Schemas
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.util.Locale

class McpManager(
    private val httpFactory: () -> okhttp3.OkHttpClient = { HttpMcpClient.defaultHttp() },
) {
    private val clients = LinkedHashMap<String, HttpMcpClient>()
    private val mutex = Mutex()
    private val _states = MutableStateFlow<List<McpServerState>>(emptyList())
    val states: StateFlow<List<McpServerState>> = _states.asStateFlow()

    suspend fun reload(configs: List<McpServerConfig>) = mutex.withLock {
        val enabled = configs.filter { it.enabled }
        val removed = clients.keys.filter { id -> enabled.none { it.id == id } }
        removed.forEach { id -> clients.remove(id)?.close() }
        _states.value = enabled.map { McpServerState(it) }
        for (config in enabled) {
            val old = clients[config.id]
            if (old != null && !old.matches(config)) {
                runCatching { old.close() }
                clients.remove(config.id)
            }
            val client = clients.getOrPut(config.id) { HttpMcpClient(config, httpFactory()) }
            try {
                client.connect()
                val tools = client.listTools()
                updateState(config.id) { it.copy(connected = true, tools = tools, error = null) }
            } catch (e: Exception) {
                updateState(config.id) {
                    it.copy(connected = false, tools = emptyList(), error = e.message ?: "连接失败")
                }
            }
        }
    }

    suspend fun closeAll() = mutex.withLock {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        _states.value = emptyList()
    }

    fun tools(): List<AgentTool> = _states.value
        .filter { it.connected }
        .flatMap { state -> state.tools.map { McpToolProxy(this, state.config, it) } }

    fun summary(): List<String> = _states.value.map { state ->
        val suffix = when {
            state.connected -> "已连接，${state.tools.size} 个工具"
            state.error != null -> "连接失败：${state.error}"
            else -> "未连接"
        }
        "- ${state.config.name}: $suffix"
    }

    suspend fun callTool(serverId: String, toolName: String, arguments: JsonObject): ToolResult {
        val state = _states.value.firstOrNull { it.config.id == serverId }
            ?: return ToolResult("MCP 服务器不存在或已禁用。", isError = true)
        val client = mutex.withLock {
            clients[serverId] ?: return@withLock null
        } ?: return ToolResult("MCP 服务器未连接：${state.config.name}", isError = true)
        return try {
            val result = client.callTool(toolName, arguments)
            val text = buildString {
                result.content.forEach { part ->
                    when (part.type) {
                        "text" -> append(part.text.orEmpty())
                        "image" -> append("[图片 ${part.mimeType ?: "image"}，base64 ${part.data?.length ?: 0} 字符]")
                        "resource" -> {
                            val resource = part.resource
                            if (resource?.text != null) append(resource.text)
                            else append("[资源 ${resource?.uri ?: part.uri ?: ""}]")
                        }
                        "resource_link" -> append("[资源链接 ${part.uri.orEmpty()}]")
                        else -> append(part.text ?: part.data ?: "")
                    }
                    append('\n')
                }
                result.structuredContent?.takeIf { it.isNotEmpty() }?.let { append(it.toString()) }
            }.trim()
            ToolResult(
                content = text.ifEmpty { "（MCP 工具执行成功，无文本输出）" },
                isError = result.isError == true,
                summary = "MCP · ${state.config.name}",
            )
        } catch (e: Exception) {
            ToolResult("MCP 工具调用失败：${e.message}", isError = true, summary = "MCP 调用失败")
        }
    }

    private fun updateState(id: String, transform: (McpServerState) -> McpServerState) {
        _states.value = _states.value.map { if (it.config.id == id) transform(it) else it }
    }
}

class McpToolProxy(
    private val manager: McpManager,
    private val server: McpServerConfig,
    private val remote: McpToolInfo,
) : AgentTool {
    override val name: String = buildToolName(server.name, remote.name)
    override val description: String
        get() = "[MCP: ${server.name}] " + (remote.description ?: remote.name)
    override val parameters: JsonObject
        get() = remote.inputSchema ?: Schemas.objectSchema(emptyMap())
    override val mutating: Boolean
        get() = remote.annotations?.readOnlyHint != true

    override fun summarize(arguments: JsonObject): String = "MCP ${server.name} · ${remote.name}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult =
        manager.callTool(server.id, remote.name, arguments)

    companion object {
        fun buildToolName(serverName: String, toolName: String): String {
            val sanitized = sanitize("mcp__${serverName}__$toolName")
            if (sanitized.length <= 64) return sanitized
            val hash = Integer.toHexString(sanitized.hashCode())
            return sanitized.take(55) + "_" + hash
        }

        private fun sanitize(value: String): String =
            value.map { ch ->
                if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch
                else '_'
            }.joinToString("").lowercase(Locale.US)
    }
}

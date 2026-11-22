package com.muzhou.droidagent.agent.tool

import com.muzhou.droidagent.agent.AgentEvent
import com.muzhou.droidagent.agent.AgentSessionContext
import com.muzhou.droidagent.agent.AgentSettings
import com.muzhou.droidagent.agent.mcp.McpManager
import com.muzhou.droidagent.agent.shell.ShellExecutor
import com.muzhou.droidagent.agent.shell.ShellJobManager
import com.muzhou.droidagent.agent.workspace.VirtualFileSystem
import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.agent.web.WebTools
import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ToolException(message: String) : Exception(message)

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val summary: String? = null,
)

data class ApprovalPreview(
    val title: String,
    val detail: String,
    val diff: String? = null,
)

data class ApprovalRequest(
    val toolName: String,
    val title: String,
    val detail: String,
    val diff: String? = null,
)

enum class ApprovalDecision { ALLOW_ONCE, ALLOW_SESSION, DENY }

fun interface ApprovalHandler {
    suspend fun request(request: ApprovalRequest): ApprovalDecision
}

data class SubAgentRequest(
    val description: String,
    val prompt: String,
    val depth: Int,
)

class ToolContext(
    val context: Context,
    val workspace: Workspace,
    val vfs: VirtualFileSystem,
    val shell: ShellExecutor?,
    val jobs: ShellJobManager?,
    val web: WebTools,
    val settings: AgentSettings,
    val session: AgentSessionContext,
    val mcp: McpManager?,
    val approval: ApprovalHandler,
    val events: (AgentEvent) -> Unit,
    val subAgentRunner: (suspend (SubAgentRequest) -> String)? = null,
)

interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    val mutating: Boolean get() = false

    fun summarize(arguments: JsonObject): String = name

    suspend fun approvalPreview(arguments: JsonObject, ctx: ToolContext): ApprovalPreview =
        ApprovalPreview(title = summarize(arguments), detail = arguments.toString().take(4000))

    suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult
}

class ToolRegistry(tools: List<AgentTool>) {
    private val byName = tools.associateBy { it.name }
    val tools: List<AgentTool> = tools

    fun find(name: String): AgentTool? = byName[name]
    fun all(): List<AgentTool> = tools
}

object Schemas {
    fun string(
        description: String,
        enum: List<String>? = null,
        default: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum != null) {
            putJsonArray("enum") { enum.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        if (default != null) put("default", default)
    }

    fun integer(description: String, default: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        if (default != null) put("default", default)
    }

    fun number(description: String, default: Double? = null): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", description)
        if (default != null) put("default", default)
    }

    fun boolean(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
        if (default != null) put("default", default)
    }

    fun array(description: String, items: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }

    fun objectSchema(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (name, schema) -> put(name, schema) }
        }
        putJsonArray("required") { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }

    fun todoItem(): JsonObject = objectSchema(
        mapOf(
            "content" to string("任务内容"),
            "status" to string("任务状态", enum = listOf("pending", "in_progress", "completed")),
        ),
        required = listOf("content", "status"),
    )
}

fun JsonObject.stringArg(name: String, default: String? = null): String =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: default
        ?: throw ToolException("缺少参数：$name")

fun JsonObject.optionalString(name: String): String? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

fun JsonObject.intArg(name: String, default: Int): Int =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default

fun JsonObject.boolArg(name: String, default: Boolean): Boolean =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default

fun JsonObject.requiredObject(name: String): JsonObject =
    this[name] as? JsonObject ?: throw ToolException("缺少参数：$name")

package com.muzhou.droidagent.agent

import com.muzhou.droidagent.agent.llm.LlmConfig
import kotlinx.serialization.Serializable

enum class PermissionMode { ASK, AUTO, READ_ONLY }

enum class AgentMode(val label: String) {
    PLAN("Plan"),
    ACTION("Action"),
}

enum class ReasoningEffort(val apiValue: String, val label: String) {
    NONE("none", "None"),
    LOW("low", "Low"),
    HIGH("high", "High"),
    MAX("max", "Max"),
}

data class AgentSettings(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val agentMode: AgentMode = AgentMode.ACTION,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    val temperature: Double? = 0.7,
    val maxTokens: Int? = null,
    val permissionMode: PermissionMode = PermissionMode.ASK,
    val contextWindowTokens: Int = 64_000,
    val maxIterations: Int = 40,
    val systemPromptExtra: String = "",
    val tavilyApiKey: String = "",
    val termuxEnabled: Boolean = false,
    val requestTimeoutSeconds: Long = 300,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    fun llmConfig(): LlmConfig = LlmConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort.apiValue,
        temperature = temperature,
        maxTokens = maxTokens,
        extraHeaders = extraHeaders,
        timeoutSeconds = requestTimeoutSeconds,
    )
}

@Serializable
data class TodoItem(
    val content: String,
    val status: String = "pending",
)

data class WorkspaceInfo(
    val id: String,
    val name: String,
    val kind: String,
    val location: String,
    val shellBackend: String?,
    val shellAvailable: Boolean,
)

class AgentSessionContext(
    val sessionId: String,
    val depth: Int = 0,
) {
    val allowedTools: MutableSet<String> = mutableSetOf()
    val todos: MutableList<TodoItem> = mutableListOf()
}

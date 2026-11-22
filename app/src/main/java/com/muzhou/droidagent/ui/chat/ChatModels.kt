package com.muzhou.droidagent.ui.chat

import com.muzhou.droidagent.agent.TodoItem
import com.muzhou.droidagent.agent.AgentEvent
import com.muzhou.droidagent.agent.llm.Usage
import com.muzhou.droidagent.agent.mcp.McpServerState
import com.muzhou.droidagent.agent.tool.ApprovalRequest
import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.data.AppSettings
import com.muzhou.droidagent.data.Session

enum class ToolStatus { WAITING, RUNNING, DONE, ERROR }

data class UiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val output: String = "",
    val status: ToolStatus = ToolStatus.WAITING,
    val summary: String? = null,
)

sealed interface ChatItem {
    val key: String

    data class User(
        override val key: String,
        val text: String,
        val attachments: List<String> = emptyList(),
        val imageCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
    ) : ChatItem

    data class Assistant(
        override val key: String,
        val text: String = "",
        val reasoning: String = "",
        val toolCalls: List<UiToolCall> = emptyList(),
        val streaming: Boolean = true,
        val interrupted: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
        val usage: Usage? = null,
        val elapsedMs: Long = 0L,
        val firstTokenMs: Long = 0L,
    ) : ChatItem

    data class Note(
        override val key: String,
        val text: String,
        val error: Boolean = false,
    ) : ChatItem
}

data class ChatUiState(
    val ready: Boolean = false,
    val sessions: List<Session> = emptyList(),
    val searchResults: Set<String> = emptySet(),
    val session: Session? = null,
    val workspaces: List<Workspace> = emptyList(),
    val workspace: Workspace? = null,
    val items: List<ChatItem> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null,
    val todo: List<TodoItem> = emptyList(),
    val pendingApproval: ApprovalRequest? = null,
    val usage: Usage? = null,
    val settings: AppSettings = AppSettings(),
    val mcpStates: List<McpServerState> = emptyList(),
    val contextStats: AgentEvent.ContextStats? = null,
)

data class AttachmentInput(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val isImage: Boolean,
)

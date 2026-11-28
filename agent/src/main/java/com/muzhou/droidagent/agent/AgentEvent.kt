package com.muzhou.droidagent.agent

import com.muzhou.droidagent.agent.llm.Usage

sealed interface AgentEvent {
    data class TurnStarted(val round: Int) : AgentEvent
    data class AssistantDelta(val messageId: String, val text: String) : AgentEvent
    data class ReasoningDelta(val messageId: String, val text: String) : AgentEvent
    data class RoundCommitted(val messageId: String, val hasToolCalls: Boolean) : AgentEvent
    data class ToolCallStarted(
        val toolCallId: String,
        val name: String,
        val arguments: String,
    ) : AgentEvent
    data class ToolCallFinished(
        val toolCallId: String,
        val name: String,
        val output: String,
        val isError: Boolean,
        val summary: String?,
    ) : AgentEvent
    data class TodoUpdated(val items: List<TodoItem>) : AgentEvent
    data class SubAgentStarted(val id: String, val description: String) : AgentEvent
    data class SubAgentFinished(
        val id: String,
        val description: String,
        val summary: String,
        val isError: Boolean,
    ) : AgentEvent
    data class UsageUpdated(
        val messageId: String,
        val usage: Usage?,
        val elapsedMs: Long,
        val firstTokenMs: Long,
    ) : AgentEvent
    data class ContextStats(
        val systemTokens: Int,
        val toolTokens: Int,
        val messageTokens: Int,
        val totalTokens: Int,
        val windowTokens: Int,
    ) : AgentEvent
    data object Compaction : AgentEvent
    data class Error(val message: String, val fatal: Boolean = true) : AgentEvent
    data object Cancelled : AgentEvent
    data object Finished : AgentEvent
}

package com.muzhou.droidagent.agent

import com.muzhou.droidagent.agent.llm.ChatMessage
import com.muzhou.droidagent.agent.llm.LlmEvent
import com.muzhou.droidagent.agent.llm.OpenAiClient
import com.muzhou.droidagent.agent.llm.ToolCall
import com.muzhou.droidagent.agent.llm.FunctionCall
import com.muzhou.droidagent.agent.llm.Usage
import com.muzhou.droidagent.agent.tool.AgentTool
import com.muzhou.droidagent.agent.tool.ApprovalDecision
import com.muzhou.droidagent.agent.tool.ApprovalRequest
import com.muzhou.droidagent.agent.tool.ToolContext
import com.muzhou.droidagent.agent.tool.ToolRegistry
import com.muzhou.droidagent.agent.tool.ToolResult
import com.muzhou.droidagent.agent.util.AgentJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.UUID

data class CommittedMessage(
    val id: String,
    val message: ChatMessage,
    val usage: Usage? = null,
    val elapsedMs: Long = 0L,
    val firstTokenMs: Long = 0L,
)

class ToolCallAccumulator {
    private class Acc(var id: String?, val name: StringBuilder, val args: StringBuilder)

    private val calls = sortedMapOf<Int, Acc>()

    fun accept(delta: LlmEvent.ToolDelta) {
        val acc = calls.getOrPut(delta.index) { Acc(null, StringBuilder(), StringBuilder()) }
        delta.id?.takeIf { it.isNotBlank() }?.let { acc.id = it }
        delta.name?.let { acc.name.append(it) }
        delta.arguments?.let { acc.args.append(it) }
    }

    fun build(): List<ToolCall> = calls.mapNotNull { (index, acc) ->
        val name = acc.name.toString().trim()
        if (name.isEmpty()) return@mapNotNull null
        ToolCall(
            id = acc.id ?: "call_${index}_${UUID.randomUUID().toString().take(6)}",
            function = FunctionCall(name = name, arguments = acc.args.toString()),
        )
    }
}

class AgentLoop(
    private val client: OpenAiClient,
    private val registry: ToolRegistry,
    private val toolContext: ToolContext,
    private val workspaceInfo: WorkspaceInfo,
    private val onMessage: suspend (CommittedMessage) -> Unit,
) {
    private val settingsProvider: () -> AgentSettings = { toolContext.settings }
    private val eventSink: (AgentEvent) -> Unit = toolContext.events
    private var projectRules: String? = null

    suspend fun run(history: MutableList<ChatMessage>) {
        projectRules = readProjectRules()
        val settings = settingsProvider()
        val maxRounds = settings.maxIterations.coerceIn(1, 200)
        if (history.none { it.role == "user" }) {
            eventSink(AgentEvent.Error("没有可处理的用户消息", fatal = true))
            return
        }
        var round = 0
        while (round < maxRounds) {
            currentCoroutineContext().ensureActive()
            round++
            eventSink(AgentEvent.TurnStarted(round))
            val system = buildSystemPrompt()
            maybeCompact(history, system)
            val finalSystem = buildSystemPrompt()
            val toolDefinitions = registry.all().map { tool ->
                com.muzhou.droidagent.agent.llm.ToolDefinition(
                    function = com.muzhou.droidagent.agent.llm.FunctionDefinition(
                        name = tool.name,
                        description = tool.description,
                        parameters = tool.parameters,
                    ),
                )
            }
            val systemTokens = TokenEstimator.estimateText(finalSystem)
            val toolTokens = toolDefinitions.sumOf { definition ->
                TokenEstimator.estimateText(definition.function.name) +
                    TokenEstimator.estimateText(definition.function.description) +
                    TokenEstimator.estimateText(definition.function.parameters.toString()) + 8
            }
            val messageTokens = TokenEstimator.estimateMessages(history)
            eventSink(
                AgentEvent.ContextStats(
                    systemTokens = systemTokens,
                    toolTokens = toolTokens,
                    messageTokens = messageTokens,
                    totalTokens = systemTokens + toolTokens + messageTokens,
                    windowTokens = settings.contextWindowTokens,
                ),
            )
            val requestMessages = buildList {
                add(ChatMessage.system(finalSystem))
                addAll(prepareForRequest(history))
            }
            val messageId = "asst_" + UUID.randomUUID().toString().take(8)
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val accumulator = ToolCallAccumulator()
            var streamError: String? = null
            var usage: Usage? = null
            var firstTokenMs = 0L
            val roundStart = System.currentTimeMillis()
            try {
                client.streamChat(
                    config = settings.llmConfig(),
                    messages = requestMessages,
                    tools = toolDefinitions,
                ) { event ->
                    when (event) {
                        is LlmEvent.Content -> {
                            if (firstTokenMs == 0L) {
                                firstTokenMs = System.currentTimeMillis() - roundStart
                            }
                            text.append(event.text)
                            eventSink(AgentEvent.AssistantDelta(messageId, event.text))
                        }
                        is LlmEvent.Reasoning -> {
                            if (firstTokenMs == 0L) {
                                firstTokenMs = System.currentTimeMillis() - roundStart
                            }
                            reasoning.append(event.text)
                            eventSink(AgentEvent.ReasoningDelta(messageId, event.text))
                        }
                        is LlmEvent.ToolDelta -> accumulator.accept(event)
                        is LlmEvent.Used -> usage = event.usage
                        is LlmEvent.Finished -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                val elapsedMs = System.currentTimeMillis() - roundStart
                if (text.isNotEmpty() || reasoning.isNotEmpty()) {
                    val partial = ChatMessage.assistant(text.toString(), reasoning.toString())
                    history.add(partial)
                    withContext(NonCancellable) {
                        onMessage(
                            CommittedMessage(
                                id = messageId,
                                message = partial,
                                usage = usage,
                                elapsedMs = elapsedMs,
                                firstTokenMs = firstTokenMs,
                            ),
                        )
                    }
                }
                eventSink(AgentEvent.Cancelled)
                return
            } catch (e: Exception) {
                streamError = e.message ?: e.javaClass.simpleName
            }

            val elapsedMs = System.currentTimeMillis() - roundStart
            val toolCalls = accumulator.build()
            if (text.isEmpty() && reasoning.isEmpty() && toolCalls.isEmpty()) {
                if (streamError != null) {
                    eventSink(AgentEvent.Error("模型请求失败：$streamError", fatal = true))
                } else {
                    eventSink(AgentEvent.Error("模型未返回任何内容", fatal = true))
                }
                eventSink(AgentEvent.Finished)
                return
            }

            val assistant = ChatMessage.assistant(
                text = text.toString(),
                reasoning = reasoning.toString().takeIf { it.isNotBlank() },
                toolCalls = toolCalls,
            )
            history.add(assistant)
            withContext(NonCancellable) {
                onMessage(
                    CommittedMessage(
                        id = messageId,
                        message = assistant,
                        usage = usage,
                        elapsedMs = elapsedMs,
                        firstTokenMs = firstTokenMs,
                    ),
                )
            }
            eventSink(AgentEvent.RoundCommitted(messageId, toolCalls.isNotEmpty()))
            eventSink(
                AgentEvent.UsageUpdated(
                    messageId = messageId,
                    usage = usage,
                    elapsedMs = elapsedMs,
                    firstTokenMs = firstTokenMs,
                ),
            )

            if (streamError != null) {
                eventSink(AgentEvent.Error("响应中断：$streamError", fatal = true))
                eventSink(AgentEvent.Finished)
                return
            }

            if (toolCalls.isEmpty()) {
                eventSink(AgentEvent.Finished)
                return
            }

            for (call in toolCalls) {
                currentCoroutineContext().ensureActive()
                val args = parseArguments(call.function.arguments)
                eventSink(AgentEvent.ToolCallStarted(call.id, call.function.name, call.function.arguments))
                val result = if (args == null) {
                    ToolResult("工具参数不是合法的 JSON：${call.function.arguments.take(500)}", isError = true)
                } else {
                    executeTool(call, args)
                }
                val toolMessage = ChatMessage.tool(call.id, call.function.name, result.content)
                history.add(toolMessage)
                withContext(NonCancellable) {
                    onMessage(CommittedMessage("msg_" + UUID.randomUUID().toString().take(8), toolMessage))
                }
                eventSink(
                    AgentEvent.ToolCallFinished(
                        toolCallId = call.id,
                        name = call.function.name,
                        output = result.content,
                        isError = result.isError,
                        summary = result.summary,
                    ),
                )
            }
        }
        eventSink(AgentEvent.Error("已达到最大工具调用轮次（$maxRounds），已停止。可发送“继续”让 Agent 接着做。", fatal = false))
        eventSink(AgentEvent.Finished)
    }

    private suspend fun executeTool(call: ToolCall, args: JsonObject): ToolResult {
        val tool = registry.find(call.function.name)
            ?: return ToolResult("未知工具：${call.function.name}", isError = true)
        if (toolContext.settings.agentMode == AgentMode.PLAN && tool.mutating) {
            return ToolResult(
                "当前为 Plan 模式，不能执行修改或命令操作：${tool.name}。请先给出计划，等待切换到 Action 模式。",
                isError = true,
            )
        }
        if (tool.mutating) {
            when (toolContext.settings.permissionMode) {
                PermissionMode.READ_ONLY -> return ToolResult(
                    "当前为只读权限模式，已拒绝写入/执行操作：${tool.name}",
                    isError = true,
                )
                PermissionMode.ASK -> {
                    if (tool.name !in toolContext.session.allowedTools) {
                        val preview = runCatching { tool.approvalPreview(args, toolContext) }
                            .getOrElse {
                                com.muzhou.droidagent.agent.tool.ApprovalPreview(
                                    title = tool.summarize(args),
                                    detail = args.toString().take(4000),
                                )
                            }
                        val decision = toolContext.approval.request(
                            ApprovalRequest(
                                toolName = tool.name,
                                title = preview.title,
                                detail = preview.detail,
                                diff = preview.diff,
                            ),
                        )
                        when (decision) {
                            ApprovalDecision.DENY -> return ToolResult(
                                "用户拒绝了工具调用“${tool.name}”。不要重复相同操作，可改用其他方案或询问用户。",
                                isError = true,
                            )
                            ApprovalDecision.ALLOW_SESSION -> toolContext.session.allowedTools.add(tool.name)
                            ApprovalDecision.ALLOW_ONCE -> Unit
                        }
                    }
                }
                PermissionMode.AUTO -> Unit
            }
        }
        return try {
            withTimeout(600_000) { tool.execute(args, toolContext) }
        } catch (timeout: TimeoutCancellationException) {
            ToolResult("工具执行超时（10 分钟）：${tool.name}", isError = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ToolResult("工具执行失败：${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private fun parseArguments(raw: String): JsonObject? {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return AgentJson.parseObjectOrNull(raw)
    }

    private fun buildSystemPrompt(): String = PromptBuilder.build(
        settings = toolContext.settings,
        workspace = workspaceInfo,
        todos = toolContext.session.todos,
        projectRules = projectRules,
        mcpSummary = toolContext.mcp?.summary() ?: emptyList(),
        depth = toolContext.session.depth,
    )

    private suspend fun readProjectRules(): String? {
        val candidates = listOf("AGENTS.md", "DROIDAGENT.md", "CLAUDE.md", ".droidagent/INSTRUCTIONS.md")
        val found = ArrayList<String>()
        for (name in candidates) {
            val text = runCatching {
                if (toolContext.vfs.exists(name)) toolContext.vfs.readText(name, maxBytes = 256 * 1024) else null
            }.getOrNull() ?: continue
            found.add("### $name\n${text.take(6000)}")
            if (found.size >= 2) break
        }
        return found.joinToString("\n\n").ifBlank { null }
    }

    private fun prepareForRequest(history: List<ChatMessage>): List<ChatMessage> = history.map { message ->
        val text = message.plainText()
        if (message.role == "tool" && text.length > 24_000) {
            message.copyWithContent(text.take(24_000) + "\n…（工具输出过长，已截断）")
        } else if (message.role == "assistant" && text.length > 32_000) {
            message.copyWithContent(text.take(32_000) + "\n…（内容已截断）")
        } else {
            message
        }
    }

    private suspend fun maybeCompact(history: MutableList<ChatMessage>, systemPrompt: String) {
        val window = settingsProvider().contextWindowTokens
        if (window < 8_000) return
        val estimated = TokenEstimator.estimateMessages(history) + TokenEstimator.estimateText(systemPrompt)
        if (estimated < (window * 0.75).toInt()) return
        if (history.size <= 6) return
        var split = (history.size - 8).coerceAtLeast(0)
        while (split > 0 && history[split].role == "tool") split--
        if (split <= 0) return
        val dropped = history.subList(0, split).toList()
        val transcript = dropped.joinToString("\n\n") { message ->
            "[${message.role}] " + message.plainText().take(3000)
        }
        val summary = try {
            client.complete(
                config = settingsProvider().llmConfig(),
                messages = listOf(
                    ChatMessage.system("你是对话压缩器，只输出摘要正文。"),
                    ChatMessage.user(PromptBuilder.compactionPrompt(transcript)),
                ),
                maxTokens = 1400,
                temperature = 0.2,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val summaryText = summary?.takeIf { it.isNotBlank() }
            ?: "（较早的 ${dropped.size} 条消息已省略以节省上下文。已修改内容仍保留在工作区中。）"
        history.subList(0, split).clear()
        history.add(
            0,
            ChatMessage.system(
                "[上下文压缩摘要]\n$summaryText\n\n（以上为历史对话摘要，后续请基于工作区真实文件继续工作。）",
            ),
        )
        eventSink(AgentEvent.Compaction)
    }
}

package com.muzhou.droidagent.data

import com.muzhou.droidagent.agent.AgentEvent
import com.muzhou.droidagent.agent.AgentLoop
import com.muzhou.droidagent.agent.AgentSessionContext
import com.muzhou.droidagent.agent.CommittedMessage
import com.muzhou.droidagent.agent.PromptBuilder
import com.muzhou.droidagent.agent.TokenEstimator
import com.muzhou.droidagent.agent.WorkspaceInfo
import com.muzhou.droidagent.agent.llm.ChatMessage
import com.muzhou.droidagent.agent.llm.OpenAiClient
import com.muzhou.droidagent.agent.mcp.McpManager
import com.muzhou.droidagent.agent.shell.ShellProvider
import com.muzhou.droidagent.agent.tool.AgentTool
import com.muzhou.droidagent.agent.tool.ApprovalDecision
import com.muzhou.droidagent.agent.tool.ApprovalHandler
import com.muzhou.droidagent.agent.tool.DeleteFileTool
import com.muzhou.droidagent.agent.tool.EditFileTool
import com.muzhou.droidagent.agent.tool.GlobTool
import com.muzhou.droidagent.agent.tool.GrepTool
import com.muzhou.droidagent.agent.tool.JobKillTool
import com.muzhou.droidagent.agent.tool.JobListTool
import com.muzhou.droidagent.agent.tool.JobOutputTool
import com.muzhou.droidagent.agent.tool.ListDirTool
import com.muzhou.droidagent.agent.tool.ReadFileTool
import com.muzhou.droidagent.agent.tool.ShellTool
import com.muzhou.droidagent.agent.tool.SubAgentRequest
import com.muzhou.droidagent.agent.tool.TaskTool
import com.muzhou.droidagent.agent.tool.TodoWriteTool
import com.muzhou.droidagent.agent.tool.ToolContext
import com.muzhou.droidagent.agent.tool.ToolRegistry
import com.muzhou.droidagent.agent.tool.WebFetchTool
import com.muzhou.droidagent.agent.tool.WebSearchTool
import com.muzhou.droidagent.agent.tool.WriteFileTool
import com.muzhou.droidagent.agent.web.WebTools
import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.agent.workspace.WorkspaceFactory
import android.content.Context

class AgentRunner(
    private val context: Context,
    private val db: SessionStore,
    private val mcp: McpManager,
    private val web: WebTools,
    private val client: OpenAiClient,
    private val jobs: com.muzhou.droidagent.agent.shell.ShellJobManager,
) {
    private data class Prepared(
        val registry: ToolRegistry,
        val context: ToolContext,
        val info: WorkspaceInfo,
    )

    suspend fun run(
        sessionId: String,
        workspace: Workspace,
        settings: AppSettings,
        history: MutableList<ChatMessage>,
        approval: ApprovalHandler,
        onEvent: (AgentEvent) -> Unit,
        onMessage: suspend (CommittedMessage) -> Unit,
    ) {        val prepared = prepare(
            sessionId = sessionId,
            workspace = workspace,
            settings = settings,
            depth = 0,
            approval = approval,
            events = onEvent,
        )
        val loop = AgentLoop(
            client = client,
            registry = prepared.registry,
            toolContext = prepared.context,
            workspaceInfo = prepared.info,
            onMessage = onMessage,
        )
        loop.run(history)
    }

    fun estimateContext(
        sessionId: String,
        workspace: Workspace,
        settings: AppSettings,
        history: List<ChatMessage>,
    ): AgentEvent.ContextStats {
        val prepared = prepare(
            sessionId = sessionId,
            workspace = workspace,
            settings = settings,
            depth = 0,
            approval = ApprovalHandler { ApprovalDecision.ALLOW_ONCE },
            events = {},
        )
        val agentSettings = settings.toAgentSettings()
        val system = PromptBuilder.build(
            settings = agentSettings,
            workspace = prepared.info,
            todos = emptyList(),
            projectRules = null,
            mcpSummary = mcp.summary(),
            depth = 0,
        )
        val systemTokens = TokenEstimator.estimateText(system)
        val toolTokens = prepared.registry.all().sumOf { tool ->
            TokenEstimator.estimateText(tool.name) +
                TokenEstimator.estimateText(tool.description) +
                TokenEstimator.estimateText(tool.parameters.toString()) + 8
        }
        val messageTokens = TokenEstimator.estimateMessages(history)
        return AgentEvent.ContextStats(
            systemTokens = systemTokens,
            toolTokens = toolTokens,
            messageTokens = messageTokens,
            totalTokens = systemTokens + toolTokens + messageTokens,
            windowTokens = agentSettings.contextWindowTokens,
        )
    }

    private fun prepare(
        sessionId: String,
        workspace: Workspace,
        settings: AppSettings,
        depth: Int,
        approval: ApprovalHandler,
        events: (AgentEvent) -> Unit,
    ): Prepared {
        val agentSettings = settings.toAgentSettings()
        val vfs = WorkspaceFactory.create(context, workspace)
        val shell = ShellProvider(context, settings.termuxEnabled).forWorkspace(workspace)
        val session = AgentSessionContext(sessionId, depth)
        val tools = ArrayList<AgentTool>().apply {
            add(ReadFileTool())
            add(WriteFileTool())
            add(EditFileTool())
            add(ListDirTool())
            add(GlobTool())
            add(GrepTool())
            add(DeleteFileTool())
            add(ShellTool())
            add(JobListTool())
            add(JobOutputTool())
            add(JobKillTool())
            add(WebSearchTool())
            add(WebFetchTool())
            add(TodoWriteTool())
            if (depth < 2) add(TaskTool())
            addAll(mcp.tools())
        }
        val registry = ToolRegistry(tools)
        val workspaceInfo = WorkspaceInfo(
            id = workspace.id,
            name = workspace.name,
            kind = when (workspace.kind) {
                com.muzhou.droidagent.agent.workspace.WorkspaceKind.LOCAL -> "应用私有"
                com.muzhou.droidagent.agent.workspace.WorkspaceKind.EXTERNAL -> "共享存储"
                com.muzhou.droidagent.agent.workspace.WorkspaceKind.SAF -> "SAF 目录"
            },
            location = WorkspaceFactory.locationLabel(workspace),
            shellBackend = shell?.backendName,
            shellAvailable = shell?.available == true,
        )
        val toolContext = ToolContext(
            context = context,
            workspace = workspace,
            vfs = vfs,
            shell = shell,
            jobs = jobs,
            web = web,
            settings = agentSettings,
            session = session,
            mcp = mcp,
            approval = approval,
            events = events,
            subAgentRunner = { request ->
                runSubAgent(workspace, settings, approval, request)
            },
        )
        return Prepared(registry, toolContext, workspaceInfo)
    }

    private suspend fun runSubAgent(
        workspace: Workspace,
        settings: AppSettings,
        approval: ApprovalHandler,
        request: SubAgentRequest,
    ): String {
        var fatal: String? = null
        var lastText = ""
        val prepared = prepare(
            sessionId = "sub_" + System.currentTimeMillis(),
            workspace = workspace,
            settings = settings,
            depth = request.depth,
            approval = approval,
            events = { event ->
                when (event) {
                    is AgentEvent.Error -> if (event.fatal) fatal = event.message
                    else -> Unit
                }
            },
        )
        val history = mutableListOf(ChatMessage.user(request.prompt))
        val loop = AgentLoop(
            client = client,
            registry = prepared.registry,
            toolContext = prepared.context,
            workspaceInfo = prepared.info,
            onMessage = { committed ->
                if (committed.message.role == "assistant") {
                    val text = committed.message.plainText().trim()
                    if (text.isNotEmpty()) lastText = text
                }
            },
        )
        loop.run(history)
        return lastText.ifBlank {
            fatal?.let { "子代理执行失败：$it" } ?: "（子代理未返回文本结果）"
        }
    }
}

package com.muzhou.droidagent.ui.chat

import com.muzhou.droidagent.agent.AgentEvent
import com.muzhou.droidagent.agent.CommittedMessage
import com.muzhou.droidagent.agent.ReasoningEffort
import com.muzhou.droidagent.agent.TodoItem
import com.muzhou.droidagent.agent.llm.ChatMessage
import com.muzhou.droidagent.agent.mcp.McpManager
import com.muzhou.droidagent.agent.tool.ApprovalDecision
import com.muzhou.droidagent.agent.tool.ApprovalHandler
import com.muzhou.droidagent.agent.tool.ApprovalRequest
import com.muzhou.droidagent.agent.util.oneLine
import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.agent.workspace.WorkspaceFactory
import com.muzhou.droidagent.agent.workspace.WorkspaceKind
import com.muzhou.droidagent.data.AppContainer
import com.muzhou.droidagent.data.AppSettings
import com.muzhou.droidagent.data.Session
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

class ChatViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val db = container.db
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var history: MutableList<ChatMessage> = mutableListOf()
    private var runningJob: Job? = null
    @Volatile
    private var lastAgentActivityAt = 0L
    private var searchJob: Job? = null
    private var approvalDeferred: CompletableDeferred<ApprovalDecision>? = null

    private val streamLock = Any()
    private val pendingText = LinkedHashMap<String, StringBuilder>()
    private val pendingReasoning = LinkedHashMap<String, StringBuilder>()
    private var flushJob: Job? = null

    private val approvalHandler = ApprovalHandler { request -> requestApproval(request) }

    init {
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            container.mcp.states.collect { states ->
                _state.update { it.copy(mcpStates = states) }
            }
        }
        viewModelScope.launch {
            container.jobs.events.collect { event ->
                val current = _state.value.session?.id
                if (current != null && current == event.ownerId) {
                    val status = when {
                        event.killed -> "已终止"
                        event.exitCode == 0 -> "已完成（退出码 0）"
                        event.exitCode != null -> "已结束（退出码 ${event.exitCode}）"
                        else -> "已结束"
                    }
                    addNote(
                        "后台任务 ${event.id} $status：" +
                            event.command.lineSequence().firstOrNull().orEmpty().take(60),
                    )
                }
            }
        }
        viewModelScope.launch { initialize() }
    }

    private suspend fun initialize() {
        var workspaces = withContext(Dispatchers.IO) { db.listWorkspaces() }
        if (workspaces.none { it.id == "default" }) {
            withContext(Dispatchers.IO) { db.ensureDefaultWorkspace() }
            workspaces = withContext(Dispatchers.IO) { db.listWorkspaces() }
        }
        val lastWorkspaceId = withContext(Dispatchers.IO) { container.settings.lastWorkspaceId() }
        val activeWorkspace = workspaces.firstOrNull { it.id == lastWorkspaceId }
            ?: workspaces.firstOrNull { it.id == "default" }
            ?: workspaces.first()
        withContext(Dispatchers.IO) {
            db.deleteEmptyDrafts()
        }
        val draft = withContext(Dispatchers.IO) { db.createSession("新对话", activeWorkspace.id) }
        val refreshedSessions = withContext(Dispatchers.IO) { db.listSessions() }
        _state.update {
            it.copy(
                sessions = refreshedSessions,
                workspaces = workspaces,
                ready = true,
            )
        }
        loadSessionInternal(draft)
    }

    private suspend fun loadSessionInternal(input: Session) {
        val session = withContext(Dispatchers.IO) { db.getSession(input.id) } ?: input
        val workspaces = withContext(Dispatchers.IO) { db.listWorkspaces() }
        val fallback = workspaces.firstOrNull { it.id == "default" } ?: workspaces.first()
        val workspace = workspaces.firstOrNull { it.id == session.workspaceId } ?: fallback
        if (workspace.id != session.workspaceId) {
            withContext(Dispatchers.IO) { db.updateSessionWorkspace(session.id, workspace.id) }
        }
        viewModelScope.launch {
            container.settings.saveLastWorkspaceId(workspace.id)
        }
        val stored = withContext(Dispatchers.IO) { db.listMessages(session.id) }
        val prepared = withContext(Dispatchers.Default) {
            val repaired = repairHistory(stored.map { it.message })
            repaired to buildItems(stored)
        }
        history = prepared.first
        _state.update {
            it.copy(
                session = session.copy(workspaceId = workspace.id),
                workspace = workspace,
                workspaces = workspaces,
                items = prepared.second,
                isRunning = false,
                error = null,
                todo = emptyList(),
                usage = null,
                contextStats = null,
                pendingApproval = null,
            )
        }
        viewModelScope.launch {
            val contextStats = withContext(Dispatchers.IO) {
                runCatching {
                    container.runner.estimateContext(
                        sessionId = session.id,
                        workspace = workspace,
                        settings = container.settings.settings.value,
                        history = prepared.first.toList(),
                    )
                }.getOrNull()
            }
            _state.update { current ->
                if (current.session?.id == session.id) current.copy(contextStats = contextStats) else current
            }
        }
    }

    fun newSession(workspaceId: String? = null) {
        if (_state.value.isRunning) return
        viewModelScope.launch {
            val targetWorkspaceId = workspaceId
                ?: _state.value.workspace?.id
                ?: "default"
            val current = _state.value.session
            if (current != null && current.title == "新对话") {
                val empty = withContext(Dispatchers.IO) { db.messageCount(current.id) } == 0
                if (empty) {
                    if (current.workspaceId == targetWorkspaceId) return@launch
                    withContext(Dispatchers.IO) { db.deleteSession(current.id) }
                }
            }
            val session = withContext(Dispatchers.IO) {
                db.createSession("新对话", targetWorkspaceId)
            }
            refreshSessions()
            loadSessionInternal(session)
        }
    }

    fun selectSession(sessionId: String) {
        if (_state.value.isRunning) {
            _state.update { it.copy(error = "请先停止当前任务，再切换会话") }
            return
        }
        if (_state.value.session?.id == sessionId) return
        viewModelScope.launch {
            val current = _state.value.session
            if (current != null && current.id != sessionId && current.title == "新对话") {
                val empty = withContext(Dispatchers.IO) { db.messageCount(current.id) } == 0
                if (empty) {
                    withContext(Dispatchers.IO) { db.deleteSession(current.id) }
                }
            }
            val session = withContext(Dispatchers.IO) { db.getSession(sessionId) } ?: return@launch
            loadSessionInternal(session)
            refreshSessions()
        }
    }

    fun searchSessions(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptySet()) }
            return
        }
        _state.update { it.copy(searchResults = emptySet()) }
        searchJob = viewModelScope.launch {
            delay(180)
            val ids = withContext(Dispatchers.IO) { db.searchSessionIds(query) }
            if (query.isNotBlank()) {
                _state.update { it.copy(searchResults = ids) }
            }
        }
    }

    fun renameSession(sessionId: String, title: String) {
        val trimmed = title.trim().ifBlank { "未命名会话" }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.updateSessionTitle(sessionId, trimmed) }
            _state.update { st ->
                st.copy(
                    sessions = st.sessions.map { if (it.id == sessionId) it.copy(title = trimmed) else it },
                    session = if (st.session?.id == sessionId) st.session.copy(title = trimmed) else st.session,
                )
            }
        }
    }

    fun deleteSession(sessionId: String) {
        if (_state.value.isRunning) return
        container.jobs.killOwner(sessionId)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteSession(sessionId) }
            val remaining = withContext(Dispatchers.IO) { db.listSessions() }
            if (remaining.isEmpty()) {
                val workspaceId = _state.value.workspace?.id ?: "default"
                val created = withContext(Dispatchers.IO) { db.createSession("新对话", workspaceId) }
                _state.update { it.copy(sessions = listOf(created)) }
                loadSessionInternal(created)
            } else {
                val next = if (_state.value.session?.id == sessionId) remaining.first() else _state.value.session ?: remaining.first()
                _state.update { it.copy(sessions = remaining) }
                if (_state.value.session?.id == sessionId) loadSessionInternal(next)
            }
        }
    }

    fun send(text: String, attachments: List<AttachmentInput> = emptyList()) {
        val snapshot = _state.value
        if (snapshot.isRunning) return
        val session = snapshot.session ?: return
        val workspace = snapshot.workspace ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        val message = buildUserMessage(trimmed, attachments)
        val userItem = userItemFromMessage(message, attachments.map { it.name })
        history.add(message)
        _state.update {
            it.copy(items = it.items + userItem, isRunning = true, error = null, usage = null)
        }
        runningJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { db.appendMessage(session.id, message) }
                viewModelScope.launch { generateTitle(session, trimmed, attachments) }
                executeRun(session, workspace, _state.value.settings)
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isRunning = false) }
            } catch (e: Exception) {
                handleEvent(AgentEvent.Error("发送失败：${e.message ?: e.javaClass.simpleName}", fatal = true))
                _state.update { it.copy(isRunning = false) }
            }
        }
    }

    fun regenerate() {
        if (_state.value.isRunning) return
        val session = _state.value.session ?: return
        val workspace = _state.value.workspace ?: return
        runningJob = viewModelScope.launch {
            try {
                val stored = withContext(Dispatchers.IO) { db.listMessages(session.id) }
                val lastUser = stored.lastOrNull { it.message.role == "user" } ?: return@launch
                withContext(Dispatchers.IO) { db.deleteMessagesAfterSeq(session.id, lastUser.seq) }
                val kept = stored.filter { it.seq <= lastUser.seq }
                val prepared = withContext(Dispatchers.Default) {
                    repairHistory(kept.map { it.message }) to buildItems(kept)
                }
                history = prepared.first
                _state.update { it.copy(items = prepared.second, isRunning = true, error = null, usage = null) }
                executeRun(session, workspace, _state.value.settings)
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isRunning = false) }
            } catch (e: Exception) {
                handleEvent(AgentEvent.Error("重新生成失败：${e.message ?: e.javaClass.simpleName}", fatal = true))
                _state.update { it.copy(isRunning = false) }
            }
        }
    }

    private suspend fun executeRun(session: Session, workspace: Workspace, settings: AppSettings) {
        _state.update { it.copy(isRunning = true, error = null) }
        lastAgentActivityAt = System.currentTimeMillis()
        val watchdog = viewModelScope.launch {
            while (true) {
                delay(15_000)
                if (System.currentTimeMillis() - lastAgentActivityAt > 12 * 60_000L) {
                    handleEvent(
                        AgentEvent.Error(
                            "任务长时间无响应，已自动停止。可发送「继续」接着上一次任务。",
                            fatal = true,
                        ),
                    )
                    runningJob?.cancel()
                    break
                }
            }
        }
        try {
            container.runner.run(
                sessionId = session.id,
                workspace = workspace,
                settings = settings,
                history = history,
                approval = approvalHandler,
                onEvent = { event -> handleEvent(event) },
                onMessage = { committed ->
                    withContext(Dispatchers.IO) {
                        db.appendMessage(
                            sessionId = session.id,
                            message = committed.message,
                            id = "db_${committed.id}",
                            usage = committed.usage,
                            elapsedMs = committed.elapsedMs,
                            firstTokenMs = committed.firstTokenMs,
                        )
                    }
                    ensureCommittedItem(committed)
                },
            )
        } catch (_: CancellationException) {
            // 取消由 AgentLoop 负责提交部分内容并发出事件
        } catch (e: Exception) {
            handleEvent(AgentEvent.Error("运行失败：${e.message ?: e.javaClass.simpleName}", fatal = true))
        } finally {
            watchdog.cancel()
            flushStreams()
            markAllAssistantDone()
            _state.update { it.copy(isRunning = false, pendingApproval = null) }
            refreshSessions()
        }
    }

    fun stop() {
        approvalDeferred?.complete(ApprovalDecision.DENY)
        approvalDeferred = null
        _state.update { it.copy(pendingApproval = null) }
        runningJob?.cancel()
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
        val current = _state.value.settings
        if (current.reasoningEffort == effort) return
        val next = current.copy(reasoningEffort = effort)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch { container.settings.save(next) }
    }

    fun resolveApproval(decision: ApprovalDecision) {
        approvalDeferred?.complete(decision)
        approvalDeferred = null
        _state.update { it.copy(pendingApproval = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun createLocalWorkspace(name: String) {
        viewModelScope.launch {
            try {
                val workspace = withContext(Dispatchers.IO) {
                    val workspace = WorkspaceFactory.createLocalWorkspace(container.context, name)
                    db.saveWorkspace(workspace)
                    workspace
                }
                afterWorkspaceCreated(workspace)
            } catch (e: Exception) {
                _state.update { it.copy(error = "创建工作区失败：${e.message}") }
            }
        }
    }

    fun createExternalWorkspace(name: String) {
        viewModelScope.launch {
            try {
                val workspace = withContext(Dispatchers.IO) {
                    val workspace = WorkspaceFactory.createExternalWorkspace(name)
                    db.saveWorkspace(workspace)
                    workspace
                }
                afterWorkspaceCreated(workspace)
            } catch (e: Exception) {
                _state.update { it.copy(error = "创建共享工作区失败：${e.message}") }
            }
        }
    }

    fun addSafWorkspace(uriString: String) {
        viewModelScope.launch {
            try {
                val uri = Uri.parse(uriString)
                val doc = DocumentFile.fromTreeUri(container.context, uri)
                val workspace = Workspace(
                    id = "saf_" + UUID.randomUUID().toString().take(8),
                    name = doc?.name?.ifBlank { "SAF 工作区" } ?: "SAF 工作区",
                    kind = WorkspaceKind.SAF,
                    uri = uriString,
                    createdAt = System.currentTimeMillis(),
                )
                withContext(Dispatchers.IO) { db.saveWorkspace(workspace) }
                afterWorkspaceCreated(workspace)
            } catch (e: Exception) {
                _state.update { it.copy(error = "打开 SAF 目录失败：${e.message}") }
            }
        }
    }

    private suspend fun afterWorkspaceCreated(workspace: Workspace) {
        val workspaces = withContext(Dispatchers.IO) { db.listWorkspaces() }
        _state.update { it.copy(workspaces = workspaces) }
    }

    fun deleteWorkspace(workspaceId: String) {
        if (workspaceId == "default") return
        if (_state.value.isRunning) return
        if (_state.value.sessions.any { it.workspaceId == workspaceId }) {
            _state.update { it.copy(error = "该工作区还有会话，请先删除会话") }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteWorkspace(workspaceId) }
            val workspaces = withContext(Dispatchers.IO) { db.listWorkspaces() }
            _state.update { it.copy(workspaces = workspaces) }
            refreshSessions()
        }
    }

    private suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        approvalDeferred = deferred
        _state.update { it.copy(pendingApproval = request) }
        return try {
            deferred.await()
        } finally {
            if (approvalDeferred === deferred) approvalDeferred = null
            _state.update { it.copy(pendingApproval = null) }
        }
    }

    fun refreshMcp() {
        container.refreshMcp()
    }

    private suspend fun generateTitle(
        session: Session,
        text: String,
        attachments: List<AttachmentInput>,
    ) {
        if (session.title != "新对话") return
        val seed = buildString {
            if (text.isNotBlank()) append(text.take(1500))
            if (attachments.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("附件：")
                append(attachments.joinToString("、") { it.name })
            }
        }
        val fallback = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(24)
            ?: attachments.firstOrNull()?.name?.take(24)
            ?: "新对话"
        val generated = try {
            withContext(Dispatchers.IO) {
                container.client.complete(
                    config = _state.value.settings.toAgentSettings().llmConfig(),
                    messages = listOf(
                        ChatMessage.system(
                            "你是对话标题生成器。根据用户的第一条消息，只输出一个不超过 12 个汉字的简短标题，" +
                                "不要引号、标点、编号或解释。",
                        ),
                        ChatMessage.user("用户的第一条消息：\n${seed.take(2000)}"),
                    ),
                    maxTokens = 32,
                    temperature = 0.2,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val title = generated
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.removePrefix("标题：")
            ?.removePrefix("标题:")
            ?.trim(' ', '"', '“', '”', '\'', '《', '》', '「', '」', '。', '.', '：', ':')
            ?.replace(Regex("\\s+"), " ")
            ?.take(24)
            ?.takeIf { it.isNotBlank() }
            ?: fallback
        val current = withContext(Dispatchers.IO) { db.getSession(session.id) }
        if (current == null || current.title != "新对话") return
        withContext(Dispatchers.IO) { db.updateSessionTitle(session.id, title) }
        _state.update { st ->
            st.copy(
                session = if (st.session?.id == session.id) st.session.copy(title = title) else st.session,
                sessions = st.sessions.map { if (it.id == session.id) it.copy(title = title) else it },
            )
        }
    }

    private fun refreshSessions() {
        viewModelScope.launch {
            val sessions = withContext(Dispatchers.IO) { db.listSessions() }
            _state.update { st ->
                st.copy(
                    sessions = sessions,
                    session = st.session?.let { current -> sessions.firstOrNull { it.id == current.id } ?: current },
                )
            }
        }
    }

    private fun buildUserMessage(text: String, attachments: List<AttachmentInput>): ChatMessage {
        if (attachments.isEmpty()) return ChatMessage.user(text)
        val array = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text.ifBlank { "请查看以下附件。" })
            })
            attachments.forEach { attachment ->
                if (attachment.isImage) {
                    val mime = attachment.mimeType.ifBlank { "image/png" }
                    val base64 = Base64.encodeToString(attachment.bytes, Base64.NO_WRAP)
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", "data:$mime;base64,$base64") })
                    })
                } else {
                    val content = if (looksBinary(attachment.bytes)) {
                        "[附件: ${attachment.name}]（二进制文件，未包含内容）"
                    } else {
                        "[附件: ${attachment.name}]\n" + String(attachment.bytes, Charsets.UTF_8).take(400_000)
                    }
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                }
            }
        }
        return ChatMessage(role = "user", content = array)
    }

    private fun looksBinary(bytes: ByteArray): Boolean =
        bytes.take(4000).any { it == 0.toByte() }

    private fun userItemFromMessage(message: ChatMessage, attachmentNames: List<String> = emptyList()): ChatItem.User {
        val array = message.content as? JsonArray
        if (array == null || array.isEmpty()) {
            return ChatItem.User(
                key = "user_" + UUID.randomUUID().toString().take(8),
                text = message.plainText(),
                attachments = attachmentNames,
            )
        }
        var text = ""
        val attachmentLabels = ArrayList<String>(attachmentNames)
        var images = 0
        array.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            if (type == "image_url") {
                images++
            } else if (type == "text") {
                val content = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (index == 0) {
                    text = content
                } else if (content.startsWith("[附件: ")) {
                    val name = content.removePrefix("[附件: ").substringBefore(']').substringBefore('）')
                    if (name.isNotBlank() && name !in attachmentLabels) attachmentLabels.add(name)
                }
            }
        }
        return ChatItem.User(
            key = "user_" + UUID.randomUUID().toString().take(8),
            text = text.ifBlank { if (images > 0 || attachmentLabels.isNotEmpty()) "（附件消息）" else "" },
            attachments = attachmentLabels,
            imageCount = images,
        )
    }

    private fun buildItems(stored: List<com.muzhou.droidagent.data.StoredMessage>): List<ChatItem> {
        val items = ArrayList<ChatItem>()
        val toolLocations = HashMap<String, Pair<Int, Int>>()
        for (entry in stored) {
            val message = entry.message
            when (message.role) {
                "user" -> items.add(userItemFromMessage(message).copy(createdAt = entry.createdAt))
                "assistant" -> {
                    if (message.plainText().isBlank() && message.toolCalls.isNullOrEmpty() &&
                        message.reasoningContent.isNullOrBlank()
                    ) {
                        continue
                    }
                    val calls = message.toolCalls.orEmpty().map { call ->
                        UiToolCall(
                            id = call.id,
                            name = call.function.name,
                            arguments = call.function.arguments,
                            status = ToolStatus.WAITING,
                        )
                    }
                    val index = items.size
                    items.add(
                        ChatItem.Assistant(
                            key = entry.id,
                            text = message.plainText(),
                            reasoning = message.reasoningContent.orEmpty(),
                            toolCalls = calls,
                            streaming = false,
                            createdAt = entry.createdAt,
                            usage = entry.usage,
                            elapsedMs = entry.elapsedMs,
                            firstTokenMs = entry.firstTokenMs,
                        ),
                    )
                    calls.forEachIndexed { callIndex, call -> toolLocations[call.id] = index to callIndex }
                }
                "tool" -> {
                    val location = message.toolCallId?.let { toolLocations[it] }
                    if (location != null) {
                        val assistant = items[location.first] as ChatItem.Assistant
                        val calls = assistant.toolCalls.toMutableList()
                        val call = calls[location.second]
                        val output = message.plainText()
                        calls[location.second] = call.copy(
                            output = output,
                            status = if (output.startsWith("工具执行失败") || output.startsWith("用户拒绝")) {
                                ToolStatus.ERROR
                            } else {
                                ToolStatus.DONE
                            },
                        )
                        items[location.first] = assistant.copy(toolCalls = calls)
                    }
                }
                "note" -> items.add(
                    ChatItem.Note(
                        key = entry.id,
                        text = message.plainText(),
                        error = false,
                    ),
                )
                "note_error" -> items.add(
                    ChatItem.Note(
                        key = entry.id,
                        text = message.plainText(),
                        error = true,
                    ),
                )
                else -> Unit
            }
        }
        return items.map { item ->
            if (item is ChatItem.Assistant && item.toolCalls.any { it.status == ToolStatus.WAITING }) {
                item.copy(
                    toolCalls = item.toolCalls.map { call ->
                        if (call.status == ToolStatus.WAITING) {
                            call.copy(
                                status = ToolStatus.ERROR,
                                output = "（该工具调用被中断，未返回结果。下次发送消息时会自动补全上下文。）",
                            )
                        } else {
                            call
                        }
                    },
                )
            } else {
                item
            }
        }
    }

    private fun repairHistory(messages: List<ChatMessage>): MutableList<ChatMessage> {
        val result = ArrayList<ChatMessage>()
        val pending = LinkedHashMap<String, String>()
        fun flushPending() {
            pending.forEach { (id, name) ->
                result.add(ChatMessage.tool(id, name, "（该工具调用被用户中断，没有返回结果。）"))
            }
            pending.clear()
        }
        for (message in messages) {
            if (message.role == "note" || message.role == "note_error") continue
            if (message.role == "tool") {
                val id = message.toolCallId ?: continue
                if (pending.remove(id) != null) result.add(message)
                continue
            }
            if (pending.isNotEmpty()) flushPending()
            if (message.role == "assistant") {
                if (message.plainText().isBlank() && message.toolCalls.isNullOrEmpty() &&
                    message.reasoningContent.isNullOrBlank()
                ) {
                    continue
                }
                message.toolCalls?.forEach { pending[it.id] = it.function.name }
            }
            result.add(message)
        }
        flushPending()
        return result
    }

    private fun ensureCommittedItem(committed: CommittedMessage) {
        if (committed.message.role != "assistant") return
        _state.update { st ->
            val index = st.items.indexOfFirst { it is ChatItem.Assistant && it.key == committed.id }
            if (index >= 0) {
                val item = st.items[index] as ChatItem.Assistant
                val items = st.items.toMutableList()
                items[index] = item.copy(
                    usage = committed.usage ?: item.usage,
                    elapsedMs = committed.elapsedMs.takeIf { it > 0 } ?: item.elapsedMs,
                    firstTokenMs = committed.firstTokenMs.takeIf { it > 0 } ?: item.firstTokenMs,
                )
                st.copy(items = items)
            } else {
                st.copy(
                    items = st.items + ChatItem.Assistant(
                        key = committed.id,
                        text = committed.message.plainText(),
                        reasoning = committed.message.reasoningContent.orEmpty(),
                        streaming = true,
                        usage = committed.usage,
                        elapsedMs = committed.elapsedMs,
                        firstTokenMs = committed.firstTokenMs,
                    ),
                )
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        lastAgentActivityAt = System.currentTimeMillis()
        when (event) {
            is AgentEvent.TurnStarted -> flushStreams()
            is AgentEvent.AssistantDelta -> enqueueDelta(event.messageId, event.text, false)
            is AgentEvent.ReasoningDelta -> enqueueDelta(event.messageId, event.text, true)
            is AgentEvent.RoundCommitted -> {
                flushStreams()
                markAssistantDone(event.messageId)
            }
            is AgentEvent.ToolCallStarted -> {
                flushStreams()
                addToolCall(event.toolCallId, event.name, event.arguments)
            }
            is AgentEvent.ToolCallFinished -> {
                updateToolCall(event.toolCallId) {
                    it.copy(
                        output = event.output,
                        status = if (event.isError) ToolStatus.ERROR else ToolStatus.DONE,
                        summary = event.summary,
                    )
                }
            }
            is AgentEvent.TodoUpdated -> _state.update { it.copy(todo = event.items) }
            is AgentEvent.UsageUpdated -> {
                _state.update { st ->
                    val items = st.items.map { item ->
                        if (item is ChatItem.Assistant && item.key == event.messageId) {
                            item.copy(
                                usage = event.usage ?: item.usage,
                                elapsedMs = event.elapsedMs.takeIf { it > 0 } ?: item.elapsedMs,
                                firstTokenMs = event.firstTokenMs.takeIf { it > 0 } ?: item.firstTokenMs,
                            )
                        } else {
                            item
                        }
                    }
                    st.copy(
                        items = items,
                        usage = event.usage ?: st.usage,
                    )
                }
            }
            is AgentEvent.ContextStats -> _state.update { it.copy(contextStats = event) }
            is AgentEvent.SubAgentStarted -> addNote("子代理启动：${event.description}")
            is AgentEvent.SubAgentFinished -> addNote(
                if (event.isError) "子代理失败：${event.description}" else "子代理完成：${event.description}",
                error = event.isError,
            )
            is AgentEvent.Compaction -> addNote("上下文已压缩：${event.summary}")
            is AgentEvent.Error -> {
                flushStreams()
                addNote(event.message, error = true)
                persistNote(event.message, error = true)
            }
            is AgentEvent.Cancelled -> {
                flushStreams()
                markAllAssistantDone(interrupted = true)
                _state.update { it.copy(isRunning = false) }
            }
            is AgentEvent.Finished -> {
                flushStreams()
                markAllAssistantDone()
            }
        }
    }

    private fun enqueueDelta(messageId: String, text: String, reasoning: Boolean) {
        synchronized(streamLock) {
            val target = if (reasoning) {
                pendingReasoning.getOrPut(messageId) { StringBuilder() }
            } else {
                pendingText.getOrPut(messageId) { StringBuilder() }
            }
            target.append(text)
        }
        if (flushJob?.isActive != true) {
            flushJob = viewModelScope.launch {
                delay(70)
                flushStreams()
            }
        }
    }

    private fun flushStreams() {
        val texts: Map<String, String>
        val reasonings: Map<String, String>
        synchronized(streamLock) {
            texts = pendingText.mapValues { it.value.toString() }.filterValues { it.isNotEmpty() }
            reasonings = pendingReasoning.mapValues { it.value.toString() }.filterValues { it.isNotEmpty() }
            pendingText.clear()
            pendingReasoning.clear()
        }
        if (texts.isEmpty() && reasonings.isEmpty()) return
        _state.update { st ->
            val items = st.items.toMutableList()
            (texts.keys + reasonings.keys).forEach { id ->
                val index = items.indexOfFirst { it is ChatItem.Assistant && it.key == id }
                if (index >= 0) {
                    val assistant = items[index] as ChatItem.Assistant
                    items[index] = assistant.copy(
                        text = assistant.text + texts[id].orEmpty(),
                        reasoning = assistant.reasoning + reasonings[id].orEmpty(),
                        streaming = true,
                    )
                } else {
                    items.add(
                        ChatItem.Assistant(
                            key = id,
                            text = texts[id].orEmpty(),
                            reasoning = reasonings[id].orEmpty(),
                            streaming = true,
                        ),
                    )
                }
            }
            st.copy(items = items)
        }
    }

    private fun markAssistantDone(messageId: String) {
        _state.update { st ->
            st.copy(items = st.items.map {
                if (it is ChatItem.Assistant && it.key == messageId) it.copy(streaming = false) else it
            })
        }
    }

    private fun markAllAssistantDone(interrupted: Boolean? = null) {
        _state.update { st ->
            val lastAssistantIndex = st.items.indexOfLast { it is ChatItem.Assistant }
            st.copy(items = st.items.mapIndexed { index, item ->
                if (item is ChatItem.Assistant) {
                    item.copy(
                        streaming = false,
                        interrupted = interrupted?.let { it && index == lastAssistantIndex }
                            ?: item.interrupted,
                    )
                } else {
                    item
                }
            })
        }
    }

    private fun addToolCall(toolCallId: String, name: String, arguments: String) {
        _state.update { st ->
            val items = st.items.toMutableList()
            var index = items.indexOfLast { it is ChatItem.Assistant }
            if (index < 0) {
                items.add(ChatItem.Assistant(key = "asst_" + UUID.randomUUID().toString().take(8), streaming = false))
                index = items.lastIndex
            }
            val assistant = items[index] as ChatItem.Assistant
            if (assistant.toolCalls.any { it.id == toolCallId }) return@update st
            items[index] = assistant.copy(
                toolCalls = assistant.toolCalls + UiToolCall(
                    id = toolCallId,
                    name = name,
                    arguments = arguments,
                    status = ToolStatus.RUNNING,
                ),
            )
            st.copy(items = items)
        }
    }

    private fun updateToolCall(toolCallId: String, transform: (UiToolCall) -> UiToolCall) {
        _state.update { st ->
            st.copy(items = st.items.map { item ->
                if (item is ChatItem.Assistant && item.toolCalls.any { it.id == toolCallId }) {
                    item.copy(toolCalls = item.toolCalls.map {
                        if (it.id == toolCallId) transform(it) else it
                    })
                } else {
                    item
                }
            })
        }
    }

    private fun addNote(text: String, error: Boolean = false) {
        _state.update {
            it.copy(items = it.items + ChatItem.Note("note_" + UUID.randomUUID().toString().take(8), text, error))
        }
    }

    private fun persistNote(text: String, error: Boolean) {
        if (text.isBlank()) return
        val sessionId = _state.value.session?.id ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { db.appendNote(sessionId, text, error) }
            }
            refreshSessions()
        }
    }

    override fun onCleared() {
        runningJob?.cancel()
        approvalDeferred?.complete(ApprovalDecision.DENY)
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(container) }
        }
    }
}

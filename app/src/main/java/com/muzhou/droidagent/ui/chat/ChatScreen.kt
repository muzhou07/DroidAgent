package com.muzhou.droidagent.ui.chat

import com.muzhou.droidagent.agent.AgentEvent
import com.muzhou.droidagent.agent.AgentMode
import com.muzhou.droidagent.agent.ReasoningEffort
import com.muzhou.droidagent.agent.llm.Usage
import com.muzhou.droidagent.ui.components.MiuixDropdownMenu
import com.muzhou.droidagent.ui.components.MiuixMenuItem
import com.muzhou.droidagent.ui.components.MiuixMenuPlacement
import com.muzhou.droidagent.ui.markdown.MarkdownText
import com.muzhou.droidagent.ui.theme.DsTheme
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(
    state: ChatUiState,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    onSend: (String, List<AttachmentInput>) -> Unit,
    onStop: () -> Unit,
    onApprove: (com.muzhou.droidagent.agent.tool.ApprovalDecision) -> Unit,
    onRegenerate: () -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onAgentModeChange: (AgentMode) -> Unit,
    onOpenModelSettings: () -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    var input by rememberSaveable(state.session?.id) { mutableStateOf("") }
    var attachments by remember(state.session?.id) { mutableStateOf<List<AttachmentInput>>(emptyList()) }
    var attachmentNotice by remember { mutableStateOf<String?>(null) }
    var expandedReasoningKey by remember(state.session?.id) { mutableStateOf<String?>(null) }
    var roundUsage by remember { mutableStateOf<RoundUsageData?>(null) }
    var showSessionStats by remember { mutableStateOf(false) }

    LaunchedEffect(attachmentNotice) {
        val notice = attachmentNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        attachmentNotice = null
    }

    val lastItem = state.items.lastOrNull()
    val lastAssistant = when (lastItem) {
        is ChatItem.Assistant -> lastItem
        else -> state.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
    }
    val lastAssistantKey = lastAssistant?.key
    val streamSize = when (lastItem) {
        is ChatItem.Assistant -> lastItem.text.length + lastItem.reasoning.length +
            lastItem.toolCalls.sumOf { it.output.length }
        else -> 0
    }
    LaunchedEffect(state.session?.id, state.items.size, streamSize, state.error) {
        if (state.items.isNotEmpty() || state.error != null) {
            val followLatest = !listState.canScrollForward ||
                state.items.lastOrNull() is ChatItem.User ||
                state.error != null
            if (followLatest) listState.scrollToBottom()
        }
    }

    val autoReasoningKey = lastAssistant
        ?.takeIf { it.streaming && it.reasoning.isNotBlank() }
        ?.key
    LaunchedEffect(state.session?.id, state.isRunning, autoReasoningKey) {
        expandedReasoningKey = if (state.isRunning) autoReasoningKey else null
    }

    val sessionStats = remember(
        state.session?.id,
        state.items.size,
        state.isRunning,
        state.contextStats,
        state.settings.contextWindow,
        lastAssistant?.usage,
        lastAssistant?.elapsedMs,
        lastAssistant?.firstTokenMs,
    ) {
        val assistants = state.items.filterIsInstance<ChatItem.Assistant>()
        var ttftSum = 0L
        var ttftCount = 0
        var modelTimeMs = 0L
        var promptTokens = 0
        var cachedTokens = 0
        var uncachedTokens = 0
        var cacheReadTokens = 0
        var cacheWriteTokens = 0
        var outputTokens = 0
        var totalTokens = 0
        assistants.forEach { item ->
            modelTimeMs += item.elapsedMs
            if (item.firstTokenMs > 0) {
                ttftSum += item.firstTokenMs
                ttftCount++
            }
            item.usage?.let { usage ->
                totalTokens += usage.effectiveTotal
                promptTokens += usage.promptTokens
                cachedTokens += usage.cachedTokens
                uncachedTokens += usage.uncachedTokens
                cacheReadTokens += usage.cacheReadTokens
                cacheWriteTokens += usage.cacheWriteTokens
                outputTokens += usage.completionTokens
            }
        }
        SessionStatsData(
            rounds = state.items.count { it is ChatItem.User },
            steps = assistants.size,
            modelTimeMs = modelTimeMs,
            averageTtftMs = if (ttftCount > 0) ttftSum / ttftCount else 0L,
            tokensPerSecond = if (modelTimeMs > 0) outputTokens * 1000.0 / modelTimeMs else 0.0,
            totalTokens = totalTokens,
            promptTokens = promptTokens,
            cachedTokens = cachedTokens,
            uncachedTokens = uncachedTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens,
            outputTokens = outputTokens,
            cacheHitPercent = if (promptTokens > 0) cachedTokens * 100.0 / promptTokens else 0.0,
            contextStats = state.contextStats,
            windowTokens = state.settings.contextWindow,
        )
    }
    val modelLabel = remember(state.settings.baseUrl, state.settings.model) {
        providerModelLabel(state.settings.baseUrl, state.settings.model)
    }
    val turnUsage = remember(
        state.session?.id,
        state.items.size,
        state.isRunning,
        lastAssistant?.usage,
        lastAssistant?.elapsedMs,
    ) {
        buildTurnUsageMap(state.items)
    }

    fun readUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val result = ArrayList<AttachmentInput>()
            val rejected = ArrayList<String>()
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    runCatching {
                        val resolver = context.contentResolver
                        var name = "附件"
                        var size = -1L
                        resolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                            }
                        }
                        val mime = resolver.getType(uri) ?: "application/octet-stream"
                        val isImage = mime.startsWith("image/")
                        val isText = mime.startsWith("text/") || mime.contains("json") ||
                            mime.contains("xml") || mime.contains("javascript") || name.matches(
                            Regex(".*\\.(md|txt|kt|java|py|js|ts|tsx|jsx|json|xml|yaml|yml|html|css|gradle|kts|toml|sh|rs|go|c|cpp|h)$", RegexOption.IGNORE_CASE),
                        )
                        if (!isImage && !isText) {
                            rejected.add("$name（不支持的类型）")
                            return@runCatching
                        }
                        val limit = if (isImage) 4 * 1024 * 1024 else 512 * 1024
                        if (size > limit) {
                            rejected.add("$name（超过 ${Formatter.formatShortFileSize(context, limit.toLong())}）")
                            return@runCatching
                        }
                        val stream = resolver.openInputStream(uri) ?: return@runCatching
                        val bytes = stream.use { source ->
                            val buffer = ByteArray(limit + 1)
                            var total = 0
                            while (total < buffer.size) {
                                val read = source.read(buffer, total, buffer.size - total)
                                if (read <= 0) break
                                total += read
                            }
                            buffer.copyOf(total)
                        }
                        if (bytes.size > limit) {
                            rejected.add("$name（文件过大）")
                            return@runCatching
                        }
                        result.add(AttachmentInput(name, mime, bytes, isImage))
                    }.onFailure { rejected.add("读取附件失败") }
                }
            }
            attachments = attachments + result
            if (rejected.isNotEmpty()) attachmentNotice = "已跳过：" + rejected.joinToString("、").take(300)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        readUris(uris)
    }

    if (!state.ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.brand)
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            ChatTopBar(
                title = state.session?.title ?: "DroidAgent",
                workspaceName = state.workspace?.name ?: "默认工作区",
                compact = LocalConfiguration.current.screenWidthDp < 600,
                onOpenDrawer = onOpenDrawer,
                onNewSession = onNewSession,
                onOpenFiles = onOpenFiles,
                onOpenTerminal = onOpenTerminal,
            )
            Box(Modifier.weight(1f)) {
                if (state.items.isEmpty() && state.error == null) {
                    val greeting = remember(state.session?.id) { NEW_CHAT_GREETINGS.random() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = greeting,
                            fontSize = 18.sp,
                            color = colors.textSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(state.items, key = { it.key }) { item ->
                            when (item) {
                                is ChatItem.User -> UserMessage(item)
                                is ChatItem.Assistant -> AssistantMessage(
                                    item = item,
                                    isLast = item.key == lastAssistantKey,
                                    shownUsage = turnUsage[item.key]?.usage,
                                    shownElapsedMs = turnUsage[item.key]?.elapsedMs ?: 0L,
                                    reasoningExpanded = expandedReasoningKey == item.key,
                                    onToggleReasoning = {
                                        expandedReasoningKey =
                                            if (expandedReasoningKey == item.key) null else item.key
                                    },
                                    onRegenerate = onRegenerate,
                                    onShowUsage = { usage, elapsed ->
                                        roundUsage = RoundUsageData(usage, elapsed, modelLabel)
                                    },
                                    onCopy = { text ->
                                        clipboard.setText(AnnotatedString(text))
                                        scope.launch {
                                            snackbarHostState.showSnackbar("已复制")
                                        }
                                    },
                                )
                                is ChatItem.Note -> NoteRow(item)
                            }
                        }
                        state.error?.let { message ->
                            item(key = "state_error") {
                                ErrorLine(message)
                            }
                        }
                    }
                }
                ScrollToBottomButton(
                    visible = state.items.isNotEmpty() && listState.canScrollForward,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    onClick = { scope.launch { listState.animateScrollToBottom() } },
                )
            }
            if (state.todo.isNotEmpty()) {
                TodoPanel(todos = state.todo)
            }
            Composer(
                value = input,
                onValueChange = { input = it },
                attachments = attachments,
                onRemoveAttachment = { index -> attachments = attachments.filterIndexed { i, _ -> i != index } },
                onPickAttachments = { picker.launch(arrayOf("*/*")) },
                onOpenModelSettings = onOpenModelSettings,
                onSend = {
                    if (input.isNotBlank() || attachments.isNotEmpty()) {
                        onSend(input, attachments)
                        input = ""
                        attachments = emptyList()
                    }
                },
                onStop = onStop,
                isRunning = state.isRunning,
                reasoningEffort = state.settings.reasoningEffort,
                onReasoningEffortChange = onReasoningEffortChange,
                agentMode = state.settings.agentMode,
                onAgentModeChange = onAgentModeChange,
                stats = sessionStats,
                onOpenStats = { showSessionStats = true },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 150.dp, start = 16.dp, end = 16.dp),
        )
    }

    state.pendingApproval?.let { request ->
        ApprovalSheet(request = request, onDecision = onApprove)
    }

    roundUsage?.let { data ->
        RoundUsageSheet(data = data, onDismiss = { roundUsage = null })
    }
    if (showSessionStats) {
        SessionStatsSheet(data = sessionStats, onDismiss = { showSessionStats = false })
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    workspaceName: String,
    compact: Boolean,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val colors = DsTheme.colors
    var overflow by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Outlined.Menu, contentDescription = "会话列表", tint = colors.textPrimary)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = workspaceName,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (compact) {
                IconButton(onClick = onNewSession) {
                    Icon(Icons.Outlined.Add, contentDescription = "新会话", tint = colors.textSecondary)
                }
                Box {
                    IconButton(onClick = { overflow = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = colors.textSecondary)
                    }
                    MiuixDropdownMenu(
                        expanded = overflow,
                        onDismissRequest = { overflow = false },
                        placement = MiuixMenuPlacement.BELOW_END,
                        gap = 4.dp,
                    ) {
                        MiuixMenuItem(
                            text = "文件",
                            icon = Icons.Outlined.FolderOpen,
                            onClick = {
                                overflow = false
                                onOpenFiles()
                            },
                        )
                        MiuixMenuItem(
                            text = "终端",
                            icon = Icons.Outlined.Terminal,
                            onClick = {
                                overflow = false
                                onOpenTerminal()
                            },
                        )
                    }
                }
            } else {
                IconButton(onClick = onOpenFiles) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = "文件", tint = colors.textSecondary)
                }
                IconButton(onClick = onOpenTerminal) {
                    Icon(Icons.Outlined.Terminal, contentDescription = "终端", tint = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun UserMessage(item: ChatItem.User) {
    val colors = DsTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f),
            horizontalAlignment = Alignment.End,
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.userBubble)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = item.text,
                        fontSize = 16.sp,
                        lineHeight = 25.sp,
                        color = colors.textPrimary,
                    )
                }
                if (item.attachments.isNotEmpty() || item.imageCount > 0) {
                    Spacer(Modifier.size(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.imageCount > 0) {
                            AttachmentBadge(
                                icon = Icons.Outlined.Image,
                                text = if (item.imageCount == 1) "图片" else "图片 ×${item.imageCount}",
                            )
                        }
                        item.attachments.forEach { name ->
                            AttachmentBadge(Icons.Outlined.Description, name.take(24))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(colors.background.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = text, fontSize = 10.sp, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun AssistantMessage(
    item: ChatItem.Assistant,
    isLast: Boolean,
    shownUsage: Usage?,
    shownElapsedMs: Long,
    reasoningExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    onRegenerate: () -> Unit,
    onShowUsage: (Usage?, Long) -> Unit,
    onCopy: (String) -> Unit,
) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth()) {
        if (item.reasoning.isNotBlank()) {
            ReasoningBlock(
                reasoning = item.reasoning,
                expanded = reasoningExpanded,
                onToggle = onToggleReasoning,
            )
            if (item.text.isNotBlank()) Spacer(Modifier.size(6.dp))
        }
        if (item.text.isNotBlank()) {
            SelectionContainer {
                MarkdownText(
                    markdown = item.text,
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.textPrimary,
                )
            }
        } else if (item.streaming && item.reasoning.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.8.dp,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.width(8.dp))
                Text("处理中…", fontSize = 13.sp, color = colors.textTertiary)
            }
        }
        if (item.toolCalls.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.toolCalls.forEach { call -> ToolCallCard(call) }
            }
        }
        val actionsAllowed = !item.streaming && (item.toolCalls.isEmpty() || item.interrupted)
        AnimatedVisibility(
            visible = actionsAllowed && (item.text.isNotBlank() || item.interrupted),
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(120)),
        ) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.text.isNotBlank()) {
                    MessageAction(Icons.Outlined.ContentCopy, "复制") { onCopy(item.text) }
                }
                if (isLast) {
                    MessageAction(Icons.Outlined.Refresh, "重新生成", onRegenerate)
                }
                if (shownUsage != null || shownElapsedMs > 0) {
                    Spacer(Modifier.width(4.dp))
                    UsageIndicator(
                        usage = shownUsage,
                        elapsedMs = shownElapsedMs,
                        onClick = { onShowUsage(shownUsage, shownElapsedMs) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = DsTheme.colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun UsageIndicator(
    usage: Usage?,
    elapsedMs: Long,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Storage,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = buildString {
                if (usage != null) append("用量 ").append(formatTokensShort(usage.effectiveTotal)).append(" tok")
                if (elapsedMs > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(formatSecondsShort(elapsedMs))
                }
            },
            fontSize = 10.sp,
            color = colors.textTertiary,
        )
    }
}

private fun providerModelLabel(baseUrl: String, model: String): String {
    val host = runCatching { java.net.URI(baseUrl).host }.getOrNull().orEmpty()
    val provider = when {
        host.contains("deepseek", ignoreCase = true) -> "deepseek"
        host.contains("openai", ignoreCase = true) -> "openai"
        host.contains("localhost", ignoreCase = true) ||
            host.startsWith("127.") ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") -> "local"
        host.isNotBlank() -> host.removePrefix("api.")
        else -> "custom"
    }
    return "$provider/${model.ifBlank { "unknown" }}"
}

@Composable
private fun ReasoningBlock(
    reasoning: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.card)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (expanded) "思考过程" else "思考",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.size(6.dp))
            SelectionContainer {
                Text(
                    text = reasoning,
                    fontSize = 12.5.sp,
                    lineHeight = 20.sp,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun NoteRow(note: ChatItem.Note) {
    val colors = DsTheme.colors
    if (note.error) {
        ErrorLine(note.text)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.card)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = note.text,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = colors.textSecondary,
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = DsTheme.colors.danger,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
    )
}

@Composable
private fun TodoPanel(todos: List<com.muzhou.droidagent.agent.TodoItem>) {
    val colors = DsTheme.colors
    var expanded by rememberSaveable { mutableStateOf(true) }
    val completed = todos.count { it.status == "completed" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.layer)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "任务清单",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("$completed/${todos.size}", fontSize = 11.sp, color = colors.textTertiary)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                LinearProgressIndicator(
                    progress = { if (todos.isEmpty()) 0f else completed.toFloat() / todos.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    color = colors.brand,
                    trackColor = colors.card,
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    todos.forEach { todo ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (todo.status) {
                                    "completed" -> Icons.Filled.CheckCircle
                                    "in_progress" -> Icons.Outlined.RadioButtonChecked
                                    else -> Icons.Outlined.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = when (todo.status) {
                                    "completed" -> colors.success
                                    "in_progress" -> colors.warning
                                    else -> colors.textTertiary
                                },
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = todo.content,
                                fontSize = 12.sp,
                                color = if (todo.status == "completed") colors.textTertiary else colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class TurnUsageTotal(
    val usage: Usage?,
    val elapsedMs: Long,
)

private fun buildTurnUsageMap(items: List<ChatItem>): Map<String, TurnUsageTotal> {
    val result = LinkedHashMap<String, TurnUsageTotal>()
    var usage: Usage? = null
    var elapsedMs = 0L
    var lastAssistant: ChatItem.Assistant? = null

    fun flush() {
        val assistant = lastAssistant
        if (assistant != null && !assistant.streaming && assistant.toolCalls.isEmpty()) {
            result[assistant.key] = TurnUsageTotal(usage, elapsedMs)
        }
        usage = null
        elapsedMs = 0L
        lastAssistant = null
    }

    for (item in items) {
        when (item) {
            is ChatItem.User -> flush()
            is ChatItem.Assistant -> {
                usage = mergeUsage(usage, item.usage)
                elapsedMs += item.elapsedMs
                lastAssistant = item
            }
            is ChatItem.Note -> Unit
        }
    }
    flush()
    return result
}

private fun mergeUsage(first: Usage?, second: Usage?): Usage? {
    if (first == null) return second
    if (second == null) return first
    return Usage(
        promptTokens = first.promptTokens + second.promptTokens,
        completionTokens = first.completionTokens + second.completionTokens,
        totalTokens = first.effectiveTotal + second.effectiveTotal,
        promptCacheHitTokens = first.cachedTokens + second.cachedTokens,
        promptCacheMissTokens = first.uncachedTokens + second.uncachedTokens,
        cacheReadInputTokens = first.cacheReadTokens + second.cacheReadTokens,
        cacheWriteInputTokens = first.cacheWriteTokens + second.cacheWriteTokens,
    )
}

private val NEW_CHAT_GREETINGS = listOf(
    "今天想做什么？",
    "从一个任务开始。",
    "需要我帮你处理什么？",
    "代码、文件，还是别的事？",
    "直接描述你的任务。",
    "准备好开始了。",
    "写下任务，我来处理。",
    "今天从哪里开始？",
)

private suspend fun LazyListState.scrollToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    scrollToItem(layoutInfo.totalItemsCount - 1)
    withFrameNanos { }
    val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val overflow = last.offset + last.size - layoutInfo.viewportEndOffset
    if (overflow > 0) scrollBy(overflow.toFloat())
}

private suspend fun LazyListState.animateScrollToBottom() {
    if (layoutInfo.totalItemsCount == 0) return
    animateScrollToItem(layoutInfo.totalItemsCount - 1)
    withFrameNanos { }
    val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val overflow = last.offset + last.size - layoutInfo.viewportEndOffset
    if (overflow > 0) animateScrollBy(overflow.toFloat())
}

@Composable
private fun ScrollToBottomButton(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.85f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f),
    ) {
        Box(
            modifier = Modifier
                .padding(end = 16.dp, bottom = 12.dp)
                .size(40.dp)
                .shadow(5.dp, RoundedCornerShape(11.dp))
                .clip(RoundedCornerShape(11.dp))
                .background(colors.layer)
                .border(1.dp, colors.border, RoundedCornerShape(11.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = "回到底部",
                tint = colors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

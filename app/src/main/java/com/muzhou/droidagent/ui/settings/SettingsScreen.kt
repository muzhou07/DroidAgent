package com.muzhou.droidagent.ui.settings

import com.muzhou.droidagent.BuildConfig
import com.muzhou.droidagent.agent.PermissionMode
import com.muzhou.droidagent.agent.ReasoningEffort
import com.muzhou.droidagent.agent.mcp.McpServerConfig
import com.muzhou.droidagent.agent.mcp.McpServerState
import com.muzhou.droidagent.data.AppSettings
import com.muzhou.droidagent.data.ThemeMode
import com.muzhou.droidagent.ui.components.MiuixSlider
import com.muzhou.droidagent.ui.components.MiuixTextField
import com.muzhou.droidagent.ui.theme.DsTheme
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    initialPage: String = "home",
    onBack: () -> Unit,
) {
    val colors = DsTheme.colors
    val startPage = remember(initialPage) { parseSettingsPage(initialPage) }
    val persisted by viewModel.settings.collectAsStateWithLifecycle()
    val servers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val mcpStates by viewModel.mcpStates.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(startPage) }
    var draft by remember { mutableStateOf(persisted) }
    var initialized by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
    var addServer by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<McpServerConfig?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val next = draft.copy(termuxEnabled = true)
            draft = next
            viewModel.saveSettings(next, immediate = true)
        } else {
            scope.launch { snackbar.showSnackbar("未授予 Termux RUN_COMMAND 权限") }
        }
    }

    LaunchedEffect(persisted) {
        if (!initialized) {
            draft = persisted
            initialized = true
        }
    }

    LaunchedEffect(draft, initialized) {
        if (initialized && draft != persisted) {
            delay(300)
            viewModel.saveSettings(draft)
        }
    }

    fun goBack() {
        if (page == startPage && startPage != SettingsPage.HOME) {
            onBack()
        } else if (page == SettingsPage.HOME) {
            onBack()
        } else {
            page = SettingsPage.HOME
        }
    }
    BackHandler { goBack() }

    val pageBackground = if (colors.dark) colors.background else Color(0xFFF2F3F5)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = if (page == SettingsPage.HOME) "设置" else page.title,
                onBack = { goBack() },
            )
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (targetState.ordinal > initialState.ordinal) {
                            (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } +
                                fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 4 } +
                                    fadeOut(tween(140)))
                        } else {
                            (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it } +
                                fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } +
                                    fadeOut(tween(140)))
                        }
                    },
                    label = "settingsPage",
                ) { current ->
                    when (current) {
                    SettingsPage.HOME -> SettingsHome(
                        model = draft.model,
                        permission = permissionLabel(draft.permissionMode),
                        mcpSummary = "${servers.count { it.enabled }} 个已启用",
                        theme = themeLabel(draft.theme),
                        version = "v${BuildConfig.VERSION_NAME}",
                        onOpen = { page = it },
                    )
                    SettingsPage.MODEL -> ModelSettingsPage(
                        draft = draft,
                        onChange = { draft = it },
                        showApiKey = showApiKey,
                        onToggleApiKey = { showApiKey = !showApiKey },
                        advanced = advanced,
                        onToggleAdvanced = { advanced = !advanced },
                    )
                    SettingsPage.PERMISSION -> PermissionSettingsPage(
                        draft = draft,
                        onChange = { draft = it },
                        onTermuxChange = { enabled ->
                            if (!enabled) {
                                draft = draft.copy(termuxEnabled = false)
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    TERMUX_PERMISSION,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    draft = draft.copy(termuxEnabled = true)
                                } else {
                                    termuxPermission.launch(TERMUX_PERMISSION)
                                }
                            }
                        },
                    )
                    SettingsPage.MCP -> McpSettingsPage(
                        servers = servers,
                        states = mcpStates,
                        onReconnect = { viewModel.reloadMcp() },
                        onToggle = { server, enabled -> viewModel.setMcpEnabled(server, enabled) },
                        onEdit = { editingServer = it },
                        onDelete = { deleteTarget = it },
                        onAdd = { addServer = true },
                    )
                    SettingsPage.APPEARANCE -> AppearanceSettingsPage(
                        theme = draft.theme,
                        onSelect = { mode ->
                            val next = draft.copy(theme = mode)
                            draft = next
                            viewModel.saveSettings(next, immediate = true)
                        },
                    )
                    SettingsPage.ABOUT -> AboutSettingsPage(
                        onShowLicense = { showLicense = true },
                        onOpenUrl = { uriHandler.openUri(it) },
                    )
                }
            }
        }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        )
    }

    if (addServer || editingServer != null) {
        McpEditDialog(
            existing = editingServer,
            onDismiss = {
                addServer = false
                editingServer = null
            },
            onSave = { server ->
                viewModel.saveMcp(server)
                addServer = false
                editingServer = null
            },
        )
    }

    deleteTarget?.let { server ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 MCP 服务器？") },
            text = { Text("「${server.name}」的配置将被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMcp(server.id)
                    deleteTarget = null
                }) { Text("删除", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("MIT License") },
            text = {
                Column(
                    Modifier
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        LICENSE_TEXT,
                        fontSize = 10.5.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) { Text("关闭") }
            },
        )
    }
}

private enum class SettingsPage(val title: String) {
    HOME("设置"),
    MODEL("模型与 API"),
    PERMISSION("权限与工具"),
    MCP("MCP 服务器"),
    APPEARANCE("外观"),
    ABOUT("关于"),
}

private fun parseSettingsPage(raw: String?): SettingsPage = when (raw?.lowercase()) {
    "model" -> SettingsPage.MODEL
    "permission" -> SettingsPage.PERMISSION
    "mcp" -> SettingsPage.MCP
    "appearance" -> SettingsPage.APPEARANCE
    "about" -> SettingsPage.ABOUT
    else -> SettingsPage.HOME
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(44.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.layer)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = colors.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun SettingsHome(
    model: String,
    permission: String,
    mcpSummary: String,
    theme: String,
    version: String,
    onOpen: (SettingsPage) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        SectionLabel("模型")
        SettingsGroup {
            SettingsNavRow(Icons.Outlined.Tune, "模型与 API", model.ifBlank { "未配置" }) {
                onOpen(SettingsPage.MODEL)
            }
        }
        SectionLabel("Agent")
        SettingsGroup {
            SettingsNavRow(Icons.Outlined.Lock, "权限与工具", permission) {
                onOpen(SettingsPage.PERMISSION)
            }
            SettingsNavRow(Icons.Outlined.Extension, "MCP 服务器", mcpSummary) {
                onOpen(SettingsPage.MCP)
            }
        }
        SectionLabel("应用")
        SettingsGroup {
            SettingsNavRow(Icons.Outlined.Palette, "外观", theme) {
                onOpen(SettingsPage.APPEARANCE)
            }
        }
        SectionLabel("关于")
        SettingsGroup {
            SettingsNavRow(Icons.Outlined.Info, "关于 DroidAgent", version) {
                onOpen(SettingsPage.ABOUT)
            }
        }
    }
}

@Composable
private fun ModelSettingsPage(
    draft: AppSettings,
    onChange: (AppSettings) -> Unit,
    showApiKey: Boolean,
    onToggleApiKey: () -> Unit,
    advanced: Boolean,
    onToggleAdvanced: () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        SectionLabel("连接")
        SettingsGroup(padding = 14.dp) {
            SettingsField(
                label = "Base URL",
                value = draft.baseUrl,
                onValueChange = { onChange(draft.copy(baseUrl = it)) },
                placeholder = "https://api.deepseek.com/v1",
                keyboardType = KeyboardType.Uri,
            )
            SettingsField(
                label = "API Key",
                value = draft.apiKey,
                onValueChange = { onChange(draft.copy(apiKey = it)) },
                placeholder = "sk-…（留空表示无需鉴权）",
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = onToggleApiKey) {
                        Icon(
                            if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = "显示密钥",
                            tint = DsTheme.colors.textTertiary,
                        )
                    }
                },
            )
            SettingsField(
                label = "模型名称",
                value = draft.model,
                onValueChange = { onChange(draft.copy(model = it)) },
                placeholder = "deepseek-flash",
            )
            Text(
                text = "思考强度",
                fontSize = 11.sp,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 5.dp),
            )
            val selectedBackground = if (colors.dark) Color.White.copy(alpha = 0.14f) else colors.layer
            val selectedIndex = ReasoningEffort.entries.indexOf(draft.reasoningEffort).coerceAtLeast(0)
            val animatedIndex by animateFloatAsState(
                targetValue = selectedIndex.toFloat(),
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "reasoningEffort",
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(colors.card)
                    .padding(3.dp),
            ) {
                val segmentWidth = maxWidth / ReasoningEffort.entries.size
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = segmentWidth.toPx() * animatedIndex
                        }
                        .width(segmentWidth)
                        .height(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(selectedBackground),
                )
                Row(Modifier.fillMaxSize()) {
                    ReasoningEffort.entries.forEach { effort ->
                        val selected = draft.reasoningEffort == effort
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clickable { onChange(draft.copy(reasoningEffort = effort)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = effort.label,
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                color = if (selected) colors.textPrimary else colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
        SectionLabel("高级")
        SettingsGroup(padding = 14.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleAdvanced),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "高级参数",
                    fontSize = 13.sp,
                    color = DsTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = DsTheme.colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (advanced) {
                Text(
                    "Temperature：${"%.2f".format(draft.temperature)}",
                    fontSize = 12.sp,
                    color = DsTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                MiuixSlider(
                    value = draft.temperature.toFloat(),
                    onValueChange = { onChange(draft.copy(temperature = it.toDouble())) },
                    valueRange = 0f..2f,
                    steps = 19,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                SettingsField(
                    label = "最大输出 tokens（0 = 模型默认）",
                    value = draft.maxTokens.toString(),
                    onValueChange = { onChange(draft.copy(maxTokens = it.toIntOrNull() ?: 0)) },
                    keyboardType = KeyboardType.Number,
                )
                SettingsField(
                    label = "上下文窗口 tokens",
                    value = draft.contextWindow.toString(),
                    onValueChange = { onChange(draft.copy(contextWindow = it.toIntOrNull() ?: 64000)) },
                    keyboardType = KeyboardType.Number,
                )
                SettingsField(
                    label = "最大工具轮次",
                    value = draft.maxIterations.toString(),
                    onValueChange = { onChange(draft.copy(maxIterations = it.toIntOrNull() ?: 40)) },
                    keyboardType = KeyboardType.Number,
                )
                SettingsField(
                    label = "自定义请求头（每行 Key: Value）",
                    value = headersToText(draft.extraHeaders),
                    onValueChange = { onChange(draft.copy(extraHeaders = textToHeaders(it))) },
                    minLines = 3,
                )
            }
        }
    }
}

@Composable
private fun PermissionSettingsPage(
    draft: AppSettings,
    onChange: (AppSettings) -> Unit,
    onTermuxChange: (Boolean) -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        SectionLabel("权限模式")
        SettingsGroup(padding = 6.dp) {
            PermissionMode.entries.forEach { mode ->
                PermissionRow(
                    mode = mode,
                    selected = draft.permissionMode == mode,
                    onClick = { onChange(draft.copy(permissionMode = mode)) },
                )
            }
        }
        SectionLabel("Shell")
        SettingsGroup(padding = 14.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Termux Shell", fontSize = 13.sp, color = colors.textPrimary)
                    Text(
                        "需 Termux 开启 allow-external-apps 并授权存储",
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = colors.textTertiary,
                    )
                }
                Switch(checked = draft.termuxEnabled, onCheckedChange = onTermuxChange)
            }
        }
        SectionLabel("搜索与系统指令")
        SettingsGroup(padding = 14.dp) {
            SettingsField(
                label = "Tavily Key（可选）",
                value = draft.tavilyApiKey,
                onValueChange = { onChange(draft.copy(tavilyApiKey = it.trim())) },
                placeholder = "tvly-…",
                visualTransformation = PasswordVisualTransformation(),
            )
            SettingsField(
                label = "附加系统指令（可选）",
                value = draft.systemExtra,
                onValueChange = { onChange(draft.copy(systemExtra = it)) },
                placeholder = "例如：始终使用中文回答，先写测试再改代码……",
                minLines = 3,
            )
        }
    }
}

@Composable
private fun McpSettingsPage(
    servers: List<McpServerConfig>,
    states: List<McpServerState>,
    onReconnect: () -> Unit,
    onToggle: (McpServerConfig, Boolean) -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onDelete: (McpServerConfig) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        SectionLabel("服务器")
        SettingsGroup(padding = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${servers.count { it.enabled }} 个已启用 · Streamable HTTP",
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReconnect, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("重新连接", fontSize = 11.sp)
                }
            }
            if (servers.isEmpty()) {
                Text(
                    "尚未配置 MCP 服务器",
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            servers.forEach { server ->
                Spacer(Modifier.height(6.dp))
                McpServerRow(
                    server = server,
                    state = states.firstOrNull { it.config.id == server.id },
                    onToggle = { onToggle(server, it) },
                    onEdit = { onEdit(server) },
                    onDelete = { onDelete(server) },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(9.dp))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = colors.brand, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加 MCP 服务器", fontSize = 12.sp, color = colors.brand)
            }
        }
    }
}

@Composable
private fun AppearanceSettingsPage(
    theme: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        SectionLabel("主题")
        SettingsGroup {
            ThemeMode.entries.forEach { mode ->
                val (icon, label) = when (mode) {
                    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto to "跟随系统"
                    ThemeMode.LIGHT -> Icons.Outlined.LightMode to "浅色"
                    ThemeMode.DARK -> Icons.Outlined.DarkMode to "深色"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        label,
                        fontSize = 14.sp,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (theme == mode) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = "已选择",
                            tint = colors.brand,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSettingsPage(
    onShowLicense: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        SectionLabel("应用")
        SettingsGroup {
            SettingsInfoRow(Icons.Outlined.Info, "版本", "v${BuildConfig.VERSION_NAME}")
            SettingsNavRow(Icons.Outlined.Description, "开源许可", "MIT") { onShowLicense() }
        }
        SectionLabel("项目")
        SettingsGroup {
            SettingsNavRow(Icons.Outlined.Link, "GitHub 仓库", "muzhou07/DroidAgent") {
                onOpenUrl("https://github.com/muzhou07/DroidAgent")
            }
            SettingsNavRow(Icons.Outlined.Link, "作者", "muzhou07") {
                onOpenUrl("https://github.com/muzhou07")
            }
        }
        SectionLabel("参考项目")
        SettingsGroup {
            SettingsNavRow(Icons.Outlined.Link, "DeepSeek chat-demo", "界面参考") {
                onOpenUrl("https://github.com/deepseek-ai/chat-demo")
            }
            SettingsNavRow(Icons.Outlined.Link, "DeepSeek Harness", "架构参考") {
                onOpenUrl("https://github.com/deepseek-ai/deepseek-harness")
            }
            SettingsNavRow(Icons.Outlined.Link, "Miuix", "界面参考") {
                onOpenUrl("https://github.com/compose-miuix-ui/miuix")
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = DsTheme.colors.textTertiary,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 7.dp),
    )
}

@Composable
private fun SettingsGroup(
    padding: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DsTheme.colors.layer)
            .padding(padding),
        content = content,
    )
}

@Composable
private fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = summary,
            fontSize = 12.5.sp,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun SettingsInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = value,
            fontSize = 12.5.sp,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    minLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
) {
    MiuixTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailing = trailing,
    )
}

@Composable
private fun PermissionRow(mode: PermissionMode, selected: Boolean, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val title = when (mode) {
        PermissionMode.ASK -> "询问"
        PermissionMode.AUTO -> "自动执行"
        PermissionMode.READ_ONLY -> "只读"
    }
    val description = when (mode) {
        PermissionMode.ASK -> "写文件、删除、Shell 需批准"
        PermissionMode.AUTO -> "全部自动执行"
        PermissionMode.READ_ONLY -> "禁止修改与执行"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.brandSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(15.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (selected) colors.brand else colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.brand),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontSize = 13.sp, color = colors.textPrimary)
            Text(description, fontSize = 10.sp, lineHeight = 14.sp, color = colors.textTertiary)
        }
    }
}

@Composable
private fun McpServerRow(
    server: McpServerConfig,
    state: McpServerState?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    val statusColor = when {
        !server.enabled -> colors.textTertiary
        state?.connected == true -> colors.success
        state?.error != null -> colors.danger
        else -> colors.warning
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.card)
            .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(statusColor),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(server.name, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary, maxLines = 1)
            Text(
                text = when {
                    !server.enabled -> "已禁用"
                    state?.connected == true -> "${state.tools.size} 个工具 · ${server.url}"
                    state?.error != null -> "连接失败：${state.error}"
                    else -> "连接中… · ${server.url}"
                },
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = if (state?.error != null && server.enabled) colors.danger else colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Edit, contentDescription = "编辑", tint = colors.textTertiary, modifier = Modifier.size(15.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = colors.textTertiary, modifier = Modifier.size(15.dp))
        }
        Switch(
            checked = server.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

@Composable
private fun McpEditDialog(
    existing: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var headers by remember { mutableStateOf(existing?.headers?.let(::headersToText).orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    val colors = DsTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加 MCP 服务器" else "编辑 MCP 服务器") },
        text = {
            Column(
                Modifier
                    .height(430.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsField("名称", name, { name = it }, placeholder = "例如 GitHub MCP")
                SettingsField(
                    "服务器 URL",
                    url,
                    { url = it },
                    placeholder = "https://example.com/mcp",
                    keyboardType = KeyboardType.Uri,
                )
                SettingsField(
                    "请求头（每行 Key: Value）",
                    headers,
                    { headers = it },
                    placeholder = "Authorization: Bearer …",
                    minLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(
                    "保存后自动连接并加载工具。",
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    color = colors.textTertiary,
                )
                error?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(it, fontSize = 11.sp, color = colors.danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmedUrl = url.trim()
                if (name.isBlank() || trimmedUrl.isBlank()) {
                    error = "名称与 URL 不能为空"
                    return@TextButton
                }
                if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
                    error = "URL 必须以 http:// 或 https:// 开头"
                    return@TextButton
                }
                onSave(
                    McpServerConfig(
                        id = existing?.id ?: "mcp_" + java.util.UUID.randomUUID().toString().take(8),
                        name = name.trim(),
                        url = trimmedUrl,
                        headers = textToHeaders(headers),
                        enabled = enabled,
                        transport = "http",
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun permissionLabel(mode: PermissionMode): String = when (mode) {
    PermissionMode.ASK -> "询问"
    PermissionMode.AUTO -> "自动执行"
    PermissionMode.READ_ONLY -> "只读"
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun headersToText(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

private const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"

private fun textToHeaders(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    text.lines().forEach { line ->
        val index = line.indexOf(':')
        if (index > 0) {
            val key = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim()
            if (key.isNotEmpty()) result[key] = value
        }
    }
    return result
}

private const val LICENSE_TEXT = """MIT License

Copyright (c) 2026 muzhou07

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---

Third-party notices:
- DeepSeek chat-demo (MIT) - UI design inspiration and interaction reference.
- DeepSeek Harness (MIT) - agent architecture reference.
- Miuix (Apache-2.0) - menu, input and slider style reference.
- OkHttp, kotlinx.serialization, Jsoup, AndroidX (Apache-2.0)."""

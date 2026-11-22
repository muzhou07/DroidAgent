package com.muzhou.droidagent.ui.terminal

import com.muzhou.droidagent.agent.shell.ShellProvider
import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.ui.theme.DsTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    workspace: Workspace,
    termuxEnabled: Boolean,
    onBack: () -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shell = remember(workspace.id, workspace.path, workspace.uri, termuxEnabled) {
        runCatching { ShellProvider(context, termuxEnabled).forWorkspace(workspace) }.getOrNull()
    }
    var output by remember(workspace.id) {
        mutableStateOf(
            "DroidAgent Terminal\n" +
                "${workspace.name} · ${shell?.backendName ?: "不可用"}\n\n",
        )
    }
    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    fun run() {
        val cmd = command.trim()
        if (cmd.isEmpty() || running) return
        val executor = shell
        output += "$ $cmd\n"
        command = ""
        if (executor == null) {
            output += "当前工作区不支持 Shell（SAF 目录无法映射为本地路径）。\n\n"
            return
        }
        running = true
        scope.launch {
            val result = executor.run(cmd, 300_000)
            output += buildString {
                append(result.combined())
                append("\n[退出码 ${result.exitCode}")
                if (result.timedOut) append(" · 超时")
                append(" · ${result.durationMs}ms]\n\n")
            }
            running = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = colors.textPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "终端",
                    fontSize = 15.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "${workspace.name} · ${shell?.backendName ?: "Shell 不可用"}",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = colors.textTertiary,
                )
            }
            IconButton(onClick = { output = "" }) {
                Icon(Icons.Outlined.Delete, contentDescription = "清空", tint = colors.textSecondary)
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.codeBackground)
                .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
        ) {
            SelectionContainer {
                Text(
                    text = output,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 18.sp,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(10.dp),
                )
            }
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(14.dp),
                    strokeWidth = 2.dp,
                    color = colors.brand,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = colors.brand)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 13.sp,
                    color = colors.textPrimary,
                ),
                cursorBrush = SolidColor(colors.brand),
                maxLines = 3,
                decorationBox = { inner ->
                    Box {
                        if (command.isEmpty()) {
                            Text(
                                "输入命令，例如 ls -la",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                lineHeight = 13.sp,
                                color = colors.textTertiary,
                            )
                        }
                        inner()
                    }
                },
            )
            IconButton(
                onClick = { run() },
                enabled = command.isNotBlank() && !running,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "执行",
                    tint = if (command.isNotBlank() && !running) colors.brand else colors.textTertiary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

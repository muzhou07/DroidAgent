package com.muzhou.droidagent.ui.chat

import com.muzhou.droidagent.agent.util.AgentJson
import com.muzhou.droidagent.ui.theme.DsTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val PRETTY_JSON = Json { prettyPrint = true }

@Composable
fun ToolCallCard(call: UiToolCall) {
    val colors = DsTheme.colors
    var expanded by rememberSaveable(call.id) { mutableStateOf(call.status == ToolStatus.ERROR) }
    val subtitle = remember(call.arguments, call.name) { toolSubtitle(call) }
    val icon = toolIcon(call.name)
    val borderColor = when (call.status) {
        ToolStatus.ERROR -> colors.danger.copy(alpha = 0.45f)
        ToolStatus.RUNNING -> colors.brand.copy(alpha = 0.35f)
        else -> colors.border
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(colors.layer)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = displayName(call.name),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = colors.textTertiary,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            ToolStatusView(call.status)
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = colors.textTertiary,
                modifier = Modifier
                    .padding(start = 3.dp)
                    .size(16.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val prettyArgs = remember(call.arguments) { prettyJson(call.arguments) }
                if (prettyArgs.isNotBlank()) {
                    Label("参数")
                    CodeBox(prettyArgs, colors.textSecondary)
                }
                if (call.output.isNotBlank()) {
                    Label(if (call.status == ToolStatus.ERROR) "错误输出" else "输出")
                    CodeBox(
                        call.output.take(30_000),
                        if (call.status == ToolStatus.ERROR) colors.danger else colors.textPrimary,
                    )
                } else if (call.status == ToolStatus.RUNNING) {
                    Text("正在执行…", fontSize = 12.sp, color = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = DsTheme.colors.textTertiary,
    )
}

@Composable
private fun CodeBox(text: String, color: Color) {
    val colors = DsTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBackground)
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 18.sp,
                color = color,
            )
        }
    }
}

@Composable
private fun ToolStatusView(status: ToolStatus) {
    val colors = DsTheme.colors
    when (status) {
        ToolStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            strokeWidth = 2.dp,
            color = colors.brand,
        )
        ToolStatus.DONE -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "完成",
            tint = colors.success,
            modifier = Modifier.size(16.dp),
        )
        ToolStatus.ERROR -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = "失败",
            tint = colors.danger,
            modifier = Modifier.size(16.dp),
        )
        ToolStatus.WAITING -> Icon(
            Icons.Outlined.Code,
            contentDescription = "等待",
            tint = colors.textTertiary,
            modifier = Modifier.size(15.dp),
        )
    }
}

private fun prettyJson(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        val element = AgentJson.json.parseToJsonElement(raw)
        PRETTY_JSON.encodeToString(JsonElement.serializer(), element)
    }.getOrDefault(raw.take(4000))
}

private fun displayName(name: String): String = when {
    name.startsWith("mcp__") -> {
        val parts = name.removePrefix("mcp__").split("__")
        if (parts.size >= 2) "${parts[0]} · ${parts.drop(1).joinToString("_")}" else name
    }
    else -> when (name) {
        "read_file" -> "读取文件"
        "write_file" -> "写入文件"
        "edit_file" -> "编辑文件"
        "list_dir" -> "浏览目录"
        "glob" -> "查找文件"
        "grep" -> "搜索内容"
        "delete_file" -> "删除文件"
        "run_shell" -> "Shell"
        "web_search" -> "网络搜索"
        "web_fetch" -> "网页抓取"
        "todo_write" -> "更新清单"
        "task" -> "子代理"
        else -> name
    }
}

private fun toolIcon(name: String): ImageVector = when {
    name.startsWith("mcp__") -> Icons.Outlined.Build
    else -> when (name) {
        "run_shell" -> Icons.Outlined.Terminal
        "read_file" -> Icons.Outlined.Description
        "write_file", "edit_file" -> Icons.Outlined.Edit
        "list_dir", "glob" -> Icons.Outlined.FolderOpen
        "grep" -> Icons.Outlined.Search
        "web_search", "web_fetch" -> Icons.Outlined.Language
        "todo_write" -> Icons.Outlined.Checklist
        "task" -> Icons.Outlined.AccountTree
        else -> Icons.Outlined.Build
    }
}

private fun toolSubtitle(call: UiToolCall): String {
    val args = runCatching { AgentJson.parseObjectOrNull(call.arguments) }.getOrNull() ?: return ""
    fun value(key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    return when (call.name) {
        "run_shell" -> value("command")?.replace(Regex("\\s+"), " ")?.take(120).orEmpty()
        "read_file", "write_file", "edit_file", "delete_file", "list_dir" -> value("path")
            ?.let { if (it == ".") "工作区根目录" else it }
            .orEmpty()
        "glob" -> value("pattern")?.let { "pattern: $it" }.orEmpty()
        "grep" -> value("pattern")?.let { "pattern: $it" }.orEmpty()
        "web_fetch" -> value("url").orEmpty()
        "web_search" -> value("query").orEmpty()
        "task" -> value("description").orEmpty()
        else -> (args["path"] as? JsonPrimitive)?.contentOrNull
            ?: (args["command"] as? JsonPrimitive)?.contentOrNull
            ?: ""
    }
}

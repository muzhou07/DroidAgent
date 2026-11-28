package com.muzhou.droidagent.ui.chat

import com.muzhou.droidagent.agent.AgentMode
import com.muzhou.droidagent.agent.ReasoningEffort
import com.muzhou.droidagent.ui.components.MiuixDropdownMenu
import com.muzhou.droidagent.ui.components.MiuixMenuItem
import com.muzhou.droidagent.ui.components.MiuixMenuPlacement
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<AttachmentInput>,
    onRemoveAttachment: (Int) -> Unit,
    onPickAttachments: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean,
    reasoningEffort: ReasoningEffort,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    agentMode: AgentMode,
    onAgentModeChange: (AgentMode) -> Unit,
    stats: SessionStatsData,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val canSend = !isRunning && (value.isNotBlank() || attachments.isNotEmpty())
    val inputStyle = TextStyle(
        fontSize = 16.sp,
        lineHeight = 17.sp,
        color = colors.textPrimary,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.input)
                .border(1.dp, colors.border, RoundedCornerShape(18.dp)),
        ) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                if (attachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(attachments) { index, attachment ->
                            AttachmentChip(attachment, onRemove = { onRemoveAttachment(index) })
                        }
                    }
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    textStyle = inputStyle,
                    cursorBrush = SolidColor(colors.brand),
                    maxLines = 8,
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    text = "输入任务…",
                                    style = inputStyle.copy(color = colors.textTertiary),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPickAttachments, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = "添加附件",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onOpenModelSettings, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = "模型与 API 设置",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    AgentModeButton(
                        mode = agentMode,
                        enabled = !isRunning,
                        onToggle = {
                            onAgentModeChange(
                                if (agentMode == AgentMode.PLAN) AgentMode.ACTION else AgentMode.PLAN,
                            )
                        },
                    )
                    Spacer(Modifier.width(2.dp))
                    ReasoningChip(
                        effort = reasoningEffort,
                        enabled = !isRunning,
                        onSelect = onReasoningEffortChange,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.brand)
                                .clickable(onClick = onStop),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "停止",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (canSend) colors.brand else colors.card)
                                .clickable(enabled = canSend, onClick = onSend),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = "发送",
                                tint = if (canSend) androidx.compose.ui.graphics.Color.White else colors.textTertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable(onClick = onOpenStats)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${stats.rounds} 轮 ${stats.steps} 步 · ${stats.tokensPerSecond.toInt()} tok/s",
                    fontSize = 9.5.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${formatCompact(stats.totalTokens)} tok · 缓存命中 ${formatPercentCompact(stats.cacheHitPercent)}",
                    fontSize = 9.5.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val contextTotal = stats.contextStats?.totalTokens ?: 0
                val window = stats.contextStats?.windowTokens?.takeIf { it > 0 } ?: stats.windowTokens
                val percent = if (window > 0) (contextTotal * 100.0 / window) else 0.0
                CircularProgressIndicator(
                    progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = colors.brand,
                    trackColor = colors.card,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${formatPercentCompact(percent, decimals = 0)}",
                    fontSize = 9.5.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AgentModeButton(
    mode: AgentMode,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = DsTheme.colors
    val plan = mode == AgentMode.PLAN
    IconButton(
        onClick = onToggle,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            if (plan) Icons.Outlined.Map else Icons.Filled.PlayArrow,
            contentDescription = if (plan) {
                "Plan 模式，点击切换到 Action"
            } else {
                "Action 模式，点击切换到 Plan"
            },
            tint = if (enabled) colors.textSecondary else colors.textTertiary,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun ReasoningChip(
    effort: ReasoningEffort,
    enabled: Boolean,
    onSelect: (ReasoningEffort) -> Unit,
) {
    val colors = DsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                tint = if (enabled) colors.textSecondary else colors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "思考·${effort.label}",
                fontSize = 11.sp,
                color = if (enabled) colors.textSecondary else colors.textTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(13.dp),
            )
        }
        MiuixDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            placement = MiuixMenuPlacement.ABOVE_END,
            gap = 6.dp,
        ) {
            ReasoningEffort.entries.forEach { option ->
                MiuixMenuItem(
                    text = option.label,
                    selected = option == effort,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(attachment: AttachmentInput, onRemove: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(9.dp))
            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (attachment.isImage) Icons.Outlined.Image else Icons.Outlined.Description,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = attachment.name.take(26),
            fontSize = 11.sp,
            color = colors.textSecondary,
            maxLines = 1,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "移除附件",
                tint = colors.textTertiary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

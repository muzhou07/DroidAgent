package com.muzhou.droidagent.ui.chat

import com.muzhou.droidagent.agent.AgentEvent
import com.muzhou.droidagent.agent.llm.Usage
import com.muzhou.droidagent.ui.theme.DsTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

data class RoundUsageData(
    val usage: Usage?,
    val elapsedMs: Long,
    val modelLabel: String,
)

data class SessionStatsData(
    val rounds: Int,
    val steps: Int,
    val modelTimeMs: Long,
    val averageTtftMs: Long,
    val tokensPerSecond: Double,
    val totalTokens: Int,
    val promptTokens: Int,
    val cachedTokens: Int,
    val uncachedTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val outputTokens: Int,
    val cacheHitPercent: Double,
    val contextStats: AgentEvent.ContextStats?,
    val windowTokens: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundUsageSheet(
    data: RoundUsageData,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.background,
        contentColor = colors.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.borderStrong) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            UsageHeader(
                title = "本轮用量",
                value = data.usage?.let { "${formatInteger(it.effectiveTotal)} tok" } ?: "—",
            )
            Spacer(Modifier.size(12.dp))
            StatRow("提供方 / 模型", data.modelLabel)
            if (data.elapsedMs > 0) {
                StatRow("模型用时", formatSeconds(data.elapsedMs))
            }
            if (data.usage == null) {
                Spacer(Modifier.size(10.dp))
                Text(
                    "该轮未返回 token 统计。",
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                )
            } else {
                val usage = data.usage
                StatRow("缓存命中", formatPercent(usage.cacheHitPercent))
                StatRow("未缓存输入", "${formatInteger(usage.uncachedTokens)} tok")
                StatRow("缓存读取", "${formatInteger(usage.cacheReadTokens)} tok")
                StatRow("缓存写入", "${formatInteger(usage.cacheWriteTokens)} tok")
                StatRow("输出", "${formatInteger(usage.completionTokens)} tok")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionStatsSheet(
    data: SessionStatsData,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.background,
        contentColor = colors.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.borderStrong) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            SectionHeader(
                icon = Icons.Outlined.QueryStats,
                title = "会话统计",
                value = "${data.rounds} 轮 ${data.steps} 步",
            )
            StatRow("模型用时", formatSeconds(data.modelTimeMs))
            StatRow("首 token 平均（TTFT）", formatSeconds(data.averageTtftMs))
            StatRow("输出速度（TPS）", if (data.tokensPerSecond > 0) {
                String.format(Locale.US, "%.0f tok/s", data.tokensPerSecond)
            } else {
                "—"
            })

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = colors.divider,
            )

            UsageHeader(
                title = "Token 用量",
                value = "${formatInteger(data.totalTokens)} tok",
            )
            Spacer(Modifier.size(12.dp))
            StatRow("缓存命中", formatPercent(data.cacheHitPercent))
            StatRow("未缓存输入", "${formatInteger(data.uncachedTokens)} tok")
            StatRow("缓存读取", "${formatInteger(data.cacheReadTokens)} tok")
            StatRow("输出", "${formatInteger(data.outputTokens)} tok")

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = colors.divider,
            )

            val context = data.contextStats
            val total = context?.totalTokens ?: 0
            val window = context?.windowTokens?.takeIf { it > 0 } ?: data.windowTokens
            val percent = if (window > 0) total * 100.0 / window else 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "上下文已用 ${formatPercent(percent, decimals = 0)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "~${formatCompact(total)} / ${formatCompact(window)}",
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.size(10.dp))
            LinearProgressIndicator(
                progress = { if (window > 0) (total.toFloat() / window).coerceIn(0f, 1f) else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp)),
                color = colors.brand,
                trackColor = colors.card,
            )
            Spacer(Modifier.size(12.dp))
            if (context == null) {
                Text(
                    "运行一次后更新上下文统计。",
                    fontSize = 11.sp,
                    color = colors.textTertiary,
                )
            } else {
                ContextLegend(
                    color = colors.textTertiary,
                    label = "系统提示词",
                    tokens = context.systemTokens,
                )
                ContextLegend(
                    color = Color(0xFFA78BFA),
                    label = "工具定义",
                    tokens = context.toolTokens,
                )
                ContextLegend(
                    color = colors.brand,
                    label = "对话消息",
                    tokens = context.messageTokens,
                )
            }
        }
    }
}

@Composable
private fun UsageHeader(title: String, value: String) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Storage,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun ContextLegend(color: Color, label: String, tokens: Int) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "~${formatCompact(tokens)}",
            fontSize = 11.sp,
            color = colors.textTertiary,
        )
    }
}

fun formatTokensShort(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}

fun formatInteger(value: Int): String = String.format(Locale.US, "%,d", value)

fun formatCompact(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

fun formatSeconds(milliseconds: Long): String =
    if (milliseconds <= 0) "—" else String.format(Locale.US, "%.1f 秒", milliseconds / 1000.0)

fun formatSecondsShort(milliseconds: Long): String =
    if (milliseconds <= 0) "" else String.format(Locale.US, "%.1fs", milliseconds / 1000.0)

fun formatPercent(value: Double, decimals: Int = 1): String =
    String.format(Locale.US, "%.${decimals}f%%", value)

fun formatPercentCompact(value: Double, decimals: Int = 1): String {
    val rounded = if (decimals == 0) value.toInt().toDouble() else (value * 10).toInt() / 10.0
    val actualDecimals = if (rounded % 1.0 == 0.0) 0 else decimals
    return String.format(Locale.US, "%.${actualDecimals}f%%", value)
}

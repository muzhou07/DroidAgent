package com.muzhou.droidagent.ui.chat

import com.muzhou.droidagent.agent.tool.ApprovalDecision
import com.muzhou.droidagent.agent.tool.ApprovalRequest
import com.muzhou.droidagent.ui.theme.DsTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(
    request: ApprovalRequest,
    onDecision: (ApprovalDecision) -> Unit,
) {
    val colors = DsTheme.colors
    var alwaysAllow by remember(request.toolName) { mutableStateOf(false) }
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { onDecision(ApprovalDecision.DENY) },
        sheetState = sheetState,
        containerColor = colors.background,
        contentColor = colors.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.borderStrong) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = request.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                text = request.toolName,
                fontSize = 11.sp,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(14.dp))
            Text(
                text = request.detail,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontFamily = if (request.detail.contains('\n')) FontFamily.Monospace else FontFamily.Default,
                color = colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.card)
                    .padding(12.dp),
            )
            if (!request.diff.isNullOrBlank()) {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "变更",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.size(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.codeBackground)
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    request.diff.orEmpty().lines().take(400).forEach { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 18.sp,
                            color = when {
                                line.startsWith("+") -> colors.success
                                line.startsWith("-") -> colors.danger
                                line.startsWith("@@") -> colors.brand
                                else -> colors.textSecondary
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it })
                Text(
                    text = "本会话始终允许",
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onDecision(ApprovalDecision.DENY) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("拒绝", fontSize = 14.sp, color = colors.textPrimary)
                }
                Button(
                    onClick = {
                        onDecision(
                            if (alwaysAllow) ApprovalDecision.ALLOW_SESSION else ApprovalDecision.ALLOW_ONCE,
                        )
                    },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.brand,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("允许本次执行", fontSize = 14.sp)
                }
            }
        }
    }
}

package com.muzhou.droidagent.ui.components

import com.muzhou.droidagent.ui.theme.DsTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

enum class MiuixMenuPlacement {
    ABOVE_END,
    BELOW_END,
    BELOW_START,
}

@Composable
fun MiuixDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    placement: MiuixMenuPlacement = MiuixMenuPlacement.BELOW_START,
    gap: Dp = 4.dp,
    shadowElevation: Dp = 10.dp,
    contentPadding: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val colors = DsTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val density = LocalDensity.current
    val padPx = with(density) { contentPadding.roundToPx() }
    val gapPx = with(density) { gap.roundToPx() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val menuScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.94f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "menuScale",
    )
    val menuAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(140),
        label = "menuAlpha",
    )
    val positionProvider = remember(placement, padPx, gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val cardWidth = (popupContentSize.width - padPx * 2).coerceAtLeast(1)
                val cardHeight = (popupContentSize.height - padPx * 2).coerceAtLeast(1)
                val fitsAbove = anchorBounds.top - gapPx - cardHeight >= 0
                val fitsBelow = anchorBounds.bottom + gapPx + cardHeight <= windowSize.height
                val above = when (placement) {
                    MiuixMenuPlacement.ABOVE_END -> fitsAbove
                    else -> !fitsBelow && fitsAbove
                }
                val cardX = when (placement) {
                    MiuixMenuPlacement.ABOVE_END,
                    MiuixMenuPlacement.BELOW_END -> anchorBounds.right - cardWidth
                    MiuixMenuPlacement.BELOW_START -> anchorBounds.left
                }.coerceIn(0, (windowSize.width - cardWidth).coerceAtLeast(0))
                val cardY = if (above) {
                    anchorBounds.top - gapPx - cardHeight
                } else {
                    anchorBounds.bottom + gapPx
                }
                val clampedY = cardY.coerceIn(0, (windowSize.height - cardHeight).coerceAtLeast(0))
                return IntOffset(cardX - padPx, clampedY - padPx)
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Box(Modifier.padding(contentPadding)) {
            Column(
                modifier = modifier
                    .width(176.dp)
                    .graphicsLayer {
                        scaleX = menuScale
                        scaleY = menuScale
                        alpha = menuAlpha
                    }
                    .shadow(shadowElevation, shape, clip = false)
                    .clip(shape)
                    .background(colors.layer)
                    .border(BorderStroke(0.5.dp, colors.border), shape)
                    .padding(vertical = 5.dp),
                content = content,
            )
        }
    }
}

@Composable
fun MiuixMenuItem(
    text: String,
    icon: ImageVector? = null,
    danger: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) colors.danger else colors.textSecondary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = text,
            fontSize = 13.5.sp,
            color = if (danger) colors.danger else colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = colors.brand,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun MiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    val colors = DsTheme.colors
    val density = LocalDensity.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val thumbRadius = with(density) { 10.dp.toPx() }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val trackWidth = (widthPx - thumbRadius * 2f).coerceAtLeast(1f)
        val range = valueRange.endInclusive - valueRange.start
        val fraction = if (range > 0f) {
            ((value - valueRange.start) / range).coerceIn(0f, 1f)
        } else {
            0f
        }

        fun update(rawX: Float) {
            val f = ((rawX - thumbRadius) / trackWidth).coerceIn(0f, 1f)
            var next = valueRange.start + f * range
            if (steps > 0) {
                val step = range / (steps + 1)
                val index = ((next - valueRange.start) / step).roundToInt().coerceIn(0, steps + 1)
                next = valueRange.start + index * step
            }
            currentOnValueChange(next)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(valueRange, steps, trackWidth) {
                    detectTapGestures { offset -> update(offset.x) }
                }
                .pointerInput(valueRange, steps, trackWidth) {
                    detectDragGestures { change, _ ->
                        update(change.position.x)
                        change.consume()
                    }
                },
        ) {
            val centerY = size.height / 2f
            val trackHeight = 10.dp.toPx()
            val left = thumbRadius
            val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            drawRoundRect(
                color = colors.card,
                topLeft = Offset(left, centerY - trackHeight / 2f),
                size = Size(trackWidth, trackHeight),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = colors.brand,
                topLeft = Offset(left, centerY - trackHeight / 2f),
                size = Size(trackWidth * fraction, trackHeight),
                cornerRadius = corner,
            )
            val thumbX = left + trackWidth * fraction
            drawCircle(
                color = Color.Black.copy(alpha = 0.12f),
                radius = thumbRadius + 1.5.dp.toPx(),
                center = Offset(thumbX, centerY + 1.dp.toPx()),
            )
            drawCircle(
                color = colors.layer,
                radius = thumbRadius,
                center = Offset(thumbX, centerY),
            )
            drawCircle(
                color = colors.borderStrong,
                radius = thumbRadius,
                center = Offset(thumbX, centerY),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

@Composable
fun MiuixTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = DsTheme.colors
    val singleLine = minLines == 1
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 4.dp, bottom = 5.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(colors.card)
                .padding(
                    start = 14.dp,
                    end = if (trailing != null) 4.dp else 14.dp,
                    top = if (singleLine) 0.dp else 12.dp,
                    bottom = if (singleLine) 0.dp else 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .then(if (singleLine) Modifier.padding(vertical = 12.dp) else Modifier),
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = colors.textPrimary,
                ),
                cursorBrush = SolidColor(colors.brand),
                singleLine = singleLine,
                minLines = minLines,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                fontSize = 13.sp,
                                lineHeight = if (singleLine) 20.sp else 19.sp,
                                color = colors.textTertiary,
                            )
                        }
                        inner()
                    }
                },
            )
            if (trailing != null) {
                trailing()
            }
        }
    }
}

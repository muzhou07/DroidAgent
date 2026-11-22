package com.muzhou.droidagent.ui.markdown

import com.muzhou.droidagent.ui.theme.DsColors
import com.muzhou.droidagent.ui.theme.DsTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val indent: Int, val marker: String, val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

fun parseMarkdown(source: String): List<MdBlock> {
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    var index = 0

    fun flushParagraph() {
        val text = paragraph.toString().trim('\n')
        if (text.isNotBlank()) blocks.add(MdBlock.Paragraph(text.trimEnd()))
        paragraph.setLength(0)
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            val fence = trimmed.take(3)
            val language = trimmed.removePrefix(fence).trim().substringBefore(' ')
            index++
            val code = StringBuilder()
            while (index < lines.size && !lines[index].trimStart().startsWith(fence)) {
                code.append(lines[index]).append('\n')
                index++
            }
            if (index < lines.size) index++
            blocks.add(MdBlock.Code(language, code.toString().trimEnd('\n')))
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            index++
            continue
        }
        val heading = Regex("^(#{1,6})\\s+(.*)$").matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trimEnd('#')))
            index++
            continue
        }
        if (Regex("^([-*_])(\\s*\\1){2,}\\s*$").matches(trimmed)) {
            flushParagraph()
            blocks.add(MdBlock.Divider)
            index++
            continue
        }
        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quote = StringBuilder()
            while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                quote.append(lines[index].trimStart().removePrefix(">").removePrefix(" ")).append('\n')
                index++
            }
            blocks.add(MdBlock.Quote(quote.toString().trimEnd()))
            continue
        }
        val listItem = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$").matchEntire(line)
        if (listItem != null) {
            flushParagraph()
            val indent = listItem.groupValues[1].length / 2
            val rawMarker = listItem.groupValues[2]
            val marker = if (rawMarker[0].isDigit()) rawMarker else "•"
            blocks.add(MdBlock.ListItem(indent, marker, listItem.groupValues[3]))
            index++
            continue
        }
        if (line.contains('|') && index + 1 < lines.size && isTableSeparator(lines[index + 1])) {
            flushParagraph()
            val header = parseTableRow(line)
            index += 2
            val rows = ArrayList<List<String>>()
            while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                rows.add(parseTableRow(lines[index]))
                index++
            }
            blocks.add(MdBlock.Table(header, rows))
            continue
        }
        paragraph.append(line).append('\n')
        index++
    }
    flushParagraph()
    return blocks
}

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || !trimmed.contains('-')) return false
    if (!trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }) return false
    return trimmed.count { it == '-' } >= 3
}

private fun parseTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = DsTheme.colors.textPrimary,
) {
    val colors = DsTheme.colors
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Paragraph -> ParagraphText(block.text, color)
                is MdBlock.Heading -> HeadingText(block.level, block.text, color)
                is MdBlock.Code -> CodeBlock(block.language, block.code)
                is MdBlock.Quote -> QuoteBlock(block.text, color)
                is MdBlock.ListItem -> ListItemRow(block, color)
                is MdBlock.Table -> TableBlock(block)
                MdBlock.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = colors.divider,
                )
            }
            if (index != blocks.lastIndex) {
                val spacing = when (block) {
                    is MdBlock.Code, is MdBlock.Table -> 10.dp
                    is MdBlock.Heading -> 8.dp
                    else -> 6.dp
                }
                Spacer(Modifier.height(spacing))
            }
        }
    }
}

@Composable
private fun ParagraphText(text: String, color: Color) {
    InlineMarkdownText(
        text = text,
        style = TextStyle(
            fontSize = 16.sp,
            lineHeight = 26.sp,
            color = color,
        ),
    )
}

@Composable
private fun HeadingText(level: Int, text: String, color: Color) {
    val size = when (level) {
        1 -> 24.sp
        2 -> 21.sp
        3 -> 18.sp
        4 -> 16.sp
        else -> 15.sp
    }
    InlineMarkdownText(
        text = text,
        style = TextStyle(
            fontSize = size,
            lineHeight = size * 1.4f,
            fontWeight = FontWeight.SemiBold,
            color = color,
        ),
    )
}

@Composable
private fun QuoteBlock(text: String, color: Color) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(colors.brand, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        InlineMarkdownText(
            text = text,
            style = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, color = colors.textSecondary),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ListItemRow(item: MdBlock.ListItem, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (item.indent * 16).dp, bottom = 3.dp),
    ) {
        Text(
            text = item.marker,
            color = DsTheme.colors.textSecondary,
            fontSize = 15.sp,
            lineHeight = 25.sp,
            modifier = Modifier.width(24.dp),
        )
        InlineMarkdownText(
            text = item.text,
            style = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, color = color),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table) {
    val colors = DsTheme.colors
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .horizontalScroll(scroll)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .background(colors.background, RoundedCornerShape(10.dp)),
    ) {
        Row(Modifier.background(colors.card)) {
            table.header.forEach { cell ->
                InlineMarkdownText(
                    text = cell,
                    style = TextStyle(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                    modifier = Modifier
                        .width(150.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        table.rows.forEach { row ->
            HorizontalDivider(color = colors.divider)
            Row {
                table.header.indices.forEach { column ->
                    val cell = row.getOrNull(column).orEmpty()
                    InlineMarkdownText(
                        text = cell,
                        style = TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = colors.textSecondary,
                        ),
                        modifier = Modifier
                            .width(150.dp)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val colors = DsTheme.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .background(colors.codeBackground, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeHeader)
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = language.ifBlank { "text" }.lowercase(),
                fontSize = 12.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                    scope.launch {
                        delay(1400)
                        copied = false
                    }
                },
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "复制代码",
                    modifier = Modifier.size(14.dp),
                    tint = colors.textSecondary,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (copied) "已复制" else "复制", fontSize = 12.sp, color = colors.textSecondary)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                text = highlightCode(code, language, colors),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                softWrap = false,
                color = colors.textPrimary,
            )
        }
    }
}

/** 只允许外部浏览器/邮件链接，避免模型输出触发任意 Intent scheme。 */
private fun isSafeExternalLink(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val scheme = trimmed.substringBefore(':', "").lowercase()
    if (scheme.isEmpty()) return false
    return scheme == "http" || scheme == "https" || scheme == "mailto"
}

@Composable
fun InlineMarkdownText(
    text: String,
    style: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    val annotated = remember(text, colors) { buildInline(text, colors) }
    val uriHandler = LocalUriHandler.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style,
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures { position ->
                val result = layout ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(position)
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { annotation ->
                    if (isSafeExternalLink(annotation.item)) {
                        runCatching { uriHandler.openUri(annotation.item) }
                    }
                }
            }
        },
        onTextLayout = { layout = it },
    )
}

private fun buildInline(text: String, colors: DsColors): AnnotatedString {
    val builder = AnnotatedString.Builder()
    appendInline(builder, text, colors)
    return builder.toAnnotatedString()
}

private fun appendInline(builder: AnnotatedString.Builder, text: String, colors: DsColors) {
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        if (ch == '\\' && index + 1 < text.length) {
            builder.append(text[index + 1])
            index += 2
            continue
        }
        if (text.startsWith("**", index) || text.startsWith("__", index)) {
            val delimiter = text.substring(index, index + 2)
            val end = text.indexOf(delimiter, index + 2)
            if (end > index + 2) {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                appendInline(builder, text.substring(index + 2, end), colors)
                builder.pop()
                index = end + 2
                continue
            }
        }
        if (text.startsWith("~~", index)) {
            val end = text.indexOf("~~", index + 2)
            if (end > index + 2) {
                builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInline(builder, text.substring(index + 2, end), colors)
                builder.pop()
                index = end + 2
                continue
            }
        }
        if (ch == '`') {
            val end = text.indexOf('`', index + 1)
            if (end > index + 1) {
                builder.pushStyle(inlineCodeStyle(colors))
                builder.append(text.substring(index + 1, end))
                builder.pop()
                index = end + 1
                continue
            }
        }
        if (ch == '*' || ch == '_') {
            val end = text.indexOf(ch, index + 1)
            if (end > index + 1) {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInline(builder, text.substring(index + 1, end), colors)
                builder.pop()
                index = end + 1
                continue
            }
        }
        if (ch == '[') {
            val labelEnd = text.indexOf("](", index + 1)
            if (labelEnd > index) {
                val urlEnd = text.indexOf(')', labelEnd + 2)
                if (urlEnd > labelEnd) {
                    val label = text.substring(index + 1, labelEnd)
                    val url = text.substring(labelEnd + 2, urlEnd).trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        builder.pushStringAnnotation("URL", url)
                        builder.pushStyle(
                            SpanStyle(color = colors.brand, textDecoration = TextDecoration.Underline),
                        )
                        appendInline(builder, label, colors)
                        builder.pop()
                        builder.pop()
                        index = urlEnd + 1
                        continue
                    }
                }
            }
        }
        builder.append(ch)
        index++
    }
}

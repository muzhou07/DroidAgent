package com.muzhou.droidagent.agent.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

fun formatTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

fun truncateMiddle(text: String, max: Int): String {
    if (text.length <= max) return text
    val half = (max - 20) / 2
    return text.take(half) + "\n…（内容已截断）…\n" + text.takeLast(half)
}

fun truncateTail(text: String, max: Int): String =
    if (text.length <= max) text else "…（前 ${text.length - max} 字符已截断）…\n" + text.takeLast(max)

fun String.oneLine(max: Int = 160): String {
    val s = replace(Regex("\\s+"), " ").trim()
    return if (s.length <= max) s else s.take(max) + "…"
}

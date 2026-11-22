package com.muzhou.droidagent.agent.tool

import com.muzhou.droidagent.agent.util.formatBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

private const val MAX_TOOL_OUTPUT = 60_000

class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description =
        "读取工作区内的文本文件。返回带行号的内容。支持 offset/limit 分段读取大文件。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "path" to Schemas.string("相对于工作区根目录的文件路径"),
            "offset" to Schemas.integer("起始行号（从 1 开始）", 1),
            "limit" to Schemas.integer("最多读取的行数", 2000),
        ),
        required = listOf("path"),
    )

    override fun summarize(arguments: JsonObject): String =
        "读取 ${arguments.optionalString("path").orEmpty()}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val path = arguments.stringArg("path")
        val offset = arguments.intArg("offset", 1).coerceAtLeast(1)
        val limit = arguments.intArg("limit", 2000).coerceIn(1, 5000)
        val text = ctx.vfs.readText(path)
        if (text.indexOf('\u0000') >= 0) {
            return ToolResult("无法读取二进制文件：$path", isError = true)
        }
        val lines = text.split('\n')
        val total = lines.size
        if (offset > total) {
            return ToolResult("文件 $path 共 $total 行，起始行 $offset 超出末尾。", isError = true)
        }
        val end = minOf(total, offset + limit - 1)
        val out = StringBuilder()
        out.append("文件：").append(path).append("（共 ").append(total).append(" 行，显示 ")
            .append(offset).append('-').append(end).append("）\n")
        for (index in offset..end) {
            val line = lines[index - 1].removeSuffix("\r")
            out.append(index.toString().padStart(6)).append("  ").append(line).append('\n')
            if (out.length > MAX_TOOL_OUTPUT) {
                out.append("…（输出过长已截断，请使用 offset/limit 分段读取）")
                break
            }
        }
        if (end < total) out.append("…（还有 ").append(total - end).append(" 行未显示）")
        return ToolResult(out.toString())
    }
}

class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description =
        "创建或覆盖写入工作区内的文本文件。会自动创建父目录。修改已有文件前应优先使用 edit_file。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "path" to Schemas.string("相对于工作区根目录的文件路径"),
            "content" to Schemas.string("要写入的完整文件内容"),
        ),
        required = listOf("path", "content"),
    )
    override val mutating = true

    override fun summarize(arguments: JsonObject): String =
        "写入 ${arguments.optionalString("path").orEmpty()}"

    override suspend fun approvalPreview(arguments: JsonObject, ctx: ToolContext): ApprovalPreview {
        val path = arguments.stringArg("path")
        val content = arguments.stringArg("content")
        val exists = runCatching { ctx.vfs.exists(path) }.getOrDefault(false)
        val lines = content.lines()
        val diff = lines.take(250).joinToString("\n") { "+ $it" } +
            if (lines.size > 250) "\n…（其余 ${lines.size - 250} 行省略）" else ""
        return ApprovalPreview(
            title = "写入文件",
            detail = "$path\n${lines.size} 行 · ${formatBytes(content.toByteArray().size.toLong())}" +
                if (exists) "\n⚠️ 文件已存在，将被完整覆盖" else "\n将创建新文件",
            diff = diff,
        )
    }

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val path = arguments.stringArg("path")
        val content = arguments.stringArg("content")
        if (content.toByteArray().size > 2 * 1024 * 1024) {
            return ToolResult("内容超过 2MB，拒绝写入。请拆分文件。", isError = true)
        }
        ctx.vfs.writeText(path, content)
        return ToolResult(
            "已写入 $path（${content.lines().size} 行，${formatBytes(content.toByteArray().size.toLong())}）",
            summary = "已写入 $path",
        )
    }
}

class EditFileTool : AgentTool {
    override val name = "edit_file"
    override val description =
        "精确替换文件中的一段文本。old_string 必须与文件内容完全一致（包括缩进）。" +
            "若 old_string 出现多次，需设置 replace_all=true。修改前建议先 read_file。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "path" to Schemas.string("文件路径"),
            "old_string" to Schemas.string("要被替换的原始文本（必须精确匹配）"),
            "new_string" to Schemas.string("替换后的新文本，可为空字符串以删除"),
            "replace_all" to Schemas.boolean("是否替换所有匹配项", false),
        ),
        required = listOf("path", "old_string", "new_string"),
    )
    override val mutating = true

    override fun summarize(arguments: JsonObject): String =
        "编辑 ${arguments.optionalString("path").orEmpty()}"

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    override suspend fun approvalPreview(arguments: JsonObject, ctx: ToolContext): ApprovalPreview {
        val path = arguments.stringArg("path")
        val oldString = arguments.stringArg("old_string")
        val newString = arguments.stringArg("new_string")
        val replaceAll = arguments.boolArg("replace_all", false)
        val original = runCatching { ctx.vfs.readText(path) }.getOrNull()
            ?: return ApprovalPreview("编辑文件", "$path\n（无法读取原文件）")
        val index = original.indexOf(oldString)
        val count = countOccurrences(original, oldString)
        val lines = if (index < 0) {
            "未找到要替换的文本"
        } else {
            val before = original.substring(0, index)
            val startLine = before.count { it == '\n' }
            val oldLines = oldString.lines()
            val newLines = newString.lines()
            val context = original.lines()
            buildString {
                val from = (startLine - 3).coerceAtLeast(0)
                for (i in from until startLine) append("  ").append(context[i]).append('\n')
                oldLines.forEach { append("- ").append(it).append('\n') }
                newLines.forEach { append("+ ").append(it).append('\n') }
                val after = startLine + oldLines.size
                for (i in after until minOf(context.size, after + 3)) {
                    append("  ").append(context[i]).append('\n')
                }
            }
        }
        return ApprovalPreview(
            title = "编辑文件",
            detail = "$path\n匹配 $count 处" + if (replaceAll) "（全部替换）" else "",
            diff = lines.take(12_000),
        )
    }

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val path = arguments.stringArg("path")
        val oldString = arguments.stringArg("old_string")
        val newString = arguments.stringArg("new_string")
        val replaceAll = arguments.boolArg("replace_all", false)
        if (oldString.isEmpty()) return ToolResult("old_string 不能为空", isError = true)
        val original = ctx.vfs.readText(path)
        val count = countOccurrences(original, oldString)
        if (count == 0) {
            return ToolResult(
                "在 $path 中未找到 old_string。请先用 read_file 查看当前内容，并确保缩进与换行完全一致。",
                isError = true,
            )
        }
        if (count > 1 && !replaceAll) {
            return ToolResult(
                "old_string 在 $path 中出现了 $count 次。请提供更多上下文使其唯一，或设置 replace_all=true。",
                isError = true,
            )
        }
        val updated = if (replaceAll) original.replace(oldString, newString)
        else original.replaceFirst(oldString, newString)
        ctx.vfs.writeText(path, updated)
        return ToolResult(
            "已编辑 $path（替换 ${if (replaceAll) count else 1} 处）",
            summary = "已编辑 $path",
        )
    }
}

class ListDirTool : AgentTool {
    override val name = "list_dir"
    override val description = "列出工作区内某个目录的直接子项（目录与文件）。"
    override val parameters = Schemas.objectSchema(
        mapOf("path" to Schemas.string("目录路径，默认为工作区根目录", default = ".")),
    )

    override fun summarize(arguments: JsonObject): String =
        "列出 ${arguments.optionalString("path") ?: "."}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val path = arguments.optionalString("path") ?: "."
        val entries = ctx.vfs.list(path)
        if (entries.isEmpty()) return ToolResult("目录 $path 为空。")
        val out = StringBuilder("目录：$path（${entries.size} 项）\n")
        entries.take(400).forEach { entry ->
            if (entry.isDirectory) {
                out.append("[DIR ] ").append(entry.name).append('/')
            } else {
                out.append("[FILE] ").append(entry.name).append("  ").append(formatBytes(entry.size))
            }
            out.append('\n')
        }
        if (entries.size > 400) out.append("…（其余 ${entries.size - 400} 项已省略）")
        return ToolResult(out.toString())
    }
}

class GlobTool : AgentTool {
    override val name = "glob"
    override val description =
        "按 glob 模式查找文件，例如 \"**/*.kt\"、\"src/**/*.ts\"、\"*.md\"。返回匹配的相对路径。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "pattern" to Schemas.string("glob 模式，支持 *、**、?"),
            "path" to Schemas.string("搜索起始目录", default = "."),
        ),
        required = listOf("pattern"),
    )

    override fun summarize(arguments: JsonObject): String =
        "查找 ${arguments.optionalString("pattern").orEmpty()}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = arguments.stringArg("pattern").trim().removePrefix("./")
        val start = arguments.optionalString("path") ?: "."
        val regex = globToRegex(pattern)
        val entries = ctx.vfs.walk(start, maxDepth = 20, maxEntries = 20000)
        val matches = entries.filter { entry ->
            if (entry.isDirectory) false
            else regex.matches(entry.relPath) || (!pattern.contains('/') && regex.matches(entry.name))
        }.sortedBy { it.relPath }
        if (matches.isEmpty()) return ToolResult("没有找到匹配 \"$pattern\" 的文件。")
        return ToolResult(
            buildString {
                append("匹配 \"").append(pattern).append("\" 的文件（").append(matches.size).append("）：\n")
                matches.take(400).forEach { append(it.relPath).append("  (").append(formatBytes(it.size)).append(")\n") }
                if (matches.size > 400) append("…（其余 ${matches.size - 400} 个已省略）")
            },
        )
    }

    companion object {
        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder("^")
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when (c) {
                    '*' -> {
                        if (i + 1 < glob.length && glob[i + 1] == '*') {
                            sb.append(".*")
                            i++
                            if (i + 1 < glob.length && glob[i + 1] == '/') i++
                        } else {
                            sb.append("[^/]*")
                        }
                    }
                    '?' -> sb.append("[^/]")
                    in ".+()|^\$@%{}[]\\" -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
                i++
            }
            sb.append('$')
            return Regex(sb.toString())
        }
    }
}

class GrepTool : AgentTool {
    override val name = "grep"
    override val description =
        "在工作区文件中搜索正则表达式，返回 文件:行号:内容。可配合 include 限定文件类型。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "pattern" to Schemas.string("正则表达式"),
            "path" to Schemas.string("搜索目录", default = "."),
            "include" to Schemas.string("文件名 glob 过滤，例如 *.kt、*.ts"),
            "case_sensitive" to Schemas.boolean("是否区分大小写", false),
            "max_results" to Schemas.integer("最多返回条数", 100),
        ),
        required = listOf("pattern"),
    )

    override fun summarize(arguments: JsonObject): String =
        "搜索 ${arguments.optionalString("pattern").orEmpty()}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = arguments.stringArg("pattern")
        val start = arguments.optionalString("path") ?: "."
        val include = arguments.optionalString("include")?.takeIf { it.isNotBlank() }
        val caseSensitive = arguments.boolArg("case_sensitive", false)
        val maxResults = arguments.intArg("max_results", 100).coerceIn(1, 500)
        val regex = try {
            Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            return ToolResult("无效的正则表达式：${e.message}", isError = true)
        }
        val includeRegex = include?.let { GlobTool.globToRegex(it.removePrefix("./")) }
        val files = ctx.vfs.walk(start, maxDepth = 20, maxEntries = 20000)
            .filter { !it.isDirectory && it.size <= 2L * 1024 * 1024 }
            .filter { entry -> includeRegex == null || includeRegex.matches(entry.name) || includeRegex.matches(entry.relPath) }
        val out = StringBuilder()
        var count = 0
        outer@ for (file in files) {
            val text = try {
                ctx.vfs.readText(file.relPath, maxBytes = 2 * 1024 * 1024)
            } catch (_: Exception) {
                continue
            }
            if (text.indexOf('\u0000') >= 0) continue
            for ((index, line) in text.lineSequence().withIndex()) {
                if (count >= maxResults) break@outer
                if (regex.containsMatchIn(line)) {
                    out.append(file.relPath).append(':').append(index + 1).append(": ")
                        .append(line.trim().take(400)).append('\n')
                    count++
                }
            }
        }
        if (count == 0) return ToolResult("没有找到匹配 \"$pattern\" 的内容。")
        if (out.length > MAX_TOOL_OUTPUT) {
            return ToolResult(out.take(MAX_TOOL_OUTPUT).toString() + "\n…（结果过长已截断）")
        }
        return ToolResult("共 $count 处匹配（最多显示 $maxResults）：\n$out")
    }
}

class DeleteFileTool : AgentTool {
    override val name = "delete_file"
    override val description = "删除工作区内的文件或目录。删除目录时默认递归删除，请谨慎使用。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "path" to Schemas.string("要删除的路径"),
            "recursive" to Schemas.boolean("删除目录时是否递归", true),
        ),
        required = listOf("path"),
    )
    override val mutating = true

    override fun summarize(arguments: JsonObject): String =
        "删除 ${arguments.optionalString("path").orEmpty()}"

    override suspend fun approvalPreview(arguments: JsonObject, ctx: ToolContext): ApprovalPreview {
        val path = arguments.stringArg("path")
        val recursive = arguments.boolArg("recursive", true)
        return ApprovalPreview("删除文件", "$path\n" + if (recursive) "将递归删除（不可恢复）" else "仅删除空目录或文件")
    }

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val path = arguments.stringArg("path")
        val recursive = arguments.boolArg("recursive", true)
        val deleted = ctx.vfs.delete(path, recursive)
        return if (deleted) ToolResult("已删除 $path", summary = "已删除 $path")
        else ToolResult("删除失败：$path", isError = true)
    }
}

class ShellTool : AgentTool {
    override val name = "run_shell"
    override val description =
        "在工作区目录中执行 Shell 命令。Android 环境命令有限（toybox），可用 ls/cat/grep/find/sed 等。" +
            "HOME 指向工作区。长时间下载/编译/安装请设置 run_in_background=true，立即返回任务 ID，" +
            "之后用 job_output 读取输出、job_list 查看状态、job_kill 终止。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "command" to Schemas.string("要执行的 Shell 命令"),
            "run_in_background" to Schemas.boolean("是否放到后台执行（适合 wget/curl 下载、编译、安装）", false),
            "timeout_ms" to Schemas.integer("前台命令的超时时间（毫秒）；后台任务不受此限制", 120000),
        ),
        required = listOf("command"),
    )
    override val mutating = true

    override fun summarize(arguments: JsonObject): String = buildString {
        append("执行 ").append(arguments.optionalString("command").orEmpty().take(60))
        if (arguments.boolArg("run_in_background", false)) append("（后台）")
    }

    override suspend fun approvalPreview(arguments: JsonObject, ctx: ToolContext): ApprovalPreview {
        val command = arguments.stringArg("command")
        val background = arguments.boolArg("run_in_background", false)
        return ApprovalPreview(
            title = if (background) "后台执行 Shell 命令" else "执行 Shell 命令",
            detail = "后端：${ctx.shell?.backendName ?: "不可用"}\n\n$command",
        )
    }

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val command = arguments.stringArg("command")
        val shell = ctx.shell
            ?: return ToolResult("当前工作区不支持 Shell（SAF 目录且无法映射为本地路径）。", isError = true)
        if (arguments.boolArg("run_in_background", false)) {
            val manager = ctx.jobs
                ?: return ToolResult("后台任务不可用。", isError = true)
            val job = manager.start(ctx.session.sessionId, command, shell)
            return ToolResult(
                buildString {
                    append("已启动后台任务 ").append(job.id).append("（").append(shell.backendName).append("）。\n")
                    append("命令：").append(command.take(200)).append("\n\n")
                    append("不要用 sleep 轮询。继续处理其他步骤；")
                    append("需要结果时调用 job_output（wait=true 才会等待），")
                    append("用 job_list 查看状态，用 job_kill 终止。任务完成时会在会话内通知。")
                },
                summary = "后台 ${job.id}",
            )
        }
        val timeout = arguments.intArg("timeout_ms", 120_000).coerceIn(1_000, 600_000).toLong()
        val result = shell.run(command, timeout)
        val output = result.combined()
        val clipped = if (output.length > MAX_TOOL_OUTPUT) output.take(MAX_TOOL_OUTPUT) + "\n…（输出已截断）" else output
        val header = "[${result.backend}] 退出码 ${result.exitCode} · ${result.durationMs}ms" +
            if (result.timedOut) " · 已超时" else ""
        return ToolResult(
            "$header\n\n$clipped",
            isError = result.exitCode != 0 || result.timedOut,
            summary = "${result.backend} · 退出码 ${result.exitCode}",
        )
    }
}

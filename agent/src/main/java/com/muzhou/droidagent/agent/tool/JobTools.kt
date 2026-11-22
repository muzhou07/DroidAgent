package com.muzhou.droidagent.agent.tool

import com.muzhou.droidagent.agent.shell.ShellJobInfo
import kotlinx.serialization.json.JsonObject

class JobListTool : AgentTool {
    override val name = "job_list"
    override val description =
        "列出当前会话的后台 Shell 任务及状态。后台任务用于 wget/curl 下载、编译、安装等长时间命令。"
    override val parameters = Schemas.objectSchema(emptyMap())

    override fun summarize(arguments: JsonObject): String = "查看后台任务"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val manager = ctx.jobs ?: return ToolResult("后台任务不可用", isError = true)
        val jobs = manager.list(ctx.session.sessionId)
        if (jobs.isEmpty()) return ToolResult("没有后台任务。")
        return ToolResult(
            jobs.joinToString("\n\n") { job ->
                buildString {
                    append("- ").append(job.id).append(" · ").append(status(job))
                    append(" · ").append(duration((job.finishedAt ?: System.currentTimeMillis()) - job.startedAt))
                    append(" · ").append(job.backend)
                    append('\n').append("  ").append(job.command.lineSequence().firstOrNull().orEmpty().take(100))
                    job.error?.let { append('\n').append("  ").append(it.take(200)) }
                }
            },
        )
    }

    private fun status(job: ShellJobInfo): String = when {
        job.running -> "运行中"
        job.killed -> "已终止"
        job.exitCode == 0 -> "已完成"
        job.exitCode != null -> "失败（退出码 ${job.exitCode}）"
        else -> "已结束"
    }

    private fun duration(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1_000} 秒"
        else -> "${ms / 60_000} 分 ${(ms % 60_000) / 1_000} 秒"
    }
}

class JobOutputTool : AgentTool {
    override val name = "job_output"
    override val description =
        "读取后台任务的新增输出。默认立即返回；wait=true 时最多等待 timeout_ms 直到有输出或任务结束。" +
            "真正需要结果时才 wait，不要用 sleep 轮询。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "job_id" to Schemas.string("后台任务 ID，例如 job_ab12cd34"),
            "wait" to Schemas.boolean("是否等待任务完成或有新输出", false),
            "timeout_ms" to Schemas.integer("wait=true 时最长等待毫秒数", 30000),
        ),
        required = listOf("job_id"),
    )

    override fun summarize(arguments: JsonObject): String =
        "读取后台任务 ${arguments.optionalString("job_id").orEmpty()}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val manager = ctx.jobs ?: return ToolResult("后台任务不可用", isError = true)
        val id = arguments.stringArg("job_id")
        val wait = arguments.boolArg("wait", false)
        val timeout = arguments.intArg("timeout_ms", 30_000).coerceIn(0, 60_000).toLong()
        val output = manager.output(ctx.session.sessionId, id, wait, timeout)
            ?: return ToolResult("未找到后台任务：$id", isError = true)
        val header = buildString {
            append("[").append(output.id)
            append(" · ").append(
                when {
                    output.running -> "运行中"
                    output.killed -> "已终止"
                    output.exitCode == 0 -> "已完成"
                    output.exitCode != null -> "失败（退出码 ${output.exitCode}）"
                    else -> "已结束"
                },
            )
            append("]")
        }
        val body = output.text.ifEmpty {
            if (output.running) "（暂无新输出）" else "（没有更多输出）"
        }
        val footer = when {
            output.running -> "\n\n任务仍在运行。可继续其他工作，稍后再读取，或在确实需要结果时使用 wait=true。"
            output.killed -> "\n\n任务已被终止。"
            else -> ""
        }
        output.error?.let { error ->
            return ToolResult("$header\n\n$body\n\n错误：$error$footer", isError = true)
        }
        return ToolResult("$header\n\n$body$footer")
    }
}

class JobKillTool : AgentTool {
    override val name = "job_kill"
    override val description =
        "终止指定后台任务及其子进程。只有确认任务不再需要时才调用。"
    override val parameters = Schemas.objectSchema(
        mapOf("job_id" to Schemas.string("要终止的后台任务 ID")),
        required = listOf("job_id"),
    )
    override val mutating: Boolean = true

    override fun summarize(arguments: JsonObject): String =
        "终止后台任务 ${arguments.optionalString("job_id").orEmpty()}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val manager = ctx.jobs ?: return ToolResult("后台任务不可用", isError = true)
        val id = arguments.stringArg("job_id")
        val result = manager.kill(ctx.session.sessionId, id)
            ?: return ToolResult("未找到后台任务：$id", isError = true)
        return ToolResult(result)
    }
}

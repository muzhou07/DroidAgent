package com.muzhou.droidagent.agent.tool

import com.muzhou.droidagent.agent.AgentEvent
import com.muzhou.droidagent.agent.TodoItem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class WebSearchTool : AgentTool {
    override val name = "web_search"
    override val description = "使用搜索引擎检索互联网信息，返回标题、链接和摘要。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "query" to Schemas.string("搜索关键词"),
            "max_results" to Schemas.integer("最多结果数", 5),
        ),
        required = listOf("query"),
    )

    override fun summarize(arguments: JsonObject): String =
        "搜索 ${arguments.optionalString("query").orEmpty()}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val query = arguments.stringArg("query")
        val max = arguments.intArg("max_results", 5).coerceIn(1, 10)
        return try {
            ToolResult(ctx.web.search(query, max, ctx.settings.tavilyApiKey))
        } catch (e: Exception) {
            ToolResult("搜索失败：${e.message}", isError = true)
        }
    }
}

class WebFetchTool : AgentTool {
    override val name = "web_fetch"
    override val description = "抓取网页并提取可读正文，用于阅读文档、文章或搜索结果页面。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "url" to Schemas.string("要抓取的 http/https URL"),
            "max_chars" to Schemas.integer("最多返回的字符数", 12000),
        ),
        required = listOf("url"),
    )

    override fun summarize(arguments: JsonObject): String =
        "抓取 ${arguments.optionalString("url").orEmpty()}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val url = arguments.stringArg("url")
        val maxChars = arguments.intArg("max_chars", 12000).coerceIn(1000, 40000)
        return try {
            ToolResult(ctx.web.fetch(url, maxChars))
        } catch (e: Exception) {
            ToolResult("抓取失败：${e.message}", isError = true)
        }
    }
}

class TodoWriteTool : AgentTool {
    override val name = "todo_write"
    override val description =
        "更新当前任务清单。用于多步骤任务规划与进度跟踪。传入完整的最新清单，会覆盖旧清单。"
    override val parameters = Schemas.objectSchema(
        mapOf("items" to Schemas.array("任务清单", Schemas.todoItem())),
        required = listOf("items"),
    )

    override fun summarize(arguments: JsonObject): String = "更新任务清单"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val raw = arguments["items"] as? JsonArray ?: throw ToolException("缺少 items 数组")
        val items = raw.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val content = (obj["content"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val status = (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: "pending"
            TodoItem(content.trim(), status.takeIf { it in setOf("pending", "in_progress", "completed") } ?: "pending")
        }
        ctx.session.todos.clear()
        ctx.session.todos.addAll(items)
        ctx.events(AgentEvent.TodoUpdated(items.toList()))
        if (items.isEmpty()) return ToolResult("任务清单已清空。")
        val text = buildString {
            append("任务清单已更新：\n")
            items.forEach { item ->
                val mark = when (item.status) {
                    "completed" -> "[x]"
                    "in_progress" -> "[~]"
                    else -> "[ ]"
                }
                append(mark).append(' ').append(item.content).append('\n')
            }
        }
        return ToolResult(text, summary = "任务清单 ${items.count { it.status == "completed" }}/${items.size}")
    }
}

class TaskTool : AgentTool {
    override val name = "task"
    override val description =
        "启动一个子代理处理独立子任务（探索代码、批量分析等），它会使用自己的上下文并返回结果摘要。" +
            "适合可并行或上下文很长的子任务；不要用它替代简单的文件读取。"
    override val parameters = Schemas.objectSchema(
        mapOf(
            "description" to Schemas.string("任务的简短标题（3-8 个词）"),
            "prompt" to Schemas.string("交给子代理的完整任务说明，需包含背景、目标与预期输出"),
        ),
        required = listOf("description", "prompt"),
    )

    override fun summarize(arguments: JsonObject): String =
        "子代理 ${arguments.optionalString("description").orEmpty()}"

    override suspend fun execute(arguments: JsonObject, ctx: ToolContext): ToolResult {
        val description = arguments.stringArg("description")
        val prompt = arguments.stringArg("prompt")
        if (ctx.session.depth >= 2) {
            return ToolResult("子代理嵌套层级已达上限（2 层），请直接完成该任务。", isError = true)
        }
        val runner = ctx.subAgentRunner
            ?: return ToolResult("当前环境未启用子代理。", isError = true)
        val id = "sub_" + UUID.randomUUID().toString().take(8)
        ctx.events(AgentEvent.SubAgentStarted(id, description))
        return try {
            val result = runner(SubAgentRequest(description, prompt, ctx.session.depth + 1))
            ctx.events(AgentEvent.SubAgentFinished(id, description, result.take(2000), false))
            ToolResult(
                content = "子代理「$description」已完成：\n\n" + result.take(60_000),
                summary = "子代理完成：$description",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ctx.events(AgentEvent.SubAgentFinished(id, description, e.message ?: "失败", true))
            ToolResult("子代理执行失败：${e.message}", isError = true, summary = "子代理失败")
        }
    }
}

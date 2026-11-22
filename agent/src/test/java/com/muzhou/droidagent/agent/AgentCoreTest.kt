package com.muzhou.droidagent.agent

import com.muzhou.droidagent.agent.llm.ChatMessage
import com.muzhou.droidagent.agent.llm.ChatRequest
import com.muzhou.droidagent.agent.llm.FunctionCall
import com.muzhou.droidagent.agent.llm.LlmEvent
import com.muzhou.droidagent.agent.llm.ToolCall
import com.muzhou.droidagent.agent.tool.GlobTool
import com.muzhou.droidagent.agent.util.AgentJson
import com.muzhou.droidagent.agent.workspace.VfsException
import com.muzhou.droidagent.agent.workspace.normalizeRelative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class AgentCoreTest {
    @Test
    fun accumulatesStreamingToolCalls() {
        val accumulator = ToolCallAccumulator()
        accumulator.accept(LlmEvent.ToolDelta(0, "call_1", "read_", ""))
        accumulator.accept(LlmEvent.ToolDelta(0, null, "file", "{\"path\":"))
        accumulator.accept(LlmEvent.ToolDelta(0, null, null, "\"a.txt\"}"))
        val calls = accumulator.build()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("read_file", calls[0].function.name)
        assertEquals("{\"path\":\"a.txt\"}", calls[0].function.arguments)
    }

    @Test
    fun globMatchingHandlesDoubleStar() {
        val regex = GlobTool.globToRegex("**/*.kt")
        assertTrue(regex.matches("src/main/Foo.kt"))
        assertTrue(regex.matches("Foo.kt"))
        assertFalse(regex.matches("src/main/Foo.java"))
    }

    @Test
    fun relativePathsRejectTraversal() {
        assertEquals("a/b.txt", normalizeRelative("./a/b.txt"))
        assertEquals(".", normalizeRelative("/"))
        var threw = false
        try {
            normalizeRelative("../secret")
        } catch (_: VfsException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun assistantToolCallSerializesNullContent() {
        val message = ChatMessage.assistant(
            text = "",
            toolCalls = listOf(
                ToolCall(id = "call_1", function = FunctionCall("read_file", "{\"path\":\"a\"}")),
            ),
        )
        assertTrue(message.content is JsonNull)
        val request = ChatRequest(model = "test", messages = listOf(message), stream = true)
        val encoded = AgentJson.json.encodeToString(ChatRequest.serializer(), request)
        assertTrue(encoded.contains("\"content\":null"))
        assertTrue(encoded.contains("\"tool_calls\""))
    }

    @Test
    fun reasoningEffortIsOptionalInRequest() {
        val base = ChatRequest(model = "test", messages = emptyList())
        val auto = AgentJson.json.encodeToString(ChatRequest.serializer(), base)
        assertFalse(auto.contains("reasoning_effort"))
        val high = AgentJson.json.encodeToString(
            ChatRequest.serializer(),
            base.copy(reasoningEffort = "high"),
        )
        assertTrue(high.contains("\"reasoning_effort\":\"high\""))
    }

    @Test
    fun userContentSupportsArrayParts() {
        val element = AgentJson.json.parseToJsonElement(
            """[{"type":"text","text":"看这张图"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]""",
        )
        val message = ChatMessage(role = "user", content = element)
        assertEquals("看这张图", message.plainText())
        assertEquals(1, message.imageParts().size)
    }

    @Test
    fun promptContainsWorkspaceAndPermission() {
        val prompt = PromptBuilder.build(
            settings = AgentSettings(permissionMode = PermissionMode.ASK),
            workspace = WorkspaceInfo("1", "演示", "应用私有", "/tmp", "内置 Shell", true),
            todos = listOf(TodoItem("写测试", "in_progress")),
            projectRules = "使用中文",
            mcpSummary = listOf("- demo: connected"),
            depth = 0,
        )
        assertTrue(prompt.contains("DroidAgent"))
        assertTrue(prompt.contains("演示"))
        assertTrue(prompt.contains("写测试"))
        assertTrue(prompt.contains("使用中文"))
        assertTrue(prompt.contains("询问"))
    }

    @Test
    fun tokenEstimatorCountsChinese() {
        assertTrue(TokenEstimator.estimateText("你好世界") >= 4)
        assertTrue(TokenEstimator.estimateText(JsonPrimitive("hello world").toString()) > 0)
    }
}

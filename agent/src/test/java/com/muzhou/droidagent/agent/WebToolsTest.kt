package com.muzhou.droidagent.agent

import com.muzhou.droidagent.agent.tool.ToolException
import com.muzhou.droidagent.agent.web.WebTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsTest {
    @Test
    fun webFetchRejectsNonHttpSchemes() = runBlocking {
        val tools = WebTools()
        listOf("file:///data/data/", "ftp://example.com/", "javascript:alert(1)").forEach { url ->
            val error = runCatching { tools.fetch(url, 1000) }.exceptionOrNull()
            assertTrue("应拒绝 $url，实际：$error", error is ToolException)
        }
    }

    @Test
    fun webFetchNoLongerBlocksLocalhost() = runBlocking {
        val error = runCatching { WebTools().fetch("http://127.0.0.1:1/", 1000) }.exceptionOrNull()
        assertTrue("应进入网络请求阶段，实际：$error", error is ToolException)
        assertTrue(
            "不应被地址策略拦截，实际：${error?.message}",
            error?.message?.contains("禁止访问") != true,
        )
    }
}

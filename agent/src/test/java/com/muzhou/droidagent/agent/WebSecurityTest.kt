package com.muzhou.droidagent.agent

import com.muzhou.droidagent.agent.tool.ToolException
import com.muzhou.droidagent.agent.web.WebTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSecurityTest {
    private val blocked = listOf(
        "http://localhost/",
        "http://127.0.0.1/",
        "http://10.1.2.3/",
        "http://172.16.0.1/",
        "http://192.168.1.1/",
        "http://169.254.169.254/",
        "http://100.64.0.1/",
        "http://0.0.0.0/",
        "http://[::1]/",
        "http://[::ffff:127.0.0.1]/",
        "http://[fc00::1]/",
    )

    @Test
    fun webFetchRejectsLocalAndPrivateTargets() = runBlocking {
        val tools = WebTools()
        blocked.forEach { url ->
            val error = runCatching { tools.fetch(url, 1000) }.exceptionOrNull()
            assertTrue("应拒绝 $url，实际：$error", error is ToolException)
        }
    }

    @Test
    fun webFetchRejectsNonHttpSchemes() = runBlocking {
        val tools = WebTools()
        listOf("file:///data/data/", "ftp://example.com/", "javascript:alert(1)").forEach { url ->
            val error = runCatching { tools.fetch(url, 1000) }.exceptionOrNull()
            assertTrue("应拒绝 $url，实际：$error", error is ToolException)
        }
    }
}

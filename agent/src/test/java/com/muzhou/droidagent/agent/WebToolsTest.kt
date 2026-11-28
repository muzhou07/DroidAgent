package com.muzhou.droidagent.agent

import com.muzhou.droidagent.agent.tool.ToolException
import com.muzhou.droidagent.agent.web.WebTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

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
    fun webFetchReadsLocalhostHtml() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(name = "web-tools-test") {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) {
                        // consume request headers
                    }
                    val body = "<html><head><title>Local</title></head><body><main>Hello web</main></body></html>"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=UTF-8\r\n")
                        append("Content-Length: ").append(bytes.size).append("\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().use { out ->
                        out.write(response)
                        out.write(bytes)
                        out.flush()
                    }
                }
            }
        }
        try {
            val text = WebTools().fetch("http://127.0.0.1:${server.localPort}/", 1000)
            assertTrue(text.contains("Hello web"))
        } finally {
            server.close()
            worker.join(2000)
        }
    }
}

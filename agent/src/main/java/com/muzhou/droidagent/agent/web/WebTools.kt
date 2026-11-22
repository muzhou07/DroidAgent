package com.muzhou.droidagent.agent.web

import com.muzhou.droidagent.agent.tool.ToolException
import com.muzhou.droidagent.agent.util.AgentJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class WebTools(
    private val http: OkHttpClient = defaultClient(),
) {
    suspend fun search(query: String, maxResults: Int, tavilyKey: String): String =
        withContext(Dispatchers.IO) {
            if (tavilyKey.isNotBlank()) {
                searchTavily(query, maxResults, tavilyKey)
            } else {
                searchDuckDuckGo(query, maxResults)
            }
        }

    suspend fun fetch(url: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        var current = validateUrl(url)
        var redirects = 0
        while (true) {
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
                .build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: Exception) {
                throw ToolException("网页请求失败：${e.message}")
            }
            if (response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw ToolException("网页重定向缺少 Location")
                if (++redirects > 20) throw ToolException("网页重定向次数过多")
                val next = current.resolve(location) ?: throw ToolException("无效的重定向地址")
                current = validateUrl(next.toString())
                continue
            }
            response.use { resp ->
                if (!resp.isSuccessful) throw ToolException("网页请求失败：HTTP ${resp.code}")
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val body = resp.peekBody(10L * 1024 * 1024).string()
                return@withContext when {
                    contentType.contains("text/html") || contentType.contains("application/xhtml") ||
                        contentType.contains("application/xml") || contentType.contains("text/xml") -> {
                        val doc = Jsoup.parse(body, current.toString())
                        val title = doc.title().ifBlank { current.host }
                        doc.select("script,style,noscript,svg,iframe,header,footer,nav,aside,form,button,canvas")
                            .remove()
                        val main = doc.select("article, main, [role=main], .content, #content").firstOrNull()
                            ?: doc.body()
                        val text = main.wholeText()
                            .replace('\u00a0', ' ')
                            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                            .replace(Regex("\\n\\s*\\n+"), "\n\n")
                            .trim()
                        if (text.isBlank()) {
                            "标题：$title\n地址：$current\n\n（页面没有可提取的正文，可能是动态渲染页面）"
                        } else {
                            "标题：$title\n地址：$current\n\n" + clip(text, maxChars)
                        }
                    }
                    contentType.contains("text/") || contentType.contains("json") -> {
                        "地址：$current\n\n" + clip(body, maxChars)
                    }
                    else -> {
                        "无法提取网页正文：不支持的内容类型 $contentType（地址：$current）"
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }

    private fun searchTavily(query: String, maxResults: Int, apiKey: String): String {
        val payload = AgentJson.json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("api_key", apiKey)
                put("query", query)
                put("max_results", maxResults.coerceIn(1, 10))
                put("include_answer", true)
                put("search_depth", "basic")
            },
        )
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ToolException("Tavily 搜索失败：HTTP ${resp.code} ${text.take(300)}")
                text
            }
        } catch (e: ToolException) {
            throw e
        } catch (e: Exception) {
            throw ToolException("Tavily 搜索失败：${e.message}")
        }
        val root = AgentJson.parseObjectOrNull(body) ?: throw ToolException("Tavily 返回无法解析的响应")
        val results = root["results"]?.jsonArray ?: return "没有找到相关结果。"
        return buildString {
            (root["answer"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                append("摘要：").append(it).append("\n\n")
            }
            results.take(maxResults).forEachIndexed { index, element ->
                val obj = element.jsonObject
                append(index + 1).append(". ")
                append((obj["title"] as? JsonPrimitive)?.contentOrNull ?: "(无标题)").append('\n')
                append((obj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()).append('\n')
                append((obj["content"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(600)).append("\n\n")
            }
        }.trim()
    }

    private fun searchDuckDuckGo(query: String, maxResults: Int): String {
        val doc = try {
            Jsoup.connect("https://html.duckduckgo.com/html/")
                .data("q", query)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .userAgent(USER_AGENT)
                .timeout(30_000)
                .post()
        } catch (e: Exception) {
            throw ToolException("搜索失败：${e.message}。可改用 web_fetch 直接访问已知网址。")
        }
        val results = doc.select("div.result, div.web-result")
        if (results.isEmpty()) return "没有找到相关结果。"
        return buildString {
            results.take(maxResults).forEachIndexed { index, result ->
                val link = result.selectFirst("a.result__a") ?: return@forEachIndexed
                val title = link.text().trim()
                val rawHref = link.attr("abs:href").ifBlank { link.attr("href") }
                val url = decodeDuckUrl(rawHref)
                val snippet = result.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                append(index + 1).append(". ").append(title).append('\n')
                append(url).append('\n')
                if (snippet.isNotEmpty()) append(snippet.take(500)).append('\n')
                append('\n')
            }
        }.trim()
    }

    private fun decodeDuckUrl(href: String): String {
        if (!href.contains("uddg=")) return href
        val encoded = href.substringAfter("uddg=").substringBefore('&')
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
    }

    private fun validateUrl(raw: String): okhttp3.HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: throw ToolException("无效的 URL：$raw")
        if (url.scheme != "http" && url.scheme != "https") throw ToolException("仅支持 http/https URL")
        return url
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 DroidAgent/1.0"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        private fun clip(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            return text.take(maxChars) + "\n\n…（正文已截断，共 ${text.length} 字符）"
        }
    }
}

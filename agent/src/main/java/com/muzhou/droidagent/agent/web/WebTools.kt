package com.muzhou.droidagent.agent.web

import com.muzhou.droidagent.agent.tool.ToolException
import com.muzhou.droidagent.agent.util.AgentJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.concurrent.TimeUnit

class WebTools(
    private val http: OkHttpClient = defaultClient(),
) {
    private val cookieJar = object : CookieJar {
        private val cookies = HashMap<String, MutableList<Cookie>>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
            cookies[url.host]?.filter { it.matches(url) }.orEmpty()
        }

        override fun saveFromResponse(url: HttpUrl, list: List<Cookie>) = synchronized(cookies) {
            val stored = cookies.getOrPut(url.host) { ArrayList() }
            for (cookie in list) {
                stored.removeAll { it.name == cookie.name && it.path == cookie.path }
                stored.add(cookie)
            }
            if (stored.size > 100) stored.subList(0, stored.size - 100).clear()
        }
    }

    private val fetchClient: OkHttpClient by lazy {
        http.newBuilder().cookieJar(cookieJar).build()
    }

    private val searchClient: OkHttpClient by lazy {
        http.newBuilder()
            .cookieJar(cookieJar)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private data class SearchHit(val title: String, val url: String, val snippet: String)

    suspend fun search(query: String, maxResults: Int, tavilyKey: String): String =
        withContext(Dispatchers.IO) {
            val errors = ArrayList<String>()
            if (tavilyKey.isNotBlank()) {
                try {
                    searchTavily(query, maxResults, tavilyKey)?.let { return@withContext it }
                } catch (e: Exception) {
                    errors.add("Tavily: ${e.message.orEmpty().take(160)}")
                }
            }
            val engines = listOf<Pair<String, () -> String?>>(
                "Bing" to { searchBing(query, maxResults) },
                "DuckDuckGo" to { searchDuckDuckGo(query, maxResults) },
            )
            for ((name, engine) in engines) {
                try {
                    engine()?.let { return@withContext it }
                } catch (e: Exception) {
                    errors.add("$name: ${e.message.orEmpty().take(160)}")
                }
            }
            if (errors.isNotEmpty()) {
                throw ToolException(
                    "搜索失败：${errors.joinToString("；")}。可改用 web_fetch 直接访问已知网址。",
                )
            }
            "没有找到相关结果。"
        }

    suspend fun fetch(url: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        var current = validateUrl(url)
        var redirects = 0
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", if (attempt == 1) MOBILE_USER_AGENT else DESKTOP_USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,text/plain;q=0.7,*/*;q=0.5",
                )
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Cache-Control", "no-cache")
                .build()
            val response = try {
                fetchClient.newCall(request).execute()
            } catch (e: Exception) {
                if (attempt < MAX_ATTEMPTS - 1) {
                    attempt++
                    delay(300L * attempt)
                    continue
                }
                throw ToolException("网页请求失败：${e.message.orEmpty()}")
            }

            if (response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw ToolException("网页重定向缺少 Location")
                if (++redirects > MAX_REDIRECTS) throw ToolException("网页重定向次数过多")
                val next = current.resolve(location) ?: throw ToolException("无效的重定向地址")
                current = validateUrl(next.toString())
                continue
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                if ((code == 403 || code == 429 || code >= 500) && attempt < MAX_ATTEMPTS - 1) {
                    attempt++
                    delay(if (code == 429 || code >= 500) 700L * attempt else 250L)
                    continue
                }
                throw ToolException(
                    when (code) {
                        401, 403 -> "网页请求失败：HTTP $code（站点可能拒绝自动抓取）"
                        429 -> "网页请求失败：HTTP 429（请求过于频繁）"
                        else -> "网页请求失败：HTTP $code"
                    },
                )
            }

            response.use { resp ->
                val contentType = resp.header("Content-Type").orEmpty()
                val mime = contentType.substringBefore(';').trim().lowercase()
                val charset = charsetName(
                    Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                        .find(contentType)?.groupValues?.getOrNull(1)?.trim()?.trim('"'),
                )
                val bytes = resp.peekBody(MAX_BODY_BYTES).bytes()
                val isHtml = mime.contains("html") || mime.contains("xml") || mime.isBlank() ||
                    (mime == "application/octet-stream" && looksLikeHtml(bytes))
                return@withContext if (isHtml) {
                    extractHtml(bytes, charset, current, maxChars)
                } else if (mime.startsWith("text/") || mime.contains("json") ||
                    looksLikeJson(bytes) || (mime == "application/octet-stream" && !looksBinary(bytes))
                ) {
                    val text = decodeText(bytes, charset)
                    if (text.contains('\u0000')) {
                        "无法提取网页正文：二进制内容（地址：$current）"
                    } else {
                        "地址：$current\n\n" + clip(text, maxChars)
                    }
                } else {
                    "无法提取网页正文：不支持的内容类型 ${mime.ifBlank { "未知" }}（地址：$current）"
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }

    private fun extractHtml(
        bytes: ByteArray,
        charset: String?,
        url: HttpUrl,
        maxChars: Int,
    ): String {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), charset, url.toString())
        val title = doc.title().ifBlank {
            doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
                .ifBlank { url.host }
        }
        val description = doc.selectFirst("meta[name=description],meta[property=og:description]")
            ?.attr("content").orEmpty().trim()
        doc.select(
            "script,style,noscript,svg,iframe,header,footer,nav,aside,form,button,canvas,select,input,textarea,dialog",
        ).remove()
        val candidates = doc.select(
            "article,main,[role=main],#content,.content,.post-content,.entry-content,.article-content,.markdown-body",
        )
        val bodyText = normalizeText(doc.body().wholeText())
        val candidateText = candidates.maxByOrNull { it.text().length }
            ?.let { normalizeText(it.wholeText()) }
            .orEmpty()
        val text = when {
            candidateText.length >= 600 && candidateText.length >= bodyText.length * 0.55 -> candidateText
            bodyText.isNotBlank() -> bodyText
            else -> candidateText
        }
        val body = when {
            text.isNotBlank() -> clip(text, maxChars)
            description.isNotBlank() -> description
            else -> "页面没有可提取的正文，可能是动态渲染页面"
        }
        return "标题：$title\n地址：$url\n\n$body"
    }

    private fun normalizeText(raw: String): String = raw
        .replace('\u00a0', ' ')
        .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
        .replace(Regex(" +"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun searchTavily(query: String, maxResults: Int, apiKey: String): String? {
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
                if (!resp.isSuccessful) {
                    throw ToolException("HTTP ${resp.code} ${text.take(300)}")
                }
                text
            }
        } catch (e: ToolException) {
            throw e
        } catch (e: Exception) {
            throw ToolException(e.message.orEmpty())
        }
        val root = AgentJson.parseObjectOrNull(body) ?: throw ToolException("返回无法解析的响应")
        val results = root["results"]?.jsonArray
        val answer = (root["answer"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if ((results == null || results.isEmpty()) && answer == null) return null
        return buildString {
            answer?.let { append("摘要：").append(it).append("\n\n") }
            results?.take(maxResults)?.forEachIndexed { index, element ->
                val obj = element.jsonObject
                append(index + 1).append(". ")
                append((obj["title"] as? JsonPrimitive)?.contentOrNull ?: "(无标题)").append('\n')
                append((obj["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()).append('\n')
                append((obj["content"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(600)).append("\n\n")
            }
        }.trim()
    }

    private fun searchDuckDuckGo(query: String, maxResults: Int): String? {
        val htmlUrl = "https://html.duckduckgo.com/html/".toHttpUrlOrNull()!!
        val liteUrl = "https://lite.duckduckgo.com/lite/".toHttpUrlOrNull()!!
            .newBuilder().addQueryParameter("q", query).build()
        val attempts = listOf<() -> Document>(
            {
                getDocument(
                    htmlUrl,
                    DESKTOP_USER_AGENT,
                    FormBody.Builder().add("q", query).build(),
                )
            },
            {
                getDocument(
                    htmlUrl.newBuilder().addQueryParameter("q", query).build(),
                    DESKTOP_USER_AGENT,
                )
            },
            { getDocument(liteUrl, DESKTOP_USER_AGENT) },
        )
        var lastError: Exception? = null
        for (attempt in attempts) {
            try {
                val doc = attempt()
                val hits = if (doc.select("div.result, div.web-result").isNotEmpty()) {
                    parseDuckHtml(doc).ifEmpty { parseDuckLite(doc) }
                } else {
                    parseDuckLite(doc)
                }
                formatHits(hits, maxResults)?.let { return it }
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw ToolException(it.message.orEmpty()) }
        return null
    }

    private fun searchBing(query: String, maxResults: Int): String? {
        val url = "https://www.bing.com/search".toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("setlang", "zh-Hans")
            .addQueryParameter("mkt", "zh-CN")
            .build()
        val hits = parseBing(getDocument(url, DESKTOP_USER_AGENT))
        return formatHits(hits, maxResults)
    }

    private fun getDocument(
        url: HttpUrl,
        userAgent: String,
        form: FormBody? = null,
    ): Document {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (form == null) builder.get() else builder.post(form)
        searchClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw ToolException("HTTP ${resp.code}")
            val body = resp.body ?: throw ToolException("响应为空")
            val contentType = resp.header("Content-Type").orEmpty()
            val charset = charsetName(
                Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                    .find(contentType)?.groupValues?.getOrNull(1)?.trim()?.trim('"'),
            )
            return Jsoup.parse(ByteArrayInputStream(body.bytes()), charset, url.toString())
        }
    }

    private fun parseDuckHtml(doc: Document): List<SearchHit> =
        doc.select("div.result, div.web-result").mapNotNull { element ->
            val link = element.selectFirst("a.result__a") ?: return@mapNotNull null
            val href = decodeDuckUrl(link.absUrl("href").ifBlank { link.attr("href") })
            if (href.isBlank()) return@mapNotNull null
            SearchHit(
                title = link.text().trim().ifBlank { href },
                url = href,
                snippet = element.selectFirst(".result__snippet")?.text()?.trim().orEmpty(),
            )
        }

    private fun parseDuckLite(doc: Document): List<SearchHit> =
        doc.select("a.result-link").mapNotNull { link ->
            val href = decodeDuckUrl(link.absUrl("href").ifBlank { link.attr("href") })
            if (href.isBlank()) return@mapNotNull null
            val row = link.parents().firstOrNull { it.tagName() == "tr" }
            SearchHit(
                title = link.text().trim().ifBlank { href },
                url = href,
                snippet = row?.selectFirst(".result-snippet")?.text()?.trim().orEmpty(),
            )
        }

    private fun parseBing(doc: Document): List<SearchHit> =
        doc.select("li.b_algo").mapNotNull { element ->
            val link = element.selectFirst("h2 a") ?: return@mapNotNull null
            val href = decodeBingUrl(link.absUrl("href").ifBlank { link.attr("href") })
            if (href.isBlank()) return@mapNotNull null
            SearchHit(
                title = link.text().trim().ifBlank { href },
                url = href,
                snippet = element.selectFirst(".b_caption p")?.text()?.trim()
                    ?: element.selectFirst("p")?.text()?.trim().orEmpty(),
            )
        }

    private fun formatHits(hits: List<SearchHit>, maxResults: Int): String? {
        val unique = LinkedHashMap<String, SearchHit>()
        hits.forEach { hit ->
            if (hit.url.isNotBlank() && !unique.containsKey(hit.url)) unique[hit.url] = hit
        }
        if (unique.isEmpty()) return null
        return buildString {
            unique.values.take(maxResults).forEachIndexed { index, hit ->
                append(index + 1).append(". ").append(hit.title).append('\n')
                append(hit.url).append('\n')
                if (hit.snippet.isNotBlank()) append(hit.snippet.take(500)).append('\n')
                append('\n')
            }
        }.trim()
    }

    private fun decodeDuckUrl(href: String): String {
        if (!href.contains("uddg=")) return href
        val encoded = href.substringAfter("uddg=").substringBefore('&')
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
    }

    private fun decodeBingUrl(href: String): String {
        val url = href.toHttpUrlOrNull() ?: return href
        val raw = url.queryParameter("u") ?: return href
        if (!raw.startsWith("a1")) return href
        return runCatching {
            val encoded = raw.substring(2)
            val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrDefault(href)
    }

    private fun decodeText(bytes: ByteArray, charset: String?): String =
        runCatching { String(bytes, charset?.let(Charset::forName) ?: Charsets.UTF_8) }
            .getOrDefault(String(bytes, Charsets.UTF_8))

    private fun charsetName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Charset.forName(raw).name() }.getOrNull()
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
            .trimStart()
            .lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") ||
            head.startsWith("<head") || head.startsWith("<body") || head.startsWith("<?xml")
    }

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8).trimStart()
        return head.startsWith("{") || head.startsWith("[")
    }

    private fun looksBinary(bytes: ByteArray): Boolean =
        bytes.take(minOf(bytes.size, 1024)).any { it == 0.toByte() }

    private fun validateUrl(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: throw ToolException("无效的 URL：$raw")
        if (url.scheme != "http" && url.scheme != "https") throw ToolException("仅支持 http/https URL")
        return url
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val MAX_REDIRECTS = 20
        private const val MAX_BODY_BYTES = 10L * 1024 * 1024
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        private fun clip(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            return text.take(maxChars) + "\n\n…（正文已截断，共 ${text.length} 字符）"
        }
    }
}

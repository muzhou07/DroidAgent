package com.muzhou.droidagent.data

import com.muzhou.droidagent.agent.util.AgentJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
)

object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/muzhou07/droidagent/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 返回比当前版本更新的版本；没有新版本时返回 null。 */
    fun latest(currentVersion: String): UpdateInfo? {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DroidAgent/$currentVersion")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub API HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
        val root = AgentJson.parseObjectOrNull(body)
            ?: throw IllegalStateException("GitHub API 返回无法解析的响应")
        val tag = (root["tag_name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (tag.isBlank()) throw IllegalStateException("GitHub API 缺少版本号")
        if (!isNewer(tag, currentVersion)) return null
        return UpdateInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            title = (root["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: tag,
            notes = (root["body"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(12_000),
            pageUrl = (root["html_url"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.startsWith("https://") }
                ?: "https://github.com/muzhou07/DroidAgent/releases",
        )
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        if (a.isEmpty() || b.isEmpty()) return latest != current
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .mapNotNull { it.toIntOrNull() }
}

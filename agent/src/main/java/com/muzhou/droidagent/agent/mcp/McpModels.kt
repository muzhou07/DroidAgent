package com.muzhou.droidagent.agent.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val transport: String = "http",
)

@Serializable
data class McpAnnotations(
    @SerialName("readOnlyHint") val readOnlyHint: Boolean? = null,
    @SerialName("destructiveHint") val destructiveHint: Boolean? = null,
    @SerialName("idempotentHint") val idempotentHint: Boolean? = null,
    @SerialName("openWorldHint") val openWorldHint: Boolean? = null,
)

@Serializable
data class McpToolInfo(
    val name: String,
    val description: String? = null,
    @SerialName("inputSchema") val inputSchema: JsonObject? = null,
    val annotations: McpAnnotations? = null,
)

@Serializable
data class McpContent(
    val type: String = "text",
    val text: String? = null,
    val data: String? = null,
    @SerialName("mimeType") val mimeType: String? = null,
    val uri: String? = null,
    val resource: McpEmbeddedResource? = null,
)

@Serializable
data class McpEmbeddedResource(
    val uri: String? = null,
    @SerialName("mimeType") val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
)

@Serializable
data class McpToolResult(
    val content: List<McpContent> = emptyList(),
    @SerialName("isError") val isError: Boolean? = null,
    @SerialName("structuredContent") val structuredContent: JsonObject? = null,
)

data class McpServerState(
    val config: McpServerConfig,
    val connected: Boolean = false,
    val tools: List<McpToolInfo> = emptyList(),
    val error: String? = null,
)

class McpException(message: String) : Exception(message)

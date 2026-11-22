package com.muzhou.droidagent.agent.llm

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val reasoningEffort: String? = null,
    val temperature: Double? = 0.7,
    val maxTokens: Int? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val timeoutSeconds: Long = 300,
)

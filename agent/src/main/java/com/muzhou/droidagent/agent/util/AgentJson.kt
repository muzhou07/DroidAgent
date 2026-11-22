package com.muzhou.droidagent.agent.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object AgentJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    fun parseObjectOrNull(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    }

    fun parseElementOrNull(raw: String): JsonElement? = try {
        json.parseToJsonElement(raw)
    } catch (_: Exception) {
        null
    }

    fun string(value: String): JsonPrimitive = JsonPrimitive(value)

    fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())
}

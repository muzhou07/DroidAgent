package com.muzhou.droidagent.data

import com.muzhou.droidagent.agent.AgentSettings
import com.muzhou.droidagent.agent.PermissionMode
import com.muzhou.droidagent.agent.ReasoningEffort
import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.muzhou.droidagent.agent.util.AgentJson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH,
    val temperature: Double = 0.7,
    val maxTokens: Int = 0,
    val permissionMode: PermissionMode = PermissionMode.ASK,
    val contextWindow: Int = 64_000,
    val maxIterations: Int = 40,
    val systemExtra: String = "",
    val tavilyApiKey: String = "",
    val termuxEnabled: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    fun toAgentSettings(): AgentSettings = AgentSettings(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        reasoningEffort = reasoningEffort,
        temperature = temperature,
        maxTokens = maxTokens.takeIf { it > 0 },
        permissionMode = permissionMode,
        contextWindowTokens = contextWindow.coerceIn(4_000, 2_000_000),
        maxIterations = maxIterations.coerceIn(1, 200),
        systemPromptExtra = systemExtra,
        tavilyApiKey = tavilyApiKey,
        termuxEnabled = termuxEnabled,
        extraHeaders = extraHeaders,
    )
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("droidagent_settings")

class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope,
) {
    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val apiKey = stringPreferencesKey("api_key")
        val model = stringPreferencesKey("model")
        val reasoningEffort = stringPreferencesKey("reasoning_effort")
        val temperature = doublePreferencesKey("temperature")
        val maxTokens = intPreferencesKey("max_tokens")
        val permissionMode = stringPreferencesKey("permission_mode")
        val contextWindow = intPreferencesKey("context_window")
        val maxIterations = intPreferencesKey("max_iterations")
        val systemExtra = stringPreferencesKey("system_extra")
        val tavilyKey = stringPreferencesKey("tavily_key")
        val termux = booleanPreferencesKey("termux_enabled")
        val theme = stringPreferencesKey("theme")
        val extraHeaders = stringPreferencesKey("extra_headers")
        val lastWorkspaceId = stringPreferencesKey("last_workspace_id")
    }

    val settings: StateFlow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            baseUrl = prefs[Keys.baseUrl] ?: "https://api.deepseek.com/v1",
            apiKey = SecretCrypto.decrypt(prefs[Keys.apiKey].orEmpty()),
            model = prefs[Keys.model] ?: "deepseek-flash",
            reasoningEffort = runCatching {
                ReasoningEffort.valueOf(prefs[Keys.reasoningEffort] ?: ReasoningEffort.HIGH.name)
            }.getOrDefault(ReasoningEffort.HIGH),
            temperature = prefs[Keys.temperature] ?: 0.7,
            maxTokens = prefs[Keys.maxTokens] ?: 0,
            permissionMode = runCatching {
                PermissionMode.valueOf(prefs[Keys.permissionMode] ?: PermissionMode.ASK.name)
            }.getOrDefault(PermissionMode.ASK),
            contextWindow = prefs[Keys.contextWindow] ?: 64_000,
            maxIterations = prefs[Keys.maxIterations] ?: 40,
            systemExtra = prefs[Keys.systemExtra].orEmpty(),
            tavilyApiKey = SecretCrypto.decrypt(prefs[Keys.tavilyKey].orEmpty()),
            termuxEnabled = prefs[Keys.termux] ?: false,
            theme = runCatching {
                ThemeMode.valueOf(prefs[Keys.theme] ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM),
            extraHeaders = decodeHeaders(SecretCrypto.decrypt(prefs[Keys.extraHeaders].orEmpty())),
        )
    }.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.baseUrl] = settings.baseUrl.trim()
            storeSecret(prefs, Keys.apiKey, settings.apiKey.trim())
            prefs[Keys.model] = settings.model.trim()
            prefs[Keys.reasoningEffort] = settings.reasoningEffort.name
            prefs[Keys.temperature] = settings.temperature
            prefs[Keys.maxTokens] = settings.maxTokens
            prefs[Keys.permissionMode] = settings.permissionMode.name
            prefs[Keys.contextWindow] = settings.contextWindow
            prefs[Keys.maxIterations] = settings.maxIterations
            prefs[Keys.systemExtra] = settings.systemExtra
            storeSecret(prefs, Keys.tavilyKey, settings.tavilyApiKey.trim())
            prefs[Keys.termux] = settings.termuxEnabled
            prefs[Keys.theme] = settings.theme.name
            storeSecret(prefs, Keys.extraHeaders, encodeHeaders(settings.extraHeaders))
        }
    }

    /** 把旧版本遗留的明文/Base64 密钥迁移为 Keystore 密文。 */
    suspend fun migrateSecrets() {
        context.settingsDataStore.edit { prefs ->
            listOf(Keys.apiKey, Keys.tavilyKey, Keys.extraHeaders).forEach { key ->
                val stored = prefs[key].orEmpty()
                if (stored.isBlank() || stored.startsWith(SecretCrypto.VERSION)) return@forEach
                val plain = SecretCrypto.decrypt(stored)
                if (plain.isBlank()) return@forEach
                val encrypted = SecretCrypto.encrypt(plain)
                if (encrypted.isNotEmpty()) prefs[key] = encrypted
            }
        }
    }

    private fun storeSecret(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        plain: String,
    ) {
        if (plain.isEmpty()) {
            prefs[key] = ""
            return
        }
        val encrypted = SecretCrypto.encrypt(plain)
        // Keystore 不可用时保持原有密文，绝不回退为明文/Base64。
        if (encrypted.isNotEmpty()) prefs[key] = encrypted
    }

    suspend fun lastWorkspaceId(): String? =
        context.settingsDataStore.data.first()[Keys.lastWorkspaceId]

    suspend fun saveLastWorkspaceId(workspaceId: String) {
        context.settingsDataStore.edit { it[Keys.lastWorkspaceId] = workspaceId }
    }

    private fun encodeHeaders(headers: Map<String, String>): String =
        if (headers.isEmpty()) "" else
            AgentJson.json.encodeToString(MapSerializer(String.serializer(), String.serializer()), headers)

    private fun decodeHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            AgentJson.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
        }.getOrDefault(emptyMap())
    }
}

object SecretCrypto {
    private const val ALIAS = "droidagent_secret_key_v1"
    const val VERSION = "v1:"
    private const val BASE64_VERSION = "b64:"

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            VERSION + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (_: Exception) {
            // Fail closed: never fall back to plaintext/Base64 storage if Keystore is unavailable.
            ""
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            when {
                stored.startsWith(BASE64_VERSION) ->
                    String(Base64.decode(stored.removePrefix(BASE64_VERSION), Base64.NO_WRAP), Charsets.UTF_8)
                stored.startsWith(VERSION) -> {
                    val bytes = Base64.decode(stored.removePrefix(VERSION), Base64.NO_WRAP)
                    if (bytes.size < 13) return ""
                    val iv = bytes.copyOfRange(0, 12)
                    val payload = bytes.copyOfRange(12, bytes.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                    String(cipher.doFinal(payload), Charsets.UTF_8)
                }
                else -> stored
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

package com.muzhou.droidagent.data

import com.muzhou.droidagent.agent.llm.ChatMessage
import com.muzhou.droidagent.agent.llm.Usage
import com.muzhou.droidagent.agent.mcp.McpServerConfig
import com.muzhou.droidagent.agent.util.AgentJson
import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.agent.workspace.WorkspaceFactory
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.UUID

data class Session(
    val id: String,
    val title: String,
    val workspaceId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class StoredMessage(
    val id: String,
    val sessionId: String,
    val seq: Int,
    val message: ChatMessage,
    val createdAt: Long,
    val usage: Usage? = null,
    val elapsedMs: Long = 0L,
    val firstTokenMs: Long = 0L,
)

class SessionStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    private val lock = Any()
    private val appContext = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                workspace_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                role TEXT NOT NULL,
                raw TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                usage TEXT,
                elapsed_ms INTEGER NOT NULL DEFAULT 0,
                first_token_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id, seq)")
        db.execSQL("CREATE INDEX idx_sessions_updated ON sessions(updated_at DESC)")
        db.execSQL(
            """
            CREATE TABLE workspaces (
                id TEXT PRIMARY KEY NOT NULL,
                raw TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE mcp_servers (
                id TEXT PRIMARY KEY NOT NULL,
                raw TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN usage TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN elapsed_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN first_token_ms INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_updated ON sessions(updated_at DESC)")
        }
    }

    fun ensureDefaultWorkspace(): Workspace = synchronized(lock) {
        val existing = findWorkspaceLocked("default")
        if (existing != null) return existing
        val fallback = WorkspaceFactory.defaultWorkspace(appContext)
        writableDatabase.insertWithOnConflict(
            "workspaces",
            null,
            ContentValues().apply {
                put("id", fallback.id)
                put("raw", AgentJson.json.encodeToString(Workspace.serializer(), fallback))
                put("created_at", fallback.createdAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        fallback
    }

    fun listSessions(): List<Session> = synchronized(lock) {
        val result = ArrayList<Session>()
        readableDatabase.query(
            "sessions",
            arrayOf("id", "title", "workspace_id", "created_at", "updated_at"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Session(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        workspaceId = cursor.getString(2),
                        createdAt = cursor.getLong(3),
                        updatedAt = cursor.getLong(4),
                    ),
                )
            }
        }
        result
    }

    fun deleteEmptyDrafts() = synchronized(lock) {
        writableDatabase.execSQL(
            "DELETE FROM sessions WHERE title = ? AND NOT EXISTS (" +
                "SELECT 1 FROM messages WHERE messages.session_id = sessions.id)",
            arrayOf("新对话"),
        )
        Unit
    }

    fun searchSessionIds(query: String): Set<String> = synchronized(lock) {
        if (query.isBlank()) return emptySet()
        val ids = LinkedHashSet<String>()
        readableDatabase.rawQuery(
            "SELECT id FROM sessions WHERE instr(lower(title), lower(?)) > 0 " +
                "UNION SELECT DISTINCT session_id FROM messages WHERE " +
                "instr(lower(COALESCE(json_extract(raw, '$.content'), '')), lower(?)) > 0 " +
                "OR instr(lower(COALESCE(json_extract(raw, '$.reasoning_content'), '')), lower(?)) > 0",
            arrayOf(query, query, query),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0))
            }
        }
        ids
    }

    fun getSession(id: String): Session? = synchronized(lock) {
        readableDatabase.query(
            "sessions",
            arrayOf("id", "title", "workspace_id", "created_at", "updated_at"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Session(
                id = cursor.getString(0),
                title = cursor.getString(1),
                workspaceId = cursor.getString(2),
                createdAt = cursor.getLong(3),
                updatedAt = cursor.getLong(4),
            )
        }
    }

    fun createSession(title: String, workspaceId: String): Session = synchronized(lock) {
        val now = System.currentTimeMillis()
        val session = Session(
            id = "ses_" + UUID.randomUUID().toString().take(10),
            title = title,
            workspaceId = workspaceId,
            createdAt = now,
            updatedAt = now,
        )
        writableDatabase.insertOrThrow(
            "sessions",
            null,
            ContentValues().apply {
                put("id", session.id)
                put("title", session.title)
                put("workspace_id", session.workspaceId)
                put("created_at", session.createdAt)
                put("updated_at", session.updatedAt)
            },
        )
        session
    }

    fun updateSessionTitle(sessionId: String, title: String) = synchronized(lock) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("title", title)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(sessionId),
        )
        Unit
    }

    fun updateSessionWorkspace(sessionId: String, workspaceId: String) = synchronized(lock) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("workspace_id", workspaceId)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(sessionId),
        )
        Unit
    }

    fun touchSession(sessionId: String) = synchronized(lock) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(sessionId),
        )
        Unit
    }

    fun deleteSession(sessionId: String) = synchronized(lock) {
        writableDatabase.delete("messages", "session_id = ?", arrayOf(sessionId))
        writableDatabase.delete("sessions", "id = ?", arrayOf(sessionId))
        Unit
    }

    fun listMessages(sessionId: String): List<StoredMessage> = synchronized(lock) {
        val result = ArrayList<StoredMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "seq", "raw", "created_at", "usage", "elapsed_ms", "first_token_ms"),
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            "seq ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val raw = cursor.getString(2)
                val message = runCatching {
                    AgentJson.json.decodeFromString(ChatMessage.serializer(), raw)
                }.getOrNull() ?: continue
                val usage = cursor.getString(4)?.let { usageRaw ->
                    runCatching {
                        AgentJson.json.decodeFromString(Usage.serializer(), usageRaw)
                    }.getOrNull()
                }
                result.add(
                    StoredMessage(
                        id = cursor.getString(0),
                        sessionId = sessionId,
                        seq = cursor.getInt(1),
                        message = message,
                        createdAt = cursor.getLong(3),
                        usage = usage,
                        elapsedMs = cursor.getLong(5),
                        firstTokenMs = cursor.getLong(6),
                    ),
                )
            }
        }
        result
    }

    fun appendMessage(
        sessionId: String,
        message: ChatMessage,
        id: String = "msg_" + UUID.randomUUID().toString().take(10),
        createdAt: Long = System.currentTimeMillis(),
        usage: Usage? = null,
        elapsedMs: Long = 0L,
        firstTokenMs: Long = 0L,
    ): StoredMessage = synchronized(lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val seq = db.rawQuery(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE session_id = ?",
                arrayOf(sessionId),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 1
            }
            db.insertOrThrow(
                "messages",
                null,
                ContentValues().apply {
                    put("id", id)
                    put("session_id", sessionId)
                    put("seq", seq)
                    put("role", message.role)
                    put("raw", AgentJson.json.encodeToString(ChatMessage.serializer(), message))
                    put("created_at", createdAt)
                    put(
                        "usage",
                        usage?.let { AgentJson.json.encodeToString(Usage.serializer(), it) },
                    )
                    put("elapsed_ms", elapsedMs)
                    put("first_token_ms", firstTokenMs)
                },
            )
            db.update(
                "sessions",
                ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
                "id = ?",
                arrayOf(sessionId),
            )
            db.setTransactionSuccessful()
            StoredMessage(id, sessionId, seq, message, createdAt)
        } finally {
            db.endTransaction()
        }
    }

    fun appendNote(
        sessionId: String,
        text: String,
        error: Boolean = true,
    ): StoredMessage = appendMessage(
        sessionId = sessionId,
        message = ChatMessage.note(text.take(8_000), error),
    )

    fun lastUserSeq(sessionId: String): Int? = synchronized(lock) {
        readableDatabase.rawQuery(
            "SELECT MAX(seq) FROM messages WHERE session_id = ? AND role = 'user'",
            arrayOf(sessionId),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
        }
    }

    fun deleteMessagesAfterSeq(sessionId: String, seq: Int) = synchronized(lock) {
        writableDatabase.delete("messages", "session_id = ? AND seq > ?", arrayOf(sessionId, seq.toString()))
        Unit
    }

    fun deleteMessagesFromSeq(sessionId: String, seq: Int) = synchronized(lock) {
        writableDatabase.delete("messages", "session_id = ? AND seq >= ?", arrayOf(sessionId, seq.toString()))
        Unit
    }

    fun messageCount(sessionId: String): Int = synchronized(lock) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE session_id = ?",
            arrayOf(sessionId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun listWorkspaces(): List<Workspace> = synchronized(lock) {
        val result = ArrayList<Workspace>()
        readableDatabase.query("workspaces", arrayOf("raw"), null, null, null, null, "created_at ASC").use { cursor ->
            while (cursor.moveToNext()) {
                runCatching {
                    AgentJson.json.decodeFromString(Workspace.serializer(), cursor.getString(0))
                }.getOrNull()?.let { result.add(withLocalPath(it)) }
            }
        }
        result
    }

    fun findWorkspace(id: String): Workspace? = synchronized(lock) { findWorkspaceLocked(id) }

    private fun findWorkspaceLocked(id: String): Workspace? {
        readableDatabase.query(
            "workspaces",
            arrayOf("raw"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return runCatching {
                AgentJson.json.decodeFromString(Workspace.serializer(), cursor.getString(0))
            }.getOrNull()?.let(::withLocalPath)
        }
    }

    private fun withLocalPath(workspace: Workspace): Workspace {
        if (workspace.kind != com.muzhou.droidagent.agent.workspace.WorkspaceKind.LOCAL) return workspace
        val dir = File(File(appContext.filesDir, "workspaces"), workspace.id)
        return if (workspace.path == dir.absolutePath) workspace else workspace.copy(path = dir.absolutePath)
    }

    fun saveWorkspace(workspace: Workspace) = synchronized(lock) {
        writableDatabase.insertWithOnConflict(
            "workspaces",
            null,
            ContentValues().apply {
                put("id", workspace.id)
                put("raw", AgentJson.json.encodeToString(Workspace.serializer(), workspace))
                put("created_at", workspace.createdAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    fun deleteWorkspace(id: String) = synchronized(lock) {
        if (id == "default") return@synchronized
        writableDatabase.delete("workspaces", "id = ?", arrayOf(id))
        writableDatabase.update(
            "sessions",
            ContentValues().apply { put("workspace_id", "default") },
            "workspace_id = ?",
            arrayOf(id),
        )
        Unit
    }

    fun listMcpServers(): List<McpServerConfig> = synchronized(lock) {
        val result = ArrayList<McpServerConfig>()
        readableDatabase.query("mcp_servers", arrayOf("raw"), null, null, null, null, "updated_at ASC").use { cursor ->
            while (cursor.moveToNext()) {
                runCatching {
                    AgentJson.json.decodeFromString(McpServerConfig.serializer(), cursor.getString(0))
                }.getOrNull()?.let { config ->
                    result.add(config.copy(headers = decryptHeaders(config.headers)))
                }
            }
        }
        result
    }

    fun saveMcpServer(server: McpServerConfig) = synchronized(lock) {
        val stored = server.copy(headers = encryptHeaders(server.headers))
        writableDatabase.insertWithOnConflict(
            "mcp_servers",
            null,
            ContentValues().apply {
                put("id", stored.id)
                put("raw", AgentJson.json.encodeToString(McpServerConfig.serializer(), stored))
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    fun deleteMcpServer(id: String) = synchronized(lock) {
        writableDatabase.delete("mcp_servers", "id = ?", arrayOf(id))
        Unit
    }

    /** MCP 请求头常含 Bearer Token，落库前用 Keystore 加密；加密失败时丢弃而不是回退明文。 */
    private fun encryptHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (_, value) ->
            if (value.isEmpty()) value else SecretCrypto.encrypt(value)
        }

    private fun decryptHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (_, value) ->
            if (value.isEmpty()) value else SecretCrypto.decrypt(value)
        }

    /** 仅重写仍为明文的 MCP 请求头，避免每次启动重复加密。 */
    fun migrateMcpSecrets() = synchronized(lock) {
        val db = writableDatabase
        val pending = ArrayList<Pair<String, String>>()
        db.query("mcp_servers", arrayOf("id", "raw"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val config = runCatching {
                    AgentJson.json.decodeFromString(McpServerConfig.serializer(), cursor.getString(1))
                }.getOrNull() ?: continue
                val hasPlaintext = config.headers.values.any {
                    it.isNotEmpty() && !it.startsWith(SecretCrypto.VERSION)
                }
                if (!hasPlaintext) continue
                pending.add(
                    cursor.getString(0) to
                        AgentJson.json.encodeToString(
                            McpServerConfig.serializer(),
                            config.copy(headers = encryptHeaders(config.headers)),
                        ),
                )
            }
        }
        if (pending.isEmpty()) return@synchronized
        db.beginTransaction()
        try {
            pending.forEach { (id, raw) ->
                db.update(
                    "mcp_servers",
                    ContentValues().apply { put("raw", raw) },
                    "id = ?",
                    arrayOf(id),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val DB_NAME = "droidagent.db"
        private const val DB_VERSION = 3
    }
}

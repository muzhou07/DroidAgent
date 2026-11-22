package com.muzhou.droidagent.agent.workspace

import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceKind { LOCAL, EXTERNAL, SAF }

@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val kind: WorkspaceKind = WorkspaceKind.LOCAL,
    val path: String? = null,
    val uri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class FsEntry(
    val relPath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
)

class VfsException(message: String) : Exception(message)

interface VirtualFileSystem {
    val displayRoot: String
    val writable: Boolean

    suspend fun list(rel: String): List<FsEntry>
    suspend fun readText(rel: String, maxBytes: Int = DEFAULT_MAX_READ): String
    suspend fun writeText(rel: String, content: String)
    suspend fun exists(rel: String): Boolean
    suspend fun isDirectory(rel: String): Boolean
    suspend fun delete(rel: String, recursive: Boolean = true): Boolean
    suspend fun walk(
        rel: String = ".",
        maxDepth: Int = 16,
        maxEntries: Int = 8000,
    ): List<FsEntry>

    companion object {
        const val DEFAULT_MAX_READ = 8 * 1024 * 1024
    }
}

package com.muzhou.droidagent.agent.workspace

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SafFileSystem(
    private val context: Context,
    treeUri: Uri,
    override val writable: Boolean = true,
) : VirtualFileSystem {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw VfsException("无法打开 SAF 目录")
    private val rootUri: Uri = treeUri
    override val displayRoot: String = buildString {
        append(root.name ?: rootUri.authority ?: "SAF")
        append(" (")
        append(rootUri.authority ?: "saf")
        append(')')
    }

    override suspend fun list(rel: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val parent = normalizeRelative(rel)
        val dir = resolve(parent) ?: throw VfsException("目录不存在：$rel")
        if (!dir.isDirectory) throw VfsException("不是目录：$rel")
        dir.listFiles()
            .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { (it.name ?: "").lowercase() })
            .map { doc ->
                FsEntry(
                    relPath = childRelative(parent, doc.name ?: "(未命名)"),
                    name = doc.name ?: "(未命名)",
                    isDirectory = doc.isDirectory,
                    size = doc.length(),
                    lastModified = doc.lastModified(),
                )
            }
    }

    override suspend fun readText(rel: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val doc = resolve(normalizeRelative(rel)) ?: throw VfsException("文件不存在：$rel")
        if (doc.isDirectory) throw VfsException("这是目录，不是文件：$rel")
        if (doc.length() > maxBytes) {
            throw VfsException("文件过大（${doc.length()} 字节），请分段读取或使用 grep：$rel")
        }
        val input = context.contentResolver.openInputStream(doc.uri)
            ?: throw VfsException("无法读取文件：$rel")
        input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override suspend fun writeText(rel: String, content: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeRelative(rel)
        if (normalized == ".") throw VfsException("不能写入工作区根目录")
        val parentPath = normalized.substringBeforeLast('/', ".")
        val name = normalized.substringAfterLast('/')
        val parent = resolveDirectory(parentPath, create = true)
            ?: throw VfsException("无法创建目录：$parentPath")
        var doc = parent.findFile(name)
        if (doc != null && doc.isDirectory) throw VfsException("同名目录已存在：$rel")
        if (doc == null) {
            doc = parent.createFile(mimeOf(name), name)
                ?: throw VfsException("无法创建文件：$rel")
        }
        val output = context.contentResolver.openOutputStream(doc.uri, "wt")
            ?: throw VfsException("无法写入文件：$rel")
        output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun exists(rel: String): Boolean = withContext(Dispatchers.IO) {
        resolve(normalizeRelative(rel)) != null
    }

    override suspend fun isDirectory(rel: String): Boolean = withContext(Dispatchers.IO) {
        resolve(normalizeRelative(rel))?.isDirectory == true
    }

    override suspend fun delete(rel: String, recursive: Boolean): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeRelative(rel)
        if (normalized == ".") throw VfsException("不能删除工作区根目录")
        val doc = resolve(normalized) ?: return@withContext false
        if (doc.isDirectory && !recursive && doc.listFiles().isNotEmpty()) {
            throw VfsException("目录非空，无法删除：$rel")
        }
        if (doc.isDirectory && recursive) {
            doc.listFiles().forEach { deleteDocumentRecursive(it) }
        }
        doc.delete()
    }

    override suspend fun walk(rel: String, maxDepth: Int, maxEntries: Int): List<FsEntry> =
        withContext(Dispatchers.IO) {
            val startRel = normalizeRelative(rel)
            val start = resolve(startRel) ?: throw VfsException("路径不存在：$rel")
            val results = ArrayList<FsEntry>()
            val queue = ArrayDeque<Pair<DocumentFile, Int>>()
            if (start.isDirectory) queue.add(start to 0) else {
                results.add(FsEntry(startRel, start.name ?: startRel, false, start.length(), start.lastModified()))
            }
            while (queue.isNotEmpty() && results.size < maxEntries) {
                val (dir, depth) = queue.removeFirst()
                if (depth >= maxDepth) continue
                val parentRel = relPathOf(dir)
                dir.listFiles().sortedBy { (it.name ?: "").lowercase() }.forEach { child ->
                    if (results.size >= maxEntries) return@forEach
                    val name = child.name ?: return@forEach
                    val childRel = childRelative(parentRel, name)
                    results.add(FsEntry(childRel, name, child.isDirectory, child.length(), child.lastModified()))
                    if (child.isDirectory) queue.add(child to depth + 1)
                }
            }
            results
        }

    private fun deleteDocumentRecursive(doc: DocumentFile) {
        if (doc.isDirectory) doc.listFiles().forEach { deleteDocumentRecursive(it) }
        doc.delete()
    }

    private fun resolve(rel: String): DocumentFile? {
        if (rel == ".") return root
        var current: DocumentFile = root
        for (segment in rel.split('/')) {
            if (segment.isEmpty()) continue
            val next = current.findFile(segment) ?: return null
            current = next
        }
        return current
    }

    private fun resolveDirectory(rel: String, create: Boolean): DocumentFile? {
        if (rel == ".") return root
        var current: DocumentFile = root
        for (segment in rel.split('/')) {
            if (segment.isEmpty()) continue
            var next = current.findFile(segment)
            if (next == null) {
                if (!create) return null
                next = current.createDirectory(segment) ?: return null
            }
            if (!next.isDirectory) return null
            current = next
        }
        return current
    }

    private fun relPathOf(doc: DocumentFile): String {
        val segments = ArrayList<String>()
        var current: DocumentFile? = doc
        while (current != null && current.uri != root.uri) {
            segments.add(current.name ?: "")
            val parentName = current.parentFile?.name ?: break
            val parent = current.parentFile
            if (parent == null) break
            if (parent.uri == root.uri) break
            current = parent
            if (segments.size > 64) break
        }
        segments.reverse()
        return segments.filter { it.isNotEmpty() }.joinToString("/").ifEmpty { "." }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "text/plain"
    }
}

object WorkspaceFactory {
    private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"

    fun create(context: Context, workspace: Workspace): VirtualFileSystem = when (workspace.kind) {
        WorkspaceKind.LOCAL, WorkspaceKind.EXTERNAL -> {
            val path = workspace.path ?: throw VfsException("工作区路径为空")
            LocalFileSystem(File(path))
        }
        WorkspaceKind.SAF -> {
            val uri = workspace.uri?.let(Uri::parse) ?: throw VfsException("工作区 URI 为空")
            SafFileSystem(context, uri)
        }
    }

    fun realPath(workspace: Workspace): File? = when (workspace.kind) {
        WorkspaceKind.LOCAL, WorkspaceKind.EXTERNAL -> workspace.path?.let(::File)
        WorkspaceKind.SAF -> workspace.uri?.let(Uri::parse)?.let { safUriToFile(it) }
    }

    fun safUriToFile(uri: Uri): File? {
        if (uri.authority != EXTERNAL_AUTHORITY) return null
        if (!DocumentsContract.isTreeUri(uri)) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val type = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "")
        val base = when {
            type.equals("primary", ignoreCase = true) ->
                android.os.Environment.getExternalStorageDirectory()
            type.equals("home", ignoreCase = true) ->
                File(android.os.Environment.getExternalStorageDirectory(), "Documents")
            type.matches(Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")) -> File("/storage/$type")
            else -> return null
        }
        return if (relative.isBlank()) base else File(base, relative)
    }

    fun defaultWorkspace(context: Context): Workspace {
        val dir = File(context.filesDir, "workspaces/default")
        dir.mkdirs()
        return Workspace(
            id = "default",
            name = "默认工作区",
            kind = WorkspaceKind.LOCAL,
            path = dir.absolutePath,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun createLocalWorkspace(context: Context, name: String): Workspace {
        val id = "ws_" + java.util.UUID.randomUUID().toString().take(8)
        val dir = File(File(context.filesDir, "workspaces"), id)
        if (!dir.mkdirs() && !dir.isDirectory) throw VfsException("无法创建本地工作区目录")
        return Workspace(
            id = id,
            name = name.ifBlank { "本地工作区" },
            kind = WorkspaceKind.LOCAL,
            path = dir.absolutePath,
        )
    }

    fun createExternalWorkspace(name: String): Workspace {
        val safeName = name.ifBlank { "workspace" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifEmpty { "workspace" }
        val base = File(android.os.Environment.getExternalStorageDirectory(), "DroidAgent")
        val dir = File(base, safeName)
        if (!dir.exists() && !dir.mkdirs()) {
            throw VfsException("无法创建共享存储目录，请先授予“所有文件访问权限”")
        }
        val id = "sh_" + java.util.UUID.randomUUID().toString().take(8)
        return Workspace(
            id = id,
            name = safeName,
            kind = WorkspaceKind.EXTERNAL,
            path = dir.absolutePath,
        )
    }

    fun locationLabel(workspace: Workspace): String = when (workspace.kind) {
        WorkspaceKind.LOCAL -> "应用私有 · ${workspace.path.orEmpty()}"
        WorkspaceKind.EXTERNAL -> "共享存储 · ${workspace.path.orEmpty()}"
        WorkspaceKind.SAF -> "SAF · ${workspace.uri.orEmpty()}"
    }
}

package com.muzhou.droidagent.agent.workspace

import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun normalizeRelative(path: String?): String {
    var value = (path ?: ".").replace('\\', '/').trim()
    if (value.isEmpty()) return "."
    while (value.startsWith("./")) value = value.substring(2)
    if (value == "/" || value == ".") return "."
    val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.any { it == ".." }) throw VfsException("路径不能包含 '..'：$path")
    return parts.joinToString("/").ifEmpty { "." }
}

fun childRelative(parent: String, name: String): String =
    if (parent == "." || parent.isEmpty()) name else "$parent/$name"

fun absoluteFile(root: File, rel: String): File {
    val normalized = normalizeRelative(rel)
    val file = if (normalized == ".") root else File(root, normalized)
    val rootPath = root.canonicalFile.path
    val target = file.canonicalFile
    if (target.path != rootPath && !target.path.startsWith(rootPath + File.separator)) {
        throw VfsException("路径越界：$rel")
    }
    return target
}

class LocalFileSystem(
    private val root: File,
    override val writable: Boolean = true,
) : VirtualFileSystem {
    override val displayRoot: String = root.absolutePath

    override suspend fun list(rel: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dir = absoluteFile(root, rel)
        if (!dir.exists()) throw VfsException("目录不存在：$rel")
        if (!dir.isDirectory) throw VfsException("不是目录：$rel")
        val parent = normalizeRelative(rel)
        return@withContext dir.listFiles().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { file ->
                FsEntry(
                    relPath = childRelative(parent, file.name),
                    name = file.name,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    lastModified = file.lastModified(),
                )
            }
    }

    override suspend fun readText(rel: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val file = absoluteFile(root, rel)
        if (!file.exists()) throw VfsException("文件不存在：$rel")
        if (file.isDirectory) throw VfsException("这是目录，不是文件：$rel")
        if (file.length() > maxBytes) {
            throw VfsException("文件过大（${file.length()} 字节），请分段读取或使用 grep：$rel")
        }
        return@withContext file.readBytes().toString(StandardCharsets.UTF_8)
    }

    override suspend fun writeText(rel: String, content: String) = withContext(Dispatchers.IO) {
        val file = absoluteFile(root, rel)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
    }

    override suspend fun exists(rel: String): Boolean = withContext(Dispatchers.IO) {
        absoluteFile(root, rel).exists()
    }

    override suspend fun isDirectory(rel: String): Boolean = withContext(Dispatchers.IO) {
        absoluteFile(root, rel).isDirectory
    }

    override suspend fun delete(rel: String, recursive: Boolean): Boolean = withContext(Dispatchers.IO) {
        val file = absoluteFile(root, rel)
        if (file == root.canonicalFile) throw VfsException("不能删除工作区根目录")
        if (!recursive && file.isDirectory && file.list()?.isNotEmpty() == true) {
            throw VfsException("目录非空，无法删除：$rel")
        }
        deleteNoFollow(file)
    }

    /** 递归删除时不跟随符号链接，避免通过工作区内的 symlink 删除外部文件。 */
    private fun deleteNoFollow(file: File): Boolean {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return runCatching { java.nio.file.Files.deleteIfExists(file.toPath()) }.getOrDefault(false)
        }
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach { child -> deleteNoFollow(child) }
        }
        return file.delete()
    }

    override suspend fun walk(rel: String, maxDepth: Int, maxEntries: Int): List<FsEntry> =
        withContext(Dispatchers.IO) {
            val start = absoluteFile(root, rel)
            if (!start.exists()) throw VfsException("路径不存在：$rel")
            val results = ArrayList<FsEntry>()
            val queue = ArrayDeque<Pair<File, Int>>()
            val base = normalizeRelative(rel)
            if (start.isDirectory) queue.add(start to 0) else results.add(entryOf(base, start))
            while (queue.isNotEmpty() && results.size < maxEntries) {
                val (dir, depth) = queue.removeFirst()
                if (depth >= maxDepth) continue
                val parentRel = relPathOf(root, dir)
                dir.listFiles().orEmpty().sortedBy { it.name.lowercase() }.forEach { child ->
                    if (results.size >= maxEntries) return@forEach
                    val childRel = childRelative(parentRel, child.name)
                    results.add(entryOf(childRel, child))
                    if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                        queue.add(child to depth + 1)
                    }
                }
            }
            results
        }

    private fun entryOf(relPath: String, file: File) = FsEntry(
        relPath = relPath,
        name = file.name,
        isDirectory = file.isDirectory,
        size = if (file.isFile) file.length() else 0L,
        lastModified = file.lastModified(),
    )

    private fun relPathOf(root: File, file: File): String {
        val relative = file.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
        return relative.replace(File.separatorChar, '/').ifEmpty { "." }
    }
}

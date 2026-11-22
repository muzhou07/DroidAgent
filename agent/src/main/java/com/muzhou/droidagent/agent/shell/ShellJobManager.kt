package com.muzhou.droidagent.agent.shell

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class ShellJobInfo(
    val id: String,
    val ownerId: String,
    val command: String,
    val backend: String,
    val startedAt: Long,
    val running: Boolean,
    val killed: Boolean,
    val exitCode: Int?,
    val finishedAt: Long?,
    val outputBytes: Long,
    val error: String?,
)

data class ShellJobOutput(
    val id: String,
    val running: Boolean,
    val killed: Boolean,
    val exitCode: Int?,
    val text: String,
    val error: String?,
)

data class ShellJobEvent(
    val ownerId: String,
    val id: String,
    val command: String,
    val exitCode: Int?,
    val killed: Boolean,
)

/**
 * 后台 Shell 任务管理。命令输出写入缓存日志，模型通过 job_output 增量读取，
 * 通过 job_list 查看状态，通过 job_kill 终止进程树。
 */
class ShellJobManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    private class JobEntry(
        val id: String,
        val ownerId: String,
        val command: String,
        val backend: String,
        val startedAt: Long,
        val logFile: File,
        val rcFile: File,
        val pidFile: File,
    ) {
        var running: Boolean = true
        var killed: Boolean = false
        var exitCode: Int? = null
        var finishedAt: Long? = null
        var readOffset: Long = 0L
        var error: String? = null
        var coroutine: kotlinx.coroutines.Job? = null
    }

    private val dir = File(context.cacheDir, "shell-jobs").apply { mkdirs() }
    private val lock = Any()
    private val jobs = LinkedHashMap<String, JobEntry>()

    private val _events = MutableSharedFlow<ShellJobEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ShellJobEvent> = _events

    fun start(ownerId: String, command: String, shell: ShellExecutor): ShellJobInfo {
        val id = "job_" + UUID.randomUUID().toString().take(8)
        val job = JobEntry(
            id = id,
            ownerId = ownerId,
            command = command,
            backend = shell.backendName,
            startedAt = System.currentTimeMillis(),
            logFile = File(dir, "$id.log"),
            rcFile = File(dir, "$id.rc"),
            pidFile = File(dir, "$id.pid"),
        )
        runCatching { job.logFile.delete() }
        runCatching { job.rcFile.delete() }
        runCatching { job.pidFile.delete() }
        val pidPath = quote(job.pidFile.absolutePath)
        val logPath = quote(job.logFile.absolutePath)
        val rcPath = quote(job.rcFile.absolutePath)
        val script = buildString {
            append("echo \$\$ > ").append(pidPath).append('\n')
            append("{ ").append(command).append('\n')
            append("} > ").append(logPath).append(" 2>&1\n")
            append("echo \$? > ").append(rcPath)
        }
        synchronized(lock) {
            jobs[id] = job
            trimLocked(ownerId)
        }
        job.coroutine = scope.launch {
            val result = runCatching { shell.run(script, timeoutMs = 0) }
            val exit = job.rcFile.takeIf { it.exists() }
                ?.let { runCatching { it.readText().trim().toIntOrNull() }.getOrNull() }
            synchronized(lock) {
                if (!job.killed) {
                    job.running = false
                    job.exitCode = exit ?: result.getOrNull()?.exitCode
                    job.finishedAt = System.currentTimeMillis()
                    job.error = result.exceptionOrNull()?.message
                }
            }
            trimLog(job)
            _events.tryEmit(
                ShellJobEvent(
                    ownerId = ownerId,
                    id = id,
                    command = command,
                    exitCode = job.exitCode,
                    killed = job.killed,
                ),
            )
        }
        return info(job)
    }

    fun list(ownerId: String): List<ShellJobInfo> = synchronized(lock) {
        jobs.values.filter { it.ownerId == ownerId }.map(::info).asReversed()
    }

    suspend fun output(
        ownerId: String,
        jobId: String,
        wait: Boolean,
        waitMs: Long = 30_000,
    ): ShellJobOutput? {
        val deadline = System.currentTimeMillis() + waitMs.coerceIn(0, 60_000)
        while (true) {
            val job = synchronized(lock) {
                jobs[jobId]?.takeIf { it.ownerId == ownerId }
            } ?: return null
            val text = readNew(job)
            val snapshot = synchronized(lock) { outputLocked(job, text) }
            if (text.isNotEmpty() || !job.running || !wait || System.currentTimeMillis() >= deadline) {
                return snapshot
            }
            delay(250)
        }
    }

    fun kill(ownerId: String, jobId: String): String? {
        val job = synchronized(lock) {
            jobs[jobId]?.takeIf { it.ownerId == ownerId }
        } ?: return null
        val pid = runCatching { job.pidFile.readText().trim().toLongOrNull() }.getOrNull()
        if (pid != null && pid > 0) {
            killProcessTree(pid)
        }
        job.coroutine?.cancel()
        synchronized(lock) {
            if (job.running) {
                job.killed = true
                job.running = false
                job.finishedAt = System.currentTimeMillis()
                appendLog(job, "\n[已请求终止后台任务]")
            }
        }
        return if (job.backend == "Termux Shell") {
            "已请求终止 ${job.id}。Termux 后端可能仍在后台运行。"
        } else {
            "已终止 ${job.id}。"
        }
    }

    fun killOwner(ownerId: String) {
        val ids = synchronized(lock) {
            jobs.values.filter { it.ownerId == ownerId && it.running }.map { it.id }
        }
        ids.forEach { kill(ownerId, it) }
    }

    private fun info(job: JobEntry): ShellJobInfo = synchronized(lock) {
        ShellJobInfo(
            id = job.id,
            ownerId = job.ownerId,
            command = job.command,
            backend = job.backend,
            startedAt = job.startedAt,
            running = job.running,
            killed = job.killed,
            exitCode = job.exitCode,
            finishedAt = job.finishedAt,
            outputBytes = runCatching { job.logFile.length() }.getOrDefault(0L),
            error = job.error,
        )
    }

    private fun outputLocked(job: JobEntry, text: String): ShellJobOutput = ShellJobOutput(
        id = job.id,
        running = job.running,
        killed = job.killed,
        exitCode = job.exitCode,
        text = text,
        error = job.error,
    )

    private fun readNew(job: JobEntry): String = synchronized(lock) {
        val file = job.logFile
        if (!file.exists()) return ""
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (job.readOffset > length) job.readOffset = 0
            if (job.readOffset >= length) return ""
            raf.seek(job.readOffset)
            val available = (length - job.readOffset).coerceAtMost(64L * 1024L).toInt()
            val bytes = ByteArray(available)
            val read = raf.read(bytes)
            if (read <= 0) return ""
            job.readOffset += read
            String(bytes, 0, read, Charsets.UTF_8)
        }
    }

    private fun trimLocked(ownerId: String) {
        val owned = jobs.values.filter { it.ownerId == ownerId }
        if (owned.size <= 20) return
        owned.filter { !it.running }
            .take(owned.size - 20)
            .forEach { old ->
                jobs.remove(old.id)
                runCatching { old.logFile.delete() }
                runCatching { old.rcFile.delete() }
                runCatching { old.pidFile.delete() }
            }
    }

    private fun trimLog(job: JobEntry) {
        val maxBytes = 512L * 1024L
        val length = runCatching { job.logFile.length() }.getOrDefault(0L)
        if (length <= maxBytes) return
        val start = length - maxBytes
        val tail = runCatching {
            RandomAccessFile(job.logFile, "r").use { raf ->
                raf.seek(start)
                ByteArray(maxBytes.toInt()).also { raf.readFully(it) }
            }
        }.getOrNull() ?: return
        runCatching { job.logFile.writeBytes(tail) }
        synchronized(lock) { job.readOffset = 0 }
    }

    private fun appendLog(job: JobEntry, text: String) {
        runCatching { job.logFile.appendText(text) }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

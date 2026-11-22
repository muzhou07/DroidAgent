package com.muzhou.droidagent.agent.shell

import android.content.Context
import android.content.Intent
import android.os.Environment
import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.agent.workspace.WorkspaceFactory
import com.muzhou.droidagent.agent.workspace.WorkspaceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val backend: String = "shell",
    val durationMs: Long = 0L,
) {
    fun combined(): String = buildString {
        if (stdout.isNotBlank()) append(stdout.trimEnd())
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("[stderr]\n").append(stderr.trimEnd())
        }
        if (isEmpty()) append("(命令执行成功，无输出)")
    }
}

interface ShellExecutor {
    val available: Boolean
    val backendName: String
    suspend fun run(command: String, timeoutMs: Long = 120_000): ShellResult
}

class LocalShellExecutor(
    private val root: File,
    private val tempDir: File? = null,
) : ShellExecutor {
    override val available: Boolean
        get() = root.isDirectory && root.exists()
    override val backendName: String = "内置 Shell"

    override suspend fun run(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (!available) {
            return@withContext ShellResult(1, "", "工作区不可访问：${root.absolutePath}", backend = backendName)
        }
        val maxCapture = 400_000
        val pidFile = File(tempDir ?: root, ".droidagent_pid_${UUID.randomUUID().toString().take(8)}")
        runCatching { pidFile.delete() }
        val wrapped = "echo \$\$ > ${shellQuote(pidFile.absolutePath)}; $command"
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", wrapped)
                .directory(root)
                .redirectErrorStream(false)
                .also { prepared ->
                    val env = prepared.environment()
                    env["HOME"] = root.absolutePath
                    tempDir?.let {
                        it.mkdirs()
                        env["TMPDIR"] = it.absolutePath
                    }
                    env["TERM"] = "dumb"
                    env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/product/bin:${env["PATH"].orEmpty()}"
                }
                .start()
        } catch (e: Exception) {
            runCatching { pidFile.delete() }
            return@withContext ShellResult(1, "", "无法启动 Shell：${e.message}", backend = backendName)
        }
        val pidFromFile = runCatching {
            repeat(30) {
                pidFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()?.let { pid ->
                    if (pid > 0) return@runCatching pid
                }
                Thread.sleep(10)
            }
            null
        }.getOrNull()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        fun pump(source: java.io.InputStream, target: StringBuilder) {
            runCatching {
                source.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (target.length < maxCapture) target.append(line).append('\n')
                    }
                }
            }
        }
        val outThread = thread(name = "droidagent-stdout", isDaemon = true) {
            pump(process.inputStream, stdout)
        }
        val errThread = thread(name = "droidagent-stderr", isDaemon = true) {
            pump(process.errorStream, stderr)
        }
        val finished = try {
            if (timeoutMs <= 0) {
                process.waitFor()
                true
            } else {
                process.waitFor(timeoutMs.coerceIn(1_000, 900_000), TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            false
        }
        val timedOut = !finished
        val shellPid = pidFromFile ?: pidOf(process)
        if (!finished) {
            killProcessTree(shellPid)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            process.destroy()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            process.destroyForcibly()
        }
        outThread.join(2_000)
        errThread.join(2_000)
        if (outThread.isAlive || errThread.isAlive) {
            killProcessTree(shellPid)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            outThread.join(500)
            errThread.join(500)
        }
        runCatching { pidFile.delete() }
        ShellResult(
            exitCode = if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-1),
            stdout = stdout.toString(),
            stderr = stderr.toString() + if (timedOut) "\n命令执行超时，已终止进程树。" else "",
            timedOut = timedOut,
            backend = backendName,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun pidOf(process: Process): Long {
        runCatching {
            process.javaClass.getMethod("pid").invoke(process) as? Long
        }.getOrNull()?.let { return it }
        return Regex("pid=(\\d+)").find(process.toString())
            ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
    }
}

internal fun killProcessTree(rootPid: Long) {
    if (rootPid <= 0) return
    val children = HashMap<Long, MutableList<Long>>()
    File("/proc").listFiles()?.forEach { dir ->
        val pid = dir.name.toLongOrNull() ?: return@forEach
        val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
        val close = stat.lastIndexOf(')')
        if (close < 0) return@forEach
        val fields = stat.substring(close + 1).trim().split(' ')
        val ppid = fields.getOrNull(1)?.toLongOrNull() ?: return@forEach
        children.getOrPut(ppid) { mutableListOf() }.add(pid)
    }

    fun kill(pid: Long) {
        children[pid]?.forEach(::kill)
        runCatching { android.os.Process.sendSignal(pid.toInt(), 9) }
    }

    kill(rootPid)
}

/**
 * 通过 Termux RUN_COMMAND 服务执行命令。仅适用于双方都能访问的共享存储路径。
 */
class TermuxShellExecutor(
    private val context: Context,
    private val root: File,
) : ShellExecutor {
    override val available: Boolean
        get() = root.isDirectory &&
            isTermuxInstalled() &&
            context.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    override val backendName: String = "Termux Shell"

    override suspend fun run(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (!available) {
            return@withContext ShellResult(1, "", "Termux 不可用（未安装或未授予 RUN_COMMAND 权限）", backend = backendName)
        }
        val token = UUID.randomUUID().toString().take(8)
        val marker = "__DROIDAGENT_EXIT_${token}__"
        val log = File(root, ".droidagent_exec_$token.log")
        runCatching { log.delete() }
        val script = buildString {
            append("cd ").append(shellQuote(root.absolutePath)).append(" || exit 97\n")
            append("{ ").append(command).append("\n} > ").append(shellQuote(log.absolutePath)).append(" 2>&1\n")
            append("__rc=$?\n")
            append("printf '\\n").append(marker).append("%s\\n' \"\$__rc\" >> ").append(shellQuote(log.absolutePath))
        }
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_PATH", "/data/data/$TERMUX_PACKAGE/files/usr/bin/bash")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_WORKDIR", root.absolutePath)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND", true)
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_SESSION_ACTION", "0")
            putExtra("$TERMUX_PACKAGE.RUN_COMMAND_COMMAND_LABEL", "DroidAgent")
        }
        val startResult = runCatching { context.startService(intent) }
        if (startResult.isFailure) {
            val error = startResult.exceptionOrNull()
            return@withContext ShellResult(
                1,
                "",
                buildString {
                    append("无法启动 Termux 命令服务：")
                    append(error?.javaClass?.simpleName ?: "未知错误")
                    error?.message?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
                    append("\n请确认 Termux 已开启 allow-external-apps=true 并重启，")
                    append("且 DroidAgent 已获得 RUN_COMMAND 权限。")
                },
                backend = backendName,
            )
        }
        val deadline = if (timeoutMs > 0) {
            System.currentTimeMillis() + timeoutMs.coerceIn(1_000, 900_000)
        } else {
            Long.MAX_VALUE
        }
        var content = ""
        var exit: Int? = null
        var sawLog = false
        while (timeoutMs <= 0 || System.currentTimeMillis() < deadline) {
            delay(200)
            if (!log.exists()) {
                if (System.currentTimeMillis() - started > 10_000) {
                    return@withContext ShellResult(
                        1,
                        "",
                        "Termux 未开始执行命令。请确认：\n" +
                            "1. Termux 的 allow-external-apps=true 已开启并重启 Termux；\n" +
                            "2. 在 Termux 执行 termux-setup-storage 并授予存储权限；\n" +
                            "3. DroidAgent 已授予 RUN_COMMAND 权限。",
                        backend = backendName,
                    )
                }
                continue
            }
            sawLog = true
            content = runCatching { log.readText() }.getOrDefault("")
            val markerIndex = content.lastIndexOf(marker)
            if (markerIndex >= 0) {
                val exitText = content.substring(markerIndex + marker.length).trim().takeWhile { it.isDigit() || it == '-' }
                exit = exitText.toIntOrNull()
                content = content.substring(0, markerIndex)
                break
            }
        }
        val timedOut = exit == null
        val cleaned = content.trimEnd()
        runCatching { log.delete() }
        ShellResult(
            exitCode = exit ?: -1,
            stdout = cleaned,
            stderr = if (timedOut) {
                if (sawLog) "Termux 命令执行超时（后台任务可能仍在运行）"
                else "Termux 未开始执行命令，请检查 allow-external-apps 与存储权限。"
            } else {
                ""
            },
            timedOut = timedOut,
            backend = backendName,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    private fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    }
}

class ShellProvider(
    private val context: Context,
    private val termuxEnabled: Boolean,
) {
    fun forWorkspace(workspace: Workspace): ShellExecutor? {
        val real = WorkspaceFactory.realPath(workspace)
        if (real == null) return null
        val root = if (workspace.kind == WorkspaceKind.LOCAL) {
            workspace.path?.let(::File) ?: return null
        } else {
            real
        }
        if (!root.exists()) return null
        val sharedRoot = Environment.getExternalStorageDirectory().absolutePath
        val isShared = root.absolutePath.startsWith(sharedRoot)
        if (termuxEnabled && isShared) {
            val termux = TermuxShellExecutor(context, root)
            if (termux.available) return termux
        }
        val local = LocalShellExecutor(root, File(context.cacheDir, "shell-tmp"))
        return local.takeIf { it.available }
    }
}

package com.virjar.tk.shared.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class AgentSystemCommandTimeouts(
    val identityMillis: Long = 3_000,
    val systemctlMillis: Long = 30_000,
    val terminationGraceMillis: Long = 1_000,
    val outputDrainMillis: Long = 1_000,
) {
    init {
        require(identityMillis > 0)
        require(systemctlMillis > 0)
        require(terminationGraceMillis > 0)
        require(outputDrainMillis > 0)
    }
}

internal open class AgentSystemCommandException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class AgentSystemCommandTimeoutException(
    operation: String,
) : AgentSystemCommandException("$operation timed out")

/** 窄的特权命令端口。调用方无法提供可执行文件或任意参数。 */
internal interface AgentSystemCommands {
    fun currentUid(): Int

    fun resolveServiceIdentity(user: String): AgentUnixIdentity?

    fun stopAgentService()

    fun disableAgentService()

    fun reloadSystemdManager()
}

internal fun interface AgentProcessStarter {
    fun start(arguments: List<String>, captureStdout: Boolean): Process
}

/**
 * 安装/卸载边界的 JVM 实现。
 *
 * 每个子进程都是一次直接的、固定的二进制调用：没有 shell、没有继承 stdin、没有环境提供的
 * 参数，也不捕获 stderr。身份 stdout 被并发排空到一个小的有界缓冲区；
 * 所有 systemctl 输出都在 OS 管道边界被丢弃。
 */
internal class JvmAgentSystemCommands(
    private val timeouts: AgentSystemCommandTimeouts = AgentSystemCommandTimeouts(),
    private val processStarter: AgentProcessStarter = DEFAULT_PROCESS_STARTER,
) : AgentSystemCommands {

    override fun currentUid(): Int {
        val result = execute(
            operation = "current uid lookup",
            arguments = listOf(ID_EXECUTABLE, "-u"),
            timeoutMillis = timeouts.identityMillis,
            captureStdout = true,
        )
        requireSuccessfulIdentityResult(result, "current uid lookup")
        return result.stdout.toIntOrNull()
            ?: throw AgentSystemCommandException("current uid lookup returned invalid output")
    }

    override fun resolveServiceIdentity(user: String): AgentUnixIdentity? {
        require(SERVICE_USER.matches(user)) { "invalid service identity lookup" }
        val uidText = identityField(user, "-u", "service uid lookup") ?: return null
        val uid = uidText.toIntOrNull()
            ?: throw AgentSystemCommandException("service uid lookup returned invalid output")
        val gidText = identityField(user, "-g", "service gid lookup") ?: return null
        val gid = gidText.toIntOrNull()
            ?: throw AgentSystemCommandException("service gid lookup returned invalid output")
        val group = identityField(user, "-gn", "service group lookup") ?: return null
        return AgentUnixIdentity(userName = user, uid = uid, gid = gid, primaryGroupName = group)
    }

    override fun stopAgentService() {
        requireSuccessfulSystemctl(
            executeSystemctl("stop", "systemd service stop"),
            "systemd service stop",
        )
    }

    override fun disableAgentService() {
        requireSuccessfulSystemctl(
            executeSystemctl("disable", "systemd service disable"),
            "systemd service disable",
        )
    }

    override fun reloadSystemdManager() {
        requireSuccessfulSystemctl(
            executeSystemctl("daemon-reload", "systemd manager reload", includeUnit = false),
            "systemd manager reload",
        )
    }

    private fun identityField(user: String, flag: String, operation: String): String? {
        val result = execute(
            operation = operation,
            arguments = listOf(ID_EXECUTABLE, flag, user),
            timeoutMillis = timeouts.identityMillis,
            captureStdout = true,
        )
        if (result.exitCode != 0) return null
        requireSuccessfulIdentityResult(result, operation)
        return result.stdout
    }

    private fun executeSystemctl(
        action: String,
        operation: String,
        includeUnit: Boolean = true,
    ): AgentCommandResult = execute(
        operation = operation,
        arguments = buildList {
            add(SYSTEMCTL_EXECUTABLE)
            add(action)
            if (includeUnit) add(UNIT_NAME)
        },
        timeoutMillis = timeouts.systemctlMillis,
        captureStdout = false,
    )

    private fun requireSuccessfulIdentityResult(result: AgentCommandResult, operation: String) {
        if (result.exitCode != 0) {
            throw AgentSystemCommandException("$operation failed")
        }
        if (result.stdoutTruncated || result.stdout.isBlank()) {
            throw AgentSystemCommandException("$operation returned invalid output")
        }
    }

    private fun requireSuccessfulSystemctl(result: AgentCommandResult, operation: String) {
        if (result.exitCode != 0) {
            throw AgentSystemCommandException("$operation failed")
        }
    }

    private fun execute(
        operation: String,
        arguments: List<String>,
        timeoutMillis: Long,
        captureStdout: Boolean,
    ): AgentCommandResult {
        val process = try {
            processStarter.start(arguments, captureStdout)
        } catch (failure: IOException) {
            throw AgentSystemCommandException("$operation could not start", failure)
        } catch (failure: SecurityException) {
            throw AgentSystemCommandException("$operation could not start", failure)
        }
        var outputDrain: BoundedOutputDrain? = null

        try {
            if (captureStdout) {
                val drain = BoundedOutputDrain(process.inputStream, MAX_IDENTITY_OUTPUT_BYTES)
                outputDrain = drain
                drain.start()
            }
            // 没有固定命令接受输入。立即关闭可以防止子进程等待
            // 继承的终端或意外保留的父进程管道。
            try {
                process.outputStream.close()
            } catch (failure: IOException) {
                throw AgentSystemCommandException("$operation input could not be closed", failure)
            }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw AgentSystemCommandTimeoutException(operation)
            }
            outputDrain?.await(timeouts.outputDrainMillis)
            outputDrain?.throwIfFailed(operation)
            return AgentCommandResult(
                exitCode = process.exitValue(),
                stdout = outputDrain?.text()?.trim().orEmpty(),
                stdoutTruncated = outputDrain?.truncated ?: false,
            )
        } catch (failure: Throwable) {
            cleanupAfterFailure(process, outputDrain, failure)
            throw failure
        } finally {
            closeQuietly(process.outputStream)
            closeQuietly(process.inputStream)
            closeQuietly(process.errorStream)
        }
    }

    private fun cleanupAfterFailure(
        process: Process,
        outputDrain: BoundedOutputDrain?,
        primary: Throwable,
    ) {
        val interrupted = primary is InterruptedException || Thread.currentThread().isInterrupted
        if (interrupted) Thread.interrupted()
        try {
            terminate(process)
            outputDrain?.close()
            outputDrain?.await(timeouts.outputDrainMillis)
            outputDrain?.failure()?.let { drainFailure ->
                if (drainFailure is Error) {
                    if (drainFailure !== primary) drainFailure.addSuppressed(primary)
                    throw drainFailure
                }
                primary.addSuppressed(
                    AgentSystemCommandException("command output drain failed", drainFailure),
                )
            }
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure is Error) {
                if (cleanupFailure !== primary) cleanupFailure.addSuppressed(primary)
                throw cleanupFailure
            }
            if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun terminate(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (process.waitFor(timeouts.terminationGraceMillis, TimeUnit.MILLISECONDS)) return
        process.destroyForcibly()
        if (!process.waitFor(timeouts.terminationGraceMillis, TimeUnit.MILLISECONDS)) {
            throw AgentSystemCommandException("command process could not be terminated")
        }
    }

    private data class AgentCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stdoutTruncated: Boolean,
    )

    private class BoundedOutputDrain(
        private val input: InputStream,
        private val maxBytes: Int,
    ) {
        private val captured = ByteArrayOutputStream(maxBytes)
        private val failure = AtomicReference<Throwable?>()
        private val worker = Thread(
            {
                try {
                    val buffer = ByteArray(256)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val remaining = maxBytes - captured.size()
                        if (remaining > 0) captured.write(buffer, 0, minOf(read, remaining))
                        if (read > remaining) truncated = true
                    }
                } catch (drainFailure: Throwable) {
                    failure.set(drainFailure)
                }
            },
            "tt-agent-command-output",
        ).apply { isDaemon = true }

        @Volatile
        var truncated: Boolean = false
            private set

        fun start() = worker.start()

        fun await(timeoutMillis: Long) {
            worker.join(timeoutMillis)
            if (!worker.isAlive) return
            close()
            worker.join(timeoutMillis)
            if (worker.isAlive) {
                throw AgentSystemCommandException("command output drain did not terminate")
            }
        }

        fun close() = closeQuietly(input)

        fun failure(): Throwable? = failure.get()

        fun throwIfFailed(operation: String) {
            failure.get()?.let { drainFailure ->
                if (drainFailure is Error) throw drainFailure
                throw AgentSystemCommandException("$operation output could not be read", drainFailure)
            }
        }

        fun text(): String = captured.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val ID_EXECUTABLE = "/usr/bin/id"
        const val SYSTEMCTL_EXECUTABLE = "/usr/bin/systemctl"
        const val UNIT_NAME = "tt-agent"
        const val MAX_IDENTITY_OUTPUT_BYTES = 256
        val SERVICE_USER = Regex("[a-z_][a-z0-9_-]{0,30}")
        val DEFAULT_PROCESS_STARTER = AgentProcessStarter { arguments, captureStdout ->
            ProcessBuilder(arguments)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply {
                    if (!captureStdout) redirectOutput(ProcessBuilder.Redirect.DISCARD)
                }
                .start()
        }
    }
}

internal enum class AgentServiceUninstallResult {
    UNINSTALLED,
    ALREADY_ABSENT,
}

/** 特权且刻意破坏性的卸载序列的失败关闭协调器。 */
internal class AgentServiceUninstaller(
    private val osName: String,
    private val unitPath: Path,
    private val commands: AgentSystemCommands,
) {
    fun uninstall(): AgentServiceUninstallResult {
        require(osName.trim().equals("linux", ignoreCase = true)) {
            "systemd service uninstall only supports Linux"
        }
        require(commands.currentUid() == 0) { "systemd service uninstall must run as root" }

        return when (readUnitEntry()) {
            UnitEntry.ABSENT -> {
                // 在先前删除成功但 daemon-reload 没有成功的情况下，这也完成了一次重试。
                commands.reloadSystemdManager()
                AgentServiceUninstallResult.ALREADY_ABSENT
            }
            UnitEntry.REGULAR_FILE -> {
                commands.stopAgentService()
                commands.disableAgentService()
                Files.delete(unitPath)
                try {
                    commands.reloadSystemdManager()
                } catch (failure: Exception) {
                    throw AgentSystemCommandException(
                        "systemd unit was removed but manager reload failed",
                        failure,
                    )
                }
                AgentServiceUninstallResult.UNINSTALLED
            }
        }
    }

    private fun readUnitEntry(): UnitEntry {
        val attributes = try {
            Files.readAttributes(
                unitPath,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            return UnitEntry.ABSENT
        }
        require(!attributes.isSymbolicLink) { "Refusing to remove a symlinked systemd unit" }
        require(attributes.isRegularFile) { "Refusing to remove a non-file systemd unit" }
        return UnitEntry.REGULAR_FILE
    }

    private enum class UnitEntry {
        ABSENT,
        REGULAR_FILE,
    }
}

private fun closeQuietly(closeable: AutoCloseable) {
    try {
        closeable.close()
    } catch (_: Exception) {
        // 关闭进程管道只属于清理；命令成功与否在此点之前已经确定。
    }
}

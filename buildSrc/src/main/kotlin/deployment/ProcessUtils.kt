package deployment

/**
 * 有界、可分类的外部进程执行器。
 *
 * 部署代码不得直接使用 [ProcessBuilder]：关键命令必须走 [runCheckedProcess]，只读探测
 * 必须声明可接受的非零退出码，清理等非关键动作则必须显式走 [runBestEffortProcess]。
 */

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.gradle.api.GradleException

internal const val DEFAULT_PROCESS_TIMEOUT_MILLIS = 120_000L
internal const val LONG_PROCESS_TIMEOUT_MILLIS = 1_200_000L
private const val MAX_CAPTURED_OUTPUT_CHARS = 64 * 1024
internal const val MAX_SENSITIVE_PROCESS_INPUT_BYTES = 64 * 1024
private const val MAX_FAILURE_OUTPUT_CHARS = 4 * 1024

internal typealias ProcessStarter = (ProcessBuilder) -> Process

internal enum class ProcessOutputMode {
    LIVE,
    CAPTURE,
    DISCARD,
}

internal data class ProcessSpec(
    val label: String,
    val arguments: List<String>,
    val timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
    val outputMode: ProcessOutputMode = ProcessOutputMode.LIVE,
    val workingDirectory: File? = null,
    val environment: Map<String, String> = emptyMap(),
)

internal data class ProcessResult(
    val exitCode: Int,
    val output: String,
    /** 当 [output] 中只保留了有界尾部时为 true。 */
    val outputTruncated: Boolean = false,
)

internal sealed class ExternalProcessException(message: String, cause: Throwable? = null) :
    GradleException(message, cause)

internal class ProcessStartException(label: String, cause: Throwable) :
    ExternalProcessException("Cannot start external process: $label", cause)

internal class ProcessTimeoutException(label: String, timeoutMillis: Long) :
    ExternalProcessException("External process timed out after ${timeoutMillis}ms: $label")

internal class ProcessOutputException(label: String, cause: Throwable) :
    ExternalProcessException("Cannot read external process output: $label", cause)

internal class ProcessProtocolException(label: String, detail: String) :
    ExternalProcessException("External process protocol failed: $label ($detail)")

internal class ProcessInputException(label: String, cause: Throwable) :
    ExternalProcessException("Cannot write external process input: $label", cause)

internal class ProcessExitException(
    label: String,
    val exitCode: Int,
    output: String,
) : ExternalProcessException(
    buildString {
        append("External process failed (exit=").append(exitCode).append("): ").append(label)
        output.takeLast(MAX_FAILURE_OUTPUT_CHARS).takeIf { it.isNotBlank() }?.let {
            append("\nOutput tail:\n").append(it)
        }
    },
)

/** 针对捕获输出绝不能复制进日志或异常的进程的退出失败。 */
internal class SensitiveProcessExitException(
    label: String,
    val exitCode: Int,
) : ExternalProcessException(
    "Sensitive external process failed (exit=$exitCode): $label; captured output withheld",
)

/** 敏感进程的 I/O 失败有意不保留原因和进程数据。 */
internal class SensitiveProcessIoException(label: String, operation: String) :
    ExternalProcessException(
        "Sensitive external process $operation failed: $label; details withheld",
    )

/** 敏感捕获必须是完整的；有界尾部永远不能作为有效的密钥来源。 */
internal class SensitiveProcessOutputLimitException(label: String) :
    ExternalProcessException(
        "Sensitive external process output exceeded the capture limit: $label; output withheld",
    )

private class BoundedOutput {
    private val value = StringBuilder()
    private var truncated = false

    @Synchronized
    fun append(line: String) {
        val required = line.length + 1
        if (required >= MAX_CAPTURED_OUTPUT_CHARS) {
            truncated = truncated || value.isNotEmpty() || required > MAX_CAPTURED_OUTPUT_CHARS
            value.clear()
            value.append(line.takeLast(MAX_CAPTURED_OUTPUT_CHARS - 1)).append('\n')
            return
        }
        value.append(line).append('\n')
        val overflow = value.length - MAX_CAPTURED_OUTPUT_CHARS
        if (overflow > 0) {
            truncated = true
            value.delete(0, overflow)
        }
    }

    @Synchronized
    fun append(buffer: CharArray, length: Int) {
        require(length in 0..buffer.size)
        if (length >= MAX_CAPTURED_OUTPUT_CHARS) {
            truncated = truncated || value.isNotEmpty() || length > MAX_CAPTURED_OUTPUT_CHARS
            value.clear()
            value.append(
                buffer,
                length - MAX_CAPTURED_OUTPUT_CHARS,
                MAX_CAPTURED_OUTPUT_CHARS,
            )
            return
        }
        value.append(buffer, 0, length)
        val overflow = value.length - MAX_CAPTURED_OUTPUT_CHARS
        if (overflow > 0) {
            truncated = true
            value.delete(0, overflow)
        }
    }

    @Synchronized
    fun snapshot(): String = value.toString().trimEnd()

    @Synchronized
    fun wasTruncated(): Boolean = truncated
}

private fun terminate(process: Process) {
    runCatching { process.destroy() }
    runCatching {
        if (process.isAlive) process.destroyForcibly()
    }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    runCatching { process.outputStream.close() }
}

/**
 * 标准输入在有界的就绪握手后有意保持打开的进程。
 * 关闭该输入就是生命周期信号；顽固的本地进程随后会被强制终止，
 * 以免其 SSH socket 继续持有远程内核租约。
 */
internal class ManagedReadyProcess private constructor(
    private val label: String,
    private val process: Process,
    private val outputThread: Thread,
    private val readFailure: AtomicReference<Throwable?>,
    private val captured: BoundedOutput,
    private val closeTimeoutMillis: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    internal fun requireRunning() {
        if (closed.get()) {
            throw ProcessProtocolException(label, "managed process is already closed")
        }
        readFailure.get()?.let { throw ProcessOutputException(label, it) }
        if (!process.isAlive) {
            val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            throw ProcessExitException(label, exitCode, captured.snapshot())
        }
    }

    internal fun isRunning(): Boolean = !closed.get() && process.isAlive

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var interrupted = false
        runCatching { process.outputStream.close() }
        val completed = try {
            process.waitFor(closeTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
            false
        }
        if (!completed) {
            terminate(process)
            try {
                process.waitFor(closeTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        try {
            outputThread.join(closeTimeoutMillis)
        } catch (_: InterruptedException) {
            interrupted = true
        }
        if (outputThread.isAlive || process.isAlive) {
            outputThread.interrupt()
            terminate(process)
            try {
                process.waitFor(closeTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        internal fun start(
            spec: ProcessSpec,
            readyMarker: String,
            closeTimeoutMillis: Long = 10_000L,
            startProcess: (ProcessBuilder) -> Process = { it.start() },
        ): ManagedReadyProcess {
            require(spec.label.isNotBlank()) { "Process label must not be blank" }
            require(spec.arguments.isNotEmpty() && spec.arguments.first().isNotBlank()) {
                "Process arguments must name an executable"
            }
            require(spec.timeoutMillis > 0) { "Process timeout must be positive" }
            require(readyMarker.isNotBlank() && '\n' !in readyMarker && '\r' !in readyMarker) {
                "Ready marker must be one non-blank line"
            }
            require(closeTimeoutMillis > 0) { "Close timeout must be positive" }

            val processBuilder = ProcessBuilder(spec.arguments)
                .directory(spec.workingDirectory)
                .redirectErrorStream(true)
                .apply { environment().putAll(spec.environment) }
            val process = try {
                startProcess(processBuilder)
            } catch (failure: Exception) {
                throw ProcessStartException(spec.label, failure)
            }

            val captured = BoundedOutput()
            val readFailure = AtomicReference<Throwable?>()
            val ready = AtomicBoolean(false)
            val startupSignal = CountDownLatch(1)
            val outputThread = thread(
                start = true,
                isDaemon = true,
                name = "teamtalk-managed-process-output",
            ) {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (spec.outputMode == ProcessOutputMode.LIVE) println("  $line")
                            if (spec.outputMode != ProcessOutputMode.DISCARD) captured.append(line)
                            if (line == readyMarker && ready.compareAndSet(false, true)) {
                                startupSignal.countDown()
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    readFailure.compareAndSet(null, failure)
                } finally {
                    startupSignal.countDown()
                }
            }

            fun fail(failure: ExternalProcessException): Nothing {
                terminate(process)
                outputThread.interrupt()
                runCatching { process.waitFor(1_000L, TimeUnit.MILLISECONDS) }
                throw failure
            }

            val signalled = try {
                startupSignal.await(spec.timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                fail(ProcessProtocolException(spec.label, "readiness wait was interrupted"))
            }
            if (!signalled) fail(ProcessTimeoutException(spec.label, spec.timeoutMillis))
            readFailure.get()?.let { fail(ProcessOutputException(spec.label, it)) }
            if (!ready.get()) {
                val exited = runCatching {
                    process.waitFor(100L, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
                if (exited) {
                    fail(ProcessExitException(spec.label, process.exitValue(), captured.snapshot()))
                }
                fail(ProcessProtocolException(spec.label, "output closed before readiness"))
            }
            if (!process.isAlive) {
                fail(ProcessExitException(spec.label, process.exitValue(), captured.snapshot()))
            }
            return ManagedReadyProcess(
                spec.label,
                process,
                outputThread,
                readFailure,
                captured,
                closeTimeoutMillis,
            )
        }
    }
}

/** 执行单个进程。超时覆盖进程执行和完整的输出排空。 */
internal fun executeProcess(
    spec: ProcessSpec,
    startProcess: (ProcessBuilder) -> Process = { it.start() },
): ProcessResult = executeProcessInternal(
    spec = spec,
    standardInput = null,
    redactIoFailures = false,
    startProcess = startProcess,
)

private fun executeProcessInternal(
    spec: ProcessSpec,
    standardInput: ByteArray?,
    redactIoFailures: Boolean,
    startProcess: (ProcessBuilder) -> Process,
): ProcessResult {
    require(spec.label.isNotBlank()) { "Process label must not be blank" }
    require(spec.arguments.isNotEmpty() && spec.arguments.first().isNotBlank()) {
        "Process arguments must name an executable"
    }
    require(spec.timeoutMillis > 0) { "Process timeout must be positive" }

    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(spec.timeoutMillis)
    val processBuilder = ProcessBuilder(spec.arguments)
        .directory(spec.workingDirectory)
        .redirectErrorStream(true)
        .apply { environment().putAll(spec.environment) }
    val process = try {
        startProcess(processBuilder)
    } catch (failure: Exception) {
        if (redactIoFailures) {
            throw SensitiveProcessIoException(spec.label, "start")
        }
        throw ProcessStartException(spec.label, failure)
    }

    val captured = BoundedOutput()
    val readFailure = AtomicReference<Throwable?>()
    val outputThread = thread(
        start = true,
        isDaemon = true,
        name = "teamtalk-process-output",
    ) {
        try {
            if (spec.outputMode == ProcessOutputMode.LIVE) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        captured.append(line)
                        println("  $line")
                    }
                }
            } else {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4 * 1024)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        if (spec.outputMode == ProcessOutputMode.CAPTURE) {
                            captured.append(buffer, read)
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            readFailure.compareAndSet(null, failure)
        }
    }
    val writeFailure = AtomicReference<Throwable?>()
    val inputThread = standardInput?.let { input ->
        thread(
            start = true,
            isDaemon = true,
            name = "teamtalk-process-input",
        ) {
            try {
                process.outputStream.use { stream ->
                    stream.write(input)
                    stream.flush()
                }
            } catch (failure: Throwable) {
                writeFailure.compareAndSet(null, failure)
            }
        }
    } ?: run {
        runCatching { process.outputStream.close() }
        null
    }

    fun remainingNanos(): Long = deadline - System.nanoTime()
    val completed = remainingNanos().takeIf { it > 0L }?.let {
        process.waitFor(it, TimeUnit.NANOSECONDS)
    } ?: false
    if (!completed) {
        terminate(process)
        outputThread.interrupt()
        inputThread?.interrupt()
        throw ProcessTimeoutException(spec.label, spec.timeoutMillis)
    }

    fun joinBeforeDeadline(worker: Thread?) {
        if (worker == null) return
        val remainingForDrain = remainingNanos()
        if (remainingForDrain <= 0L) {
            terminate(process)
            outputThread.interrupt()
            inputThread?.interrupt()
            throw ProcessTimeoutException(spec.label, spec.timeoutMillis)
        }
        val drainMillis = TimeUnit.NANOSECONDS.toMillis(remainingForDrain)
        val drainNanos = (remainingForDrain % 1_000_000L).toInt()
        worker.join(drainMillis, drainNanos)
        if (worker.isAlive) {
            terminate(process)
            outputThread.interrupt()
            inputThread?.interrupt()
            throw ProcessTimeoutException(spec.label, spec.timeoutMillis)
        }
    }
    joinBeforeDeadline(inputThread)
    joinBeforeDeadline(outputThread)
    readFailure.get()?.let {
        if (redactIoFailures) throw SensitiveProcessIoException(spec.label, "output read")
        throw ProcessOutputException(spec.label, it)
    }
    writeFailure.get()?.let {
        if (redactIoFailures) throw SensitiveProcessIoException(spec.label, "input write")
        throw ProcessInputException(spec.label, it)
    }
    return ProcessResult(
        exitCode = process.exitValue(),
        output = captured.snapshot(),
        outputTruncated = captured.wasTruncated(),
    )
}

internal fun runCheckedProcess(
    spec: ProcessSpec,
    startProcess: (ProcessBuilder) -> Process = { it.start() },
): ProcessResult {
    val result = executeProcess(spec, startProcess)
    if (result.exitCode != 0) {
        throw ProcessExitException(spec.label, result.exitCode, result.output)
    }
    return result
}

/**
 * 运行语义探测。传输/启动/超时失败以及未声明的退出码仍然致命；
 * 只有 [allowedExitCodes] 会被转换为布尔结果。
 */
internal fun runProcessProbe(
    spec: ProcessSpec,
    allowedExitCodes: Set<Int> = setOf(0, 1),
    startProcess: (ProcessBuilder) -> Process = { it.start() },
): Boolean {
    require(0 in allowedExitCodes) { "A process probe must accept exit code 0" }
    val result = executeProcess(spec, startProcess)
    if (result.exitCode !in allowedExitCodes) {
        throw ProcessExitException(spec.label, result.exitCode, result.output)
    }
    return result.exitCode == 0
}

/** 捕获敏感探测，同时在任何退出状态失败中都隐瞒其输出。 */
internal fun runSensitiveCaptureProbe(
    spec: ProcessSpec,
    allowedExitCodes: Set<Int> = setOf(0, 1),
    startProcess: (ProcessBuilder) -> Process = { it.start() },
): String? {
    require(spec.outputMode == ProcessOutputMode.CAPTURE) {
        "A sensitive capture probe must use CAPTURE output mode"
    }
    require(0 in allowedExitCodes) { "A process probe must accept exit code 0" }
    val result = executeProcessInternal(
        spec = spec,
        standardInput = null,
        redactIoFailures = true,
        startProcess = startProcess,
    )
    if (result.outputTruncated) throw SensitiveProcessOutputLimitException(spec.label)
    if (result.exitCode !in allowedExitCodes) {
        throw SensitiveProcessExitException(spec.label, result.exitCode)
    }
    return result.output.takeIf { result.exitCode == 0 }
}

/**
 * 执行一个有界标准输入为机密的命令。输入永远不会放进 [ProcessSpec.arguments]，
 * 进程输出永远不会被保留，并且所有失败都会省略输出和底层异常文本。
 */
internal fun runSensitiveStdinCheckedProcess(
    spec: ProcessSpec,
    standardInput: ByteArray,
    startProcess: (ProcessBuilder) -> Process = { it.start() },
): ProcessResult {
    require(spec.outputMode == ProcessOutputMode.DISCARD) {
        "A sensitive stdin process must discard output"
    }
    require(spec.environment.isEmpty()) {
        "A sensitive stdin process cannot carry environment overrides"
    }
    require(standardInput.size <= MAX_SENSITIVE_PROCESS_INPUT_BYTES) {
        "Sensitive process input exceeds the $MAX_SENSITIVE_PROCESS_INPUT_BYTES-byte limit"
    }
    val result = executeProcessInternal(
        spec = spec,
        standardInput = standardInput,
        redactIoFailures = true,
        startProcess = startProcess,
    )
    if (result.exitCode != 0) {
        throw SensitiveProcessExitException(spec.label, result.exitCode)
    }
    return ProcessResult(result.exitCode, "")
}

/** 仅用于非关键清理。每次失败都是有界的、可见的，并且被有意忽略。 */
internal fun runBestEffortProcess(
    spec: ProcessSpec,
    startProcess: (ProcessBuilder) -> Process = { it.start() },
): ProcessResult? = try {
    executeProcess(spec, startProcess).also { result ->
        if (result.exitCode != 0) {
            println("  WARNING: best-effort action failed (exit=${result.exitCode}): ${spec.label}")
        }
    }
} catch (failure: ExternalProcessException) {
    println("  WARNING: best-effort action failed: ${spec.label}: ${failure.message}")
    null
}

internal fun localChecked(
    label: String,
    arguments: List<String>,
    timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
    outputMode: ProcessOutputMode = ProcessOutputMode.LIVE,
    workingDirectory: File? = null,
    environment: Map<String, String> = emptyMap(),
): ProcessResult {
    require(arguments.isNotEmpty()) { "Local process arguments must not be empty" }
    requireGuardedRemoteTransfer(arguments)
    return runCheckedProcess(
        ProcessSpec(
            label,
            arguments,
            timeoutMillis,
            outputMode,
            workingDirectory,
            environment,
        ),
    )
}

/**
 * 返回远程主机上可用的 docker compose 检测命令。
 * 优先使用 v2 (`docker compose`)，不可用时回退到 v1 (`docker-compose`)。
 *
 * @param systemdContext 如果为 true，输出中的 `$` 会转义为 `$$` 以适配 systemd unit 文件。
 */
fun dockerComposeCmd(systemdContext: Boolean = false): String {
    val esc = if (systemdContext) "\$\$" else "\$"
    return "${esc}(docker compose version >/dev/null 2>&1 && echo docker compose || echo docker-compose)"
}

private val passwordRandom = SecureRandom()

/** 生成 32 字符密码，使用密码学安全随机源。 */
fun genPassword(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return CharArray(32) { chars[passwordRandom.nextInt(chars.length)] }.concatToString()
}

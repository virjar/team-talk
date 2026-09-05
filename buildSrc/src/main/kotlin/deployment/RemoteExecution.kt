package deployment

/** SSH 传输、部署围栏以及受防护的远程文件传输。 */

import java.io.File
import org.gradle.api.GradleException

private const val SSH_SERVER_ALIVE_INTERVAL_SECONDS = 5
private const val SSH_SERVER_ALIVE_COUNT_MAX = 3

/** 用于固定远程命令的 shell 引号编码。与环境变量值不同，命令可以包含换行。 */
internal fun remoteShellQuote(value: String): String {
    require('\u0000' !in value) { "Remote shell values must not contain NUL" }
    return "'${value.replace("'", "'\\''")}'"
}

/**
 * 当前生效的部署代号（generation）。有意不做成 data class：围栏令牌绝不能出现在隐式的
 * toString、异常或 Gradle 诊断输出中。
 */
internal class RemoteDeploymentCommandGuard(
    internal val host: String,
    internal val user: String,
    internal val port: Int,
    private val ownerLockPath: String,
    private val operationLockPath: String,
    private val generationPath: String,
    private val generationToken: String,
    private val requireOwnerSession: () -> Unit,
) {
    init {
        require(port in 1..65535) { "Deployment SSH port is invalid" }
        requireDeploymentGeneration(generationToken)
        requireDeploymentLockPath(ownerLockPath)
        requireDeploymentLockPath(operationLockPath)
        requireDeploymentLockPath(generationPath)
    }

    internal fun matches(candidateHost: String, candidateUser: String, candidatePort: Int): Boolean =
        host == candidateHost && user == candidateUser && port == candidatePort

    internal fun requireOwner() = requireOwnerSession()

    private fun fenceScript(): String = deploymentFenceScript(
        ownerLockPath = ownerLockPath,
        generationPath = generationPath,
        generationToken = generationToken,
    )

    /** 外层 flock 进程会在子进程的整个生命周期内持有独占的操作锁。 */
    internal fun wrapRemoteCommand(command: String): String {
        requireOwner()
        return "flock -n -E $DEPLOYMENT_OPERATION_CONFLICT_EXIT_CODE -x " +
            "${remoteShellQuote(operationLockPath)} " +
            "sh -c ${remoteShellQuote(fenceScript())} teamtalk-operation " +
            "sh -c ${remoteShellQuote(command)}"
    }

    /** 让返回的两个词都保持 shell 安全：legacy/openrsync 在调用 SSH 前会先按词切分该值。 */
    internal fun remoteRsyncProgram(): String {
        requireOwner()
        return "/bin/sh ${deploymentRsyncProgramPath(ownerLockPath, generationToken)}"
    }
}

internal fun deploymentFenceScript(
    ownerLockPath: String,
    generationPath: String,
    generationToken: String,
): String {
    requireDeploymentLockPath(ownerLockPath)
    requireDeploymentLockPath(generationPath)
    requireDeploymentGeneration(generationToken)
    return "IFS= read -r teamtalk_generation < ${remoteShellQuote(generationPath)} || " +
            "exit $DEPLOYMENT_FENCE_REJECTED_EXIT_CODE; " +
            "test \"\$teamtalk_generation\" = ${remoteShellQuote(generationToken)} || " +
            "exit $DEPLOYMENT_FENCE_REJECTED_EXIT_CODE; " +
            "flock -n -E $DEPLOYMENT_OWNER_CONFLICT_EXIT_CODE -x " +
            "${remoteShellQuote(ownerLockPath)} true; " +
            "teamtalk_owner_state=\$?; " +
            "test \"\$teamtalk_owner_state\" -eq $DEPLOYMENT_OWNER_CONFLICT_EXIT_CODE || " +
            "exit $DEPLOYMENT_FENCE_REJECTED_EXIT_CODE; " +
            "exec \"\$@\""
}

/*
 * deployServer 有意保持同步。将作用域限制在单个线程内，可避免无关的 Gradle 任务继承
 * 部署能力；未来的并行部署流程必须显式地把租约传给其 worker，而不是扩大此上下文。
 */
private val activeRemoteDeploymentGuard = ThreadLocal<RemoteDeploymentCommandGuard?>()

internal fun <T> withRemoteDeploymentCommandGuard(
    guard: RemoteDeploymentCommandGuard,
    action: () -> T,
): T {
    if (activeRemoteDeploymentGuard.get() != null) {
        throw GradleException("Nested remote deployment operation scopes are forbidden")
    }
    guard.requireOwner()
    activeRemoteDeploymentGuard.set(guard)
    return try {
        action().also { guard.requireOwner() }
    } finally {
        activeRemoteDeploymentGuard.remove()
    }
}

private fun activeGuardFor(
    host: String,
    user: String,
    port: Int,
): RemoteDeploymentCommandGuard? = activeRemoteDeploymentGuard.get()?.also { guard ->
    if (!guard.matches(host, user, port)) {
        throw GradleException(
            "A deployment operation attempted a different SSH target while the target lease was active",
        )
    }
    guard.requireOwner()
}

internal fun requireActiveRemoteDeploymentGuard(host: String, user: String, port: Int) {
    if (activeGuardFor(host, user, port) == null) {
        throw GradleException("Server deployment mutation requires an active target lease")
    }
}

private fun sshClientOptions(port: Int): List<String> {
    require(port in 1..65535) { "SSH port must be in 1..65535" }
    return listOf(
        "-p", port.toString(),
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=10",
        "-o", "ServerAliveInterval=$SSH_SERVER_ALIVE_INTERVAL_SECONDS",
        "-o", "ServerAliveCountMax=$SSH_SERVER_ALIVE_COUNT_MAX",
        "-o", "StrictHostKeyChecking=accept-new",
    )
}

internal fun unguardedSshArguments(
    host: String,
    user: String,
    port: Int,
    command: String,
): List<String> = listOf("ssh") + sshClientOptions(port) + listOf("$user@$host", command)

internal fun remoteCommandArguments(
    host: String,
    user: String,
    port: Int,
    command: String,
): List<String> {
    val guardedCommand = activeGuardFor(host, user, port)?.wrapRemoteCommand(command) ?: command
    return unguardedSshArguments(host, user, port, guardedCommand)
}

internal fun sshTransportCommand(port: Int): String =
    (listOf("ssh") + sshClientOptions(port)).joinToString(" ")

internal fun remoteRsyncTransportArguments(
    host: String,
    user: String,
    port: Int,
): List<String> {
    val remoteProgram = activeGuardFor(host, user, port)?.remoteRsyncProgram() ?: "rsync"
    return listOf(
        "--rsync-path=$remoteProgram",
        "-e", sshTransportCommand(port),
    )
}

internal fun remoteChecked(
    label: String,
    host: String,
    user: String,
    command: String,
    port: Int = 22,
    timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
    outputMode: ProcessOutputMode = ProcessOutputMode.LIVE,
): ProcessResult = runCheckedProcess(
    ProcessSpec(label, remoteCommandArguments(host, user, port, command), timeoutMillis, outputMode),
)

internal fun remoteProbe(
    label: String,
    host: String,
    user: String,
    command: String,
    port: Int = 22,
    allowedExitCodes: Set<Int> = setOf(0, 1),
    timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
): Boolean = runProcessProbe(
    ProcessSpec(
        label,
        remoteCommandArguments(host, user, port, command),
        timeoutMillis,
        ProcessOutputMode.DISCARD,
    ),
    allowedExitCodes,
)

internal fun remoteCaptureProbe(
    label: String,
    host: String,
    user: String,
    command: String,
    port: Int = 22,
    allowedExitCodes: Set<Int> = setOf(0, 1),
    timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
): String? {
    val spec = ProcessSpec(
        label,
        remoteCommandArguments(host, user, port, command),
        timeoutMillis,
        ProcessOutputMode.CAPTURE,
    )
    val result = executeProcess(spec)
    if (result.exitCode !in allowedExitCodes) {
        throw ProcessExitException(spec.label, result.exitCode, result.output)
    }
    return result.output.takeIf { result.exitCode == 0 }
}

internal fun remoteSensitiveCaptureProbe(
    label: String,
    host: String,
    user: String,
    command: String,
    port: Int = 22,
    allowedExitCodes: Set<Int> = setOf(0, 1),
    timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
): String? = runSensitiveCaptureProbe(
    ProcessSpec(
        label,
        remoteCommandArguments(host, user, port, command),
        timeoutMillis,
        ProcessOutputMode.CAPTURE,
    ),
    allowedExitCodes,
)

internal fun remoteSensitiveStdinChecked(
    label: String,
    host: String,
    user: String,
    command: String,
    standardInput: ByteArray,
    port: Int = 22,
    timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
): ProcessResult = runSensitiveStdinCheckedProcess(
    ProcessSpec(
        label,
        remoteCommandArguments(host, user, port, command),
        timeoutMillis,
        ProcessOutputMode.DISCARD,
    ),
    standardInput,
)

internal fun remoteCaptureChecked(
    label: String,
    host: String,
    user: String,
    command: String,
    port: Int = 22,
    timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
): String = remoteChecked(
    label,
    host,
    user,
    command,
    port,
    timeoutMillis,
    ProcessOutputMode.CAPTURE,
).output

internal fun remoteBestEffort(
    label: String,
    host: String,
    user: String,
    command: String,
    port: Int = 22,
    timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
): ProcessResult? = try {
    runBestEffortProcess(
        ProcessSpec(
            label,
            remoteCommandArguments(host, user, port, command),
            timeoutMillis,
            ProcessOutputMode.DISCARD,
        ),
    )
} catch (failure: GradleException) {
    println("  WARNING: best-effort action could not start safely: $label: ${failure.message}")
    null
}

private fun requireSafeRemoteTransferPath(value: String) {
    require(value.startsWith('/') && value != "/") { "Remote upload path must be absolute" }
    val segments = value.drop(1).split('/')
    require(segments.all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".." &&
            segment.all { character ->
                character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                    character == '.' || character == '_' || character == '-'
            }
    }) { "Remote upload path is unsafe" }
}

/** 通过 rsync 上传单个普通文件，使远程服务器进程能持有操作锁。 */
internal fun remoteFileUploadChecked(
    label: String,
    file: File,
    host: String,
    user: String,
    port: Int,
    remotePath: String,
    timeoutMillis: Long = DEFAULT_PROCESS_TIMEOUT_MILLIS,
): ProcessResult {
    requireActiveRemoteDeploymentGuard(host, user, port)
    require(file.isFile) { "Remote upload source must be a regular file" }
    requireSafeRemoteTransferPath(remotePath)
    return localChecked(
        label,
        buildList {
            add("rsync")
            // 单文件上传必须由远程部署用户持有（而非本地 UID）。
            add("-z")
            addAll(remoteRsyncTransportArguments(host, user, port))
            add("--")
            add(file.absolutePath)
            add("$user@$host:$remotePath")
        },
        timeoutMillis = timeoutMillis,
        outputMode = ProcessOutputMode.DISCARD,
    )
}

internal fun requireGuardedRemoteTransfer(arguments: List<String>) {
    val guard = activeRemoteDeploymentGuard.get() ?: return
    guard.requireOwner()
    val executable = File(arguments.first()).name
    when (executable) {
        "ssh", "scp", "sftp" -> throw GradleException(
            "Raw $executable is forbidden while a deployment lease is active",
        )
        "rsync" -> {
            val expectedProgram = "--rsync-path=${guard.remoteRsyncProgram()}"
            val transportIndex = arguments.indexOf("-e")
            if (expectedProgram !in arguments ||
                transportIndex < 0 ||
                arguments.getOrNull(transportIndex + 1) != sshTransportCommand(guard.port)
            ) {
                throw GradleException(
                    "Remote rsync is missing the active deployment operation guard",
                )
            }
        }
    }
}

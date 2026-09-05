package deployment

import java.security.MessageDigest
import java.util.UUID
import org.gradle.api.GradleException

internal enum class DeploymentMode { FIRST_DEPLOY, UPGRADE }

internal data class RemoteDeploymentState(
    val distributionPresent: Boolean,
    val environmentPresent: Boolean,
    val composePresent: Boolean,
    val systemdUnitPresent: Boolean,
    val dataEpochPresent: Boolean,
    val datasetIdPresent: Boolean,
    val deployPathPopulated: Boolean,
)

/**
 * 首次部署是指真正为空的目标。升级是指完整且正在运行的安装。
 * 任何部分组合都是运维可见的损坏，绝不能据此选择凭据策略。
 */
internal fun RemoteDeploymentState.requireDeploymentMode(): DeploymentMode {
    if (!deployPathPopulated &&
        !distributionPresent &&
        !environmentPresent &&
        !composePresent &&
        !systemdUnitPresent &&
        !dataEpochPresent &&
        !datasetIdPresent
    ) {
        return DeploymentMode.FIRST_DEPLOY
    }
    val required = linkedMapOf(
        "server distribution" to distributionPresent,
        "conf/env.sh" to environmentPresent,
        "docker-compose.yml" to composePresent,
        "systemd unit" to systemdUnitPresent,
        "data epoch marker" to dataEpochPresent,
        "dataset identity marker" to datasetIdPresent,
    )
    if (required.values.all { it }) return DeploymentMode.UPGRADE
    val present = required.filterValues { it }.keys
    val missing = required.filterValues { !it }.keys
    throw GradleException(
        "Remote TeamTalk installation is partial; refusing to choose first-deploy or upgrade " +
            "credential semantics (present=${present.joinToString().ifEmpty { "none" }}, " +
            "missing=${missing.joinToString()})",
    )
}

internal fun parseRemoteDeploymentState(output: String): RemoteDeploymentState {
    val expected = setOf(
        "distribution", "environment", "compose", "systemd", "dataEpoch", "datasetId", "populated",
    )
    val values = linkedMapOf<String, Boolean>()
    output.lineSequence().filter(String::isNotBlank).forEach { line ->
        val pieces = line.split('=', limit = 2)
        require(pieces.size == 2 && pieces[0] in expected && pieces[0] !in values) {
            "Remote deployment state response is malformed"
        }
        values[pieces[0]] = when (pieces[1]) {
            "0" -> false
            "1" -> true
            else -> throw IllegalArgumentException("Remote deployment state value is malformed")
        }
    }
    require(values.keys == expected) { "Remote deployment state response is incomplete" }
    return RemoteDeploymentState(
        distributionPresent = values.getValue("distribution"),
        environmentPresent = values.getValue("environment"),
        composePresent = values.getValue("compose"),
        systemdUnitPresent = values.getValue("systemd"),
        dataEpochPresent = values.getValue("dataEpoch"),
        datasetIdPresent = values.getValue("datasetId"),
        deployPathPopulated = values.getValue("populated"),
    )
}

internal fun deploymentStateReadCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    fun flag(name: String, condition: String) =
        "if $condition; then echo $name=1; else echo $name=0; fi"
    return listOf(
        flag(
            "distribution",
            "test -d $deployPath/current/bin || test -d $deployPath/bin",
        ),
        flag("environment", "test -f $deployPath/conf/env.sh"),
        flag("compose", "test -f $deployPath/docker-compose.yml"),
        flag("systemd", "test -f /etc/systemd/system/teamtalk.service"),
        flag("dataEpoch", "test -f $deployPath/data/data-epoch"),
        flag("datasetId", "test -f $deployPath/data/dataset-id"),
        flag(
            "populated",
            "test -d $deployPath && test -n \"\$(find $deployPath -mindepth 1 -maxdepth 1 -print -quit)\"",
        ),
    ).joinToString("; ")
}

internal fun readRemoteDeploymentMode(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
): DeploymentMode {
    val output = remoteCaptureChecked(
        "inspect complete TeamTalk deployment state",
        host,
        user,
        deploymentStateReadCommand(deployPath),
        port,
    )
    val state = try {
        parseRemoteDeploymentState(output)
    } catch (failure: IllegalArgumentException) {
        throw GradleException("Cannot classify remote TeamTalk deployment state", failure)
    }
    return state.requireDeploymentMode()
}

internal fun remoteDeploymentLockPath(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(deployPath.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(24)
    return "/run/lock/teamtalk-deploy-$digest.lock"
}

private val deploymentLockPathPattern = Regex("/run/lock/[A-Za-z0-9._-]+")
private val deploymentGenerationPattern =
    Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

internal fun requireDeploymentLockPath(value: String): String = value.also {
    require(it.matches(deploymentLockPathPattern)) { "Deployment lock path is unsafe" }
}

internal fun requireDeploymentGeneration(value: String): String = value.also {
    require(it.matches(deploymentGenerationPattern)) { "Deployment generation token is malformed" }
}

internal data class RemoteDeploymentLockPaths(
    val owner: String,
    val operations: String,
    val generation: String,
) {
    init {
        requireDeploymentLockPath(owner)
        requireDeploymentLockPath(operations)
        requireDeploymentLockPath(generation)
    }
}

internal fun remoteDeploymentLockPaths(deployPath: String): RemoteDeploymentLockPaths {
    val owner = remoteDeploymentLockPath(deployPath)
    return RemoteDeploymentLockPaths(
        owner = owner,
        operations = "$owner.operations",
        generation = "$owner.generation",
    )
}

internal const val DEPLOYMENT_LEASE_READY_MARKER = "TEAMTALK_DEPLOYMENT_LEASE_READY"
internal const val DEPLOYMENT_OWNER_CONFLICT_EXIT_CODE = 73
internal const val DEPLOYMENT_DRAIN_CONFLICT_EXIT_CODE = 74
internal const val DEPLOYMENT_FENCE_REJECTED_EXIT_CODE = 75
internal const val DEPLOYMENT_OPERATION_CONFLICT_EXIT_CODE = 76
private const val DEPLOYMENT_LEASE_START_TIMEOUT_MILLIS = 20_000L

/**
 * rsync 会先通过 legacy 客户端和 SSH 传输该值，然后远程 shell 才会看到它。
 * 按代号（generation）生成、shell 安全的可执行路径，既能防止参数被重新分词，
 * 也能防止延迟的传输借用后续部署的租约。
 */
internal fun deploymentRsyncProgramPath(ownerLockPath: String, generationToken: String): String {
    requireDeploymentLockPath(ownerLockPath)
    requireDeploymentGeneration(generationToken)
    val generationDigest = MessageDigest.getInstance("SHA-256")
        .digest(generationToken.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(24)
    return requireDeploymentLockPath("$ownerLockPath.rsync-$generationDigest")
}

internal fun deploymentRsyncWrapperScript(
    paths: RemoteDeploymentLockPaths,
    generationToken: String,
): String {
    val fenceScript = deploymentFenceScript(
        ownerLockPath = paths.owner,
        generationPath = paths.generation,
        generationToken = generationToken,
    )
    return """
        #!/bin/sh
        exec flock -n -E $DEPLOYMENT_OPERATION_CONFLICT_EXIT_CODE -x ${remoteShellQuote(paths.operations)} sh -c ${remoteShellQuote(fenceScript)} teamtalk-operation rsync "${'$'}@"
    """.trimIndent()
}

/**
 * 在长连接的 SSH 通道中持有 owner 锁。generation 文件是围栏元数据，而不是锁：
 * 它在崩溃后可以安全残留，并且只在持有内核 owner 锁期间才会被替换。
 * 当孤立的操作仍在收尾时，排空探测会拒绝新的控制器。
 */
internal fun deploymentLeaseRemoteCommand(
    paths: RemoteDeploymentLockPaths,
    generationToken: String,
): String {
    requireDeploymentGeneration(generationToken)
    val generationTemp = requireDeploymentLockPath("${paths.generation}.tmp-$generationToken")
    val rsyncProgram = deploymentRsyncProgramPath(paths.owner, generationToken)
    val rsyncProgramTemp = requireDeploymentLockPath("$rsyncProgram.tmp")
    val rsyncWrapper = deploymentRsyncWrapperScript(paths, generationToken)
    val staleRsyncPrograms = "${remoteShellQuote("${paths.owner}.rsync-")}*"
    val childScript =
        "if ! { printf '%s\\n' ${remoteShellQuote(generationToken)} > " +
            "${remoteShellQuote(generationTemp)} && chmod 600 " +
            "${remoteShellQuote(generationTemp)} && mv -f " +
            "${remoteShellQuote(generationTemp)} ${remoteShellQuote(paths.generation)}; }; then " +
            "rm -f ${remoteShellQuote(generationTemp)}; exit 70; fi; " +
            "flock -n -E $DEPLOYMENT_DRAIN_CONFLICT_EXIT_CODE -x " +
            "${remoteShellQuote(paths.operations)} true || exit \$?; " +
            "rm -f $staleRsyncPrograms || exit 70; " +
            "if ! { printf '%s\\n' ${remoteShellQuote(rsyncWrapper)} > " +
            "${remoteShellQuote(rsyncProgramTemp)} && chmod 700 " +
            "${remoteShellQuote(rsyncProgramTemp)} && mv -f " +
            "${remoteShellQuote(rsyncProgramTemp)} ${remoteShellQuote(rsyncProgram)}; }; then " +
            "rm -f ${remoteShellQuote(rsyncProgramTemp)}; exit 70; fi; " +
            "printf '%s\\n' ${remoteShellQuote(DEPLOYMENT_LEASE_READY_MARKER)}; " +
            "exec cat >/dev/null"
    return "command -v flock >/dev/null 2>&1 || exit 69; umask 077; " +
        "exec flock -n -E $DEPLOYMENT_OWNER_CONFLICT_EXIT_CODE -x " +
        "${remoteShellQuote(paths.owner)} " +
        "sh -c ${remoteShellQuote(childScript)}"
}

/** 贯穿每次远程探测、上传、切换、回滚和健康检查的内核租约。 */
internal class RemoteDeploymentLease private constructor(
    private val session: ManagedReadyProcess,
    private val commandGuard: RemoteDeploymentCommandGuard,
) : AutoCloseable {
    internal fun <T> withOperationsGuarded(action: () -> T): T =
        withRemoteDeploymentCommandGuard(commandGuard, action)

    override fun close() = session.close()

    companion object {
        fun acquire(
            host: String,
            user: String,
            port: Int,
            deployPath: String,
            startProcess: ProcessStarter = { it.start() },
        ): RemoteDeploymentLease {
            val paths = remoteDeploymentLockPaths(deployPath)
            val token = UUID.randomUUID().toString()
            val command = deploymentLeaseRemoteCommand(paths, token)
            val session = try {
                ManagedReadyProcess.start(
                    ProcessSpec(
                        label = "acquire exclusive TeamTalk deployment lease",
                        arguments = unguardedSshArguments(host, user, port, command),
                        timeoutMillis = DEPLOYMENT_LEASE_START_TIMEOUT_MILLIS,
                        outputMode = ProcessOutputMode.CAPTURE,
                    ),
                    readyMarker = DEPLOYMENT_LEASE_READY_MARKER,
                    startProcess = startProcess,
                )
            } catch (failure: ProcessExitException) {
                when (failure.exitCode) {
                    DEPLOYMENT_OWNER_CONFLICT_EXIT_CODE -> throw GradleException(
                        "Another TeamTalk deployment already holds the target lease",
                        failure,
                    )
                    DEPLOYMENT_DRAIN_CONFLICT_EXIT_CODE -> throw GradleException(
                        "A previous TeamTalk deployment operation is still finishing; retry later",
                        failure,
                    )
                    else -> throw failure
                }
            }
            val guard = RemoteDeploymentCommandGuard(
                host = host,
                user = user,
                port = port,
                ownerLockPath = paths.owner,
                operationLockPath = paths.operations,
                generationPath = paths.generation,
                generationToken = token,
                requireOwnerSession = session::requireRunning,
            )
            return RemoteDeploymentLease(session, guard)
        }
    }
}

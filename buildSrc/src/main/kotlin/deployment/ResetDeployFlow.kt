package deployment

/** 显式的破坏性预发布部署，使用空的服务端数据集。 */

import java.io.File
import java.util.Properties
import java.util.UUID
import org.gradle.api.GradleException

const val RESET_DEPLOY_CONFIRM_PROPERTY = "teamtalk.resetDeployConfirm"

internal fun resetDeploymentConfirmation(host: String, deployPath: String): String {
    require(host.matches(Regex("[A-Za-z0-9._-]+"))) { "Reset deployment host is malformed" }
    requireCanonicalDeployPath(deployPath)
    return "$host:$deployPath"
}

internal fun requireResetDeploymentConfirmation(
    suppliedConfirmation: String?,
    host: String,
    deployPath: String,
) {
    val expected = resetDeploymentConfirmation(host, deployPath)
    if (suppliedConfirmation != expected) {
        throw GradleException(
            "Destructive server reset was not confirmed for the exact target. " +
                "Rerun with -P$RESET_DEPLOY_CONFIRM_PROPERTY=$expected",
        )
    }
}

internal fun requireCompleteInstallationForReset(mode: DeploymentMode) {
    if (mode != DeploymentMode.UPGRADE) {
        throw GradleException(
            "Destructive server reset requires a complete existing TeamTalk installation; " +
                "first-deploy and partial targets are refused",
        )
    }
}

/** 在向其下暂存任何内容之前，拒绝符号链接或物理上不同的目标。 */
internal fun resetTargetIdentityCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    val dataPath = "$deployPath/data"
    return "test -d $deployPath && test ! -L $deployPath && " +
        "test \"\$(readlink -f -- $deployPath)\" = $deployPath && " +
        "test -d $dataPath && test ! -L $dataPath && " +
        "test \"\$(readlink -f -- $dataPath)\" = $dataPath && " +
        "test -f $dataPath/data-epoch && test -f $dataPath/dataset-id"
}

internal fun dockerComposeDownCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    return "cd $deployPath && set -a && . conf/env.sh && set +a && " +
        "export DB_PASSWORD=\"\$DATABASE_PASSWORD\" && " +
        "${dockerComposeCmd()} down --remove-orphans"
}

/**
 * 在唯一一次递归删除之前，立即重新校验确切的物理父目录。数据目录可能不存在，
 * 因此该命令在空数据集恢复期间可以安全地重复执行。
 */
internal fun clearAndRecreateDeploymentDataCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    val dataPath = "$deployPath/data"
    val directories = deploymentDataDirectories(deployPath).joinToString(" ")
    return "test -d $deployPath && test ! -L $deployPath && " +
        "test \"\$(readlink -f -- $deployPath)\" = $deployPath && " +
        "test ! -L $dataPath && " +
        "if test -e $dataPath; then test -d $dataPath && " +
        "test \"\$(readlink -f -- $dataPath)\" = $dataPath; fi && " +
        "rm -rf -- $dataPath && mkdir -p -- $directories && " +
        "test -d $dataPath && test ! -L $dataPath && " +
        "test \"\$(readlink -f -- $dataPath)\" = $dataPath"
}

private val resetTransactionIdPattern =
    Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

private fun requireResetTransactionPath(
    deployPath: String,
    transactionPath: String,
    kind: String,
): String {
    requireCanonicalDeployPath(deployPath)
    requireCanonicalDeployPath(transactionPath)
    val expectedPrefix = "$deployPath/.$kind-"
    require(
        transactionPath.startsWith(expectedPrefix) &&
            transactionPath.removePrefix(expectedPrefix).matches(resetTransactionIdPattern),
    ) { "Reset $kind path must be one UUID-named direct child of deployPath" }
    return transactionPath
}

internal fun snapshotResetRollbackCommand(deployPath: String, rollbackPath: String): String {
    requireResetTransactionPath(deployPath, rollbackPath, "rollback")
    return "mkdir -p $rollbackPath/root && " +
        "rsync -a --delete " +
        "--exclude='/.release-*' --exclude='/.rollback-*' " +
        "--exclude='/data/' --exclude='/logs/' --exclude='/static/downloads/' " +
        "$deployPath/ $rollbackPath/root/ && " +
        "cp -a /etc/systemd/system/teamtalk.service $rollbackPath/teamtalk.service"
}

internal fun publishResetReleaseCommand(
    deployPath: String,
    stagedPath: String,
): String {
    requireResetTransactionPath(deployPath, stagedPath, "release")
    return "rsync -a --delete " +
        "--exclude='/.release-*' --exclude='/.rollback-*' " +
        "--exclude='/data/' --exclude='/logs/' --exclude='/docker-compose.yml' " +
        "--exclude='/conf/ssl/' --exclude='/conf/env.sh' " +
        "--exclude='/static/downloads/' $stagedPath/ $deployPath/"
}

private fun restorePreviousReleaseCommand(deployPath: String, rollbackPath: String): String {
    requireResetTransactionPath(deployPath, rollbackPath, "rollback")
    return "test -d $rollbackPath/root && test -f $rollbackPath/teamtalk.service && " +
        "rsync -a --delete " +
        "--exclude='/.release-*' --exclude='/.rollback-*' " +
        "--exclude='/data/' --exclude='/logs/' --exclude='/static/downloads/' " +
        "$rollbackPath/root/ $deployPath/ && " +
        "cp -f $rollbackPath/teamtalk.service /etc/systemd/system/teamtalk.service"
}

private fun quiesceTeamTalkForResetRecovery(
    host: String,
    user: String,
    port: Int,
) {
    remoteChecked(
        "quiesce failed TeamTalk release before reset recovery",
        host,
        user,
        "systemctl stop teamtalk 2>/dev/null || true; " +
            "systemctl kill --kill-who=all --signal=TERM teamtalk 2>/dev/null || true; " +
            "test \"\$(systemctl show teamtalk -p MainPID --value)\" = '0' && " +
            "! systemctl is-active --quiet teamtalk",
        port,
        timeoutMillis = 300_000L,
        outputMode = ProcessOutputMode.DISCARD,
    )
}

private fun restorePreviousReleaseAndVerify(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
    rollbackPath: String,
    healthEndpoint: RemoteHealthEndpoint,
    previousBuildIdentity: String,
) {
    quiesceTeamTalkForResetRecovery(host, user, port)
    remoteChecked(
        "restore previous TeamTalk release and configuration",
        host,
        user,
        restorePreviousReleaseCommand(deployPath, rollbackPath),
        port,
        timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
        outputMode = ProcessOutputMode.DISCARD,
    )
    remoteChecked(
        "restart previous TeamTalk release",
        host,
        user,
        "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk",
        port,
        timeoutMillis = 300_000L,
        outputMode = ProcessOutputMode.DISCARD,
    )
    healthCheck(
        host = host,
        user = user,
        port = port,
        sslEnabled = healthEndpoint.sslEnabled,
        httpPort = healthEndpoint.httpPort,
        sslPort = healthEndpoint.sslPort,
        expectedBuildIdentity = previousBuildIdentity,
    )
}

private fun restorePreviousReleaseOnEmptyDataAndVerify(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
    rollbackPath: String,
    secrets: Properties,
    healthEndpoint: RemoteHealthEndpoint,
    previousBuildIdentity: String,
) {
    quiesceTeamTalkForResetRecovery(host, user, port)
    remoteChecked(
        "restore previous TeamTalk release and configuration for empty-data recovery",
        host,
        user,
        restorePreviousReleaseCommand(deployPath, rollbackPath),
        port,
        timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
        outputMode = ProcessOutputMode.DISCARD,
    )
    remoteChecked(
        "stop PostgreSQL before empty-data recovery",
        host,
        user,
        dockerComposeDownCommand(deployPath),
        port,
        timeoutMillis = 300_000L,
        outputMode = ProcessOutputMode.DISCARD,
    )
    remoteChecked(
        "reset TeamTalk data again for previous-release recovery",
        host,
        user,
        clearAndRecreateDeploymentDataCommand(deployPath),
        port,
        timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
        outputMode = ProcessOutputMode.DISCARD,
    )
    startPostgresAndWait(host, user, port, deployPath)
    ensureDbUser(host, user, port, deployPath, secrets.getProperty("DATABASE_PASSWORD"))
    remoteChecked(
        "start previous TeamTalk release on an empty dataset",
        host,
        user,
        "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk",
        port,
        timeoutMillis = 300_000L,
        outputMode = ProcessOutputMode.DISCARD,
    )
    healthCheck(
        host = host,
        user = user,
        port = port,
        sslEnabled = healthEndpoint.sslEnabled,
        httpPort = healthEndpoint.httpPort,
        sslPort = healthEndpoint.sslPort,
        expectedBuildIdentity = previousBuildIdentity,
    )
}

internal fun resetDeploymentRecoveredFailureMessage(dataResetStarted: Boolean): String =
    if (dataResetStarted) {
        "TeamTalk reset deployment failed; the previous release is healthy on newly empty data. " +
            "The original server data was destroyed and was not restored."
    } else {
        "TeamTalk reset deployment failed before data deletion; the previous release was " +
            "resumed and is healthy."
    }

internal fun deployResetUpgrade(
    distribution: File,
    host: String,
    user: String,
    deployPort: Int,
    deployPath: String,
    secrets: Properties,
    sslEnabled: Boolean,
    sslPort: String,
    preparedTlsKeystore: File?,
    httpPort: Int,
    tcpPort: String,
    expectedBuildIdentity: String,
    healthSslPort: Int,
) {
    requireCanonicalDeployPath(deployPath)
    requireActiveRemoteDeploymentGuard(host, user, deployPort)
    remoteChecked(
        "verify exact TeamTalk reset target",
        host,
        user,
        resetTargetIdentityCommand(deployPath),
        deployPort,
        outputMode = ProcessOutputMode.DISCARD,
    )
    val previousBuildIdentity = readRemoteServerBuildIdentity(host, user, deployPort, deployPath)
    val previousHealthEndpoint = readRemoteHealthEndpoint(host, user, deployPort, deployPath)
    val transactionId = UUID.randomUUID().toString()
    val stagedPath = "$deployPath/.release-$transactionId"
    val rollbackPath = "$deployPath/.rollback-$transactionId"
    var rollbackArmed = false
    var dataResetStarted = false
    var committed = false
    var recoveryVerified = false

    try {
        println("  Uploading and validating staged server distribution before interruption ...")
        remoteChecked(
            "create empty staged TeamTalk reset release",
            host,
            user,
            "mkdir $stagedPath",
            deployPort,
            outputMode = ProcessOutputMode.DISCARD,
        )
        uploadStagedServerDistribution(
            label = "upload staged TeamTalk reset release",
            distribution = distribution,
            host = host,
            user = user,
            port = deployPort,
            stagedPath = stagedPath,
        )
        requireRemoteStagedRelease(
            host = host,
            user = user,
            port = deployPort,
            stagedPath = stagedPath,
            expectedBuildIdentity = expectedBuildIdentity,
        )

        println("  Snapshotting the previous release and configuration (not server data) ...")
        remoteChecked(
            "snapshot TeamTalk release before destructive reset",
            host,
            user,
            snapshotResetRollbackCommand(deployPath, rollbackPath),
            deployPort,
            timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
            outputMode = ProcessOutputMode.DISCARD,
        )
        rollbackArmed = true

        stopTeamTalkUnitExactly(host, user, deployPort)
        println("  Stopping PostgreSQL ...")
        remoteChecked(
            "stop PostgreSQL before TeamTalk data reset",
            host,
            user,
            dockerComposeDownCommand(deployPath),
            deployPort,
            timeoutMillis = 300_000L,
            outputMode = ProcessOutputMode.DISCARD,
        )

        println("  Permanently clearing PostgreSQL and local TeamTalk stores ...")
        dataResetStarted = true
        remoteChecked(
            "clear and recreate exact TeamTalk data directory",
            host,
            user,
            clearAndRecreateDeploymentDataCommand(deployPath),
            deployPort,
            timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
            outputMode = ProcessOutputMode.DISCARD,
        )

        println("  Publishing staged server distribution ...")
        remoteChecked(
            "publish staged TeamTalk reset release",
            host,
            user,
            publishResetReleaseCommand(deployPath, stagedPath),
            deployPort,
            timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
            outputMode = ProcessOutputMode.DISCARD,
        )
        uploadEnvSh(
            generateEnvShContent(secrets, sslEnabled, sslPort, deployPath, httpPort, tcpPort),
            host,
            user,
            deployPort,
            deployPath,
        )
        if (sslEnabled && preparedTlsKeystore != null) {
            println("  Updating SSL certificate ...")
            uploadTlsKeystore(host, user, deployPort, deployPath, preparedTlsKeystore)
        }
        uploadDockerCompose(
            dockerComposeContent(deployPath),
            host,
            user,
            deployPort,
            deployPath,
        )
        startPostgresAndWait(host, user, deployPort, deployPath)
        ensureDbUser(host, user, deployPort, deployPath, secrets.getProperty("DATABASE_PASSWORD"))
        registerSystemd(host, user, deployPort, deployPath)
        println("  Starting TeamTalk Server on the new empty dataset ...")
        remoteChecked(
            "enable and start reset TeamTalk service",
            host,
            user,
            "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk",
            deployPort,
            timeoutMillis = 300_000L,
            outputMode = ProcessOutputMode.DISCARD,
        )
        healthCheck(
            host = host,
            user = user,
            port = deployPort,
            sslEnabled = sslEnabled,
            httpPort = httpPort,
            sslPort = healthSslPort,
            expectedBuildIdentity = expectedBuildIdentity,
        )
        committed = true
        println("  Destructive reset deployment committed on a newly empty dataset")
    } catch (failure: Exception) {
        if (!rollbackArmed) throw failure
        val recoveryFailure = runCatching {
            if (dataResetStarted) {
                println("  Reset deployment failed; trying the previous release on another empty dataset ...")
                restorePreviousReleaseOnEmptyDataAndVerify(
                    host = host,
                    user = user,
                    port = deployPort,
                    deployPath = deployPath,
                    rollbackPath = rollbackPath,
                    secrets = secrets,
                    healthEndpoint = previousHealthEndpoint,
                    previousBuildIdentity = previousBuildIdentity,
                )
            } else {
                println("  Reset deployment failed before data deletion; resuming the previous release ...")
                restorePreviousReleaseAndVerify(
                    host = host,
                    user = user,
                    port = deployPort,
                    deployPath = deployPath,
                    rollbackPath = rollbackPath,
                    healthEndpoint = previousHealthEndpoint,
                    previousBuildIdentity = previousBuildIdentity,
                )
            }
        }.exceptionOrNull()
        if (recoveryFailure == null) {
            recoveryVerified = true
            throw GradleException(
                resetDeploymentRecoveredFailureMessage(dataResetStarted),
                failure,
            )
        }
        failure.addSuppressed(recoveryFailure)
        throw GradleException(
            "TeamTalk reset deployment failed and recovery health could not be verified; " +
                "the release snapshot (which contains no server data) remains at $rollbackPath",
            failure,
        )
    } finally {
        remoteBestEffort(
            "remove staged TeamTalk reset release",
            host,
            user,
            "rm -rf -- $stagedPath",
            deployPort,
            timeoutMillis = 60_000L,
        )
        if (!rollbackArmed) {
            remoteBestEffort(
                "remove incomplete TeamTalk reset rollback snapshot",
                host,
                user,
                "rm -rf -- $rollbackPath",
                deployPort,
                timeoutMillis = 60_000L,
            )
        } else if (committed || recoveryVerified) {
            remoteBestEffort(
                "remove verified TeamTalk reset rollback snapshot",
                host,
                user,
                "rm -rf -- $rollbackPath",
                deployPort,
                timeoutMillis = 60_000L,
            )
        }
    }
}

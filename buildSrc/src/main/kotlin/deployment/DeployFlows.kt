package deployment

/** 首次部署与升级部署流程。所有关键外部命令均失败即停。 */

import java.io.File
import java.util.Properties
import java.util.UUID
import org.gradle.api.GradleException

internal fun uploadDockerCompose(
    content: String,
    host: String,
    user: String,
    port: Int,
    deployPath: String,
) {
    val localTemporary = File.createTempFile("teamtalk-dc-", ".yml")
    val remoteTemporary = "$deployPath/.docker-compose-${UUID.randomUUID()}.yml.tmp"
    try {
        localTemporary.writeText(content)
        remoteFileUploadChecked(
            label = "upload Docker Compose configuration",
            file = localTemporary,
            host = host,
            user = user,
            port = port,
            remotePath = remoteTemporary,
        )
        remoteChecked(
            "publish Docker Compose configuration",
            host,
            user,
            "chmod 600 $remoteTemporary && mv -f $remoteTemporary $deployPath/docker-compose.yml",
            port,
            outputMode = ProcessOutputMode.DISCARD,
        )
    } catch (failure: Exception) {
        remoteBestEffort(
            "remove unpublished Docker Compose configuration",
            host,
            user,
            "rm -f $remoteTemporary",
            port,
            timeoutMillis = 20_000L,
        )
        throw failure
    } finally {
        localTemporary.delete()
    }
}

internal fun dockerComposeContent(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    return """
services:
  postgres:
    image: postgres:16-alpine
    restart: always
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U teamtalk"]
      interval: 5s
      timeout: 3s
      retries: 10
    ports:
      - "127.0.0.1:5432:5432"
    environment:
      POSTGRES_USER: teamtalk
      POSTGRES_PASSWORD: ${'$'}{DB_PASSWORD}
      POSTGRES_DB: teamtalk
    volumes:
      - $deployPath/data/pgdata:/var/lib/postgresql/data
    """.trimIndent()
}

internal fun deploymentDataDirectories(deployPath: String): List<String> {
    requireCanonicalDeployPath(deployPath)
    val dataPath = "$deployPath/data"
    return listOf(
        "$dataPath/pgdata",
        "$dataPath/rocksdb",
        "$dataPath/lucene-index",
        "$dataPath/client-telemetry-index",
        "$dataPath/file-store/rocksdb",
        "$dataPath/file-store/files",
        "$dataPath/file-store/tmp",
        "$dataPath/logs",
    )
}

internal fun createDeploymentDirectoriesCommand(deployPath: String): String {
    val directories = deploymentDataDirectories(deployPath) + listOf(
        "$deployPath/conf/ssl",
        "$deployPath/conf",
        "$deployPath/static/downloads",
    )
    return "mkdir -p -- ${directories.joinToString(" ")}"
}

/**
 * `pg_isready` 用 1 表示拒绝、2 表示无响应、3 表示无法发起探测。
 * 在新初始化的容器仍在启动期间，这三种情况都是正常的，因此有界的就绪循环必须对它们
 * 重试，而不是把第一次的瞬时状态误判为 SSH 失败。
 */
internal val POSTGRES_READINESS_EXIT_CODES: Set<Int> = setOf(0, 1, 2, 3)

internal fun startPostgresAndWait(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
) {
    requireCanonicalDeployPath(deployPath)
    requireActiveRemoteDeploymentGuard(host, user, port)
    println("  Starting PostgreSQL ...")
    remoteChecked(
        "start PostgreSQL container",
        host,
        user,
        "cd $deployPath && set -a && . conf/env.sh && set +a && " +
            "export DB_PASSWORD=\"\$DATABASE_PASSWORD\" && ${dockerComposeCmd()} up -d",
        port,
        timeoutMillis = 300_000L,
    )

    val readinessCommand =
        "cd $deployPath && set -a && . conf/env.sh && set +a && " +
            "export DB_PASSWORD=\"\$DATABASE_PASSWORD\" && " +
            "${dockerComposeCmd()} exec -T postgres pg_isready -U teamtalk >/dev/null 2>&1"
    print("  Waiting for PostgreSQL ...")
    var retries = 0
    while (retries < 30) {
        val ready = remoteProbe(
            "probe PostgreSQL readiness",
            host,
            user,
            readinessCommand,
            port,
            allowedExitCodes = POSTGRES_READINESS_EXIT_CODES,
            timeoutMillis = 20_000L,
        )
        if (ready) {
            println(" OK")
            return
        }
        print(".")
        Thread.sleep(2_000L)
        retries++
    }
    throw GradleException("PostgreSQL startup timeout")
}

fun deployNew(
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
) {
    requireCanonicalDeployPath(deployPath)
    requireActiveRemoteDeploymentGuard(host, user, deployPort)
    if (sslEnabled && preparedTlsKeystore == null) {
        throw GradleException("HTTPS first deployment requires a prepared TLS keystore")
    }
    remoteChecked(
        "create TeamTalk deployment directories",
        host,
        user,
        createDeploymentDirectoriesCommand(deployPath),
        deployPort,
    )

    println("  Uploading server distribution ...")
    localChecked(
        "upload server distribution",
        buildList {
            addAll(
                listOf(
                    "rsync", "-avz", "--no-owner", "--no-group", "--delete",
                    "--exclude=data", "--exclude=logs", "--exclude=env.sh",
                    "--exclude=docker-compose.yml", "--exclude=conf/ssl",
                    "--exclude=conf/env.sh",
                ),
            )
            addAll(remoteRsyncTransportArguments(host, user, deployPort))
            add("${distribution.absolutePath}/")
            add("$user@$host:$deployPath/")
        },
        timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
    )

    println("  Generating env.sh ...")
    uploadEnvSh(
        generateEnvShContent(secrets, sslEnabled, sslPort, deployPath, httpPort, tcpPort),
        host,
        user,
        deployPort,
        deployPath,
    )

    if (sslEnabled) {
        uploadTlsKeystore(
            host,
            user,
            deployPort,
            deployPath,
            requireNotNull(preparedTlsKeystore),
        )
    }

    println("  Configuring Docker infrastructure ...")
    uploadDockerCompose(dockerComposeContent(deployPath), host, user, deployPort, deployPath)

    startPostgresAndWait(host, user, deployPort, deployPath)

    ensureDbUser(host, user, deployPort, deployPath, secrets.getProperty("DATABASE_PASSWORD"))
    println("  Registering systemd service ...")
    registerSystemd(host, user, deployPort, deployPath)
    println("  Starting TeamTalk Server ...")
    remoteChecked(
        "enable and start TeamTalk systemd service",
        host,
        user,
        "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk",
        deployPort,
        timeoutMillis = 300_000L,
    )
    println("  First-deploy changes applied; awaiting strict health verification")
}

fun deployUpgrade(
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
    requiredEpoch: Int,
    expectedBuildIdentity: String,
    healthSslPort: Int,
    protocolWindow: ServerProtocolWindow,
) {
    requireCanonicalDeployPath(deployPath)
    requireActiveRemoteDeploymentGuard(host, user, deployPort)
    println("  Checking schema/data epoch compatibility ...")
    preflightUpgradeEpoch(host, user, deployPort, deployPath, requiredEpoch)
    val previousBuildIdentity = readRemoteServerBuildIdentity(host, user, deployPort, deployPath)
    val previousHealthEndpoint = readRemoteHealthEndpoint(host, user, deployPort, deployPath)
    val minimumProtocolMinor = readRemoteMinimumProtocolMinor(host, user, deployPort, deployPath, protocolWindow)
    val upgradedEnv = generateEnvShContent(
        secrets, sslEnabled, sslPort, deployPath, httpPort, tcpPort, minimumProtocolMinor,
    )
    val transactionId = UUID.randomUUID().toString()
    val stagedPath = "$deployPath/.release-$transactionId"
    val rollbackPath = "$deployPath/.rollback-$transactionId"
    var rollbackArmed = false
    var committed = false

    try {
        println("  Uploading and validating staged server distribution ...")
        remoteChecked(
            "create empty staged TeamTalk release",
            host,
            user,
            "mkdir $stagedPath",
            deployPort,
            outputMode = ProcessOutputMode.DISCARD,
        )
        uploadStagedServerDistribution(
            label = "upload staged server distribution",
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

        println("  Snapshotting the rollback boundary ...")
        remoteChecked(
            "snapshot current TeamTalk release and configuration",
            host,
            user,
            "mkdir -p $rollbackPath/root && " +
                "rsync -a --delete " +
                "--exclude='/.release-*' --exclude='/.rollback-*' " +
                "--exclude='/data/' --exclude='/logs/' --exclude='/static/downloads/' " +
                "$deployPath/ $rollbackPath/root/ && " +
                "cp -a /etc/systemd/system/teamtalk.service $rollbackPath/teamtalk.service",
            deployPort,
            timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
            outputMode = ProcessOutputMode.DISCARD,
        )
        rollbackArmed = true

        stopTeamTalkUnitExactly(host, user, deployPort)

        println("  Publishing staged server distribution ...")
        remoteChecked(
            "publish staged TeamTalk release",
            host,
            user,
            "rsync -a --delete " +
                "--exclude='/.release-*' --exclude='/.rollback-*' " +
                "--exclude='/data/' --exclude='/logs/' --exclude='/docker-compose.yml' " +
                "--exclude='/conf/ssl/' --exclude='/conf/env.sh' " +
                "--exclude='/static/downloads/' $stagedPath/ $deployPath/",
            deployPort,
            timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
            outputMode = ProcessOutputMode.DISCARD,
        )

        println("  Syncing port configuration ...")
        uploadEnvSh(
            upgradedEnv,
            host,
            user,
            deployPort,
            deployPath,
        )
        ensureDbUser(host, user, deployPort, deployPath, secrets.getProperty("DATABASE_PASSWORD"))

        if (sslEnabled && preparedTlsKeystore != null) {
            println("  Updating SSL certificate ...")
            uploadTlsKeystore(host, user, deployPort, deployPath, preparedTlsKeystore)
        }

        println("  Re-registering systemd service ...")
        registerSystemd(host, user, deployPort, deployPath)
        println("  Starting TeamTalk Server ...")
        remoteChecked(
            "enable and start upgraded TeamTalk service",
            host,
            user,
            "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk",
            deployPort,
            timeoutMillis = 300_000L,
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
        println("  Upgrade transaction committed after strict health verification")
    } catch (failure: Exception) {
        if (!rollbackArmed) throw failure
        val rollbackFailure = runCatching {
            rollbackUpgrade(
                host = host,
                user = user,
                port = deployPort,
                deployPath = deployPath,
                rollbackPath = rollbackPath,
                healthEndpoint = previousHealthEndpoint,
                previousBuildIdentity = previousBuildIdentity,
            )
        }.exceptionOrNull()
        if (rollbackFailure == null) {
            throw GradleException(
                "TeamTalk upgrade failed; the previous release was restored and is healthy",
                failure,
            )
        }
        failure.addSuppressed(rollbackFailure)
        throw GradleException(
            "TeamTalk upgrade failed and rollback could not be verified; " +
                "rollback snapshot retained at $rollbackPath",
            failure,
        )
    } finally {
        remoteBestEffort(
            "remove staged TeamTalk release",
            host,
            user,
            "rm -rf $stagedPath",
            deployPort,
            timeoutMillis = 60_000L,
        )
        if (!rollbackArmed) {
            remoteBestEffort(
                "remove incomplete TeamTalk rollback snapshot",
                host,
                user,
                "rm -rf $rollbackPath",
                deployPort,
                timeoutMillis = 60_000L,
            )
        }
        if (committed) {
            remoteBestEffort(
                "remove committed TeamTalk rollback snapshot",
                host,
                user,
                "rm -rf $rollbackPath",
                deployPort,
                timeoutMillis = 60_000L,
            )
        }
    }
}

internal fun readRemoteServerBuildIdentity(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
): String {
    val identity = remoteCaptureChecked(
        "read current TeamTalk build identity",
        host,
        user,
        "if test -f $deployPath/current/$RELEASE_ARTIFACT_MANIFEST_FILE; then " +
            "sed -n 's/^buildIdentity=//p' $deployPath/current/$RELEASE_ARTIFACT_MANIFEST_FILE; " +
            "else sed -n 's/^buildIdentity=//p' $deployPath/$RELEASE_ARTIFACT_MANIFEST_FILE; fi",
        port,
    ).trim()
    if (!identity.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,199}"))) {
        throw GradleException("Current TeamTalk build identity is missing or malformed")
    }
    return identity
}

internal fun requireRemoteStagedRelease(
    host: String,
    user: String,
    port: Int,
    stagedPath: String,
    expectedBuildIdentity: String,
) {
    require(expectedBuildIdentity.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,199}"))) {
        "Expected build identity is unsafe for remote validation"
    }
    remoteChecked(
        "validate staged TeamTalk release",
        host,
        user,
        "test -x $stagedPath/bin/teamtalk.sh && test -s $stagedPath/lib/server.jar && " +
            "test \"\$(sed -n 's/^buildIdentity=//p' " +
            "$stagedPath/$RELEASE_ARTIFACT_MANIFEST_FILE)\" = '$expectedBuildIdentity'",
        port,
        outputMode = ProcessOutputMode.DISCARD,
    )
}

internal fun stopTeamTalkUnitExactly(host: String, user: String, port: Int) {
    println("  Stopping TeamTalk Server ...")
    remoteChecked(
        "stop TeamTalk systemd unit and verify its cgroup is quiescent",
        host,
        user,
        "systemctl stop teamtalk && " +
            "{ systemctl kill --kill-who=all --signal=TERM teamtalk 2>/dev/null || " +
            "test \"\$(systemctl show teamtalk -p MainPID --value)\" = '0'; } && " +
            "test \"\$(systemctl show teamtalk -p MainPID --value)\" = '0' && " +
            "! systemctl is-active --quiet teamtalk",
        port,
        timeoutMillis = 300_000L,
        outputMode = ProcessOutputMode.DISCARD,
    )
}

private fun rollbackUpgrade(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
    rollbackPath: String,
    healthEndpoint: RemoteHealthEndpoint,
    previousBuildIdentity: String,
) {
    println("  Upgrade failed; restoring the previous TeamTalk release ...")
    remoteChecked(
        "stop failed TeamTalk release before rollback",
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
    remoteChecked(
        "restore previous TeamTalk release and configuration",
        host,
        user,
        "test -d $rollbackPath/root && test -f $rollbackPath/teamtalk.service && " +
            "rsync -a --delete " +
            "--exclude='/.release-*' --exclude='/.rollback-*' " +
            "--exclude='/data/' --exclude='/logs/' --exclude='/static/downloads/' " +
            "$rollbackPath/root/ $deployPath/ && " +
            "cp -f $rollbackPath/teamtalk.service /etc/systemd/system/teamtalk.service && " +
            "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk",
        port,
        timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
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
    remoteBestEffort(
        "remove verified TeamTalk rollback snapshot",
        host,
        user,
        "rm -rf $rollbackPath",
        port,
        timeoutMillis = 60_000L,
    )
}

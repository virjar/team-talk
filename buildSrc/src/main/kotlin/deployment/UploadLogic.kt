package deployment

/** 客户端产物上传与服务端部署入口。 */

import java.io.File
import java.util.UUID
import org.gradle.api.GradleException

internal fun requireArtifact(file: File?, description: String): File {
    if (file == null || !file.isFile || file.length() <= 0L) {
        throw GradleException("Required artifact is missing or empty: $description")
    }
    return file
}

internal fun requireSingleArtifact(
    directory: File,
    description: String,
    predicate: (File) -> Boolean,
): File {
    if (!directory.isDirectory) {
        throw GradleException("Required artifact directory is missing: $description ($directory)")
    }
    val matches = directory.walkTopDown()
        .filter { it.isFile && predicate(it) }
        .sortedBy(File::getAbsolutePath)
        .toList()
    if (matches.size != 1) {
        throw GradleException(
            "Required artifact selection for $description expected exactly one file, " +
                "found ${matches.size}",
        )
    }
    return requireArtifact(matches.single(), description)
}

fun deployServer(
    rootDir: File,
    serverDistribution: File,
    config: DeploymentConfig,
    sslCert: String?,
    sslKey: String?,
    expectedVersion: String,
    expectedBuildIdentity: String,
) {
    val artifactIdentity = requireReleaseArtifact(
        artifactDirectory = serverDistribution,
        expectedArtifactType = "server-distribution",
        expectedVersion = expectedVersion,
        expectedBuildIdentity = expectedBuildIdentity,
    )
    val sslEnabled = config.sslEnabled
    val localTlsPemFiles = validateLocalTlsPemFiles(rootDir, sslCert, sslKey)
    if (!sslEnabled) requireTlsPemFilesForDeployment(false, false, localTlsPemFiles)
    val host = config.deployHost
    val user = config.deployUser
    val deployPort = config.deployPort
    val deployPath = config.deployPath
    val sslPort = config.sslPort.toString()
    val tcpPort = config.tcpPort.toString()
    val httpPort = if (sslEnabled) 8080 else extractHttpPort(config.serverUrl)

    println("")
    println("=== TeamTalk Server Deploy ===")
    println("  Target: $user@$host:$deployPort")
    println("  Path:   $deployPath")
    println("  HTTP:   port $httpPort")
    println("  TCP:    port $tcpPort")
    println("  SSL:    ${if (sslEnabled) "enabled (port $sslPort)" else "disabled"}")
    println("  Build:  ${artifactIdentity.buildIdentity}")
    println("")

    val deploymentLease = RemoteDeploymentLease.acquire(host, user, deployPort, deployPath)
    var preparedTlsKeystore: File? = null
    try {
        deploymentLease.withOperationsGuarded {
            val deploymentMode = readRemoteDeploymentMode(host, user, deployPort, deployPath)
            val isFirstDeploy = deploymentMode == DeploymentMode.FIRST_DEPLOY
            requireTlsPemFilesForDeployment(sslEnabled, isFirstDeploy, localTlsPemFiles)

            val secretsFile = File(rootDir, "gradle/deployment.secrets")
            val secrets = if (isFirstDeploy) {
                loadOrGenerateFirstDeploymentSecrets(secretsFile)
            } else {
                loadRequiredUpgradeSecretsFromRemote(
                    secretsFile,
                    host,
                    user,
                    deployPort,
                    deployPath,
                )
            }
            if (sslEnabled) requireCompatibleTlsPasswords(secrets)
            if (sslEnabled && !isFirstDeploy && localTlsPemFiles == null) {
                preflightRetainedTlsKeystore(host, user, deployPort, deployPath)
            }
            preparedTlsKeystore = if (sslEnabled) {
                localTlsPemFiles?.let { prepareTlsKeystore(it, secrets) }
            } else {
                null
            }

            if (isFirstDeploy) {
                println("=== First Deploy ===")
                deployNew(
                    serverDistribution, host, user, deployPort, deployPath, secrets, sslEnabled,
                    sslPort, preparedTlsKeystore, httpPort, tcpPort,
                )
            } else {
                println("=== Upgrade ===")
                deployUpgrade(
                    serverDistribution, host, user, deployPort, deployPath, secrets, sslEnabled,
                    sslPort, preparedTlsKeystore, httpPort, tcpPort, readServerDataEpoch(rootDir),
                    artifactIdentity.buildIdentity, config.sslPort,
                    checkNotNull(artifactIdentity.serverProtocol),
                )
            }
            if (isFirstDeploy) {
                healthCheck(
                    host,
                    user,
                    deployPort,
                    sslEnabled,
                    httpPort,
                    config.sslPort,
                    artifactIdentity.buildIdentity,
                )
            }
            println("========================================")
            println("       TeamTalk Deploy Complete!")
            println("========================================")
        }
    } finally {
        preparedTlsKeystore?.let { temporary ->
            if (!temporary.delete() && temporary.exists()) {
                println("  WARNING: could not delete local temporary TLS keystore")
            }
        }
        deploymentLease.close()
    }
}

/**
 * 在有意清空重建 PostgreSQL 以及所有本地持久化存储后，部署一个新的服务端发布版本。
 * 该入口与 deployServer 分离，使常规的 epoch 门禁保持失败即停，
 * 缺失的属性永远不会悄悄选择破坏性行为。
 */
fun deployServerResetData(
    rootDir: File,
    serverDistribution: File,
    config: DeploymentConfig,
    sslCert: String?,
    sslKey: String?,
    expectedVersion: String,
    expectedBuildIdentity: String,
    resetConfirmation: String?,
) {
    requireResetDeploymentConfirmation(
        suppliedConfirmation = resetConfirmation,
        host = config.deployHost,
        deployPath = config.deployPath,
    )
    val artifactIdentity = requireReleaseArtifact(
        artifactDirectory = serverDistribution,
        expectedArtifactType = "server-distribution",
        expectedVersion = expectedVersion,
        expectedBuildIdentity = expectedBuildIdentity,
    )
    val sslEnabled = config.sslEnabled
    val localTlsPemFiles = validateLocalTlsPemFiles(rootDir, sslCert, sslKey)
    if (!sslEnabled) requireTlsPemFilesForDeployment(false, false, localTlsPemFiles)
    val host = config.deployHost
    val user = config.deployUser
    val deployPort = config.deployPort
    val deployPath = config.deployPath
    val sslPort = config.sslPort.toString()
    val tcpPort = config.tcpPort.toString()
    val httpPort = if (sslEnabled) 8080 else extractHttpPort(config.serverUrl)

    println("")
    println("=== TeamTalk Server Destructive Reset Deploy ===")
    println("  Confirmed target: ${resetDeploymentConfirmation(host, deployPath)}")
    println("  SSH:              $user@$host:$deployPort")
    println("  HTTP:             port $httpPort")
    println("  TCP:              port $tcpPort")
    println("  SSL:              ${if (sslEnabled) "enabled (port $sslPort)" else "disabled"}")
    println("  Build:            ${artifactIdentity.buildIdentity}")
    println("  DATA LOSS:        $deployPath/data will be permanently rebuilt from empty")
    println("")

    val deploymentLease = RemoteDeploymentLease.acquire(host, user, deployPort, deployPath)
    var preparedTlsKeystore: File? = null
    try {
        deploymentLease.withOperationsGuarded {
            requireCompleteInstallationForReset(
                readRemoteDeploymentMode(host, user, deployPort, deployPath),
            )
            requireTlsPemFilesForDeployment(sslEnabled, false, localTlsPemFiles)

            val secrets = loadRequiredUpgradeSecretsFromRemote(
                File(rootDir, "gradle/deployment.secrets"),
                host,
                user,
                deployPort,
                deployPath,
            )
            if (sslEnabled) requireCompatibleTlsPasswords(secrets)
            if (sslEnabled && localTlsPemFiles == null) {
                preflightRetainedTlsKeystore(host, user, deployPort, deployPath)
            }
            preparedTlsKeystore = if (sslEnabled) {
                localTlsPemFiles?.let { prepareTlsKeystore(it, secrets) }
            } else {
                null
            }

            deployResetUpgrade(
                distribution = serverDistribution,
                host = host,
                user = user,
                deployPort = deployPort,
                deployPath = deployPath,
                secrets = secrets,
                sslEnabled = sslEnabled,
                sslPort = sslPort,
                preparedTlsKeystore = preparedTlsKeystore,
                httpPort = httpPort,
                tcpPort = tcpPort,
                expectedBuildIdentity = artifactIdentity.buildIdentity,
                healthSslPort = config.sslPort,
            )
            println("================================================")
            println(" TeamTalk Reset Deploy Complete (Empty Dataset)")
            println("================================================")
        }
    } finally {
        preparedTlsKeystore?.let { temporary ->
            if (!temporary.delete() && temporary.exists()) {
                println("  WARNING: could not delete local temporary TLS keystore")
            }
        }
        deploymentLease.close()
    }
}

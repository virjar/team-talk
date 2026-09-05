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

private fun uploadFile(
    file: File,
    remoteName: String,
    host: String,
    user: String,
    port: Int,
    remoteDir: String,
) {
    requireArtifact(file, remoteName)
    val remoteTemporary = "$remoteDir/.$remoteName-${UUID.randomUUID()}.tmp"
    try {
        localChecked(
            "upload $remoteName",
            listOf(
                "scp", "-P", port.toString(),
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "-o", "StrictHostKeyChecking=accept-new",
                file.absolutePath,
                "$user@$host:$remoteTemporary",
            ),
            timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
            outputMode = ProcessOutputMode.DISCARD,
        )
        remoteChecked(
            "publish $remoteName atomically",
            host,
            user,
            "chmod 644 $remoteTemporary && mv -f $remoteTemporary $remoteDir/$remoteName",
            port,
            outputMode = ProcessOutputMode.DISCARD,
        )
    } catch (failure: Exception) {
        remoteBestEffort(
            "remove unpublished $remoteName",
            host,
            user,
            "rm -f $remoteTemporary",
            port,
            timeoutMillis = 20_000L,
        )
        throw failure
    }
}

fun uploadAndroidApk(
    rootDir: File,
    config: DeploymentConfig,
    stagingDir: File? = null,
) {
    val host = config.deployHost
    val user = config.deployUser
    val port = config.deployPort
    val remoteDir = "${config.deployPath}/static/downloads"
    println("Uploading APK to $user@$host:$remoteDir ...")

    val apk = if (stagingDir != null) {
        requireSingleArtifact(
            File(stagingDir, "teamtalk-android"),
            "staged TeamTalk Android APK",
        ) { file -> file.extension == "apk" || file.name.endsWith("-release.apk") }
    } else {
        requireSingleArtifact(
            File(rootDir, "client/android/build/outputs/apk/release"),
            "locally built TeamTalk Android release APK",
        ) { file -> file.name.endsWith("-release.apk") }
    }
    remoteChecked(
        "create Android download directory",
        host,
        user,
        "mkdir -p $remoteDir",
        port,
        outputMode = ProcessOutputMode.DISCARD,
    )
    println("  Uploading ${apk.name} as TeamTalk-android.apk ...")
    uploadFile(apk, "TeamTalk-android.apk", host, user, port, remoteDir)
    println("APK upload complete (1 required artifact). Download page: ${config.serverUrl}")
}

/** 运行 Conveyor 构建三平台更新站点；全过程有 20 分钟总超时。 */
fun buildDesktopSite(rootDir: File) {
    val desktopDir = File(rootDir, "client/desktop")
    if (!desktopDir.isDirectory) throw GradleException("Desktop project directory not found: $desktopDir")
    val result = localChecked(
        "build Conveyor desktop update site",
        listOf(findConveyorExecutable(), "make", "site", "--overwrite"),
        timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
        outputMode = ProcessOutputMode.CAPTURE,
        workingDirectory = desktopDir,
        environment = mapOf("CONVEYOR_AGREE_TO_LICENSE" to "1"),
    )
    result.output.lines()
        .filter { it.contains("error", ignoreCase = true) || it.contains("Done") }
        .take(20)
        .forEach { println("  [conveyor] $it") }
}

private fun findConveyorExecutable(): String {
    System.getenv("PATH")?.split(File.pathSeparator)?.forEach { directory ->
        if (directory.isNotBlank()) {
            val candidate = File(directory, "conveyor")
            if (candidate.canExecute()) return candidate.absolutePath
        }
    }
    val fallbacks = listOf(
        File("/usr/local/bin/conveyor"),
        File("/opt/homebrew/bin/conveyor"),
        File(System.getProperty("user.home"), ".conveyor/bin/conveyor"),
    )
    fallbacks.firstOrNull(File::canExecute)?.let { return it.absolutePath }
    throw GradleException(
        "conveyor executable not found in PATH or known locations. " +
            "Install: https://conveyor.hydraulic.dev/download/ or export PATH before running gradle.",
    )
}

fun uploadDesktopSite(
    siteDir: File,
    config: DeploymentConfig,
    expectedVersion: String,
    expectedBuildIdentity: String,
) {
    val artifactIdentity = requireReleaseArtifact(
        artifactDirectory = siteDir,
        expectedArtifactType = "desktop-site",
        expectedVersion = expectedVersion,
        expectedBuildIdentity = expectedBuildIdentity,
    )
    val files = if (siteDir.isDirectory) {
        siteDir.walkTopDown().filter(File::isFile).toList()
    } else {
        emptyList()
    }
    if (files.isEmpty() || files.none { it.length() > 0L }) {
        throw GradleException(
            "client/desktop/output has no uploadable artifacts. Run: cd client/desktop && conveyor make site " +
                "(see client/desktop/conveyor.conf)",
        )
    }
    val host = config.deployHost
    val user = config.deployUser
    val port = config.deployPort
    val remoteDir = "${config.deployPath}/static/downloads/desktop"
    println("Uploading Conveyor site to $user@$host:$remoteDir ...")
    remoteChecked(
        "create desktop update-site directory",
        host,
        user,
        "mkdir -p $remoteDir",
        port,
        outputMode = ProcessOutputMode.DISCARD,
    )
    localChecked(
        "upload desktop update site",
        listOf(
            "rsync", "-avz", "--delete",
            "-e", "ssh -p $port -o BatchMode=yes -o ConnectTimeout=10 " +
                "-o StrictHostKeyChecking=accept-new",
            "${siteDir.absolutePath}/", "$user@$host:$remoteDir/",
        ),
        timeoutMillis = LONG_PROCESS_TIMEOUT_MILLIS,
        outputMode = ProcessOutputMode.DISCARD,
    )
    println(
        "Update site uploaded (${files.size} files, build=${artifactIdentity.buildIdentity}): " +
            "${config.serverUrl}/downloads/desktop",
    )
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

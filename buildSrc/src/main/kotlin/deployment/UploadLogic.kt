package deployment

/**
 * 客户端产物上传逻辑。
 *
 * 不依赖 Project 上下文，通过参数传入所有依赖。
 */

import java.io.File
import org.gradle.api.GradleException
import kotlin.collections.iterator

/**
 * 执行上传：将 desktop 和 android 产物上传到远程服务器的 static/downloads 目录。
 */
fun uploadArtifacts(
    rootDir: File,
    config: DeploymentConfig,
    stagingDir: File? = null
) {
    val host = config.deployHost
    val user = config.deployUser
    val port = config.deployPort
    val path = config.deployPath

    val remoteDir = "$path/static/downloads"
    println("Uploading to $user@$host:$remoteDir ...")

    remoteExec(host, user, "mkdir -p $remoteDir", port)

    if (stagingDir != null && stagingDir.exists()) {
        uploadFromStaging(stagingDir, host, user, port, remoteDir)
    } else {
        uploadFromBuildDir(rootDir, host, user, port, remoteDir)
    }

    println("Upload complete. Download page: ${config.serverUrl}")
}

private fun uploadFromStaging(
    stagingDir: File,
    host: String,
    user: String,
    port: Int,
    remoteDir: String
) {
    val artifacts = mapOf(
        "teamtalk-desktop-linux" to "TeamTalk-linux.deb",
        "teamtalk-desktop-windows" to "TeamTalk-windows.msi",
        "teamtalk-desktop-macos-arm64" to "TeamTalk-macos-arm64.dmg",
        "teamtalk-desktop-macos-x86_64" to "TeamTalk-macos-x86_64.dmg",
        "teamtalk-android" to "TeamTalk-android.apk"
    )

    for ((dirName, remoteName) in artifacts) {
        val dir = File(stagingDir, dirName)
        if (!dir.exists()) {
            println("  Skipping $remoteName (${dirName} not found)")
            continue
        }

        val ext = remoteName.substringAfterLast(".")
        val file = dir.walkTopDown()
            .filter { it.isFile && (it.extension == ext || it.name.endsWith("-release.apk")) }
            .firstOrNull()

        if (file != null) {
            println("  Uploading ${file.name} as $remoteName ...")
            localExecSilent("scp", "-P", port.toString(), file.absolutePath, "$user@$host:$remoteDir/$remoteName")
        } else {
            println("  Skipping $remoteName (no matching file in $dirName)")
        }
    }
}

private fun uploadFromBuildDir(
    rootDir: File,
    host: String,
    user: String,
    port: Int,
    remoteDir: String
) {
    val desktopRename = mapOf(
        "deb" to "TeamTalk-linux.deb",
        "msi" to "TeamTalk-windows.msi"
    )
    val desktopDir = File(rootDir, "desktop/build/compose/binaries/main-release")
    if (desktopDir.exists()) {
        desktopDir.walkTopDown()
            .filter { it.isFile && (it.extension in desktopRename.keys) }
            .forEach { pkg ->
                val remoteName = desktopRename[pkg.extension] ?: pkg.name
                println("  Uploading ${pkg.name} as $remoteName ...")
                localExecSilent("scp", "-P", port.toString(), pkg.absolutePath, "$user@$host:$remoteDir/$remoteName")
            }

        val dmgs = desktopDir.walkTopDown().filter { it.isFile && it.extension == "dmg" }.toList()
        for (dmg in dmgs) {
            val arch = when {
                System.getProperty("os.arch").contains("aarch64") -> "arm64"
                else -> "x86_64"
            }
            val remoteName = "TeamTalk-macos-$arch.dmg"
            println("  Uploading ${dmg.name} as $remoteName ...")
            localExecSilent("scp", "-P", port.toString(), dmg.absolutePath, "$user@$host:$remoteDir/$remoteName")
        }
    }

    val apkDir = File(rootDir, "android/build/outputs/apk/release")
    if (apkDir.exists()) {
        val apk = apkDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("-release.apk") }
            .firstOrNull()
        if (apk != null) {
            println("  Uploading ${apk.name} ...")
            localExecSilent("scp", "-P", port.toString(), apk.absolutePath, "$user@$host:$remoteDir/TeamTalk-android.apk")
        }
    }
}

/**
 * 执行服务端部署：首次部署或升级。
 */
fun deployServer(
    rootDir: File,
    config: DeploymentConfig,
    sslCert: String?,
    sslKey: String?
) {
    val host = config.deployHost
    val user = config.deployUser
    val deployPort = config.deployPort
    val deployPath = config.deployPath
    val sslEnabled = config.sslEnabled
    val sslPort = config.sslPort.toString()
    val tcpPort = config.tcpPort.toString()

    val url = config.serverUrl
    val httpPort = if (sslEnabled) 8080 else extractHttpPort(url)

    println("")
    println("=== TeamTalk Server Deploy ===")
    println("  Target: $user@$host:$deployPort")
    println("  Path:   $deployPath")
    println("  HTTP:   port $httpPort")
    println("  TCP:    port $tcpPort")
    println("  SSL:    ${if (sslEnabled) "enabled (port $sslPort)" else "disabled"}")
    println("")

    val isFirstDeploy = !remoteCheck(host, user, "test -d $deployPath/bin", deployPort)

    val secretsFile = File(rootDir, "gradle/deployment.secrets")
    val secrets = if (isFirstDeploy) {
        loadOrGenerateSecrets(secretsFile, host, user, deployPort, deployPath)
    } else {
        extractSecretsFromRemote(secretsFile, host, user, deployPort, deployPath)
            ?: throw GradleException(
                "Cannot extract secrets from remote env.sh. " +
                    "Check if $deployPath/conf/env.sh exists on the server."
            )
    }

    if (isFirstDeploy) {
        println("=== First Deploy ===")
        deployNew(
            rootDir,
            host,
            user,
            deployPort,
            deployPath,
            secrets,
            sslEnabled,
            sslPort,
            sslCert,
            sslKey,
            httpPort,
            tcpPort,
        )
    } else {
        println("=== Upgrade ===")
        deployUpgrade(
            rootDir,
            host,
            user,
            deployPort,
            deployPath,
            secrets,
            sslEnabled,
            sslPort,
            sslCert,
            sslKey,
            httpPort,
            tcpPort,
        )
    }

    healthCheck(host, user, deployPort, sslEnabled, httpPort, config.sslPort)
}

package profiles

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
    demo: DemoConfig,
    stagingDir: File? = null
) {
    val host = demo.deployHost
    val user = demo.deployUser
    val path = demo.deployPath

    val remoteDir = "$path/static/downloads"
    println("Uploading to $user@$host:$remoteDir ...")

    remoteExec(host, user, "mkdir -p $remoteDir")

    if (stagingDir != null && stagingDir.exists()) {
        uploadFromStaging(stagingDir, host, user, remoteDir)
    } else {
        uploadFromBuildDir(rootDir, host, user, remoteDir)
    }

    println("Upload complete. Download page: https://$host/")
}

private fun uploadFromStaging(
    stagingDir: File,
    host: String,
    user: String,
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
            localExecSilent("scp", file.absolutePath, "$user@$host:$remoteDir/$remoteName")
        } else {
            println("  Skipping $remoteName (no matching file in $dirName)")
        }
    }
}

private fun uploadFromBuildDir(
    rootDir: File,
    host: String,
    user: String,
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
                localExecSilent("scp", pkg.absolutePath, "$user@$host:$remoteDir/$remoteName")
            }

        val dmgs = desktopDir.walkTopDown().filter { it.isFile && it.extension == "dmg" }.toList()
        for (dmg in dmgs) {
            val arch = when {
                System.getProperty("os.arch").contains("aarch64") -> "arm64"
                else -> "x86_64"
            }
            val remoteName = "TeamTalk-macos-$arch.dmg"
            println("  Uploading ${dmg.name} as $remoteName ...")
            localExecSilent("scp", dmg.absolutePath, "$user@$host:$remoteDir/$remoteName")
        }
    }

    val apkDir = File(rootDir, "android/build/outputs/apk/release")
    if (apkDir.exists()) {
        val apk = apkDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("-release.apk") }
            .firstOrNull()
        if (apk != null) {
            println("  Uploading ${apk.name} ...")
            localExecSilent("scp", apk.absolutePath, "$user@$host:$remoteDir/TeamTalk-android.apk")
        }
    }
}

/**
 * 执行服务端部署：首次部署或升级。
 */
fun deployServer(
    rootDir: File,
    demo: DemoConfig,
    sslCert: String?,
    sslKey: String?
) {
    val host = demo.deployHost
    val user = demo.deployUser
    val deployPath = demo.deployPath
    val sslEnabled = true
    val sslPort = demo.sslPort.toString()
    val tcpPort = demo.tcpPort.toString()

    val url = demo.serverUrl
    val port = extractHttpPort(url)
    val effectiveDefault = effectiveDefaultHttpPort(url)

    println("")
    println("=== TeamTalk Demo Deploy ===")
    println("  Target: $user@$host")
    println("  Path:   $deployPath")
    println("  HTTP:   port $port")
    println("  TCP:    port $tcpPort")
    println("  SSL:    ${if (sslEnabled) "enabled (port $sslPort)" else "disabled"}")
    println("")

    val isFirstDeploy = !remoteCheck(host, user, "test -d $deployPath/bin")

    val secretsFile = File(rootDir, "gradle/profiles/demo.secrets")
    val secrets = if (isFirstDeploy) {
        loadOrGenerateSecrets(secretsFile, host, user, deployPath, "demo")
    } else {
        extractSecretsFromRemote(secretsFile, host, user, deployPath, "demo")
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
            deployPath,
            secrets,
            sslEnabled,
            sslPort,
            sslCert,
            sslKey,
            "demo",
            port,
            tcpPort,
            effectiveDefault
        )
    } else {
        println("=== Upgrade ===")
        deployUpgrade(
            rootDir,
            host,
            user,
            deployPath,
            secrets,
            sslEnabled,
            sslPort,
            sslCert,
            sslKey,
            "demo",
            port,
            tcpPort,
            effectiveDefault
        )
    }

    healthCheck(host, user, deployPath, sslEnabled, port)
}

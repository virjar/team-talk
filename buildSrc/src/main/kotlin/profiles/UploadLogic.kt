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
    profile: BuildProfile,
    stagingDir: File? = null
) {
    val deploy = profile.deploy
        ?: throw GradleException(
            "uploadRelease: profile '${profile.name}' does not define deploy config."
        )
    val host = deploy.host
    val user = deploy.user
    val path = deploy.path

    val remoteDir = "$path/static/downloads"
    println("Uploading to $user@$host:$remoteDir ...")

    remoteExec(host, user, "mkdir -p $remoteDir")

    if (stagingDir != null && stagingDir.exists()) {
        uploadFromStaging(stagingDir, host, user, remoteDir)
    } else {
        uploadFromBuildDir(rootDir, profile, host, user, remoteDir)
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
    profile: BuildProfile,
    host: String,
    user: String,
    remoteDir: String
) {
    val desktopRename = mapOf(
        "deb" to "TeamTalk-linux.deb",
        "msi" to "TeamTalk-windows.msi"
    )
    val desktopDir = File(rootDir, "desktop/build/compose/binaries/${profile.name}")
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

    val apkDir = File(rootDir, "android/build/outputs/apk/${profile.name}/release")
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
    profile: BuildProfile,
    sslCert: String?,
    sslKey: String?
) {
    val deploy = profile.deploy
        ?: throw GradleException(
            "deployServer: profile '${profile.name}' does not define deploy config."
        )
    val host = deploy.host
    val user = deploy.user
    val deployPath = deploy.path
    val sslEnabled = profile.ssl != null
    val sslPort = profile.ssl?.port?.toString() ?: "443"
    val tcpPort = profile.tcpPort.toString()

    val url = profile.serverUrl
    val port = extractHttpPort(url)
    val effectiveDefault = effectiveDefaultHttpPort(url)

    println("")
    println("=== TeamTalk Deploy (profile: ${profile.name}) ===")
    println("  Target: $user@$host")
    println("  Path:   $deployPath")
    println("  HTTP:   port $port")
    println("  TCP:    port $tcpPort")
    println("  SSL:    ${if (sslEnabled) "enabled (port $sslPort)" else "disabled"}")
    println("")

    val isFirstDeploy = !remoteCheck(host, user, "test -d $deployPath/bin")

    val secretsFile = File(rootDir, "gradle/profiles/${profile.name}.secrets")
    val secrets = if (isFirstDeploy) {
        loadOrGenerateSecrets(secretsFile, host, user, deployPath, profile.name)
    } else {
        extractSecretsFromRemote(secretsFile, host, user, deployPath, profile.name)
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
            profile.name,
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
            profile.name,
            port,
            tcpPort,
            effectiveDefault
        )
    }

    healthCheck(host, user, deployPath, sslEnabled, port)
}

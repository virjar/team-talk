/**
 * 客户端产物上传逻辑。
 *
 * 从根 build.gradle.kts 提取。不依赖 Project 上下文，通过参数传入所有依赖。
 */

import java.io.File
import org.gradle.api.GradleException

/**
 * 执行上传：将 desktop 和 android 产物上传到远程服务器的 static/downloads 目录。
 */
fun uploadArtifacts(
    rootDir: File,
    allProfiles: Map<String, Map<String, String>>,
    profileName: String
) {
    val props = allProfiles[profileName]!!
    val host = props["deploy.host"]
        ?: throw GradleException(
            "uploadRelease: profile '$profileName' does not define 'deploy.host'. " +
                "Add deploy.host, deploy.user, deploy.path to your profile."
        )
    val user = props["deploy.user"] ?: "root"
    val path = props["deploy.path"] ?: "/opt/teamtalk"

    val remoteDir = "$path/static/downloads"
    println("Uploading to $user@$host:$remoteDir ...")

    remoteExec(host, user, "mkdir -p $remoteDir")

    // Upload desktop packages
    val desktopRename = mapOf("deb" to "TeamTalk-linux.deb", "msi" to "TeamTalk-windows.msi", "dmg" to "TeamTalk-macos.dmg")
    val desktopDir = File(rootDir, "desktop/build/compose/binaries/$profileName")
    if (desktopDir.exists()) {
        desktopDir.walkTopDown()
            .filter { it.isFile && (it.extension in desktopRename.keys) }
            .forEach { pkg ->
                val remoteName = desktopRename[pkg.extension] ?: pkg.name
                println("  Uploading ${pkg.name} as $remoteName ...")
                localExecSilent("scp", pkg.absolutePath, "$user@$host:$remoteDir/$remoteName")
            }
    }

    // Upload Android APK
    val apkDir = File(rootDir, "android/build/outputs/apk/$profileName/release")
    if (apkDir.exists()) {
        val apk = apkDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("-release.apk") }
            .firstOrNull()
        if (apk != null) {
            println("  Uploading ${apk.name} ...")
            localExecSilent("scp", apk.absolutePath, "$user@$host:$remoteDir/TeamTalk-android.apk")
        }
    }

    println("Upload complete. Download page: https://$host/")
}

/**
 * 执行服务端部署：首次部署或升级。
 */
fun deployServer(
    rootDir: File,
    allProfiles: Map<String, Map<String, String>>,
    profileName: String,
    sslCert: String?,
    sslKey: String?
) {
    val props = allProfiles[profileName]!!
    val host = props["deploy.host"]
        ?: throw GradleException(
            "deployServer: profile '$profileName' does not define 'deploy.host'. " +
                "Add deploy.host, deploy.user, deploy.path to your profile."
        )
    val user = props["deploy.user"] ?: "root"
    val deployPath = props["deploy.path"] ?: "/opt/teamtalk"
    val sslEnabled = props["server.ssl.enabled"]?.toBoolean() ?: false
    val sslPort = props["server.ssl.port"] ?: "443"
    val tcpPort = props["tcpPort"]

    val url = props["serverUrl"]!!
    val port = extractHttpPort(url)
    val effectiveDefault = effectiveDefaultHttpPort(url)

    println("")
    println("=== TeamTalk Deploy (profile: $profileName) ===")
    println("  Target: $user@$host")
    println("  Path:   $deployPath")
    println("  HTTP:   port $port")
    println("  TCP:    port ${tcpPort ?: 5100}")
    println("  SSL:    ${if (sslEnabled) "enabled (port $sslPort)" else "disabled"}")
    println("")

    val isFirstDeploy = !remoteCheck(host, user, "test -d $deployPath/bin")

    val secretsFile = File(rootDir, "gradle/profiles/${profileName}.secrets")
    val secrets = if (isFirstDeploy) {
        loadOrGenerateSecrets(secretsFile, host, user, deployPath, profileName)
    } else {
        extractSecretsFromRemote(secretsFile, host, user, deployPath, profileName)
            ?: throw GradleException(
                "Cannot extract secrets from remote env.sh. " +
                    "Check if $deployPath/conf/env.sh exists on the server."
            )
    }

    if (isFirstDeploy) {
        println("=== First Deploy ===")
        deployNew(rootDir, host, user, deployPath, secrets, sslEnabled, sslPort, sslCert, sslKey, profileName, port, tcpPort, effectiveDefault)
    } else {
        println("=== Upgrade ===")
        deployUpgrade(rootDir, host, user, deployPath, secrets, sslEnabled, sslPort, sslCert, sslKey, profileName, port, tcpPort, effectiveDefault)
    }

    healthCheck(host, user, deployPath, sslEnabled, port)
}

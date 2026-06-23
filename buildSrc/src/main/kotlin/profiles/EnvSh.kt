package profiles

/**
 * env.sh 配置文件生成 + 上传，以及从 serverUrl 提取 HTTP 端口的工具。
 */

import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

// ── env.sh 生成 ──

fun generateEnvShContent(
    secrets: Properties,
    sslEnabled: Boolean,
    sslPort: String,
    deployPath: String,
    profileName: String,
    httpPort: Int,
    tcpPort: String?,
    effectiveDefaultHttpPort: Int
): String {
    val lines = mutableListOf<String>()
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    lines.add("# TeamTalk 运行配置 - profile: $profileName")
    lines.add("# 自动生成于 $now")
    lines.add("# 权限 600，请勿提交到版本控制")
    lines.add("# 修改后执行: systemctl restart teamtalk")
    lines.add("#")
    lines.add("# 仅包含与 application.conf 默认值不同的项目和敏感密码")
    lines.add("# 未列出的配置使用 application.conf 默认值:")
    lines.add("#   httpPort=8080, tcpPort=5100, database=127.0.0.1:5432/teamtalk")
    lines.add("")

    lines.add("# ── 服务端口 ──")
    if (httpPort != effectiveDefaultHttpPort) {
        lines.add("KTOR_PORT=$httpPort")
    }
    if (tcpPort != null && tcpPort != "5100") {
        lines.add("TCP_PORT=$tcpPort")
    }
    lines.add("")

    lines.add("# ── 数据库 ──")
    lines.add("DATABASE_PASSWORD=\"${secrets.getProperty("DATABASE_PASSWORD")}\"")
    lines.add("")

    lines.add("# ── 认证 ──")
    lines.add("JWT_SECRET=\"${secrets.getProperty("JWT_SECRET")}\"")
    lines.add("")

    if (sslEnabled) {
        lines.add("# ── SSL ──")
        lines.add("KTOR_SSL_PORT=$sslPort")
        lines.add("SSL_KEYSTORE=\"$deployPath/conf/ssl/teamtalk.p12\"")
        lines.add("SSL_KEYSTORE_PASSWORD=\"${secrets.getProperty("SSL_KEYSTORE_PASSWORD")}\"")
        lines.add("SSL_PRIVATE_KEY_PASSWORD=\"${secrets.getProperty("SSL_PRIVATE_KEY_PASSWORD")}\"")
        lines.add("")
    }

    return lines.joinToString("\n")
}

// ── 上传 env.sh 到远程 ──

fun uploadEnvSh(envShContent: String, host: String, user: String, deployPath: String) {
    val tmpEnv = File.createTempFile("teamtalk-env-", ".sh")
    tmpEnv.deleteOnExit()
    tmpEnv.writeText(envShContent)

    localExecSilent("scp", tmpEnv.absolutePath, "$user@$host:$deployPath/conf/env.sh")
    localExecSilent("ssh", "-o", "ConnectTimeout=10", "$user@$host", "chmod 600 $deployPath/conf/env.sh")
    tmpEnv.delete()
    println("  conf/env.sh uploaded (mode 600)")
}

// ── 从 serverUrl 提取 HTTP 端口 ──

fun extractHttpPort(serverUrl: String): Int {
    return try {
        val uri = URI(serverUrl)
        val explicit = uri.port
        if (explicit != -1) explicit else if (uri.scheme == "https") 443 else 80
    } catch (_: Exception) { 8080 }
}

fun effectiveDefaultHttpPort(serverUrl: String): Int {
    return if (serverUrl.startsWith("https://")) 443 else 8080
}

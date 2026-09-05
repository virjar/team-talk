package deployment

/** env.sh 配置文件生成、原子上传，以及 serverUrl 端口解析。 */

import java.io.File
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID
import org.gradle.api.GradleException

/** POSIX shell 单引号编码；引号通过结束字符串、转义、重新进入字符串来表示。 */
internal fun posixShellQuote(value: String): String {
    require(value.none { it == '\u0000' || it == '\n' || it == '\r' }) {
        "env.sh values must not contain NUL or line breaks"
    }
    return "'${value.replace("'", "'\\''")}'"
}

private fun requiredSecret(secrets: Properties, key: String): String =
    secrets.getProperty(key)?.takeIf { it.isNotBlank() && it != "null" }
        ?: throw GradleException("$key missing in deployment secrets")

fun generateEnvShContent(
    secrets: Properties,
    sslEnabled: Boolean,
    sslPort: String,
    deployPath: String,
    httpPort: Int,
    tcpPort: String,
    minimumProtocolMinor: Int? = null,
): String {
    requireCanonicalDeployPath(deployPath)
    require(tcpPort.matches(Regex("[1-9][0-9]{0,4}")) && tcpPort.toInt() in 1..65535) {
        "tcpPort must be a canonical decimal port in 1..65535"
    }
    require(minimumProtocolMinor == null || minimumProtocolMinor in 0..65535) {
        "minimumProtocolMinor must be in 0..65535"
    }
    val lines = mutableListOf<String>()
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    lines.add("# TeamTalk 运行配置")
    lines.add("# 自动生成于 $now")
    lines.add("# 权限 600，请勿提交到版本控制")
    lines.add("# 修改后执行: systemctl restart teamtalk")
    lines.add("#")
    lines.add("# 仅包含与 application.conf 默认值不同的项目和敏感密码")
    lines.add("# 未列出的配置使用 application.conf 默认值:")
    lines.add("#   httpPort=8080, database=127.0.0.1:5432/teamtalk")
    lines.add("")

    lines.add("# ── 服务端口 ──")
    if (httpPort != 8080) lines.add("KTOR_PORT=$httpPort")
    // 对外可访问的 TCP 仅支持 TLS。纯 HTTP 部署有意保留回环地址上的开发监听器，
    // 而不是默默地在所有网卡上暴露明文。
    lines.add("TCP_HOST=${if (sslEnabled) "0.0.0.0" else "127.0.0.1"}")
    lines.add("TCP_PORT=$tcpPort")
    minimumProtocolMinor?.let { lines.add("MINIMUM_PROTOCOL_MINOR=$it") }
    lines.add("")

    lines.add("# ── 数据库 ──")
    lines.add("DATABASE_PASSWORD=${posixShellQuote(requiredSecret(secrets, "DATABASE_PASSWORD"))}")
    lines.add("")

    lines.add("# ── 管理后台（/admin）──")
    lines.add("ADMIN_USER=${posixShellQuote(requiredSecret(secrets, "ADMIN_USER"))}")
    lines.add("ADMIN_PASSWORD=${posixShellQuote(requiredSecret(secrets, "ADMIN_PASSWORD"))}")
    lines.add("")

    lines.add("# ── SSL ──")
    if (sslEnabled) {
        lines.add("KTOR_SSL_PORT=$sslPort")
        lines.add("SSL_KEYSTORE=${posixShellQuote("$deployPath/conf/ssl/teamtalk.p12")}")
    }
    // 升级恢复以远程 env.sh 为权威来源，包括在 HTTPS 被禁用期间。
    lines.add(
        "SSL_KEYSTORE_PASSWORD=${posixShellQuote(requiredSecret(secrets, "SSL_KEYSTORE_PASSWORD"))}",
    )
    lines.add(
        "SSL_PRIVATE_KEY_PASSWORD=${posixShellQuote(requiredSecret(secrets, "SSL_PRIVATE_KEY_PASSWORD"))}",
    )
    lines.add("")

    return lines.joinToString("\n")
}

/** Only a single canonical assignment is accepted; absence keeps the target build's default. */
internal fun parseRemoteMinimumProtocolMinor(lines: String, target: ServerProtocolWindow): Int? {
    val assignments = lines.lineSequence().filter(String::isNotBlank).toList()
    if (assignments.isEmpty()) return null
    require(assignments.size == 1) { "Remote MINIMUM_PROTOCOL_MINOR must appear at most once" }
    val match = Regex("MINIMUM_PROTOCOL_MINOR=(0|[1-9][0-9]{0,4})").matchEntire(assignments.single())
        ?: throw IllegalArgumentException("Remote MINIMUM_PROTOCOL_MINOR must be a canonical decimal assignment")
    val value = match.groupValues[1].toInt()
    require(value in target.minimumMinor..target.currentMinor) {
        "Remote MINIMUM_PROTOCOL_MINOR=$value is outside target protocol ${target.major}." +
            "${target.minimumMinor}..${target.currentMinor}; update the explicit floor before upgrading"
    }
    return value
}

/** Never source env.sh or capture its secrets. Related noncanonical lines become a fixed error marker. */
internal fun remoteMinimumProtocolMinorReadCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    val script = """
        /^[[:space:]]*#/ { next }
        /(^|[^A-Za-z0-9_])MINIMUM_PROTOCOL_MINOR([^A-Za-z0-9_]|$)/ {
            if (${'$'}0 ~ /^MINIMUM_PROTOCOL_MINOR=(0|[1-9][0-9]*)$/) print
            else print "INVALID_MINIMUM_PROTOCOL_MINOR"
        }
    """.trimIndent()
    val envFile = "$deployPath/conf/env.sh"
    return "test -r $envFile && awk ${remoteShellQuote(script)} $envFile"
}

internal fun readRemoteMinimumProtocolMinor(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
    target: ServerProtocolWindow,
): Int? {
    val output = remoteSensitiveCaptureProbe(
        label = "read current TeamTalk minimum protocol minor",
        host = host,
        user = user,
        command = remoteMinimumProtocolMinorReadCommand(deployPath),
        port = port,
        allowedExitCodes = setOf(0),
    ) ?: throw GradleException("Current TeamTalk protocol configuration is unavailable")
    return try {
        parseRemoteMinimumProtocolMinor(output, target)
    } catch (failure: IllegalArgumentException) {
        throw GradleException("Upgrade blocked before stopping TeamTalk: ${failure.message}", failure)
    }
}

/** 上传到唯一的兄弟文件，然后 chmod + 重命名，使读取方永远不会观察到半写入的 env 文件。 */
fun uploadEnvSh(envShContent: String, host: String, user: String, port: Int, deployPath: String) {
    requireCanonicalDeployPath(deployPath)
    requireActiveRemoteDeploymentGuard(host, user, port)
    val temporary = createOwnerOnlyTempFile("teamtalk-env-", ".sh")
    val remoteTemporary = "$deployPath/conf/.env.sh-${UUID.randomUUID()}.tmp"
    try {
        temporary.writeText(envShContent)
        setOwnerOnly(temporary.toPath())
        remoteChecked(
            "create remote configuration directory",
            host,
            user,
            "mkdir -p $deployPath/conf",
            port,
            outputMode = ProcessOutputMode.DISCARD,
        )
        remoteFileUploadChecked(
            label = "upload deployment env",
            file = temporary,
            host = host,
            user = user,
            port = port,
            remotePath = remoteTemporary,
        )
        remoteChecked(
            "publish deployment env atomically",
            host,
            user,
            "chmod 600 $remoteTemporary && mv -f $remoteTemporary $deployPath/conf/env.sh",
            port,
            outputMode = ProcessOutputMode.DISCARD,
        )
        println("  conf/env.sh uploaded atomically (mode 600)")
    } catch (failure: Exception) {
        remoteBestEffort(
            "remove unpublished deployment env",
            host,
            user,
            "rm -f $remoteTemporary",
            port,
            timeoutMillis = 20_000L,
        )
        throw failure
    } finally {
        if (!temporary.delete() && temporary.exists()) {
            println("  WARNING: could not delete local temporary env file")
        }
    }
}

fun extractHttpPort(serverUrl: String): Int = try {
    val uri = URI(serverUrl)
    val explicit = uri.port
    if (explicit != -1) explicit else if (uri.scheme == "https") 443 else 80
} catch (_: Exception) {
    8080
}

internal data class RemoteHealthEndpoint(
    val sslEnabled: Boolean,
    val httpPort: Int,
    val sslPort: Int,
)

/** 只解析生成的、非敏感的监听字段；重复项和格式错误的值一律失败即停。 */
internal fun parseRemoteHealthEndpoint(lines: String): RemoteHealthEndpoint {
    val values = linkedMapOf<String, Int>()
    lines.lineSequence().filter(String::isNotBlank).forEach { line ->
        val pieces = line.split('=', limit = 2)
        require(pieces.size == 2 && pieces[0] in setOf("KTOR_PORT", "KTOR_SSL_PORT") &&
            pieces[0] !in values
        ) { "Remote TeamTalk listener configuration is malformed" }
        val port = pieces[1].toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Remote TeamTalk listener port is malformed")
        values[pieces[0]] = port
    }
    return RemoteHealthEndpoint(
        sslEnabled = "KTOR_SSL_PORT" in values,
        httpPort = values["KTOR_PORT"] ?: 8080,
        sslPort = values["KTOR_SSL_PORT"] ?: 443,
    )
}

internal fun remoteHealthEndpointReadCommand(deployPath: String): String {
    requireCanonicalDeployPath(deployPath)
    val envFile = "$deployPath/conf/env.sh"
    return "test -r $envFile && sed -n '/^KTOR_PORT=/p;/^KTOR_SSL_PORT=/p' $envFile"
}

/** 在切换之前捕获旧的监听配置，以便回滚时能验证恢复后的运行时。 */
internal fun readRemoteHealthEndpoint(
    host: String,
    user: String,
    port: Int,
    deployPath: String,
): RemoteHealthEndpoint {
    val output = remoteSensitiveCaptureProbe(
        label = "read current TeamTalk listener configuration",
        host = host,
        user = user,
        command = remoteHealthEndpointReadCommand(deployPath),
        port = port,
        allowedExitCodes = setOf(0),
    ) ?: throw GradleException("Current TeamTalk listener configuration is unavailable")
    return try {
        parseRemoteHealthEndpoint(output)
    } catch (failure: IllegalArgumentException) {
        throw GradleException("Current TeamTalk listener configuration is invalid", failure)
    }
}

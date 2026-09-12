package com.virjar.tk.shared.client

import com.virjar.tk.shared.repository.canonicalHttpServerBase
import java.util.Base64

/**
 * 服务端连接配置。
 * Desktop 通过 JVM 系统属性读取（Gradle :desktop:run 任务注入）；Android 直接持有
 * BuildConfig 构造的不可变实例。配置由平台 composition root 显式传递，不使用进程级可变单例。
 */
data class ServerConfig(
    val serverUrl: String,
    val tcpHost: String,
    val tcpPort: Int,
    /** 私有部署分发的公开 TLS 证书；不参与凭据和数据目录的部署身份。 */
    val tcpTlsCertificatePem: String? = null,
) {
    /** 凭据、缓存、TCP 与 HTTP client 共享的一个规范部署元组。 */
    fun deploymentIdentity(): DeploymentIdentity = DeploymentIdentity.from(
        tcpHost = tcpHost,
        tcpPort = tcpPort,
        serverUrl = serverUrl,
    )
}

/**
 * 一个 TeamTalk 部署的稳定身份。
 *
 * TCP 与 HTTP 刻意是一个元组：部署可能把它们暴露在不同主机上，但调用方不能用配置的一半来持久化
 * 凭据或打开缓存。
 */
class DeploymentIdentity private constructor(
    val tcpHost: String,
    val tcpPort: Int,
    val httpBaseUrl: String,
    val fingerprint: String,
) {
    val tcpAuthority: String = if (':' in tcpHost) "[$tcpHost]:$tcpPort" else "$tcpHost:$tcpPort"

    init {
        require(fingerprint.length == SHA256_HEX_LENGTH && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Deployment fingerprint must be lowercase SHA-256"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is DeploymentIdentity &&
            tcpHost == other.tcpHost &&
            tcpPort == other.tcpPort &&
            httpBaseUrl == other.httpBaseUrl &&
            fingerprint == other.fingerprint

    override fun hashCode(): Int {
        var result = tcpHost.hashCode()
        result = 31 * result + tcpPort
        result = 31 * result + httpBaseUrl.hashCode()
        result = 31 * result + fingerprint.hashCode()
        return result
    }

    override fun toString(): String =
        "DeploymentIdentity(tcpAuthority=$tcpAuthority, httpBaseUrl=$httpBaseUrl, fingerprint=$fingerprint)"

    companion object {
        fun from(config: ServerConfig): DeploymentIdentity = config.deploymentIdentity()

        fun from(tcpHost: String, tcpPort: Int, serverUrl: String): DeploymentIdentity {
            require(tcpPort in 1..65535) { "TCP port must be in 1..65535" }
            val canonicalTcpHost = canonicalDeploymentTcpHost(tcpHost)
            val canonicalHttpBase = canonicalHttpServerBase(serverUrl)
            val authority = if (':' in canonicalTcpHost) {
                "[$canonicalTcpHost]:$tcpPort"
            } else {
                "$canonicalTcpHost:$tcpPort"
            }
            val canonicalTuple = buildString {
                append("teamtalk-deployment-v1\u0000tcp\u0000")
                append(authority)
                append("\u0000http\u0000")
                append(canonicalHttpBase)
            }
            return DeploymentIdentity(
                tcpHost = canonicalTcpHost,
                tcpPort = tcpPort,
                httpBaseUrl = canonicalHttpBase,
                fingerprint = deploymentSha256Hex(canonicalTuple),
            )
        }

        /** 未显式提供 HTTP base 时的无头 SDK 默认值。 */
        fun fromTcpWithDefaultHttp(tcpHost: String, tcpPort: Int): DeploymentIdentity {
            val canonicalHost = canonicalDeploymentTcpHost(tcpHost)
            val httpAuthority = if (':' in canonicalHost) "[$canonicalHost]" else canonicalHost
            return from(canonicalHost, tcpPort, "https://$httpAuthority")
        }

        private const val SHA256_HEX_LENGTH = 64
    }
}

/** 两个 JVM 家族目标的平台规范化与 SHA-256 助手。 */
internal expect fun canonicalDeploymentTcpHost(host: String): String

internal expect fun deploymentSha256Hex(value: String): String

fun defaultServerConfig(): ServerConfig {
    return ServerConfig(
        serverUrl = System.getProperty("teamtalk.server.url") ?: "https://im.virjar.com",
        tcpHost = System.getProperty("teamtalk.tcp.host") ?: "im.virjar.com",
        tcpPort = (System.getProperty("teamtalk.tcp.port") ?: "5100").toInt(),
        tcpTlsCertificatePem = decodeTcpTlsCertificateBase64(
            System.getProperty("teamtalk.tcp.certificate.base64"),
        ),
    )
}

/** 构建参数使用单行 Base64 传递公开证书，避免多行 PEM 被启动器拆开。 */
fun decodeTcpTlsCertificateBase64(encoded: String?): String? =
    encoded?.takeIf(String::isNotEmpty)?.let {
        Base64.getDecoder().decode(it).toString(Charsets.UTF_8)
    }

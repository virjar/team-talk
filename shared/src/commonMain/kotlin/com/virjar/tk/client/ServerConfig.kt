package com.virjar.tk.client

import com.virjar.tk.repository.canonicalHttpServerBase

/**
 * 服务端连接配置。
 * Desktop 通过 JVM 系统属性读取（Gradle :desktop:run 任务注入）。
 * Android 通过 [configureServerConfig] 注入 BuildConfig 值。
 */
data class ServerConfig(
    val serverUrl: String,
    val tcpHost: String,
    val tcpPort: Int,
) {
    /** One canonical deployment tuple shared by credentials, caches, TCP and HTTP clients. */
    fun deploymentIdentity(): DeploymentIdentity = DeploymentIdentity.from(
        tcpHost = tcpHost,
        tcpPort = tcpPort,
        serverUrl = serverUrl,
    )
}

/**
 * Stable identity of one TeamTalk deployment.
 *
 * TCP and HTTP are intentionally one tuple: deployments may expose them on different hosts, but
 * callers cannot persist a credential or open a cache using only one half of the configuration.
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

        /** Headless SDK default when no explicit HTTP base was supplied. */
        internal fun fromTcpWithDefaultHttp(tcpHost: String, tcpPort: Int): DeploymentIdentity {
            val canonicalHost = canonicalDeploymentTcpHost(tcpHost)
            val httpAuthority = if (':' in canonicalHost) "[$canonicalHost]" else canonicalHost
            return from(canonicalHost, tcpPort, "https://$httpAuthority")
        }

        private const val SHA256_HEX_LENGTH = 64
    }
}

/** Platform normalization and SHA-256 helpers for the two JVM-family targets. */
internal expect fun canonicalDeploymentTcpHost(host: String): String

internal expect fun deploymentSha256Hex(value: String): String

private var overrideConfig: ServerConfig? = null

fun configureServerConfig(config: ServerConfig) {
    overrideConfig = config
}

fun defaultServerConfig(): ServerConfig {
    return overrideConfig ?: ServerConfig(
        serverUrl = System.getProperty("teamtalk.server.url") ?: "https://im.virjar.com",
        tcpHost = System.getProperty("teamtalk.tcp.host") ?: "im.virjar.com",
        tcpPort = (System.getProperty("teamtalk.tcp.port") ?: "5100").toInt(),
    )
}

package deployment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * TeamTalk 的单一部署配置。
 *
 * 仓库内只保留这一份非敏感配置。开源 fork 可以直接修改它来连接并部署到自己的服务器，
 * 不需要引入环境矩阵、构建 flavor 或客户端服务器切换 UI。
 */
data class DeploymentConfig(
    val serverUrl: String,
    val tcpAddress: String,
    val deployHost: String,
    val deployPort: Int = 22,
    val deployUser: String = "root",
    val deployPath: String = "/opt/teamtalk",
    val sslPort: Int = 443,
) {
    val serverUri: URI = URI(serverUrl)
    val sslEnabled: Boolean get() = serverUri.scheme.equals("https", ignoreCase = true)
    val tcpHost: String get() = tcpAddress.substringBeforeLast(":")
    val tcpPort: Int get() = tcpAddress.substringAfterLast(":").toInt()

    companion object {
        private val json = Json
        private val deployHostPattern = Regex("[A-Za-z0-9._-]+")
        private val deployUserPattern = Regex("[A-Za-z_][A-Za-z0-9_-]*")
        private val deployPathPattern = Regex("/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+")
        private val allowedKeys = setOf(
            "serverUrl",
            "tcpAddress",
            "deployHost",
            "deployPort",
            "deployUser",
            "deployPath",
            "sslPort",
        )

        fun load(content: String): DeploymentConfig {
            val value = json.parseToJsonElement(content).jsonObject
            val unknownKeys = value.keys - allowedKeys
            require(unknownKeys.isEmpty()) {
                "Unknown deployment config keys: ${unknownKeys.joinToString()}"
            }

            return DeploymentConfig(
                serverUrl = value.getValue("serverUrl").jsonPrimitive.content,
                tcpAddress = value.getValue("tcpAddress").jsonPrimitive.content,
                deployHost = value.getValue("deployHost").jsonPrimitive.content,
                deployPort = value["deployPort"]?.jsonPrimitive?.intOrNull ?: 22,
                deployUser = value["deployUser"]?.jsonPrimitive?.contentOrNull ?: "root",
                deployPath = value["deployPath"]?.jsonPrimitive?.contentOrNull ?: "/opt/teamtalk",
                sslPort = value["sslPort"]?.jsonPrimitive?.intOrNull ?: 443,
            ).also { config ->
                require(
                    config.serverUri.scheme.equals("http", ignoreCase = true) ||
                        config.serverUri.scheme.equals("https", ignoreCase = true)
                ) { "serverUrl must use http or https" }
                require(!config.serverUri.host.isNullOrBlank()) {
                    "serverUrl must be an absolute URL"
                }
                require(config.tcpAddress.count { it == ':' } == 1) {
                    "tcpAddress must use host:port format"
                }
                require(config.tcpHost.isNotBlank() && config.tcpPort in 1..65535) {
                    "Invalid tcpAddress"
                }
                require(config.deployHost.matches(deployHostPattern)) {
                    "deployHost must be a hostname or IPv4 address"
                }
                require(config.deployPort in 1..65535) { "Invalid deployPort" }
                require(config.deployUser.matches(deployUserPattern)) {
                    "Invalid deployUser"
                }
                require(config.deployPath.matches(deployPathPattern)) {
                    "deployPath must be a safe absolute non-root path"
                }
                require(config.sslPort in 1..65535) { "Invalid sslPort" }
                if (config.sslEnabled) {
                    val publicSslPort = config.serverUri.port.takeIf { it != -1 } ?: 443
                    require(publicSslPort == config.sslPort) {
                        "sslPort must match the HTTPS port in serverUrl"
                    }
                }
            }
        }
    }
}

package profiles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * TeamTalk 唯一的共享环境配置。
 *
 * 项目尚未发布，开发、客户端构建、远程业务测试和部署统一使用 demo。
 * 不再为不存在的多环境需求动态发现 Profile 或生成任务矩阵。
 */
data class DemoConfig(
    val serverUrl: String,
    val tcpAddress: String,
    val deployHost: String,
    val deployUser: String = "root",
    val deployPath: String = "/opt/teamtalk",
    val sslPort: Int = 443,
) {
    val tcpHost: String get() = tcpAddress.substringBefore(":")
    val tcpPort: Int get() = tcpAddress.substringAfter(":", "5100").toInt()

    companion object {
        private val json = Json
        private val allowedKeys = setOf(
            "serverUrl", "tcpAddress", "deployHost", "deployUser", "deployPath", "sslPort",
        )

        fun load(content: String): DemoConfig {
            val value = json.parseToJsonElement(content).jsonObject
            val unknownKeys = value.keys - allowedKeys
            require(unknownKeys.isEmpty()) { "Unknown Demo config keys: ${unknownKeys.joinToString()}" }
            return DemoConfig(
                serverUrl = value.getValue("serverUrl").jsonPrimitive.content,
                tcpAddress = value.getValue("tcpAddress").jsonPrimitive.content,
                deployHost = value.getValue("deployHost").jsonPrimitive.content,
                deployUser = value["deployUser"]?.jsonPrimitive?.contentOrNull ?: "root",
                deployPath = value["deployPath"]?.jsonPrimitive?.contentOrNull ?: "/opt/teamtalk",
                sslPort = value["sslPort"]?.jsonPrimitive?.intOrNull ?: 443,
            ).also {
                val serverUri = URI(it.serverUrl)
                require(serverUri.scheme == "https" && !serverUri.host.isNullOrBlank()) {
                    "Demo serverUrl must be an absolute HTTPS URL"
                }
                require(it.tcpHost.isNotBlank() && it.tcpPort in 1..65535) { "Invalid demo tcpAddress" }
                require(serverUri.host.equals(it.tcpHost, ignoreCase = true)) {
                    "Demo HTTP and TCP endpoints must use the same host"
                }
                require(it.deployHost.equals(it.tcpHost, ignoreCase = true)) {
                    "Demo deploy and client endpoints must use the same host"
                }
            }
        }
    }
}

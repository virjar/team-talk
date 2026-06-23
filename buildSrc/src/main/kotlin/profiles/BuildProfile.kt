package profiles

import kotlinx.serialization.json.*

/**
 * 构建渠道定义。
 *
 * 每个渠道包含服务端地址、TCP 连接地址、部署配置等。
 * Profile 定义为 JSON 文件放在 `gradle/profiles/` 目录。
 */
data class BuildProfile(
    val name: String,
    val serverUrl: String,
    val tcpAddress: String,
    val allowCustomServer: Boolean = false,
    val deploy: DeployConfig? = null,
    val ssl: SslConfig? = null,
) {

    data class DeployConfig(
        val host: String,
        val user: String = "root",
        val path: String = "/opt/teamtalk",
    )

    data class SslConfig(
        val port: Int = 443,
    )

    val tcpHost: String
        get() = tcpAddress.substringBefore(":")

    val tcpPort: Int
        get() = tcpAddress.substringAfter(":", "5100").toInt()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 从 JSON 字符串解析 Profile。 */
        fun fromJson(jsonStr: String): BuildProfile {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            return BuildProfile(
                name = obj.getValue("name").jsonPrimitive.content,
                serverUrl = obj.getValue("serverUrl").jsonPrimitive.content,
                tcpAddress = obj.getValue("tcpAddress").jsonPrimitive.content,
                allowCustomServer = obj["allowCustomServer"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
                deploy = obj["deploy"]?.jsonObject?.let { d ->
                    DeployConfig(
                        host = d.getValue("host").jsonPrimitive.content,
                        user = d["user"]?.jsonPrimitive?.contentOrNull ?: "root",
                        path = d["path"]?.jsonPrimitive?.contentOrNull ?: "/opt/teamtalk",
                    )
                },
                ssl = obj["ssl"]?.jsonObject?.let { s ->
                    SslConfig(port = s["port"]?.jsonPrimitive?.intOrNull ?: 443)
                },
            )
        }
    }
}

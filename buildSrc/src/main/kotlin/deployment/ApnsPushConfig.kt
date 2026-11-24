package deployment

import java.io.File
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Deployment.kt 里只允许出现公开的 Apple ID 与本地文件路径；绝不内嵌 .p8 内容。 */
data class ApnsPushDeployment(
    val teamId: String,
    val keyId: String,
    val privateKeyFile: File,
    val environments: Set<String> = setOf("production"),
    /** 通知展示粒度：sender_only（默认：发送者/群聊+消息类型）、preview（含摘要）、generic（仅通用提示）。 */
    val contentMode: String = DEFAULT_CONTENT_MODE,
    /** 可选通知回退标题；为 null 时使用 client.displayName。 */
    val title: String? = null,
) {
    init {
        require(teamId.matches(Regex("[A-Z0-9]{10}"))) { "client.apnsPush.teamId must be an Apple Team ID" }
        require(keyId.matches(Regex("[A-Z0-9]{10}"))) { "client.apnsPush.keyId must be an APNs key ID" }
        require(environments.isNotEmpty() && environments.all { it in setOf("sandbox", "production") }) {
            "client.apnsPush.environments must contain sandbox and/or production"
        }
        require(contentMode in CONTENT_MODES) {
            "client.apnsPush.contentMode must be generic, sender_only or preview"
        }
        require(title == null || (title.isNotBlank() && title.length <= 80 && title.none(Char::isISOControl))) {
            "client.apnsPush.title must be non-blank and at most 80 characters"
        }
    }

    companion object {
        const val DEFAULT_CONTENT_MODE = "sender_only"
        val CONTENT_MODES = setOf("generic", "sender_only", "preview")
    }

    /** 在获取远程部署租约前完成校验；返回单行 env.sh 密钥。 */
    internal fun encodedPrivateKey(): String {
        require(privateKeyFile.isFile && privateKeyFile.length() in 1..16_384) { "APNs .p8 key file is missing or too large" }
        val pem = privateKeyFile.readText(Charsets.US_ASCII).trim()
        require(Regex("-----BEGIN PRIVATE KEY-----[A-Za-z0-9+/=\\s]+-----END PRIVATE KEY-----").matches(pem)) {
            "APNs key must be a PKCS#8 .p8 PEM file"
        }
        try {
            val bytes = Base64.getDecoder().decode(pem.removePrefix("-----BEGIN PRIVATE KEY-----")
                .removeSuffix("-----END PRIVATE KEY-----").filterNot(Char::isWhitespace))
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
            require(key is ECPrivateKey && key.params.order.toString(16) ==
                "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
        } catch (_: Exception) {
            throw IllegalArgumentException("APNs .p8 key must use the P-256 curve")
        }
        return Base64.getEncoder().encodeToString(pem.toByteArray(Charsets.US_ASCII))
    }
}

@DeploymentDsl
class ApnsPushDeploymentBuilder internal constructor() {
    var teamId: String = ""
    var keyId: String = ""
    var privateKeyFile: File? = null
    var environments: Set<String> = setOf("production")
    /** 通知展示粒度：sender_only（默认：发送者/群聊+消息类型）、preview（含摘要）、generic（仅通用提示）。 */
    var contentMode: String = ApnsPushDeployment.DEFAULT_CONTENT_MODE
    /** 可选通知回退标题；留空时使用 client.displayName。 */
    var title: String? = null

    internal fun build() = ApnsPushDeployment(
        teamId = teamId,
        keyId = keyId,
        privateKeyFile = requireNotNull(privateKeyFile) { "client.apnsPush.privateKeyFile must be configured" },
        environments = environments.toSet(),
        contentMode = contentMode,
        title = title,
    )
}

internal fun DeploymentConfig.apnsPushEnvironment(): Map<String, String> {
    val push = apnsPush ?: return mapOf("APNS_PUSH_ENABLED" to "false")
    return linkedMapOf(
        "APNS_PUSH_ENABLED" to "true",
        "APNS_PUSH_TEAM_ID" to push.teamId,
        "APNS_PUSH_KEY_ID" to push.keyId,
        "APNS_PUSH_BUNDLE_ID" to client.iosBundleId,
        "APNS_PUSH_TITLE" to (push.title ?: client.displayName),
        "APNS_PUSH_CONTENT_MODE" to push.contentMode,
        "APNS_PUSH_ENVIRONMENTS" to push.environments.sorted().joinToString(","),
        "APNS_PUSH_PRIVATE_KEY_BASE64" to push.encodedPrivateKey(),
    )
}

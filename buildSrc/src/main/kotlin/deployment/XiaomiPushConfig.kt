package deployment

import java.io.File
import java.util.Properties
import java.security.MessageDigest

/** 每个安装包在小米开放平台单独开通。这里只保存公开参数和凭据文件位置。 */
data class XiaomiPushConfig(
    val appId: String,
    val channelId: String,
    val templateId: String,
    val credentialsFile: File,
    val sdkFile: File,
) {
    init {
        require(appId.matches(Regex("[0-9]{1,32}"))) { "client.xiaomiPush.appId must be the Xiaomi App ID" }
        require(listOf(channelId, templateId).all { value ->
            value.length in 1..128 && value.all { it.code in 33..126 }
        }) { "client.xiaomiPush requires the approved private-message channelId and templateId" }
        require(sdkFile.isFile && sdkFile.extension == "aar") {
            "client.xiaomiPush.sdkFile must point to the China mainland AAR downloaded from Xiaomi"
        }
    }

    val sdkSha256: String = MessageDigest.getInstance("SHA-256").let { digest ->
        sdkFile.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** App Key 进入 APK；App Secret 只供部署端生成服务器 env.sh，不能写入 BuildConfig。 */
    fun readCredential(key: String): String {
        require(key == "appKey" || key == "appSecret")
        val values = Properties().apply { credentialsFile.reader(Charsets.UTF_8).use(::load) }
        return values.getProperty(key)?.takeIf { value ->
            value.isNotBlank() && value.length <= 512 && value.none(Char::isISOControl)
        } ?: throw IllegalArgumentException("Xiaomi push credentials file is missing a valid $key")
    }
}

@DeploymentDsl
class XiaomiPushDeploymentBuilder internal constructor() {
    var appId: String = ""
    var channelId: String = ""
    var templateId: String = ""
    var credentialsFile: File? = null
    var sdkFile: File? = null

    internal fun build() = XiaomiPushConfig(
        appId, channelId, templateId,
        requireNotNull(credentialsFile) { "client.xiaomiPush.credentialsFile must be configured" },
        requireNotNull(sdkFile) { "client.xiaomiPush.sdkFile must be configured" },
    )
}

internal fun DeploymentConfig.xiaomiPushEnvironment(): Map<String, String> {
    val push = xiaomiPush ?: return mapOf("XIAOMI_PUSH_ENABLED" to "false")
    return mapOf(
        "XIAOMI_PUSH_ENABLED" to "true",
        "XIAOMI_PUSH_APP_SECRET" to push.readCredential("appSecret"),
        "XIAOMI_PUSH_PACKAGE_NAME" to client.androidApplicationId,
        "XIAOMI_PUSH_CHANNEL_ID" to push.channelId,
        "XIAOMI_PUSH_TEMPLATE_ID" to push.templateId,
        "XIAOMI_PUSH_TITLE" to client.displayName,
    )
}

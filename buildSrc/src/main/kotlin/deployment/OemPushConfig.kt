package deployment

import java.io.File
import java.util.Properties
import java.security.MessageDigest

/**
 * 每个安装包在对应厂商开放平台单独开通；这里只保存公开参数、凭据文件位置和官方 AAR。
 * vendor 取值与客户端注册 RPC 的通道标识一致：xiaomi/huawei/honor/oppo/vivo/meizu。
 */
data class OemPushVendorDeployment(
    val vendor: String,
    val credentialsFile: File,
    val sdkFiles: List<File>,
    val appId: String = "",
    val appKey: String = "",
    val channelId: String = "",
    val templateId: String = "",
    val category: String = "",
) {
    init {
        require(vendor in OemPushVendors.ALL) { "Unknown OEM push vendor: $vendor" }
        require(sdkFiles.isNotEmpty() && sdkFiles.all { it.isFile && it.extension == "aar" }) {
            "client.oemPush.$vendor requires the official AAR file(s) downloaded from the vendor"
        }
        when (vendor) {
            OemPushVendors.XIAOMI -> {
                requireNumeric("appId")
                requirePrintable("channelId", 128)
                requirePrintable("templateId", 128)
            }
            OemPushVendors.HUAWEI, OemPushVendors.HONOR -> {
                requireNumeric("appId")
                requirePrintable("channelId", 128)
            }
            OemPushVendors.OPPO -> {
                requirePrintable("appKey", 128)
                requirePrintable("channelId", 128)
            }
            OemPushVendors.VIVO -> {
                requireNumeric("appId")
                requirePrintable("appKey", 64)
                requirePrintable("category", 64)
            }
            OemPushVendors.MEIZU -> {
                requireNumeric("appId")
                requirePrintable("appKey", 64)
            }
        }
    }

    /** 聚合 AAR 指纹；多文件厂商按登记顺序参与摘要，凭据文件内容不参与。 */
    val sdkSha256: String = MessageDigest.getInstance("SHA-256").let { digest ->
        sdkFiles.forEach { file ->
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * App Key 会进入 APK（小米/OPPO 注册需要）；App Secret 只供部署端生成服务器 env.sh，
     * 绝不写入 BuildConfig。OPPO 的官方注册接口在客户端同时需要 appKey 与 appSecret。
     */
    fun readCredential(key: String): String {
        require(key == "appKey" || key == "appSecret")
        val values = Properties().apply { credentialsFile.reader(Charsets.UTF_8).use(::load) }
        return values.getProperty(key)?.takeIf { value ->
            value.isNotBlank() && value.length <= 512 && value.none(Char::isISOControl)
        } ?: throw IllegalArgumentException("OEM push $vendor credentials file is missing a valid $key")
    }

    private fun requireNumeric(name: String) {
        require(this.property(name).matches(Regex("[0-9]{1,32}"))) {
            "client.oemPush.$vendor.$name must be the vendor-assigned numeric ID"
        }
    }

    private fun requirePrintable(name: String, maxLength: Int) {
        val value = this.property(name)
        require(value.isNotEmpty() && value.length <= maxLength && value.all { it.code in 33..126 }) {
            "client.oemPush.$vendor.$name must reference an approved vendor channel/category"
        }
    }

    private fun property(name: String): String = when (name) {
        "appId" -> appId
        "appKey" -> appKey
        "channelId" -> channelId
        "templateId" -> templateId
        "category" -> category
        else -> throw IllegalArgumentException(name)
    }
}

/** 厂商集合与展示名；顺序决定 canonical JSON 与 env 输出顺序。 */
object OemPushVendors {
    const val XIAOMI = "xiaomi"
    const val HUAWEI = "huawei"
    const val HONOR = "honor"
    const val OPPO = "oppo"
    const val VIVO = "vivo"
    const val MEIZU = "meizu"
    val ALL = listOf(XIAOMI, HUAWEI, HONOR, OPPO, VIVO, MEIZU)

    fun display(vendor: String): String = when (vendor) {
        XIAOMI -> "小米"
        HUAWEI -> "华为"
        HONOR -> "荣耀"
        OPPO -> "OPPO"
        VIVO -> "vivo"
        MEIZU -> "魅族"
        else -> vendor
    }
}

@DeploymentDsl
class XiaomiPushDeploymentBuilder internal constructor() {
    var appId: String = ""
    var channelId: String = ""
    var templateId: String = ""
    var credentialsFile: File? = null
    /** 官方大陆版推送 AAR；多个时改用 sdkFiles。 */
    var sdkFile: File? = null
    val sdkFiles: MutableList<File> = mutableListOf()

    internal fun build() = OemPushVendorDeployment(
        OemPushVendors.XIAOMI,
        requireNotNull(credentialsFile) { "client.xiaomiPush.credentialsFile must be configured" },
        buildList { sdkFile?.let(::add); addAll(sdkFiles) },
        appId = appId, channelId = channelId, templateId = templateId,
    )
}

@DeploymentDsl
class HuaweiStylePushDeploymentBuilder internal constructor(private val vendor: String) {
    var appId: String = ""
    var channelId: String = ""
    var credentialsFile: File? = null
    /** 官方推送 AAR；华为/荣耀发行包通常包含多个 AAR，逐个登记。 */
    var sdkFile: File? = null
    val sdkFiles: MutableList<File> = mutableListOf()

    internal fun build() = OemPushVendorDeployment(
        vendor,
        requireNotNull(credentialsFile) { "client.${vendor}Push.credentialsFile must be configured" },
        buildList { sdkFile?.let(::add); addAll(sdkFiles) },
        appId = appId, channelId = channelId,
    )
}

@DeploymentDsl
class OppoPushDeploymentBuilder internal constructor() {
    var appKey: String = ""
    var channelId: String = ""
    var credentialsFile: File? = null
    var sdkFile: File? = null
    val sdkFiles: MutableList<File> = mutableListOf()

    internal fun build() = OemPushVendorDeployment(
        OemPushVendors.OPPO,
        requireNotNull(credentialsFile) { "client.oppoPush.credentialsFile must be configured" },
        buildList { sdkFile?.let(::add); addAll(sdkFiles) },
        appKey = appKey, channelId = channelId,
    )
}

@DeploymentDsl
class VivoPushDeploymentBuilder internal constructor() {
    var appId: String = ""
    var appKey: String = ""
    /** vivo 控制台审核通过的消息分类，例如 IM。 */
    var category: String = ""
    var credentialsFile: File? = null
    var sdkFile: File? = null
    val sdkFiles: MutableList<File> = mutableListOf()

    internal fun build() = OemPushVendorDeployment(
        OemPushVendors.VIVO,
        requireNotNull(credentialsFile) { "client.vivoPush.credentialsFile must be configured" },
        buildList { sdkFile?.let(::add); addAll(sdkFiles) },
        appId = appId, appKey = appKey, category = category,
    )
}

@DeploymentDsl
class MeizuPushDeploymentBuilder internal constructor() {
    var appId: String = ""
    var appKey: String = ""
    var credentialsFile: File? = null
    var sdkFile: File? = null
    val sdkFiles: MutableList<File> = mutableListOf()

    internal fun build() = OemPushVendorDeployment(
        OemPushVendors.MEIZU,
        requireNotNull(credentialsFile) { "client.meizuPush.credentialsFile must be configured" },
        buildList { sdkFile?.let(::add); addAll(sdkFiles) },
        appId = appId, appKey = appKey,
    )
}

/** 每个厂商单独生成服务器环境变量；未配置的厂商显式输出关闭，避免远端残留旧配置。 */
internal fun DeploymentConfig.oemPushEnvironment(): Map<String, String> {
    val environment = linkedMapOf<String, String>()
    for (vendor in OemPushVendors.ALL) {
        val push = oemPush[vendor]
        val prefix = vendor.uppercase()
        if (push == null) {
            environment["${prefix}_PUSH_ENABLED"] = "false"
            continue
        }
        environment["${prefix}_PUSH_ENABLED"] = "true"
        environment["${prefix}_PUSH_APP_SECRET"] = push.readCredential("appSecret")
        environment["${prefix}_PUSH_PACKAGE_NAME"] = client.androidApplicationId
        environment["${prefix}_PUSH_TITLE"] = client.displayName
        if (push.appId.isNotEmpty()) environment["${prefix}_PUSH_APP_ID"] = push.appId
        if (push.appKey.isNotEmpty()) environment["${prefix}_PUSH_APP_KEY"] = push.appKey
        if (push.channelId.isNotEmpty()) environment["${prefix}_PUSH_CHANNEL_ID"] = push.channelId
        if (push.templateId.isNotEmpty()) environment["${prefix}_PUSH_TEMPLATE_ID"] = push.templateId
        if (push.category.isNotEmpty()) environment["${prefix}_PUSH_CATEGORY"] = push.category
    }
    return environment
}

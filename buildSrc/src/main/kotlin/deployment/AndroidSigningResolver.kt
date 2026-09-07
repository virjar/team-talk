package deployment

import org.gradle.api.GradleException
import java.io.File
import java.util.Properties

/** 仅交给 AGP 的签名参数，包含密码，不属于 DeploymentConfig 或发布快照。 */
class ResolvedAndroidSigning(
    val storeFile: File,
    val keyAlias: String,
    val storePassword: String,
    val keyPassword: String,
)

/**
 * 签名身份：部署 DSL > 环境变量 / local.properties > 固定试用证书。
 * 密码始终从环境变量 / local.properties 读取；未单独配置 keyPassword 时复用 storePassword。
 * 文件解析由调用方提供，沿用 Gradle 对根目录相对路径及绝对路径的处理。
 */
fun resolveAndroidSigning(
    configured: AndroidSigningConfig?,
    trialKeystore: File,
    localPropertiesFile: File,
    environment: (String) -> String?,
    resolveFile: (String) -> File,
): ResolvedAndroidSigning {
    val localProperties = Properties().apply {
        if (localPropertiesFile.exists()) localPropertiesFile.inputStream().use(::load)
    }
    fun value(property: String, variable: String): String? =
        environment(variable) ?: localProperties.getProperty(property)

    val storePath = configured?.storeFile ?: value("release.storeFile", "TEAMTALK_ANDROID_KEYSTORE")
        ?: return ResolvedAndroidSigning(trialKeystore, "teamtalk", "teamtalk", "teamtalk")
    val storeFile = resolveFile(storePath)
    require(storeFile.isFile) {
        "Android signing store file is missing: $storeFile（签名配置选择了自有证书，但文件不存在）"
    }
    val keyAlias = configured?.keyAlias ?: value("release.keyAlias", "TEAMTALK_ANDROID_KEY_ALIAS")
        ?: throw GradleException(
            "Android signing keyAlias is missing: 配置了自有证书，需要 release.keyAlias（local.properties）或 TEAMTALK_ANDROID_KEY_ALIAS（环境变量）",
        )
    val storePassword = value("release.storePassword", "TEAMTALK_ANDROID_STORE_PASSWORD")
        ?: throw GradleException(
            "Android signing storePassword is missing: 配置了自有证书，需要 release.storePassword（local.properties）或 TEAMTALK_ANDROID_STORE_PASSWORD（环境变量）",
        )
    val keyPassword = value("release.keyPassword", "TEAMTALK_ANDROID_KEY_PASSWORD") ?: storePassword
    return ResolvedAndroidSigning(storeFile, keyAlias, storePassword, keyPassword)
}

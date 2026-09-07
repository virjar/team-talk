package deployment

/**
 * Android 自定义签名身份（T012）。客户正式版/私有版用它选择自己的证书，
 * Debug 与 Release 共用同一身份，满足同包名覆盖安装。
 *
 * 只描述证书文件与别名：store/key 密码属于秘密，在签名时从环境变量
 * （TEAMTALK_ANDROID_STORE_PASSWORD / TEAMTALK_ANDROID_KEY_PASSWORD）或
 * Git 忽略的 local.properties（release.storePassword / release.keyPassword）解析，
 * 绝不进入部署配置、发布快照、BuildConfig 或日志。
 */
data class AndroidSigningConfig(
    /** keystore 文件；相对路径按仓库根目录解析，也允许本机绝对路径。 */
    val storeFile: String,
    val keyAlias: String,
) {
    init {
        require(storeFile.isNotBlank() && storeFile == storeFile.trim()) {
            "client.androidSigning.storeFile must be a non-blank path"
        }
        require(keyAlias.isNotBlank() && keyAlias == keyAlias.trim()) {
            "client.androidSigning.keyAlias must be a non-blank alias"
        }
    }
}

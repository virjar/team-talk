package deployment

import java.io.File
import java.net.URI

/** 防止在嵌套章节中无意写入外层同名配置；跨章节复用通过显式辅助函数表达。 */
@DslMarker
annotation class DeploymentDsl

/** 配置所有章节后统一解析默认值，再由不可变配置对象校验并交给构建任务。 */
fun deployment(configure: DeploymentBuilder.() -> Unit): DeploymentConfig =
    DeploymentBuilder().apply(configure).build()

@DeploymentDsl
class DeploymentBuilder internal constructor() {
    private val serverConfiguration = ServerDeploymentBuilder()
    private val deployConfiguration = DeploymentTargetBuilder()
    private val clientConfiguration = ClientDeploymentBuilder()

    fun server(configure: ServerDeploymentBuilder.() -> Unit) { serverConfiguration.apply(configure) }
    fun deploy(configure: DeploymentTargetBuilder.() -> Unit) { deployConfiguration.apply(configure) }
    fun client(configure: ClientDeploymentBuilder.() -> Unit) { clientConfiguration.apply(configure) }

    internal fun build(): DeploymentConfig {
        val url = serverConfiguration.httpConfiguration.url
        require(url.isNotBlank()) { "server.http.url must be configured" }
        val uri = URI(url)
        val tcp = serverConfiguration.tcpConfiguration
        val ssh = deployConfiguration.sshConfiguration
        // 延迟到所有章节完成后计算，配置章节和辅助函数的调用顺序不影响默认主机。
        val defaultHost = uri.host.orEmpty()
        return DeploymentConfig(
            serverUrl = url,
            tcpAddress = "${tcp.host ?: defaultHost}:${tcp.port}",
            deployHost = ssh.host ?: defaultHost,
            deployPort = ssh.port,
            deployUser = ssh.user,
            deployPath = deployConfiguration.directory,
            sslPort = if (uri.scheme.equals("https", ignoreCase = true)) {
                uri.port.takeIf { it != -1 } ?: 443
            } else 443,
            allowCustomServer = clientConfiguration.allowCustomServer,
            client = clientConfiguration.identityConfiguration.build(),
            androidSigning = clientConfiguration.androidSigningConfiguration
                .takeIf { it.storeFile.isNotBlank() || it.keyAlias.isNotBlank() }
                ?.build(),
            tcpTlsCertificatePem = tcp.tlsConfiguration.certificateFile?.readText(Charsets.UTF_8),
            oemPush = clientConfiguration.oemPushConfigurations,
        )
    }
}

@DeploymentDsl
class ServerDeploymentBuilder internal constructor() {
    internal val httpConfiguration = HttpDeploymentBuilder()
    internal val tcpConfiguration = TcpDeploymentBuilder()

    fun http(configure: HttpDeploymentBuilder.() -> Unit) { httpConfiguration.apply(configure) }
    fun tcp(configure: TcpDeploymentBuilder.() -> Unit) { tcpConfiguration.apply(configure) }
}

@DeploymentDsl
class HttpDeploymentBuilder internal constructor() {
    /** 必填。客户端、下载入口和 HTTPS 监听端口均由此地址派生。 */
    var url: String = ""
}

@DeploymentDsl
class TcpDeploymentBuilder internal constructor() {
    /** 留空时使用最终 HTTP URL 的主机；仅在两个入口确实不同时覆写。 */
    var host: String? = null
    var port: Int = 5100
    internal val tlsConfiguration = TcpTlsDeploymentBuilder()

    fun tls(configure: TcpTlsDeploymentBuilder.() -> Unit) { tlsConfiguration.apply(configure) }
}

@DeploymentDsl
class TcpTlsDeploymentBuilder internal constructor() {
    /** 可选的固定信任公共 PEM 证书。文件不存在或格式错误会中止构建，不回退系统信任。 */
    var certificateFile: File? = null
}

@DeploymentDsl
class DeploymentTargetBuilder internal constructor() {
    var directory: String = "/opt/teamtalk"
    internal val sshConfiguration = SshDeploymentBuilder()

    fun ssh(configure: SshDeploymentBuilder.() -> Unit) { sshConfiguration.apply(configure) }
}

@DeploymentDsl
class SshDeploymentBuilder internal constructor() {
    /** 留空时使用最终 HTTP URL 的主机；SSH 入口可以单独指定。 */
    var host: String? = null
    var port: Int = 22
    var user: String = "root"
}

@DeploymentDsl
class ClientDeploymentBuilder internal constructor() {
    var allowCustomServer: Boolean = false
    internal val identityConfiguration = ClientIdentityDeploymentBuilder()
    internal val androidSigningConfiguration = AndroidSigningDeploymentBuilder()
    internal val oemPushConfigurations = linkedMapOf<String, OemPushVendorDeployment>()

    /** 可选；未配置任何厂商的 APK 不包含厂商 SDK，服务端也不调用外部推送。 */
    fun xiaomiPush(configure: XiaomiPushDeploymentBuilder.() -> Unit) {
        oemPushConfigurations[OemPushVendors.XIAOMI] = XiaomiPushDeploymentBuilder().apply(configure).build()
    }

    /** 可选；华为 Push Kit，需要 AGC 开通推送服务的 appId 与官方 AAR。 */
    fun huaweiPush(configure: HuaweiStylePushDeploymentBuilder.() -> Unit) {
        oemPushConfigurations[OemPushVendors.HUAWEI] =
            HuaweiStylePushDeploymentBuilder(OemPushVendors.HUAWEI).apply(configure).build()
    }

    /** 可选；荣耀 Push Kit，镜像华为接入形态。 */
    fun honorPush(configure: HuaweiStylePushDeploymentBuilder.() -> Unit) {
        oemPushConfigurations[OemPushVendors.HONOR] =
            HuaweiStylePushDeploymentBuilder(OemPushVendors.HONOR).apply(configure).build()
    }

    /** 可选；OPPO PUSH，需要审核通过的私信通道 channelId。 */
    fun oppoPush(configure: OppoPushDeploymentBuilder.() -> Unit) {
        oemPushConfigurations[OemPushVendors.OPPO] = OppoPushDeploymentBuilder().apply(configure).build()
    }

    /** 可选；vivo 推送，需要审核通过的消息分类 category。 */
    fun vivoPush(configure: VivoPushDeploymentBuilder.() -> Unit) {
        oemPushConfigurations[OemPushVendors.VIVO] = VivoPushDeploymentBuilder().apply(configure).build()
    }

    /** 可选；魅族 Flyme 推送。 */
    fun meizuPush(configure: MeizuPushDeploymentBuilder.() -> Unit) {
        oemPushConfigurations[OemPushVendors.MEIZU] = MeizuPushDeploymentBuilder().apply(configure).build()
    }

    fun identity(configure: ClientIdentityDeploymentBuilder.() -> Unit) { identityConfiguration.apply(configure) }

    /** 可选。配置后 Debug/Release 都用客户证书签名；密码从环境变量或 local.properties 解析。 */
    fun androidSigning(configure: AndroidSigningDeploymentBuilder.() -> Unit) {
        androidSigningConfiguration.apply(configure)
    }
}

@DeploymentDsl
class AndroidSigningDeploymentBuilder internal constructor() {
    /** keystore 文件；相对路径按仓库根目录解析，也允许本机绝对路径。 */
    var storeFile: String = ""
    var keyAlias: String = ""

    internal fun build(): AndroidSigningConfig = AndroidSigningConfig(storeFile, keyAlias)
}

@DeploymentDsl
class ClientIdentityDeploymentBuilder internal constructor() {
    private val defaults = ClientDistributionIdentity()
    var applicationId: String = defaults.applicationId
    /** Set the final package registered with Android distribution/push services; null preserves older profiles. */
    var androidApplicationId: String? = null
    var displayName: String = defaults.displayName
    var desktopName: String = defaults.desktopName

    internal fun build(): ClientDistributionIdentity = ClientDistributionIdentity(
        applicationId, displayName, desktopName, androidApplicationId ?: "$applicationId.android",
    )
}

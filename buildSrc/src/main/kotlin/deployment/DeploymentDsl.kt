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
            tcpTlsCertificatePem = tcp.tlsConfiguration.certificateFile?.readText(Charsets.UTF_8),
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

    fun identity(configure: ClientIdentityDeploymentBuilder.() -> Unit) { identityConfiguration.apply(configure) }
}

@DeploymentDsl
class ClientIdentityDeploymentBuilder internal constructor() {
    private val defaults = ClientDistributionIdentity()
    var applicationId: String = defaults.applicationId
    var displayName: String = defaults.displayName
    var desktopName: String = defaults.desktopName

    internal fun build(): ClientDistributionIdentity = ClientDistributionIdentity(applicationId, displayName, desktopName)
}

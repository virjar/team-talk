package deployment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.security.cert.CertificateFactory

/**
 * TeamTalk 的单一部署配置。
 *
 * 由 buildSrc 编译的 deploymentConfiguration(rootDir) 创建；所有构造入口共享同一套校验。
 * 只包含可公开的配置，发布时保存其规范化 JSON 快照，以便追溯实际构建参数。
 */
data class DeploymentConfig(
    val serverUrl: String,
    val tcpAddress: String,
    val deployHost: String,
    val deployPort: Int = 22,
    val deployUser: String = "root",
    val deployPath: String = "/opt/teamtalk",
    val sslPort: Int = 443,
    /** 登录页自定义服务器入口（演示站体验）。生产部署保持 false——私有化路径是构建期注入地址。 */
    val allowCustomServer: Boolean = false,
    val client: ClientDistributionIdentity = ClientDistributionIdentity(),
    /** 私有部署 TCP TLS 的公共证书；允许自签证书，绝不能放入私钥。 */
    val tcpTlsCertificatePem: String? = null,
) {
    val serverUri: URI = URI(serverUrl)
    val sslEnabled: Boolean get() = serverUri.scheme.equals("https", ignoreCase = true)
    val tcpHost: String get() = tcpAddress.substringBeforeLast(":")
    val tcpPort: Int get() = tcpAddress.substringAfterLast(":").toInt()

    init {
        require(
            serverUri.scheme.equals("http", ignoreCase = true) ||
                serverUri.scheme.equals("https", ignoreCase = true)
        ) { "serverUrl must use http or https" }
        require(!serverUri.host.isNullOrBlank()) { "serverUrl must be an absolute URL" }
        require(tcpAddress.count { it == ':' } == 1) { "tcpAddress must use host:port format" }
        require(tcpHost.isNotBlank() && tcpPort in 1..65535) { "Invalid tcpAddress" }
        require(deployHost.matches(deployHostPattern)) { "deployHost must be a hostname or IPv4 address" }
        require(deployPort in 1..65535) { "Invalid deployPort" }
        require(deployUser.matches(deployUserPattern)) { "Invalid deployUser" }
        requireCanonicalDeployPath(deployPath)
        require(sslPort in 1..65535) { "Invalid sslPort" }
        if (sslEnabled) {
            val publicSslPort = serverUri.port.takeIf { it != -1 } ?: 443
            require(publicSslPort == sslPort) { "sslPort must match the HTTPS port in serverUrl" }
        }
        tcpTlsCertificatePem?.let { pem ->
            require(singleCertificatePem.matches(pem.trim())) {
                "tcpTlsCertificatePem must contain exactly one public CERTIFICATE PEM block and no private key"
            }
            try {
                CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream(Charsets.UTF_8))
            } catch (exception: java.security.cert.CertificateException) {
                throw IllegalArgumentException("tcpTlsCertificatePem must be a valid X.509 certificate", exception)
            }
        }
    }

    /** 固定字段顺序并写出默认值；源码格式、注释和变量名不影响实际配置的发布指纹。 */
    fun toCanonicalJson(): String {
        val value = buildJsonObject {
            put("serverUrl", serverUrl)
            put("tcpAddress", tcpAddress)
            put("deployHost", deployHost)
            put("deployPort", deployPort)
            put("deployUser", deployUser)
            put("deployPath", deployPath)
            put("sslPort", sslPort)
            put("allowCustomServer", allowCustomServer)
            putJsonObject("client") {
                put("applicationId", client.applicationId)
                put("displayName", client.displayName)
                put("desktopName", client.desktopName)
            }
            put("tcpTlsCertificatePem", tcpTlsCertificatePem)
        }
        return snapshotJson.encodeToString(JsonObject.serializer(), value) + "\n"
    }

    companion object {
        private val snapshotJson = Json { prettyPrint = true }
        private val deployHostPattern = Regex("[A-Za-z0-9._-]+")
        private val deployUserPattern = Regex("[A-Za-z_][A-Za-z0-9_-]*")
        private val singleCertificatePem = Regex(
            "-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----",
        )
    }
}

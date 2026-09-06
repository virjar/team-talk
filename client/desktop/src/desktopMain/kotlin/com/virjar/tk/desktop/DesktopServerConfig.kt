package com.virjar.tk.desktop

import com.virjar.tk.shared.client.ServerConfig
import com.virjar.tk.shared.client.decodeTcpTlsCertificateBase64
import com.virjar.tk.shared.client.defaultServerConfig

/** Desktop 安装包携带公共证书；显式运行时证书仍可覆盖，SDK 不依赖 Desktop 构建常量。 */
internal fun desktopDefaultServerConfig(
    runtimeConfig: ServerConfig = defaultServerConfig(),
    packagedCertificateBase64: String = BuildConfig.TCP_TLS_CERTIFICATE_BASE64,
): ServerConfig = runtimeConfig.copy(
    tcpTlsCertificatePem = runtimeConfig.tcpTlsCertificatePem
        ?: decodeTcpTlsCertificateBase64(packagedCertificateBase64),
)

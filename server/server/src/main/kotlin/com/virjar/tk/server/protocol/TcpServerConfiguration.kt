package com.virjar.tk.server.protocol

import io.netty.handler.ssl.SslContext

/**
 * 服务器组合根拥有的 TCP 传输权威。
 *
 * 监听地址与加密模式分别配置。运行时允许明文绑定任意地址，也支持用 PKCS12 启用 TLS。
 * 私有二进制协议只定义帧结构，不提供传输加密；现有 SDK 与部署工具的限制由各自入口决定，
 * 支持范围统一记录在 doc/07-operations/configuration.md。
 */
internal data class TcpServerConfiguration(
    val bindHost: String,
    val port: Int,
    val security: TcpTransportSecurity,
) {
    init {
        require(isValidTcpBindHost(bindHost)) { "TCP bind host is invalid" }
        require(port in 0..65535) { "TCP listener port must be in 0..65535" }
    }

    companion object {
        /** 明文模式（无 TLS），可绑定任意地址。 */
        fun plaintext(
            port: Int = 0,
            bindHost: String = "0.0.0.0",
        ): TcpServerConfiguration = TcpServerConfiguration(
            bindHost = bindHost,
            port = port,
            security = TcpTransportSecurity.Plaintext,
        )
    }
}

/** 由组合根选择的传输模式；TLS 握手失败不会自动切换到明文。 */
internal sealed interface TcpTransportSecurity {
    class Tls(val context: SslContext) : TcpTransportSecurity
    data object Plaintext : TcpTransportSecurity
}

private fun isValidTcpBindHost(host: String): Boolean =
    host.length in 1..253 && host.none { it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' }

package com.virjar.tk.shared.client

import io.netty.channel.socket.SocketChannel
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslProvider
import java.security.KeyStore
import javax.net.ssl.SSLContext as JdkSslContext
import javax.net.ssl.TrustManagerFactory

/**
 * 面向长连接 IM transport 的 TLS 策略。
 *
 * 生产连接只信任平台 WebPKI，并且始终启用 HTTPS 风格的端点识别。向 [SslContext.newHandler] 提供
 * 对端主机同样重要：它把主机名校验身份与 SNI 一起带进引擎。仅测试用的 loopback transport 保持
 * 明文，这样进程内协议 fixture 不需要证书材料。
 */
internal class ClientTransportTls(
    private val requiresTls: (String) -> Boolean = ::requiresClientTransportTls,
    private val contextFactory: () -> SslContext = ::createSystemWebPkiClientSslContext,
    private val handshakeTimeoutMillis: Long = CLIENT_TLS_HANDSHAKE_TIMEOUT_MILLIS,
) {
    init {
        require(handshakeTimeoutMillis > 0L) { "TLS handshake timeout must be positive" }
    }

    private val context: SslContext by lazy(contextFactory)

    fun newHandler(channel: SocketChannel, host: String, port: Int): SslHandler? =
        if (requiresTls(host)) {
            context.newHandler(channel.alloc(), host, port).apply {
                setHandshakeTimeoutMillis(this@ClientTransportTls.handshakeTimeoutMillis)
            }
        } else {
            null
        }
}

/**
 * 在不解析 [host] 的情况下返回 true。loopback 的 DNS 别名刻意不会削弱 transport：只有下面这些
 * 显式的字面形式才可以选择明文。
 */
internal fun requiresClientTransportTls(host: String): Boolean = !isLexicalLoopbackHost(host)

internal fun isLexicalLoopbackHost(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true) || host == "::1") return true

    val octets = host.split('.')
    if (octets.size != IPV4_OCTET_COUNT || octets.first() != IPV4_LOOPBACK_PREFIX) return false
    return octets.all { octet ->
        octet.isNotEmpty() &&
            octet.all { character -> character in '0'..'9' } &&
            octet.toIntOrNull() in IPV4_OCTET_RANGE
    }
}

/** 使用平台根存储；不安装应用自有的 CA 或宽松的 trust manager。 */
internal fun createSystemWebPkiClientSslContext(): SslContext {
    val systemTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
    val platformProtocols = JdkSslContext.getDefault().supportedSSLParameters.protocols.asIterable()
    return SslContextBuilder.forClient()
        .sslProvider(SslProvider.JDK)
        .trustManager(systemTrust)
        .protocols(selectClientTransportTlsProtocols(platformProtocols))
        // 显式设置，使 Netty 兼容性系统属性无法禁用校验。
        .endpointIdentificationAlgorithm(HOSTNAME_VERIFICATION_ALGORITHM)
        .build()
}

/**
 * 不启用任何低于 TLS 1.2 的协议。TLS 1.3 是机会式的，因为 Android/JVM provider 的支持不一，
 * 而 TLS 1.2 是强制性的互通底线，因此按 fail-fast 处理。
 */
internal fun selectClientTransportTlsProtocols(supportedProtocols: Iterable<String>): List<String> {
    val supported = supportedProtocols.toSet()
    check(TLS_V1_2 in supported) { "Platform TLS provider does not support required TLSv1.2" }
    return MODERN_TLS_PROTOCOLS.filter(supported::contains)
}

internal const val CLIENT_TLS_HANDSHAKE_TIMEOUT_MILLIS = 10_000L
private const val HOSTNAME_VERIFICATION_ALGORITHM = "HTTPS"
private const val TLS_V1_3 = "TLSv1.3"
private const val TLS_V1_2 = "TLSv1.2"
private val MODERN_TLS_PROTOCOLS = listOf(TLS_V1_3, TLS_V1_2)
private const val IPV4_OCTET_COUNT = 4
private const val IPV4_LOOPBACK_PREFIX = "127"
private val IPV4_OCTET_RANGE = 0..255

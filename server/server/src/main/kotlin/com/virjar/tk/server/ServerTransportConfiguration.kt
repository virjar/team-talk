package com.virjar.tk.server

import com.virjar.tk.server.infra.health.TcpHealthProbeConfiguration
import com.virjar.tk.server.infra.health.TcpHealthProbeSecurity
import com.virjar.tk.server.protocol.TcpServerConfiguration
import com.virjar.tk.server.protocol.TcpTransportSecurity
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

internal const val DEFAULT_TCP_PORT = 5100
internal const val DEFAULT_TCP_BIND_HOST = "0.0.0.0"

internal data class ServerTcpTransportConfiguration(
    val server: TcpServerConfiguration,
    val health: TcpHealthProbeConfiguration,
)

/** Loaded once by the production composition root and shared by HTTPS, IM TCP, and self-health. */
internal class ServerTlsMaterial private constructor(
    val keyStore: KeyStore,
    val keyAlias: String,
    private val keyStorePassword: CharArray,
    private val privateKeyPassword: CharArray,
    val tcpServerContext: io.netty.handler.ssl.SslContext,
    val tcpHealthSocketFactory: SSLSocketFactory,
) {
    fun keyStorePasswordCopy(): CharArray = keyStorePassword.copyOf()
    fun privateKeyPasswordCopy(): CharArray = privateKeyPassword.copyOf()

    companion object {
        fun create(
            keyStore: KeyStore,
            keyStorePassword: CharArray,
            privateKeyPassword: CharArray,
        ): ServerTlsMaterial {
            val keyAliases = java.util.Collections.list(keyStore.aliases()).filter(keyStore::isKeyEntry)
            require(keyAliases.size == 1) { "Server PKCS12 must contain exactly one private-key entry" }
            val alias = keyAliases.single()
            requireNotNull(keyStore.getKey(alias, privateKeyPassword)) {
                "Server PKCS12 private key cannot be opened"
            }

            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, privateKeyPassword)
            }
            val tcpServerContext = SslContextBuilder.forServer(keyManagers)
                // JDK engines are channel-owned and need no separate native reference-count release.
                .sslProvider(SslProvider.JDK)
                .protocols("TLSv1.3", "TLSv1.2")
                .build()

            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            // Self-health pins only the configured leaf. Trusting the supplied chain would turn a
            // public intermediate/root into a broad trust anchor and let a different leaf pass.
            trustStore.setCertificateEntry(
                "teamtalk-tcp-leaf",
                requireNotNull(keyStore.getCertificate(alias)) {
                    "Server PKCS12 private-key entry has no certificate"
                },
            )
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(trustStore)
            }
            val tcpHealthContext = SSLContext.getInstance("TLS").apply {
                init(null, trustManagers.trustManagers, null)
            }
            return ServerTlsMaterial(
                keyStore = keyStore,
                keyAlias = alias,
                keyStorePassword = keyStorePassword.copyOf(),
                privateKeyPassword = privateKeyPassword.copyOf(),
                tcpServerContext = tcpServerContext,
                tcpHealthSocketFactory = tcpHealthContext.socketFactory,
            )
        }
    }
}

internal fun loadServerTlsMaterial(environment: Map<String, String>): ServerTlsMaterial? {
    val keyStorePath = environment["SSL_KEYSTORE"] ?: return null
    require(keyStorePath.isNotBlank()) { "SSL_KEYSTORE must not be blank" }
    val keyStorePassword = environment["SSL_KEYSTORE_PASSWORD"]
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("SSL_KEYSTORE_PASSWORD is required when SSL_KEYSTORE is configured")
    val privateKeyPassword = environment["SSL_PRIVATE_KEY_PASSWORD"]
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("SSL_PRIVATE_KEY_PASSWORD is required when SSL_KEYSTORE is configured")
    val keyStoreFile = File(keyStorePath)
    require(keyStoreFile.isFile) { "SSL_KEYSTORE must name a regular PKCS12 file" }

    val keyStorePasswordChars = keyStorePassword.toCharArray()
    val privateKeyPasswordChars = privateKeyPassword.toCharArray()
    return try {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            keyStoreFile.inputStream().use { load(it, keyStorePasswordChars) }
        }
        ServerTlsMaterial.create(keyStore, keyStorePasswordChars, privateKeyPasswordChars)
    } finally {
        keyStorePasswordChars.fill('\u0000')
        privateKeyPasswordChars.fill('\u0000')
    }
}

internal fun serverTcpTransportConfiguration(
    environment: Map<String, String>,
    tlsMaterial: ServerTlsMaterial?,
): ServerTcpTransportConfiguration {
    val bindHost = environment["TCP_HOST"] ?: DEFAULT_TCP_BIND_HOST
    val port = parseTcpPort(environment["TCP_PORT"])
    val serverSecurity = tlsMaterial?.let { TcpTransportSecurity.Tls(it.tcpServerContext) }
        ?: TcpTransportSecurity.Plaintext
    val healthSecurity = tlsMaterial?.let { TcpHealthProbeSecurity.Tls(it.tcpHealthSocketFactory) }
        ?: TcpHealthProbeSecurity.Plaintext
    return ServerTcpTransportConfiguration(
        server = TcpServerConfiguration(bindHost, port, serverSecurity),
        health = TcpHealthProbeConfiguration(tcpHealthConnectHost(bindHost), port, healthSecurity),
    )
}

internal fun parseTcpPort(rawValue: String?): Int {
    if (rawValue == null) return DEFAULT_TCP_PORT
    require(rawValue.matches(Regex("[1-9][0-9]{0,4}"))) {
        "TCP_PORT must be a canonical decimal port in 1..65535"
    }
    return rawValue.toInt().also { port ->
        require(port in 1..65535) { "TCP_PORT must be in 1..65535" }
    }
}

private fun tcpHealthConnectHost(bindHost: String): String = when (bindHost) {
    "0.0.0.0" -> "127.0.0.1"
    "::", "0:0:0:0:0:0:0:0" -> "::1"
    else -> bindHost
}

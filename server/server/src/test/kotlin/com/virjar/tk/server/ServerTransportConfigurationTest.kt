package com.virjar.tk.server

import com.virjar.tk.server.infra.health.TcpHealthProbeSecurity
import com.virjar.tk.server.protocol.TcpTransportSecurity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ServerTransportConfigurationTest {
    @Test
    fun `TCP port is canonical strict and shared by listener and health`() {
        assertEquals(DEFAULT_TCP_PORT, parseTcpPort(null))
        val configured = serverTcpTransportConfiguration(
            environment = mapOf("TCP_PORT" to "15100"),
            tlsMaterial = null,
        )
        assertEquals(15100, configured.server.port)
        assertEquals(15100, configured.health.port)
        assertEquals(DEFAULT_TCP_BIND_HOST, configured.server.bindHost)
        assertEquals("127.0.0.1", configured.health.connectHost) // 0.0.0.0 binds all, health probe connects to loopback

        listOf("", "0", "05100", "+5100", "5100 ", " 5100", "65536", "not-a-port").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) { parseTcpPort(invalid) }
        }
    }

    @Test
    fun `plaintext transport binds any address by design`() {
        listOf("localhost", "127.0.0.1", "0.0.0.0", "192.0.2.10", "tcp.example.com", "::1").forEach { host ->
            val configured = serverTcpTransportConfiguration(
                environment = mapOf("TCP_HOST" to host),
                tlsMaterial = null,
            )
            assertIs<TcpTransportSecurity.Plaintext>(configured.server.security)
            assertIs<TcpHealthProbeSecurity.Plaintext>(configured.health.security)
        }
    }

    @Test
    fun `existing PKCS12 builds TLS for a public listener and its health probe`() {
        val keyStoreFile = File.createTempFile("teamtalk-tcp-test-", ".p12")
        val password = TEST_TLS_PASSWORD.toCharArray()
        try {
            keyStoreFile.outputStream().use { createTestPkcs12().store(it, password) }
            val material = loadServerTlsMaterial(
                mapOf(
                    "SSL_KEYSTORE" to keyStoreFile.absolutePath,
                    "SSL_KEYSTORE_PASSWORD" to TEST_TLS_PASSWORD,
                    "SSL_PRIVATE_KEY_PASSWORD" to TEST_TLS_PASSWORD,
                ),
            )
            val configured = serverTcpTransportConfiguration(
                environment = mapOf("TCP_HOST" to "0.0.0.0", "TCP_PORT" to "15100"),
                tlsMaterial = material,
            )
            assertIs<TcpTransportSecurity.Tls>(configured.server.security)
            assertIs<TcpHealthProbeSecurity.Tls>(configured.health.security)
            assertEquals("127.0.0.1", configured.health.connectHost)
            assertEquals(configured.server.port, configured.health.port)
        } finally {
            password.fill('\u0000')
            keyStoreFile.delete()
        }
    }

    @Test
    fun `configured PKCS12 fails fast when its credential boundary is incomplete`() {
        val keyStoreFile = File.createTempFile("teamtalk-tcp-test-", ".p12")
        try {
            assertFailsWith<IllegalArgumentException> {
                loadServerTlsMaterial(mapOf("SSL_KEYSTORE" to keyStoreFile.absolutePath))
            }
            assertFailsWith<IllegalArgumentException> {
                loadServerTlsMaterial(
                    mapOf(
                        "SSL_KEYSTORE" to keyStoreFile.absolutePath,
                        "SSL_KEYSTORE_PASSWORD" to TEST_TLS_PASSWORD,
                    ),
                )
            }
        } finally {
            keyStoreFile.delete()
        }
    }
}

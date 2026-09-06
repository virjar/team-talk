package deployment

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeploymentDslTest {
    @Test
    fun `defaults follow the final URL after all reusable sections finish`() {
        fun ServerDeploymentBuilder.configureEndpoint() {
            tcp { port = 5510 }
            http { url = "https://first.example.com" }
        }

        val config = deployment {
            deploy { ssh { user = "teamtalk" } }
            server { configureEndpoint() }
            server { http { url = "https://private.example.com:8443" } }
        }
        assertEquals(
            DeploymentConfig(
                serverUrl = "https://private.example.com:8443",
                tcpAddress = "private.example.com:5510",
                deployHost = "private.example.com",
                deployUser = "teamtalk",
                sslPort = 8443,
            ),
            config,
        )
    }

    @Test
    fun `separate TCP and SSH endpoints survive later HTTP changes`() {
        val config = deployment {
            server {
                tcp { host = "tcp.example.com" }
                http { url = "http://files.example.com:8080" }
            }
            deploy {
                directory = "/srv/teamtalk"
                ssh {
                    host = "ssh.example.com"
                    port = 2222
                }
            }
        }
        assertEquals("tcp.example.com:5100", config.tcpAddress)
        assertEquals("ssh.example.com", config.deployHost)
        assertEquals(2222, config.deployPort)
        assertEquals("/srv/teamtalk", config.deployPath)
        assertEquals(443, config.sslPort) // HTTP port is not a dormant HTTPS listener port.
    }

    @Test
    fun `private DSL resolves the certificate and preserves the canonical deployment snapshot`() {
        val root = Files.createTempDirectory("teamtalk-deployment-dsl-").toFile()
        try {
            val certificates = File(root, "tcp-tls")
            generateTcpTlsCertificate(certificates, "192.0.2.10")
            val certificate = File(certificates, "certificate.pem")
            val config = deployment {
                server {
                    http { url = "http://192.0.2.10" }
                    tcp { tls { certificateFile = certificate } }
                }
                client {
                    identity {
                        applicationId = "com.example.internal"
                        displayName = "TeamTalk 内部版"
                        desktopName = "TeamTalkInternal"
                    }
                }
            }
            val expected = DeploymentConfig(
                serverUrl = "http://192.0.2.10",
                tcpAddress = "192.0.2.10:5100",
                deployHost = "192.0.2.10",
                client = ClientDistributionIdentity("com.example.internal", "TeamTalk 内部版", "TeamTalkInternal"),
                tcpTlsCertificatePem = certificate.readText(Charsets.UTF_8),
            )
            assertEquals(expected.toCanonicalJson(), config.toCanonicalJson())
            certificate.delete()
            assertFailsWith<FileNotFoundException> {
                deployment {
                    server {
                        http { url = "http://192.0.2.10" }
                        tcp { tls { certificateFile = certificate } }
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing endpoints and invalid final values fail before tasks use a configuration`() {
        assertFailsWith<IllegalArgumentException> { deployment { client { allowCustomServer = true } } }
        val invalidSections: List<DeploymentBuilder.() -> Unit> = listOf(
            { server { tcp { port = 0 } } },
            { deploy { directory = "/opt/../teamtalk" } },
            { deploy { ssh { port = 65536 } } },
            { client { identity { applicationId = "com.example.private" } } },
        )
        invalidSections.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                deployment {
                    server { http { url = "https://private.example.com" } }
                    invalid()
                }
            }
        }
    }
}

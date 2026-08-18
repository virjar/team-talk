package deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentConfigTest {
    @Test
    fun `loads https deployment`() {
        val config = DeploymentConfig.load(
            """
            {
              "serverUrl": "https://im.example.com",
              "tcpAddress": "tcp.example.com:5100",
              "deployHost": "deploy.example.com",
              "deployPort": 2222,
              "deployUser": "teamtalk",
              "deployPath": "/srv/teamtalk",
              "sslPort": 443
            }
            """.trimIndent()
        )

        assertTrue(config.sslEnabled)
        assertEquals("tcp.example.com", config.tcpHost)
        assertEquals(5100, config.tcpPort)
        assertEquals(2222, config.deployPort)
    }

    @Test
    fun `allows http and independent endpoint hosts`() {
        val config = DeploymentConfig.load(
            """
            {
              "serverUrl": "http://api.internal:8080",
              "tcpAddress": "tcp.internal:15100",
              "deployHost": "10.0.0.8",
              "deployUser": "ops",
              "deployPath": "/opt/teamtalk"
            }
            """.trimIndent()
        )

        assertFalse(config.sslEnabled)
        assertEquals("api.internal", config.serverUri.host)
        assertEquals("tcp.internal", config.tcpHost)
        assertEquals("10.0.0.8", config.deployHost)
        assertEquals(22, config.deployPort)
    }

    @Test
    fun `rejects unknown fields`() {
        assertFailsWith<IllegalArgumentException> {
            DeploymentConfig.load(
                """
                {
                  "serverUrl": "http://localhost:8080",
                  "tcpAddress": "localhost:5100",
                  "deployHost": "localhost",
                  "deployPath": "/opt/teamtalk",
                  "environment": "staging"
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `rejects mismatched https port`() {
        assertFailsWith<IllegalArgumentException> {
            DeploymentConfig.load(
                """
                {
                  "serverUrl": "https://im.example.com:8443",
                  "tcpAddress": "im.example.com:5100",
                  "deployHost": "im.example.com",
                  "deployPath": "/opt/teamtalk",
                  "sslPort": 443
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `rejects root deployment path`() {
        assertFailsWith<IllegalArgumentException> {
            DeploymentConfig.load(
                """
                {
                  "serverUrl": "http://localhost:8080",
                  "tcpAddress": "localhost:5100",
                  "deployHost": "localhost",
                  "deployPath": "/"
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `rejects shell syntax in deployment target`() {
        assertFailsWith<IllegalArgumentException> {
            DeploymentConfig.load(
                """
                {
                  "serverUrl": "http://localhost:8080",
                  "tcpAddress": "localhost:5100",
                  "deployHost": "localhost",
                  "deployPath": "/opt/teamtalk;shutdown"
                }
                """.trimIndent()
            )
        }
    }
}

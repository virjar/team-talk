package deployment

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties
import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeploymentConfigTest {
    @Test
    fun `deployment mode accepts only an empty target or a complete installation`() {
        assertEquals(
            DeploymentMode.FIRST_DEPLOY,
            RemoteDeploymentState(
                distributionPresent = false,
                environmentPresent = false,
                composePresent = false,
                systemdUnitPresent = false,
                dataEpochPresent = false,
                datasetIdPresent = false,
                deployPathPopulated = false,
            ).requireDeploymentMode(),
        )
        assertEquals(
            DeploymentMode.UPGRADE,
            RemoteDeploymentState(
                distributionPresent = true,
                environmentPresent = true,
                composePresent = true,
                systemdUnitPresent = true,
                dataEpochPresent = true,
                datasetIdPresent = true,
                deployPathPopulated = true,
            ).requireDeploymentMode(),
        )
        assertFailsWith<GradleException> {
            RemoteDeploymentState(
                distributionPresent = false,
                environmentPresent = true,
                composePresent = true,
                systemdUnitPresent = true,
                dataEpochPresent = true,
                datasetIdPresent = true,
                deployPathPopulated = true,
            ).requireDeploymentMode()
        }
        assertFailsWith<GradleException> {
            RemoteDeploymentState(
                distributionPresent = false,
                environmentPresent = false,
                composePresent = false,
                systemdUnitPresent = false,
                dataEpochPresent = false,
                datasetIdPresent = false,
                deployPathPopulated = true,
            ).requireDeploymentMode()
        }
    }

    @Test
    fun `remote deployment state parser rejects omissions duplicates and unknown values`() {
        val complete = """
            distribution=1
            environment=1
            compose=1
            systemd=1
            dataEpoch=1
            datasetId=1
            populated=1
        """.trimIndent()
        assertEquals(DeploymentMode.UPGRADE, parseRemoteDeploymentState(complete).requireDeploymentMode())
        assertFailsWith<IllegalArgumentException> {
            parseRemoteDeploymentState(complete.substringBeforeLast('\n'))
        }
        assertFailsWith<IllegalArgumentException> {
            parseRemoteDeploymentState("$complete\ndistribution=1")
        }
        assertFailsWith<IllegalArgumentException> {
            parseRemoteDeploymentState(complete.replace("compose=1", "compose=2"))
        }
    }

    @Test
    fun `deployment probe and lock remain exact to the canonical target`() {
        val command = deploymentStateReadCommand("/opt/teamtalk")
        assertTrue(command.contains("/opt/teamtalk/current/bin"))
        assertTrue(command.contains("/opt/teamtalk/data/data-epoch"))
        assertTrue(command.contains("/opt/teamtalk/data/dataset-id"))
        assertTrue(command.contains("/etc/systemd/system/teamtalk.service"))
        val lock = remoteDeploymentLockPath("/opt/teamtalk")
        assertTrue(lock.matches(Regex("/run/lock/teamtalk-deploy-[0-9a-f]{24}\\.lock")))
        assertEquals(lock, remoteDeploymentLockPath("/opt/teamtalk"))
        assertFalse(lock == remoteDeploymentLockPath("/srv/teamtalk"))
    }

    @Test
    fun `saved deployment secrets contain every active credential and round trip`() {
        val output = File.createTempFile("teamtalk-deployment-secrets-", ".properties")
        try {
            val secrets = Properties().apply {
                setProperty("DATABASE_PASSWORD", "database-secret")
                setProperty("JWT_SECRET", "obsolete-secret")
                setProperty("SSL_KEYSTORE_PASSWORD", "tls-secret")
                setProperty("SSL_PRIVATE_KEY_PASSWORD", "tls-secret")
                setProperty("ADMIN_USER", "admin")
                setProperty("ADMIN_PASSWORD", "admin-\$-`-\\-'secret")
            }

            saveSecrets(output, secrets)

            val saved = output.readText()
            assertTrue(saved.contains("DATABASE_PASSWORD=database-secret"))
            assertTrue(saved.contains("SSL_KEYSTORE_PASSWORD=tls-secret"))
            assertTrue(saved.contains("SSL_PRIVATE_KEY_PASSWORD=tls-secret"))
            assertTrue(saved.contains("ADMIN_USER=admin"))
            val reloaded = Properties().apply {
                output.reader(StandardCharsets.UTF_8).use { load(it) }
            }
            assertEquals("admin-\$-`-\\-'secret", reloaded.getProperty("ADMIN_PASSWORD"))
            assertFalse(saved.contains("JWT_SECRET"))
            assertFalse(saved.contains("obsolete-secret"))
        } finally {
            output.delete()
        }
    }

    @Test
    fun `generated env contains active secrets without obsolete auth secret`() {
        val secrets = Properties().apply {
            setProperty("DATABASE_PASSWORD", "database-secret")
            setProperty("JWT_SECRET", "obsolete-secret")
            setProperty("SSL_KEYSTORE_PASSWORD", "tls-secret")
            setProperty("SSL_PRIVATE_KEY_PASSWORD", "tls-secret")
            setProperty("ADMIN_USER", "admin")
            setProperty("ADMIN_PASSWORD", "admin-secret")
        }

        val env = generateEnvShContent(
            secrets = secrets,
            sslEnabled = true,
            sslPort = "443",
            deployPath = "/opt/teamtalk",
            httpPort = 8080,
            tcpPort = "5100",
        )

        assertTrue(env.contains("DATABASE_PASSWORD='database-secret'"))
        assertTrue(env.contains("ADMIN_USER='admin'"))
        assertTrue(env.contains("ADMIN_PASSWORD='admin-secret'"))
        assertTrue(env.contains("SSL_KEYSTORE_PASSWORD='tls-secret'"))
        assertTrue(env.contains("SSL_PRIVATE_KEY_PASSWORD='tls-secret'"))
        assertTrue(env.contains("TCP_HOST=0.0.0.0"))
        assertTrue(env.contains("TCP_PORT=5100"))
        assertFalse(env.contains("JWT_SECRET"))
        assertFalse(env.contains("obsolete-secret"))
        requiredDeploymentSecretKeys.forEach { key ->
            assertEquals(1, env.lineSequence().count { it.startsWith("$key=") }, key)
        }
    }

    @Test
    fun `rollback listener parser preserves previous http or https endpoint`() {
        assertEquals(
            RemoteHealthEndpoint(sslEnabled = false, httpPort = 8080, sslPort = 443),
            parseRemoteHealthEndpoint(""),
        )
        assertEquals(
            RemoteHealthEndpoint(sslEnabled = false, httpPort = 18080, sslPort = 443),
            parseRemoteHealthEndpoint("KTOR_PORT=18080"),
        )
        assertEquals(
            RemoteHealthEndpoint(sslEnabled = true, httpPort = 18080, sslPort = 8443),
            parseRemoteHealthEndpoint("KTOR_PORT=18080\nKTOR_SSL_PORT=8443"),
        )
        listOf(
            "KTOR_PORT=0",
            "KTOR_SSL_PORT=65536",
            "KTOR_PORT=abc",
            "KTOR_PORT=8080\nKTOR_PORT=8080",
            "DATABASE_PASSWORD=not-a-listener",
        ).forEach { malformed ->
            assertFailsWith<IllegalArgumentException>(malformed) {
                parseRemoteHealthEndpoint(malformed)
            }
        }
        val command = remoteHealthEndpointReadCommand("/opt/teamtalk")
        assertTrue(command.contains("/opt/teamtalk/conf/env.sh"))
        assertFalse(command.contains("PASSWORD"))
    }

    @Test
    fun `upgrade protocol floor is preserved only within the target artifact window`() {
        val target = ServerProtocolWindow(0, 2, 5)
        assertNull(parseRemoteMinimumProtocolMinor("", target))
        assertEquals(0, parseRemoteMinimumProtocolMinor("MINIMUM_PROTOCOL_MINOR=0", ServerProtocolWindow(0, 0, 0)))
        val secrets = Properties().apply {
            requiredDeploymentSecretKeys.forEach { setProperty(it, "test-secret") }
        }
        listOf(2, 3, 5).forEach { value ->
            val retained = parseRemoteMinimumProtocolMinor("MINIMUM_PROTOCOL_MINOR=$value\n", target)
            val env = generateEnvShContent(secrets, false, "443", "/opt/teamtalk", 8080, "5100", retained)
            assertEquals(1, env.lineSequence().count { it == "MINIMUM_PROTOCOL_MINOR=$value" })
        }
        val defaultEnv = generateEnvShContent(secrets, false, "443", "/opt/teamtalk", 8080, "5100")
        assertFalse(defaultEnv.contains("MINIMUM_PROTOCOL_MINOR="))

        listOf(
            "MINIMUM_PROTOCOL_MINOR=1", "MINIMUM_PROTOCOL_MINOR=6",
            "MINIMUM_PROTOCOL_MINOR=", "MINIMUM_PROTOCOL_MINOR=-1", "MINIMUM_PROTOCOL_MINOR=02",
            "MINIMUM_PROTOCOL_MINOR=65536", "MINIMUM_PROTOCOL_MINOR=99999999999999999999",
            "MINIMUM_PROTOCOL_MINOR='2'", "MINIMUM_PROTOCOL_MINOR=2 ", "MINIMUM_PROTOCOL_MINOR=2 # comment",
            "export MINIMUM_PROTOCOL_MINOR=2", " MINIMUM_PROTOCOL_MINOR=2", "MINIMUM_PROTOCOL_MINOR =2",
            "MINIMUM_PROTOCOL_MINOR=2\nMINIMUM_PROTOCOL_MINOR=2", "INVALID_MINIMUM_PROTOCOL_MINOR",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) { parseRemoteMinimumProtocolMinor(invalid, target) }
        }
    }

    @Test
    fun `http env still persists the complete upgrade secret authority`() {
        val secrets = Properties().apply {
            setProperty("DATABASE_PASSWORD", "database-secret")
            setProperty("SSL_KEYSTORE_PASSWORD", "tls-secret")
            setProperty("SSL_PRIVATE_KEY_PASSWORD", "tls-secret")
            setProperty("ADMIN_USER", "admin")
            setProperty("ADMIN_PASSWORD", "admin-secret")
        }

        val env = generateEnvShContent(
            secrets = secrets,
            sslEnabled = false,
            sslPort = "443",
            deployPath = "/opt/teamtalk",
            httpPort = 8080,
            tcpPort = "5100",
        )

        val parsed = parseRequiredUpgradeSecrets(env)
        requiredDeploymentSecretKeys.forEach { key ->
            assertEquals(secrets.getProperty(key), parsed.getProperty(key), key)
            assertEquals(1, env.lineSequence().count { it.startsWith("$key=") }, key)
        }
        assertFalse(env.contains("KTOR_SSL_PORT="))
        assertFalse(env.contains("SSL_KEYSTORE="))
        assertTrue(env.contains("TCP_HOST=127.0.0.1"))
        assertTrue(env.contains("TCP_PORT=5100"))
    }

    @Test
    fun `deployment env writes the configured TCP port once and rejects malformed values`() {
        val secrets = Properties().apply {
            setProperty("DATABASE_PASSWORD", "database-secret")
            setProperty("SSL_KEYSTORE_PASSWORD", "tls-secret")
            setProperty("SSL_PRIVATE_KEY_PASSWORD", "tls-secret")
            setProperty("ADMIN_USER", "admin")
            setProperty("ADMIN_PASSWORD", "admin-secret")
        }
        val env = generateEnvShContent(
            secrets = secrets,
            sslEnabled = true,
            sslPort = "443",
            deployPath = "/opt/teamtalk",
            httpPort = 8080,
            tcpPort = "15100",
        )
        assertEquals(1, env.lineSequence().count { it == "TCP_PORT=15100" })
        listOf("", "0", "05100", "+5100", "5100 ", "65536", "not-a-port").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                generateEnvShContent(
                    secrets = secrets,
                    sslEnabled = true,
                    sslPort = "443",
                    deployPath = "/opt/teamtalk",
                    httpPort = 8080,
                    tcpPort = invalid,
                )
            }
        }
    }

    @Test
    fun `generated systemd service delegates process shutdown to systemd`() {
        val service = generateSystemdServiceContent("/opt/teamtalk")

        assertFalse(service.contains("ExecStop="))
        assertFalse(service.contains("MAINPID"))
        assertTrue(service.contains("WorkingDirectory=/opt/teamtalk"))
        assertTrue(service.contains("ExecStart=/opt/teamtalk/bin/teamtalk.sh"))
        assertTrue(service.contains("SuccessExitStatus=143"))
    }

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

    @Test
    fun `deployment path is canonical absolute and contains no traversal segments`() {
        assertEquals("/srv/team-talk_1.2", requireCanonicalDeployPath("/srv/team-talk_1.2"))

        listOf(
            "/", ".", "opt/teamtalk", "/.", "/..", "/opt/.", "/opt/../teamtalk",
            "/opt//teamtalk", "/opt/teamtalk/", "/opt/team talk", "/opt\\teamtalk", "/opt/团队沟通",
        ).forEach { unsafe ->
            assertFailsWith<IllegalArgumentException>(unsafe) {
                requireCanonicalDeployPath(unsafe)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            DeploymentConfig.load(
                """
                {
                  "serverUrl": "http://localhost:8080",
                  "tcpAddress": "localhost:5100",
                  "deployHost": "localhost",
                  "deployPath": "/opt/../teamtalk"
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `upgrade epoch preflight derives current source epoch and rejects either stale store`() {
        assertEquals(9, parseServerDataEpoch("object ServerDataEpoch { const val CURRENT_EPOCH = 9 }"))
        assertNull(upgradeEpochRejectionMessage(9, "9", "9", "/opt/teamtalk"))

        val staleData = requireNotNull(
            upgradeEpochRejectionMessage(9, "8", "9", "/opt/teamtalk"),
        )
        assertTrue(staleData.contains("blocked before stopping or overwriting"))
        assertTrue(staleData.contains("reviewed migration for both PostgreSQL and /opt/teamtalk/data"))
        assertTrue(staleData.contains("Destructive reset requires explicit authorization"))
        assertTrue(staleData.contains("did not delete any data"))

        val missingSchema = requireNotNull(
            upgradeEpochRejectionMessage(9, "9", null, "/opt/teamtalk"),
        )
        assertTrue(missingSchema.contains("PostgreSQL schema=missing/unreadable"))

        assertTrue(dataEpochReadCommand("/opt/teamtalk").contains("/opt/teamtalk/data/data-epoch"))
        assertTrue(schemaEpochReadCommand("/opt/teamtalk").contains("SELECT epoch FROM schema_metadata"))
        assertTrue(dataDatasetIdReadCommand("/opt/teamtalk").contains("/opt/teamtalk/data/dataset-id"))
        assertTrue(schemaDatasetIdReadCommand("/opt/teamtalk").contains("SELECT dataset_id"))

        val datasetId = "00000000-0000-4000-8000-000000000001"
        assertNull(datasetIdentityRejectionMessage(datasetId, datasetId, "/opt/teamtalk"))
        assertTrue(
            requireNotNull(datasetIdentityRejectionMessage(datasetId, null, "/opt/teamtalk"))
                .contains("PostgreSQL dataset identity is missing or invalid"),
        )
        assertTrue(
            requireNotNull(
                datasetIdentityRejectionMessage(
                    datasetId,
                    "00000000-0000-4000-8000-000000000002",
                    "/opt/teamtalk",
                ),
            ).contains("belong to different datasets"),
        )
        assertTrue(
            requireNotNull(
                datasetIdentityRejectionMessage(
                    datasetId.replaceFirst("-", "- "),
                    datasetId,
                    "/opt/teamtalk",
                ),
            ).contains("local dataset marker is missing or invalid"),
        )
    }

    @Test
    fun `upgrade rsync deletes stale distribution while preserving instance state`() {
        val args = upgradeRsyncArguments(
            distDir = File("server-dist"),
            user = "deploy",
            host = "example.com",
            port = 2222,
            deployPath = "/opt/teamtalk",
        )

        assertTrue("--delete" in args)
        assertTrue("--no-owner" in args)
        assertTrue("--no-group" in args)
        listOf(
            "--exclude=/data/",
            "--exclude=/logs/",
            "--exclude=/conf/env.sh",
            "--exclude=/conf/ssl/",
            "--exclude=/docker-compose.yml",
            "--exclude=/.pid",
            "--exclude=/static/downloads/",
        ).forEach { exclusion -> assertTrue(exclusion in args, "missing $exclusion") }
        assertEquals("deploy@example.com:/opt/teamtalk/", args.last())
    }

    @Test
    fun `env values with shell metacharacters round trip literally through POSIX sh`() {
        val special = "dollar\$ backtick` backslash\\ single' double\" end"
        val secrets = Properties().apply {
            setProperty("DATABASE_PASSWORD", special)
            setProperty("ADMIN_USER", "admin'user")
            setProperty("ADMIN_PASSWORD", special)
            setProperty("SSL_KEYSTORE_PASSWORD", special)
            setProperty("SSL_PRIVATE_KEY_PASSWORD", special)
        }
        val env = generateEnvShContent(
            secrets,
            sslEnabled = true,
            sslPort = "443",
            deployPath = "/opt/teamtalk",
            httpPort = 8080,
            tcpPort = "5100",
        )
        assertTrue(env.contains("DATABASE_PASSWORD=${posixShellQuote(special)}"))
        assertEquals(special, parseRequiredUpgradeSecrets(env).getProperty("DATABASE_PASSWORD"))

        val envFile = File.createTempFile("teamtalk-env-round-trip-", ".sh")
        try {
            envFile.writeText(env)
            val process = ProcessBuilder(
                "sh",
                "-c",
                ". \"\$1\"; printf '%s' \"\$DATABASE_PASSWORD\"",
                "teamtalk-env-round-trip",
                envFile.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
            assertEquals(0, process.waitFor())
            assertEquals(special, output)
        } finally {
            envFile.delete()
        }
    }
}

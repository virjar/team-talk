package com.virjar.tk.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import com.virjar.tk.client.DeploymentIdentity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentCredentialsSecurityTest {
    private val roots = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun `credentials are private atomically replaced redacted and keep a stable device id`() {
        val dataDir = File(temporaryRoot(), "agent-data")
        val firstIdentity = AgentCredentials.ensureIdentity(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        val pending = AgentCredentials.beginRegistration(
            dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY, "robot", "private-login-secret",
        )
        AgentCredentials.recordAuthentication(
            dataDir = dataDir,
            deploymentIdentity = TEST_AGENT_DEPLOYMENT_IDENTITY,
            expectedUsername = "robot",
            expectedDeviceId = pending.deviceId,
            uid = "uid-robot",
            authenticatedUsername = "robot",
            refreshToken = "refresh-private-secret",
        )
        val loaded = AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)!!
        val restartedIdentity = AgentCredentials.ensureIdentity(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        val credentialFile = File(dataDir, "credentials.properties")

        assertEquals(firstIdentity.deviceId, loaded.deviceId)
        assertEquals(firstIdentity.deviceId, restartedIdentity.deviceId)
        assertEquals(firstIdentity.apiToken, restartedIdentity.apiToken)
        assertNotEquals(loaded.apiToken, loaded.deviceId)
        assertTrue("private-login-secret" !in loaded.toString())
        assertTrue("refresh-private-secret" !in loaded.toString())
        assertTrue(firstIdentity.apiToken !in firstIdentity.toString())
        assertEquals("robot", loaded.username)
        assertEquals("uid-robot", loaded.uid)
        assertEquals(null, loaded.password)
        assertEquals("refresh-private-secret", loaded.refreshToken)
        assertEquals(AgentCredentialState.ACTIVE, loaded.state)
        assertTrue("private-login-secret" !in credentialFile.readText())
        assertTrue("password=" !in credentialFile.readText())
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(dataDir.toPath()),
        )
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(credentialFile.toPath()),
        )
        val verifiedDirectory = AgentDataDirectoryPolicy.openRuntime(dataDir)
        assertEquals(
            AgentCredentialState.ACTIVE,
            AgentCredentials.requireActiveForInstall(
                verifiedDirectory,
                TEST_AGENT_DEPLOYMENT_IDENTITY,
            ).state,
        )
        assertTrue(dataDir.listFiles().orEmpty().none { it.name.startsWith(".credentials-") })
    }

    @Test
    fun `systemd install gate rejects identity-only and pending credentials`() {
        val identityOnly = File(temporaryRoot(), "identity-only")
        AgentCredentials.ensureIdentity(identityOnly, TEST_AGENT_DEPLOYMENT_IDENTITY)
        assertFailsWith<IllegalArgumentException> {
            AgentCredentials.requireActiveForInstall(
                AgentDataDirectoryPolicy.openRuntime(identityOnly),
                TEST_AGENT_DEPLOYMENT_IDENTITY,
            )
        }

        val pending = File(temporaryRoot(), "pending")
        AgentCredentials.beginRegistration(
            pending, TEST_AGENT_DEPLOYMENT_IDENTITY, "pending-agent", "pending-secret",
        )
        assertFailsWith<IllegalArgumentException> {
            AgentCredentials.requireActiveForInstall(
                AgentDataDirectoryPolicy.openRuntime(pending),
                TEST_AGENT_DEPLOYMENT_IDENTITY,
            )
        }
    }

    @Test
    fun `active refresh rotation is atomic password-free and restart material is redacted`() {
        val dataDir = File(temporaryRoot(), "agent-data")
        val identity = AgentCredentials.ensureIdentity(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        AgentCredentials.recordAuthentication(
            dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY,
            "robot", identity.deviceId, "uid-robot", "robot", "refresh-one",
        )
        AgentCredentials.recordAuthentication(
            dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY,
            "robot", identity.deviceId, "uid-robot", "robot", "refresh-two",
        )

        val restarted = AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)!!
        val refresh = restarted.requireActiveRefresh()
        assertEquals("refresh-two", refresh.refreshToken)
        assertEquals(null, restarted.password)
        assertTrue("refresh-two" !in restarted.toString())
        assertTrue("refresh-two" !in refresh.toString())
        assertTrue("password=" !in File(dataDir, "credentials.properties").readText())
        assertTrue(dataDir.listFiles().orEmpty().none { it.name.startsWith(".credentials-") })
    }

    @Test
    fun `deployment switch invalidates active refresh before it can be loaded`() {
        val dataDir = File(temporaryRoot(), "agent-data")
        val runtimeIdentity = AgentCredentials.ensureIdentity(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        AgentCredentials.recordAuthentication(
            dataDir,
            TEST_AGENT_DEPLOYMENT_IDENTITY,
            "robot",
            runtimeIdentity.deviceId,
            "uid-robot",
            "robot",
            "refresh-bound-to-a",
        )
        val deploymentB = DeploymentIdentity.from(
            tcpHost = TEST_AGENT_DEPLOYMENT_IDENTITY.tcpHost,
            tcpPort = TEST_AGENT_DEPLOYMENT_IDENTITY.tcpPort,
            serverUrl = "https://other-files.test.example/api",
        )

        val switched = requireNotNull(AgentCredentials.load(dataDir, deploymentB))

        assertEquals(deploymentB.fingerprint, switched.deploymentFingerprint)
        assertEquals(null, switched.state)
        assertEquals(null, switched.username)
        assertEquals(null, switched.uid)
        assertEquals(null, switched.refreshToken)
        assertTrue("refresh-bound-to-a" !in File(dataDir, "credentials.properties").readText())

        assertFailsWith<IllegalArgumentException> {
            AgentCredentials.recordAuthentication(
                dataDir,
                TEST_AGENT_DEPLOYMENT_IDENTITY,
                "robot",
                runtimeIdentity.deviceId,
                "uid-robot",
                "robot",
                "late-refresh-for-a",
            )
        }
        val afterLateCallback = requireNotNull(AgentCredentials.load(dataDir, deploymentB))
        assertEquals(null, afterLateCallback.state)
        assertTrue("late-refresh-for-a" !in File(dataDir, "credentials.properties").readText())
    }

    @Test
    fun `credential format without deployment identity is invalidated`() {
        val dataDir = File(temporaryRoot(), "agent-data")
        AgentCredentials.beginRegistration(
            dataDir,
            TEST_AGENT_DEPLOYMENT_IDENTITY,
            "pending-agent",
            "old-format-password",
        )
        val credentialFile = File(dataDir, "credentials.properties")
        val properties = Properties().apply {
            credentialFile.inputStream().use { input -> load(input) }
            remove("deploymentFingerprint")
        }
        credentialFile.outputStream().use { properties.store(it, "old format") }

        val invalidated = requireNotNull(
            AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY),
        )

        assertEquals(null, invalidated.state)
        assertEquals(null, invalidated.username)
        assertEquals(null, invalidated.password)
        assertTrue("old-format-password" !in credentialFile.readText())
    }

    @Test
    fun `legacy active plaintext password fails fast without rewriting the file`() {
        val dataDir = File(temporaryRoot(), "agent-data")
        AgentCredentials.ensureIdentity(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        val credentialFile = File(dataDir, "credentials.properties")
        val properties = Properties().apply {
            credentialFile.inputStream().use { load(it) }
            setProperty("username", "legacy-agent")
            setProperty("password", "legacy-plaintext")
            setProperty("registrationState", AgentCredentialState.ACTIVE.name)
        }
        credentialFile.outputStream().use { properties.store(it, "legacy") }
        val before = credentialFile.readBytes()

        assertFailsWith<IllegalArgumentException> {
            AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        }

        assertTrue(before.contentEquals(credentialFile.readBytes()))
        assertTrue("legacy-plaintext" in credentialFile.readText())
    }

    @Test
    fun `data directory symlink is rejected`() {
        val root = temporaryRoot()
        val realDirectory = File(root, "real").also { assertTrue(it.mkdir()) }
        val linkedDirectory = File(root, "linked")
        Files.createSymbolicLink(linkedDirectory.toPath(), realDirectory.toPath())

        assertFailsWith<IllegalArgumentException> {
            AgentCredentials.ensureIdentity(linkedDirectory, TEST_AGENT_DEPLOYMENT_IDENTITY)
        }
        assertTrue(realDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `credential symlink and over-wide existing file fail without repair`() {
        val root = temporaryRoot()
        val dataDir = File(root, "agent-data")
        AgentCredentials.ensureIdentity(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        val credentialFile = File(dataDir, "credentials.properties")
        Files.setPosixFilePermissions(credentialFile.toPath(), PosixFilePermissions.fromString("rw-rw-rw-"))

        assertFailsWith<IllegalArgumentException> {
            AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        }
        assertEquals(
            PosixFilePermissions.fromString("rw-rw-rw-"),
            Files.getPosixFilePermissions(credentialFile.toPath()),
        )

        Files.delete(credentialFile.toPath())
        val outside = File(root, "outside-secret").apply { writeText("do-not-follow") }
        Files.createSymbolicLink(credentialFile.toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> {
            AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        }
    }

    @Test
    fun `hard-linked credential file is rejected without rewriting either name`() {
        val root = temporaryRoot()
        val dataDir = File(root, "agent-data")
        AgentCredentials.ensureIdentity(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        val credentialFile = File(dataDir, "credentials.properties")
        val hardLink = File(root, "credential-hard-link")
        Files.createLink(hardLink.toPath(), credentialFile.toPath())
        val before = credentialFile.readBytes()

        assertFailsWith<IllegalArgumentException> {
            AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        }

        assertTrue(before.contentEquals(credentialFile.readBytes()))
        assertTrue(before.contentEquals(hardLink.readBytes()))
    }

    private fun temporaryRoot(): File =
        createAgentSecurityTestRoot("agent-credentials-security-").also(roots::add)
}

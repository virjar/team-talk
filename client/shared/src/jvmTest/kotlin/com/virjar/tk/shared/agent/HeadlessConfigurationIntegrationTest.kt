package com.virjar.tk.shared.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadlessConfigurationIntegrationTest {
    @Test
    fun `private deployment survives restart and wrong endpoint leaves credentials intact`() = withRoot { root ->
        val data = File(root, "agent")
        configure(data)
        val saved = requireNotNull(HeadlessConfiguration.load(data))
        val identity = AgentCredentials.ensureIdentity(data, saved.deployment)
        AgentCredentials.recordAuthentication(data, saved.deployment, "robot", identity.deviceId, "uid-robot", "robot", "refresh-test-secret")
        val before = File(data, "credentials.properties").readBytes()
        val restarted = HeadlessConfiguration.resolve(emptyMap(), emptyMap(), HeadlessConfiguration.load(data))
        assertEquals("http://127.0.0.1:18088", restarted.serverUrl)
        assertEquals("127.0.0.1:18600", restarted.api)
        HeadlessConfiguration.requireSameDeployment(data, restarted)
        assertFailsWith<IllegalArgumentException> {
            HeadlessConfiguration.execute("configure", listOf("--data-dir", data.path, "--host", "im.virjar.com", "--server-url", "https://im.virjar.com"))
        }
        assertTrue(before.contentEquals(File(data, "credentials.properties").readBytes()))
        assertEquals(saved, HeadlessConfiguration.load(data))
        val doctor = HeadlessConfiguration.doctor(data).toString()
        assertTrue("ACTIVE" in doctor)
        assertFalse("refresh-test-secret" in doctor)
        assertFalse(identity.apiToken in doctor)
        assertTrue(before.contentEquals(File(data, "credentials.properties").readBytes()))
    }

    @Test
    fun `doctor does not create data and export keeps an explicit private reusable token file`() = withRoot { root ->
        val data = File(root, "agent")
        assertTrue("not-configured" in HeadlessConfiguration.doctor(data).toString())
        assertFalse(data.exists())
        configure(data)
        val settings = requireNotNull(HeadlessConfiguration.load(data))
        val identity = AgentCredentials.ensureIdentity(data, settings.deployment)
        AgentCredentials.recordAuthentication(data, settings.deployment, "robot", identity.deviceId, "uid-robot", "robot", "refresh-test-secret")
        val token = File(root, "cli-token")
        val args = listOf("--data-dir", data.path, "--token-file", token.path)
        HeadlessConfiguration.execute("export-cli-token", args)
        HeadlessConfiguration.execute("export-cli-token", args)
        assertEquals(identity.apiToken, readMcpTokenFile(token))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(token.toPath()))
        assertEquals(identity.apiToken, HeadlessTokenFile.createOrReuse(token) { error("must reuse durable token") })
        val different = File(root, "other-token")
        HeadlessTokenFile.createOrReuse(different) { "a".repeat(43) }
        assertFailsWith<IllegalArgumentException> {
            HeadlessConfiguration.execute("export-cli-token", listOf("--data-dir", data.path, "--token-file", different.path))
        }
        assertEquals("a".repeat(43), readMcpTokenFile(different))
    }

    @Test
    fun `namespace commands require the full owner and explicit archive confirmation before touching storage`() = withRoot { root ->
        val missing = File(root, "not-created")
        val prefix = listOf("--cache-root", missing.path, "--deployment-fingerprint", "a".repeat(64),
            "--dataset-id", "11111111-1111-4111-8111-111111111111")
        assertEquals("--uid is required", assertFailsWith<IllegalArgumentException> {
            HeadlessConfiguration.execute("export-namespace", prefix + listOf("--output", File(root, "archive").path))
        }.message)
        val owner = prefix + listOf("--uid", "alice")
        assertEquals("--output is required and must be a new directory", assertFailsWith<IllegalArgumentException> {
            HeadlessConfiguration.execute("export-namespace", owner)
        }.message)
        val unconfirmed = assertFailsWith<IllegalArgumentException> {
            HeadlessConfiguration.execute("discard-namespace", owner + listOf("--archive", File(root, "archive").path))
        }
        assertTrue(unconfirmed.message.orEmpty().startsWith("--confirm-manifest-sha256 is required"))
        assertEquals("Unknown configuration option", assertFailsWith<IllegalArgumentException> {
            HeadlessConfiguration.execute("export-namespace", owner + listOf("--database", "cache_e0.db"))
        }.message)
        assertEquals("--cache-layout must be jvm or android", assertFailsWith<IllegalStateException> {
            HeadlessConfiguration.execute("export-namespace", owner + listOf("--cache-layout", "device"))
        }.message)
        assertFalse(missing.exists())
        assertFalse(File(root, "archive").exists())
    }

    private fun configure(data: File) = HeadlessConfiguration.execute("configure", listOf(
        "--data-dir", data.path, "--host", "127.0.0.1", "--port", "5100", "--server-url", "http://127.0.0.1:18088", "--api", "127.0.0.1:18600",
    ))

    private fun withRoot(test: (File) -> Unit) {
        val root = createAgentSecurityTestRoot("headless-config-")
        try { test(root) } finally { root.deleteRecursively() }
    }
}

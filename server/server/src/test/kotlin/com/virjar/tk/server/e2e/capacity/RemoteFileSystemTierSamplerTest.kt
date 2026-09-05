package com.virjar.tk.server.e2e.capacity

import com.virjar.tk.server.e2e.RemoteTeamTalkSshTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteFileSystemTierSamplerTest {
    @Test
    fun `sampler reads the exact deployed tier with one bounded ssh command`() {
        val configuration = configuration()
        var observedArguments = emptyList<String>()
        var observedTimeout = 0L
        val sampler = RemoteFileSystemTierSampler(configuration) { arguments, timeout ->
            observedArguments = arguments
            observedTimeout = timeout
            validOutput()
        }

        val snapshot = sampler.sample("after-upload", "2026-09-01T00:00:00Z", PAYLOAD_BYTES)

        assertEquals(remoteFileSystemTierSampleSshArguments(configuration, PAYLOAD_BYTES), observedArguments)
        assertEquals(30_000L, observedTimeout)
        assertEquals(12L, snapshot.fileCount)
        assertEquals(123_456_789L, snapshot.storedBytes)
        assertEquals(1L, snapshot.payloadSizedFileCount)
        assertEquals(9_876_543_210L, snapshot.availableBytes)
    }

    @Test
    fun `filesystem command is read only bounded and cannot alter host networking or service state`() {
        val command = remoteFileSystemTierSampleCommand(configuration(), PAYLOAD_BYTES)

        assertTrue(command.startsWith("set -eu; systemctl is-active --quiet teamtalk;"))
        assertTrue(command.contains("tier_root='/opt/teamtalk/data/file-store/files'"))
        assertTrue(command.contains("find \"\$tier_root\" -xdev -type f -name '*.dat'"))
        assertTrue(command.contains("payload='$PAYLOAD_BYTES'"))
        assertTrue(command.contains("df -B1 --output=avail"))
        assertEquals(2, "systemctl is-active --quiet teamtalk".toRegex().findAll(command).count())
        listOf(
            "systemctl start",
            "systemctl stop",
            "systemctl restart",
            "systemctl kill",
            " rm ",
            " mv ",
            "mkdir",
            "touch",
            "chmod",
            "chown",
            "iptables",
            "nft ",
            "nmcli",
            "curl ",
        ).forEach { forbidden ->
            assertFalse(command.contains(forbidden), "filesystem sample must not contain $forbidden")
        }
    }

    @Test
    fun `parser rejects incomplete duplicate negative and inconsistent counters`() {
        val complete = validOutput()
        assertFailsWith<IllegalArgumentException> {
            parseFileSystemTierSample(
                complete.lineSequence().filterNot { it.startsWith("storedBytes=") }.joinToString("\n"),
                "phase",
                "now",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseFileSystemTierSample("$complete\nfileCount=13", "phase", "now")
        }
        assertFailsWith<IllegalStateException> {
            parseFileSystemTierSample(complete.replace("storedBytes=123456789", "storedBytes=-1"), "phase", "now")
        }
        assertFailsWith<IllegalArgumentException> {
            parseFileSystemTierSample(
                complete.replace("payloadSizedFileCount=1", "payloadSizedFileCount=13"),
                "phase",
                "now",
            )
        }
    }

    @Test
    fun `configuration rejects root traversal and shell syntax`() {
        listOf("/", "relative", "/opt/../teamtalk", "/opt/team talk", "/opt/teamtalk;reboot").forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                RemoteFileSystemTierSamplerConfiguration(
                    RemoteTeamTalkSshTarget("deploy.example.com", "deploy", 22),
                    path,
                )
            }
        }
    }

    private fun configuration() = RemoteFileSystemTierSamplerConfiguration(
        RemoteTeamTalkSshTarget("deploy.example.com", "deploy", 22),
        "/opt/teamtalk",
    )

    private fun validOutput(): String = """
        invocationId=0123456789abcdef0123456789abcdef
        mainPid=4242
        fileCount=12
        storedBytes=123456789
        payloadSizedFileCount=1
        availableBytes=9876543210
    """.trimIndent()

    private companion object {
        const val PAYLOAD_BYTES = FILESYSTEM_TIER_BOUNDARY_BYTES + 1_337L
    }
}

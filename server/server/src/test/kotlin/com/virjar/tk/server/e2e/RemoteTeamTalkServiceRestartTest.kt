package com.virjar.tk.server.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteTeamTalkServiceRestartTest {
    @Test
    fun `fixture runs one fixed bounded ssh restart command and parses evidence`() {
        val target = RemoteTeamTalkSshTarget("deploy.example.com", "deploy", 2222)
        var observedArguments = emptyList<String>()
        var observedTimeout = 0L
        val fixture = RemoteTeamTalkServiceRestart(target) { arguments, timeoutMillis ->
            observedArguments = arguments
            observedTimeout = timeoutMillis
            validEvidence()
        }

        val evidence = fixture.restart()

        assertEquals("ssh", observedArguments.first())
        assertTrue("BatchMode=yes" in observedArguments)
        assertTrue("ConnectTimeout=10" in observedArguments)
        assertTrue("ServerAliveInterval=5" in observedArguments)
        assertTrue("ServerAliveCountMax=3" in observedArguments)
        assertEquals("deploy@deploy.example.com", observedArguments[observedArguments.lastIndex - 1])
        assertEquals(remoteTeamTalkRestartCommand(), observedArguments.last())
        assertEquals(90_000L, observedTimeout)
        assertEquals(101L, evidence.beforeMainPid)
        assertEquals(202L, evidence.afterMainPid)
        assertFalse(evidence.beforeInvocationId == evidence.afterInvocationId)
        assertFalse(evidence.beforeMainPid == evidence.afterMainPid)
    }

    @Test
    fun `remote command can only restart teamtalk and proves a new active invocation`() {
        val command = remoteTeamTalkRestartCommand()

        assertTrue(command.contains("systemctl restart teamtalk"))
        assertTrue(command.contains("systemctl is-active --quiet teamtalk"))
        assertTrue(command.contains("systemctl show teamtalk -p InvocationID --value"))
        assertTrue(command.contains("systemctl show teamtalk -p MainPID --value"))
        assertTrue(command.contains("before_invocation"))
        assertTrue(command.contains("after_invocation"))
        assertFalse(command.contains("docker"))
        assertFalse(command.contains("network"))
    }

    @Test
    fun `restart evidence rejects unchanged invocation and invalid pid`() {
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkRestartEvidence(
                validEvidence(
                    beforeInvocationId = AFTER_INVOCATION_ID,
                    afterInvocationId = AFTER_INVOCATION_ID,
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            parseTeamTalkRestartEvidence(validEvidence(afterMainPid = "0"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseTeamTalkRestartEvidence(validEvidence(afterMainPid = "101"))
        }
    }

    @Test
    fun `ssh target rejects values that could widen the remote command`() {
        assertFailsWith<IllegalArgumentException> {
            RemoteTeamTalkSshTarget("deploy.example.com;shutdown", "deploy", 22)
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteTeamTalkSshTarget("deploy.example.com", "root other", 22)
        }
        assertFailsWith<IllegalArgumentException> {
            RemoteTeamTalkSshTarget("deploy.example.com", "deploy", 0)
        }
    }

    private fun validEvidence(
        beforeInvocationId: String = BEFORE_INVOCATION_ID,
        beforeMainPid: String = "101",
        afterInvocationId: String = AFTER_INVOCATION_ID,
        afterMainPid: String = "202",
    ): String = """
        beforeInvocationId=$beforeInvocationId
        beforeMainPid=$beforeMainPid
        afterInvocationId=$afterInvocationId
        afterMainPid=$afterMainPid
    """.trimIndent()

    private companion object {
        const val BEFORE_INVOCATION_ID = "00000000000000000000000000000001"
        const val AFTER_INVOCATION_ID = "00000000000000000000000000000002"
    }
}

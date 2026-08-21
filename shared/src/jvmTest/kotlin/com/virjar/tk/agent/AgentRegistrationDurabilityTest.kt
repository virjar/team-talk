package com.virjar.tk.agent

import com.virjar.tk.bot.ImBotAuthenticationRejectedException
import com.virjar.tk.client.AuthenticationFailureKind
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentRegistrationDurabilityTest {
    private val roots = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun `pending registration survives commit crash and restart reuses exactly one account`() = runBlocking {
        val dataDir = File(temporaryRoot(), "agent-data")
        val pending = AgentCredentials.beginRegistration(
            dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY, "agent-fixed", "durable-secret",
        )
        val accounts = mutableSetOf<String>()
        var registerCalls = 0

        assertFailsWith<SimulatedCrash> {
            AgentRegistration.recover(
                dataDir = dataDir,
                deploymentIdentity = TEST_AGENT_DEPLOYMENT_IDENTITY,
                pending = pending,
                login = {
                    throw ImBotAuthenticationRejectedException(
                        AuthenticationFailureKind.REJECTED,
                        "account does not exist yet",
                    )
                },
                registerExact = {
                    registerCalls++
                    accounts += requireNotNull(it.username)
                    throw SimulatedCrash()
                },
            )
        }
        val afterCrash = AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)!!
        assertEquals(AgentCredentialState.REGISTER_PENDING, afterCrash.state)
        assertEquals("agent-fixed", afterCrash.username)
        assertEquals(pending.deviceId, afterCrash.deviceId)

        val recovered = AgentRegistration.recover(
            dataDir = dataDir,
            deploymentIdentity = TEST_AGENT_DEPLOYMENT_IDENTITY,
            pending = afterCrash,
            login = {
                val username = requireNotNull(it.username).takeIf(accounts::contains)
                    ?: throw ImBotAuthenticationRejectedException(
                        AuthenticationFailureKind.REJECTED,
                        "account does not exist yet",
                    )
                AgentCredentials.recordAuthentication(
                    dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY,
                    username, it.deviceId, "uid-fixed", username, "refresh-after-crash",
                )
                username
            },
            registerExact = {
                registerCalls++
                error("must not create a second account")
            },
        )

        assertEquals("agent-fixed", recovered)
        assertEquals(setOf("agent-fixed"), accounts)
        assertEquals(1, registerCalls)
        val active = AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)!!
        assertEquals(AgentCredentialState.ACTIVE, active.state)
        assertEquals(null, active.password)
        assertEquals("refresh-after-crash", active.requireActiveRefresh().refreshToken)
        assertTrue("password=" !in File(dataDir, "credentials.properties").readText())
    }

    @Test
    fun `pending registration never falls through to register on transport failure`() = runBlocking {
        val dataDir = File(temporaryRoot(), "agent-data")
        val pending = AgentCredentials.beginRegistration(
            dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY, "agent-fixed", "durable-secret",
        )
        var registerCalls = 0

        assertFailsWith<SimulatedTransportFailure> {
            AgentRegistration.recover(
                dataDir = dataDir,
                deploymentIdentity = TEST_AGENT_DEPLOYMENT_IDENTITY,
                pending = pending,
                login = { throw SimulatedTransportFailure() },
                registerExact = {
                    registerCalls++
                    "must-not-register"
                },
            )
        }

        assertEquals(0, registerCalls)
        assertEquals(
            AgentCredentialState.REGISTER_PENDING,
            AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)?.state,
        )
    }

    @Test
    fun `active dataDir rejects registration and account replacement`() {
        val dataDir = File(temporaryRoot(), "agent-data")
        val identity = AgentCredentials.ensureIdentity(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        AgentCredentials.recordAuthentication(
            dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY, "existing-agent", identity.deviceId,
            "uid-existing", "existing-agent", "refresh-existing",
        )

        assertFailsWith<IllegalStateException> {
            AgentRegistration.beginOrResume(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY, "new-agent")
        }
        assertFailsWith<IllegalArgumentException> {
            AgentCredentials.recordAuthentication(
                dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY, "different-agent", identity.deviceId,
                "uid-different", "different-agent", "refresh-different",
            )
        }
        val active = AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)!!
        assertEquals("existing-agent", active.username)
        assertEquals(null, active.password)
        assertEquals("refresh-existing", active.refreshToken)
        assertEquals(AgentCredentialState.ACTIVE, active.state)
    }

    @Test
    fun `runtime rejects password command-line option before touching storage`() {
        assertFailsWith<IllegalArgumentException> {
            validateAgentRuntimeOptions(mapOf("pass" to "must-not-enter-process-args"))
        }
        assertFailsWith<IllegalArgumentException> {
            validateAgentRuntimeOptions(AgentCli.parse(arrayOf("--pass=must-not-enter-process-args")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateAgentRuntimeOptions(AgentCli.parse(arrayOf("--password=must-not-enter-process-args")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateAgentRuntimeOptions(AgentCli.parse(arrayOf("--token=must-not-enter-process-args")))
        }
        assertTrue(runCatching { validateAgentRuntimeOptions(mapOf("user" to "agent")) }.isSuccess)
    }

    @Test
    fun `active credentials require explicit same-account reauth and preserve local identity`() {
        val dataDir = File(temporaryRoot(), "agent-data")
        val identity = AgentCredentials.ensureIdentity(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)
        AgentCredentials.recordAuthentication(
            dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY, "existing-agent", identity.deviceId,
            "uid-existing", "existing-agent", "refresh-before-revoke",
        )
        val active = AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)!!

        assertFailsWith<IllegalArgumentException> {
            resolveAgentReauthentication(
                requested = false,
                credentials = active,
                suppliedUsername = "existing-agent",
                suppliedPassword = "controlled-secret",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            resolveAgentReauthentication(
                requested = true,
                credentials = active,
                suppliedUsername = "different-agent",
                suppliedPassword = "controlled-secret",
            )
        }
        assertEquals(
            "refresh-before-revoke",
            AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)?.refreshToken,
        )
        val reauth = requireNotNull(
            resolveAgentReauthentication(
                requested = true,
                credentials = active,
                suppliedUsername = "existing-agent",
                suppliedPassword = "controlled-secret",
            ),
        )
        assertEquals("existing-agent", reauth.username)
        assertEquals(identity.deviceId, reauth.deviceId)
        assertTrue("controlled-secret" !in reauth.toString())

        AgentCredentials.recordAuthentication(
            dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY, reauth.username, reauth.deviceId,
            "uid-existing", reauth.username, "refresh-after-reauth",
        )
        val recovered = AgentCredentials.load(dataDir, TEST_AGENT_DEPLOYMENT_IDENTITY)!!
        assertEquals(identity.deviceId, recovered.deviceId)
        assertEquals(identity.apiToken, recovered.apiToken)
        assertEquals("refresh-after-reauth", recovered.refreshToken)
        assertEquals(null, recovered.password)
    }

    private fun temporaryRoot(): File =
        createAgentSecurityTestRoot("agent-registration-").also(roots::add)

    private class SimulatedCrash : RuntimeException()
    private class SimulatedTransportFailure : RuntimeException()
}

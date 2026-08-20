package com.virjar.tk.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentServiceSecurityTest {

    @Test
    fun `systemd plan is non-root hardened and contains no durable authentication material`() {
        val plan = AgentService.buildInstallPlan(
            args = listOf(
                "--host", "im.example.com",
                "--port", "5100",
                "--api", "127.0.0.1:8600",
                "--server-url", "https://im.example.com",
            ),
            resolvedAppHome = "/opt/tt-agent",
        )
        val unit = plan.unit
        val lower = unit.lowercase()

        assertTrue(unit.contains("User=tt-agent"))
        assertTrue(unit.contains("Group=tt-agent"))
        assertFalse(unit.contains("User=root"))
        assertTrue(unit.contains("UMask=0077"))
        assertTrue(unit.contains("StateDirectoryMode=0700"))
        assertTrue(unit.contains("NoNewPrivileges=true"))
        assertTrue(unit.contains("ProtectSystem=strict"))
        assertTrue(unit.contains("ProtectHome=true"))
        assertTrue(unit.contains("PrivateTmp=true"))
        assertTrue(unit.contains("ReadWritePaths=\"/var/lib/tt-agent\""))
        assertTrue(unit.contains("RestartPreventExitStatus=$AGENT_REAUTH_REQUIRED_EXIT_CODE"))
        assertTrue(unit.contains("StartLimitIntervalSec=300"))
        assertTrue(unit.contains("StartLimitBurst=5"))
        assertFalse(unit.contains("EnvironmentFile"))
        assertFalse(unit.contains("TK_PASS"))
        assertFalse(lower.contains("bootstrap"))
        assertFalse(unit.contains("preferIPv4Stack"))
        assertFalse(lower.contains("password"))
        assertFalse(lower.contains("--pass"))
        assertFalse(lower.contains("--register"))
        assertFalse(lower.contains("--reauth"))
        assertFalse(lower.contains("token"))
    }

    @Test
    fun `installer refuses command-line login material and permanent registration`() {
        assertFailsWith<IllegalArgumentException> {
            AgentService.buildInstallPlan(
                listOf("--pass", "do-not-persist"),
                "/opt/tt-agent",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.buildInstallPlan(
                listOf("--pass=do-not-persist"),
                "/opt/tt-agent",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.buildInstallPlan(
                listOf("--register", "--prefix", "bot"),
                "/opt/tt-agent",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.buildInstallPlan(
                listOf("--reauth"),
                "/opt/tt-agent",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.buildInstallPlan(
                listOf("--user", "bot-user"),
                "/opt/tt-agent",
            )
        }
    }

    @Test
    fun `service data preparation is an explicit credential-free first step`() {
        val plan = AgentService.buildDataPlan(
            listOf("--data-dir", "/srv/private-tt-agent", "--service-user", "agent-service"),
        )

        assertEquals("/srv/private-tt-agent", plan.dataDirectory)
        assertEquals("agent-service", plan.serviceUser)
        listOf(
            listOf("--pass", "secret"),
            listOf("--register"),
            listOf("--reauth"),
            listOf("--user", "other"),
            listOf("--service-user", "root"),
        ).forEach { options ->
            assertFailsWith<IllegalArgumentException> {
                AgentService.buildDataPlan(options)
            }
        }
    }

    @Test
    fun `custom data directory does not grant an unrelated default state directory`() {
        val plan = AgentService.buildInstallPlan(
            args = listOf("--data-dir", "/srv/private-tt-agent"),
            resolvedAppHome = "/opt/tt-agent",
        )

        assertEquals("/srv/private-tt-agent", plan.dataDirectory)
        assertTrue(plan.unit.contains("ReadWritePaths=\"/srv/private-tt-agent\""))
        assertFalse(plan.unit.contains("StateDirectory=tt-agent"))
        assertFalse(plan.unit.contains("/var/lib/tt-agent"))
    }

    @Test
    fun `installer rejects root external binds and unit directive injection`() {
        assertFailsWith<IllegalArgumentException> {
            AgentService.buildInstallPlan(
                listOf("--service-user", "root"),
                "/opt/tt-agent",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.buildInstallPlan(
                listOf("--api", "0.0.0.0:8600"),
                "/opt/tt-agent",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.buildInstallPlan(
                listOf("--host", "im.example.com\nEnvironment=ATTACK=1"),
                "/opt/tt-agent",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.buildInstallPlan(
                listOf("--server-url", "https://im.example.com/files?credential=secret"),
                "/opt/tt-agent",
            )
        }
    }

    @Test
    fun `uid zero alias and root primary group are rejected after account resolution`() {
        assertFailsWith<IllegalArgumentException> {
            AgentService.validateServiceIdentity(
                "innocent-alias",
                AgentUnixIdentity("innocent-alias", uid = 0, gid = 20),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.validateServiceIdentity(
                "agent-user",
                AgentUnixIdentity("agent-user", uid = 501, gid = 0),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.validateServiceIdentity(
                "agent-user",
                AgentUnixIdentity("agent-user", uid = 501, gid = 501, primaryGroupName = "staff"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentService.validateServiceIdentity("missing-agent", identity = null)
        }
    }

}

package com.virjar.tk.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentBindPolicyTest {

    @Test
    fun `loopback literals are accepted and canonicalized without DNS`() {
        assertEquals(
            AgentBindEndpoint("127.0.0.1", 8600),
            AgentBindPolicy.parse("localhost:8600"),
        )
        assertEquals(
            AgentBindEndpoint("127.0.0.2", 9000),
            AgentBindPolicy.parse("127.000.000.002:9000"),
        )
        assertEquals(
            AgentBindEndpoint("::1", 8600),
            AgentBindPolicy.parse("[::1]:8600"),
        )
    }

    @Test
    fun `wildcard external DNS and malformed binds are rejected`() {
        listOf(
            "0.0.0.0:8600",
            "192.168.1.4:8600",
            "im.example.com:8600",
            "[::]:8600",
            "::1:8600",
            "127.0.0.1:0",
            "127.0.0.1:65536",
            "127.0.0.1",
            "127.0.0.1:8600\n0.0.0.0:8601",
        ).forEach { bind ->
            assertFailsWith<IllegalArgumentException>(bind) {
                AgentBindPolicy.parse(bind)
            }
        }
    }
}

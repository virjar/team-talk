package com.virjar.tk.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DeploymentIdentityTest {

    @Test
    fun `equivalent TCP and HTTP spellings produce one stable identity`() {
        val first = DeploymentIdentity.from(
            tcpHost = " IM.Example.COM. ",
            tcpPort = 5100,
            serverUrl = "HTTPS://Files.Example.COM:443/api/",
        )
        val second = DeploymentIdentity.from(
            tcpHost = "im.example.com",
            tcpPort = 5100,
            serverUrl = "https://files.example.com/api",
        )

        assertEquals(second, first)
        assertEquals("im.example.com:5100", first.tcpAuthority)
        assertEquals("https://files.example.com/api", first.httpBaseUrl)
        assertEquals(64, first.fingerprint.length)
    }

    @Test
    fun `changing either TCP or HTTP half changes the deployment fingerprint`() {
        val base = DeploymentIdentity.from("im.example.com", 5100, "https://files.example.com/api")
        val otherTcp = DeploymentIdentity.from("im.example.com", 5101, "https://files.example.com/api")
        val otherHttp = DeploymentIdentity.from("im.example.com", 5100, "https://files.example.com/v2")

        assertNotEquals(base.fingerprint, otherTcp.fingerprint)
        assertNotEquals(base.fingerprint, otherHttp.fingerprint)
    }

    @Test
    fun `invalid authority fails before a deployment identity exists`() {
        assertFailsWith<IllegalArgumentException> {
            DeploymentIdentity.from("im.example.com/path", 5100, "https://files.example.com")
        }
        assertFailsWith<IllegalArgumentException> {
            DeploymentIdentity.from("im.example.com", 0, "https://files.example.com")
        }
        assertFailsWith<IllegalArgumentException> {
            DeploymentIdentity.from("im.example.com", 5100, "https://user:secret@files.example.com")
        }
    }
}

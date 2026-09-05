package com.virjar.tk.server.infra.health

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerBuildIdentityTest {
    @Test
    fun `runtime identity requires version and exact build identity`() {
        val identity = readRuntimeBuildIdentity(
            bytes(
                """
                artifactType=server-runtime
                version=1.0.7
                buildIdentity=1.0.7+0123456789abcdef0123456789abcdef01234567
                """.trimIndent(),
            ),
        )

        assertEquals("1.0.7", identity.version)
        assertEquals("1.0.7+0123456789abcdef0123456789abcdef01234567", identity.buildIdentity)
        assertFailsWith<IllegalArgumentException> {
            readRuntimeBuildIdentity(bytes("version=1.0.7"))
        }
        assertFailsWith<IllegalArgumentException> {
            readRuntimeBuildIdentity(bytes("buildIdentity=1.0.7+revision"))
        }
    }

    private fun bytes(value: String) = ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))
}

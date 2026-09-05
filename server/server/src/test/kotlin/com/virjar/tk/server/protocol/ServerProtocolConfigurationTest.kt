package com.virjar.tk.server.protocol

import com.virjar.tk.protocol.ProtocolVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerProtocolConfigurationTest {
    @Test
    fun `runtime minimum retains the build window and rejects unsupported overrides`() {
        assertEquals(ProtocolVersions.SUPPORTED, ServerProtocolConfiguration.fromEnvironment(emptyMap()).supported)
        val key = ServerProtocolConfiguration.MINIMUM_MINOR_ENV
        assertEquals(
            ProtocolVersions.MINOR,
            ServerProtocolConfiguration.fromEnvironment(mapOf(key to ProtocolVersions.MINOR.toString()))
                .supported.minimumMinor,
        )
        listOf("", "-1", "00", " 0", "0 ", (ProtocolVersions.MINOR + 1).toString()).forEach { value ->
            assertFailsWith<IllegalArgumentException>("Invalid minimum: $value") {
                ServerProtocolConfiguration.fromEnvironment(mapOf(key to value))
            }
        }
        if (ProtocolVersions.MINIMUM_MINOR > 0) {
            assertFailsWith<IllegalArgumentException> {
                ServerProtocolConfiguration.fromEnvironment(mapOf(key to (ProtocolVersions.MINIMUM_MINOR - 1).toString()))
            }
        }
    }
}

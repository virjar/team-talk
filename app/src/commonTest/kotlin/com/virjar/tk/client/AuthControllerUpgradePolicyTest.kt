package com.virjar.tk.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthControllerUpgradePolicyTest {
    @Test
    fun `force upgrade requires typed protocol failure`() {
        assertTrue(
            requiresForcedProtocolUpgrade(
                AuthenticationFailure(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED, "upgrade"),
            ),
        )
        assertFalse(
            requiresForcedProtocolUpgrade(
                AuthenticationFailure(AuthenticationFailureKind.REJECTED, "network-like error"),
            ),
        )
        assertFalse(requiresForcedProtocolUpgrade(null))
    }

    @Test
    fun `auto login uses a renewable sync no-progress window instead of identity timeout`() {
        assertEquals(12_000L, autoLoginTimeoutMillis(ConnectionState.DISCONNECTED))
        assertEquals(12_000L, autoLoginTimeoutMillis(ConnectionState.CONNECTING))
        assertEquals(12_000L, autoLoginTimeoutMillis(ConnectionState.CONNECTED))
        assertEquals(35_000L, autoLoginTimeoutMillis(ConnectionState.SYNCHRONIZING))
        assertNull(autoLoginTimeoutMillis(ConnectionState.AUTHENTICATED))
        assertNull(autoLoginTimeoutMillis(ConnectionState.AUTH_FAILED))
    }
}

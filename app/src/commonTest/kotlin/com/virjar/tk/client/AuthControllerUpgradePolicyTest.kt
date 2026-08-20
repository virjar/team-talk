package com.virjar.tk.client

import kotlin.test.Test
import kotlin.test.assertFalse
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
}

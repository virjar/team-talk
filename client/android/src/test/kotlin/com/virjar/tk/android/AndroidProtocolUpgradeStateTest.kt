package com.virjar.tk.android

import com.virjar.tk.protocol.ProtocolVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidProtocolUpgradeStateTest {
    @Test
    fun `known refusal replaces the workspace and its loading surface`() {
        assertEquals(
            AndroidAuthenticationSurface.PROTOCOL_UPGRADE,
            androidAuthenticationSurface(true, true, true, requiresProtocolUpgrade = true),
        )
        assertEquals(
            AndroidAuthenticationSurface.AUTHENTICATED,
            androidAuthenticationSurface(true, true, false, requiresProtocolUpgrade = false),
        )
    }

    @Test
    fun `major minor refusal ID preserves credentials and does not reject the next minor`() {
        val state = AndroidAuthPreferenceState(
            ownerGeneration = 1,
            deploymentFingerprint = "deployment",
            uid = "user-a",
            refreshToken = "refresh-a",
            datasetId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        )
        val rejected = rejectAndroidAuthProtocolVersion(state, "deployment", ProtocolVersion(1, 7).id)
        assertTrue(rejected.applied)
        val claimed = claimAndroidAuthOwner(rejected.state, "deployment")
        assertEquals("user-a", claimed.owner.savedLogin?.uid)
        assertEquals(setOf(ProtocolVersion(1, 7).id), claimed.owner.rejectedProtocolVersions)
        assertTrue(ProtocolVersion(1, 8).id !in claimed.owner.rejectedProtocolVersions)
    }
}

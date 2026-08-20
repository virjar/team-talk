package com.virjar.tk.infra.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientRegistryCredentialPolicyTest {
    @Test
    fun `same-device replacement cannot regress either credential epoch`() {
        assertFalse(credentialEpochsDoNotRegress(2, 3, 2, 3))
        assertFalse(credentialEpochsDoNotRegress(3, 3, 2, 3))
        assertTrue(credentialEpochsDoNotRegress(2, 4, 2, 3))
        assertTrue(credentialEpochsDoNotRegress(3, 4, 2, 3))

        assertFalse(credentialEpochsDoNotRegress(1, 3, 2, 3))
        assertFalse(credentialEpochsDoNotRegress(2, 2, 2, 3))
        assertFalse(credentialEpochsDoNotRegress(3, 2, 2, 3))
    }

    @Test
    fun `admission accepts fence equality and rejects either stale epoch`() {
        assertTrue(credentialEpochsMeetFences(2, 4, 2, 4))
        assertTrue(credentialEpochsMeetFences(3, 5, 2, 4))

        assertFalse(credentialEpochsMeetFences(1, 4, 2, 4))
        assertFalse(credentialEpochsMeetFences(2, 3, 2, 4))
        assertFalse(credentialEpochsMeetFences(0, 4, 0, 4))
        assertFalse(credentialEpochsMeetFences(2, 0, 2, 0))
    }
}

package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidAuthOwnerLifecycleTest {
    private val deploymentA = "a".repeat(64)
    private val deploymentB = "b".repeat(64)

    @Test
    fun `claim invalidates a legacy login without deployment identity`() {
        val legacy = AndroidAuthPreferenceState(
            uid = "user-a",
            refreshToken = "refresh-a",
        )

        val claimed = claimAndroidAuthOwner(legacy, deploymentA)

        assertEquals(1L, claimed.owner.generation)
        assertNull(claimed.owner.savedLogin)
        assertNull(claimed.state.toStoredLogin())
        assertEquals(deploymentA, claimed.state.deploymentFingerprint)
        assertNull(claimed.state.uid)
        assertNull(claimed.state.refreshToken)
    }

    @Test
    fun `old activity cannot clear or overwrite a newer activity token`() {
        val firstClaim = claimAndroidAuthOwner(AndroidAuthPreferenceState(), deploymentA)
        val firstSave = saveAndroidAuthLogin(
            firstClaim.state,
            deploymentA,
            firstClaim.owner.generation,
            uid = "user-a",
            refreshToken = "refresh-from-first-activity",
        )
        assertTrue(firstSave.applied)
        val firstLogin = requireNotNull(firstSave.state.toStoredLogin())

        // Returning from an external Activity used to create another MainActivity/AuthController.
        // The new owner takes over the same credential before its successful auth rotates it.
        val secondClaim = claimAndroidAuthOwner(firstSave.state, deploymentA)
        val secondSave = saveAndroidAuthLogin(
            secondClaim.state,
            deploymentA,
            secondClaim.owner.generation,
            uid = "user-a",
            refreshToken = "refresh-from-second-activity",
        )
        assertTrue(secondSave.applied)
        val secondLogin = requireNotNull(secondSave.state.toStoredLogin())

        val staleClear = clearAndroidAuthLogin(secondSave.state, firstLogin)
        assertFalse(staleClear.applied)
        assertEquals(secondLogin, staleClear.state.toStoredLogin())

        val staleSave = saveAndroidAuthLogin(
            staleClear.state,
            deploymentA,
            firstClaim.owner.generation,
            uid = "user-a",
            refreshToken = "late-refresh-from-first-activity",
        )
        assertFalse(staleSave.applied)
        assertEquals(secondLogin, staleSave.state.toStoredLogin())
    }

    @Test
    fun `only exact uid token and owner snapshot can clear login`() {
        val claim = claimAndroidAuthOwner(AndroidAuthPreferenceState(), deploymentA)
        val saved = saveAndroidAuthLogin(
            claim.state,
            deploymentA,
            claim.owner.generation,
            uid = "user-a",
            refreshToken = "refresh-current",
        ).state
        val current = requireNotNull(saved.toStoredLogin())

        assertFalse(
            clearAndroidAuthLogin(
                saved,
                current.copy(uid = "user-b"),
            ).applied,
        )
        assertFalse(
            clearAndroidAuthLogin(
                saved,
                current.copy(refreshToken = "refresh-stale"),
            ).applied,
        )
        assertFalse(
            clearAndroidAuthLogin(
                saved,
                current.copy(ownerGeneration = current.ownerGeneration + 1L),
            ).applied,
        )

        val cleared = clearAndroidAuthLogin(saved, current)
        assertTrue(cleared.applied)
        assertNull(cleared.state.toStoredLogin())
        assertEquals(current.ownerGeneration, cleared.state.ownerGeneration)
    }

    @Test
    fun `partial legacy credentials are never exposed to auto login`() {
        val claimed = claimAndroidAuthOwner(
            AndroidAuthPreferenceState(uid = "user-a", refreshToken = null),
            deploymentA,
        )

        assertNull(claimed.owner.savedLogin)
        assertNull(claimed.state.uid)
        assertNull(claimed.state.refreshToken)
    }

    @Test
    fun `switching either half of deployment invalidates login`() {
        val firstClaim = claimAndroidAuthOwner(AndroidAuthPreferenceState(), deploymentA)
        val saved = saveAndroidAuthLogin(
            firstClaim.state,
            deploymentA,
            firstClaim.owner.generation,
            uid = "user-a",
            refreshToken = "refresh-a",
        ).state

        val switched = claimAndroidAuthOwner(saved, deploymentB)

        assertNull(switched.owner.savedLogin)
        assertEquals(deploymentB, switched.state.deploymentFingerprint)
        assertNull(switched.state.uid)
        assertNull(switched.state.refreshToken)
    }
}

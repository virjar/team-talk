package com.virjar.tk

import com.virjar.tk.client.StoredLogin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidAuthOwnerLifecycleTest {

    @Test
    fun `claim migrates a legacy login into the new owner generation`() {
        val legacy = AndroidAuthPreferenceState(
            uid = "user-a",
            refreshToken = "refresh-a",
        )

        val claimed = claimAndroidAuthOwner(legacy)

        assertEquals(1L, claimed.owner.generation)
        assertEquals(
            StoredLogin("user-a", "refresh-a", ownerGeneration = 1L),
            claimed.owner.savedLogin,
        )
        assertEquals(claimed.owner.savedLogin, claimed.state.toStoredLogin())
    }

    @Test
    fun `old activity cannot clear or overwrite a newer activity token`() {
        val firstClaim = claimAndroidAuthOwner(AndroidAuthPreferenceState())
        val firstSave = saveAndroidAuthLogin(
            firstClaim.state,
            firstClaim.owner.generation,
            uid = "user-a",
            refreshToken = "refresh-from-first-activity",
        )
        assertTrue(firstSave.applied)
        val firstLogin = requireNotNull(firstSave.state.toStoredLogin())

        // Returning from an external Activity used to create another MainActivity/AuthController.
        // The new owner takes over the same credential before its successful auth rotates it.
        val secondClaim = claimAndroidAuthOwner(firstSave.state)
        val secondSave = saveAndroidAuthLogin(
            secondClaim.state,
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
            firstClaim.owner.generation,
            uid = "user-a",
            refreshToken = "late-refresh-from-first-activity",
        )
        assertFalse(staleSave.applied)
        assertEquals(secondLogin, staleSave.state.toStoredLogin())
    }

    @Test
    fun `only exact uid token and owner snapshot can clear login`() {
        val claim = claimAndroidAuthOwner(AndroidAuthPreferenceState())
        val saved = saveAndroidAuthLogin(
            claim.state,
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
        )

        assertNull(claimed.owner.savedLogin)
        assertNull(claimed.state.uid)
        assertNull(claimed.state.refreshToken)
    }
}

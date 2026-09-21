package com.virjar.tk.app.client

import com.virjar.tk.shared.client.AccountDataCleanup
import com.virjar.tk.shared.client.AccountDataCleanupTarget
import com.virjar.tk.shared.client.AccountDataOwner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

internal class AuthBannedAccountCleanerTest {
    private val owner = AccountDataOwner(
        deploymentFingerprint = "a".repeat(64),
        datasetId = "11111111-1111-4111-8111-111111111111",
        uid = "banned-user",
    )

    private class Sequence {
        val calls = mutableListOf<String>()
        val dataRoot = Files.createTempDirectory("banned-cleaner-").toFile()
        val owned = File(dataRoot, "owned").apply { writeText("local data") }
        val cleanup = AccountDataCleanup(dataRoot) { targetOwner ->
            assertEquals("banned-user", targetOwner.uid)
            calls += "targets"
            listOf(AccountDataCleanupTarget.tree(dataRoot, "owned"))
        }
    }

    @Test
    fun `begin returns null and marks the owner for deletion`() {
        val sequence = Sequence()
        val cleaner = AuthBannedAccountCleaner(
            cleanup = sequence.cleanup,
            clearBannedCredentials = { sequence.calls += "clear-credentials" },
            beforeDelete = { sequence.calls += "before-delete" },
        )

        assertNull(cleaner.begin(owner))
        assertTrue(sequence.cleanup.pendingOwners().contains(owner))
    }

    @Test
    fun `begin failure is returned without deleting credentials`() {
        val sequence = Sequence()
        val failingMarkerRoot = File(sequence.dataRoot, "not-a-directory").apply { writeText("occupied") }
        val failing = AccountDataCleanup(failingMarkerRoot) { emptyList() }
        val cleaner = AuthBannedAccountCleaner(
            cleanup = failing,
            clearBannedCredentials = { error("credentials must not be cleared when begin fails") },
            beforeDelete = { },
        )

        assertNotNull(cleaner.begin(owner))
        assertTrue(sequence.owned.exists(), "data must be untouched when the marker write fails")
    }

    @Test
    fun `missing cleanup configuration begins as a returned failure`() {
        val cleaner = AuthBannedAccountCleaner(cleanup = null, clearBannedCredentials = {}, beforeDelete = {})
        assertTrue(cleaner.begin(owner) is IllegalStateException)
    }

    @Test
    fun `delete runs the full ordered sequence and completes the marker`() = runBlocking {
        val sequence = Sequence()
        val cleaner = AuthBannedAccountCleaner(
            cleanup = sequence.cleanup,
            clearBannedCredentials = { sequence.calls += "clear-credentials" },
            beforeDelete = { sequence.calls += "before-delete" },
        )
        cleaner.begin(owner)

        assertTrue(cleaner.delete(owner))
        assertEquals(listOf("before-delete", "targets", "clear-credentials"), sequence.calls)
        assertFalse(sequence.owned.exists(), "owned account data must be deleted")
        assertTrue(sequence.cleanup.pendingOwners().isEmpty(), "completion marker must be removed")
    }

    @Test
    fun `delete failure keeps the marker and reports false`() = runBlocking {
        val sequence = Sequence()
        var attempts = 0
        val failing = AccountDataCleanup(sequence.dataRoot) { targetOwner ->
            attempts += 1
            if (attempts > 1) error("deletion target unavailable")
            listOf(AccountDataCleanupTarget.tree(sequence.dataRoot, "owned"))
        }
        val cleaner = AuthBannedAccountCleaner(
            cleanup = failing,
            clearBannedCredentials = { error("credentials must not be cleared after a data failure") },
            beforeDelete = { },
        )
        cleaner.begin(owner)

        assertFalse(cleaner.delete(owner))
        assertTrue(failing.pendingOwners().contains(owner), "marker must survive a failed deletion")
    }

    @Test
    fun `null cleanup never reports a successful deletion`() = runBlocking {
        val cleaner = AuthBannedAccountCleaner(cleanup = null, clearBannedCredentials = {}, beforeDelete = {})
        assertFalse(cleaner.delete(owner))
    }
}

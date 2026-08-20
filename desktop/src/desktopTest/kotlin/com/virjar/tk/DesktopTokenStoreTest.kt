package com.virjar.tk

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTokenStoreTest {

    @Test
    fun `new owner survives stale save clear and store reopen`() {
        val directory = Files.createTempDirectory("teamtalk-auth-owner").toFile()
        try {
            val firstStore = DesktopTokenStore(directory)
            val firstOwner = firstStore.claimOwner()
            val firstLogin = requireNotNull(
                firstStore.save(firstOwner.generation, "user-a", "refresh-a"),
            )

            val secondStore = DesktopTokenStore(directory)
            val secondOwner = secondStore.claimOwner()
            assertEquals(
                firstLogin.copy(ownerGeneration = secondOwner.generation),
                secondOwner.savedLogin,
            )
            val secondLogin = requireNotNull(
                secondStore.save(secondOwner.generation, "user-a", "refresh-b"),
            )

            assertNull(
                firstStore.save(firstOwner.generation, "user-a", "late-refresh-a"),
                "stale owner must not overwrite the rotated credential",
            )
            assertFalse(firstStore.compareAndClear(firstLogin))

            val reopenedStore = DesktopTokenStore(directory)
            val reopenedOwner = reopenedStore.claimOwner()
            assertEquals(
                secondLogin.copy(ownerGeneration = reopenedOwner.generation),
                reopenedOwner.savedLogin,
            )
            assertFalse(secondStore.compareAndClear(secondLogin))
            assertTrue(reopenedStore.compareAndClear(requireNotNull(reopenedOwner.savedLogin)))

            val afterClear = DesktopTokenStore(directory).claimOwner()
            assertNull(afterClear.savedLogin)
        } finally {
            directory.deleteRecursively()
        }
    }
}

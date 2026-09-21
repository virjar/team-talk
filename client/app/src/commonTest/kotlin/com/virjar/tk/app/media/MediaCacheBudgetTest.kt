package com.virjar.tk.app.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MediaCacheBudgetTest {
    @Test
    fun inFlightBytesAndPinnedFilesShareOneCapacityLimit() {
        val budget = MediaCacheBudget(10, 3)
        val files = mutableListOf(MediaCacheEntry("old", "old", 8, 1))
        val pin = budget.pin("old")
        assertFailsWith<IllegalStateException> { budget.reserve(3, "new", files) { error("pinned file") } }
        assertEquals(0, budget.reservedEntries)
        pin.close(); pin.close()
        val first = budget.reserve(8, "new", files) { name -> files.removeAll { it.file == name } }
        assertTrue(files.isEmpty())
        assertFailsWith<IllegalStateException> { budget.reserve(3, "other", files) { true } }
        val installedPin = first.commit {
            files += MediaCacheEntry("new", "new", 8, 2)
            budget.pin("new")
        }
        first.close()
        assertEquals(0L, budget.reservedBytes)
        assertTrue(budget.isPinned("new"))
        assertFailsWith<IllegalStateException> { budget.reserve(3, "other", files) { error("installed pin") } }
        installedPin.close()
        assertTrue(budget.isIdle)
    }

    @Test
    fun zeroByteFilesAreStillBoundedByEntryReservations() {
        val budget = MediaCacheBudget(10, 2)
        val files = mutableListOf(MediaCacheEntry("a", "a", 0, 1), MediaCacheEntry("b", "b", 0, 2))
        val pin = budget.pin("a")
        val reservation = budget.reserve(0, "c", files) { name -> files.removeAll { it.file == name } }
        assertEquals(listOf("a"), files.map { it.key })
        assertEquals(1, budget.reservedEntries)
        assertFailsWith<IllegalStateException> { budget.reserve(0, "d", files) { error("pinned file") } }
        reservation.close(); pin.close()
        assertTrue(budget.isIdle)
    }

    @Test
    fun slowAttachmentDoesNotBlockOtherTargetsAndCancelledWaiterDoesNotStrandItsKey() = runTest {
        val writes = MediaCacheTargetCoordinator()
        val release = CompletableDeferred<Unit>()
        val first = launch { writes.withTarget("video") { release.await() } }
        runCurrent()
        val waiting = launch { writes.withTarget("video") { error("cancelled waiter") } }
        val cachedImage = async { writes.withTarget("image") { "cached" } }
        runCurrent()
        assertEquals("cached", cachedImage.await())
        waiting.cancel()
        release.complete(Unit)
        first.join(); waiting.join()
        assertEquals("ready", writes.withTarget("video") { "ready" })
    }
}

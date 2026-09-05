package com.virjar.tk.server.infra.sync

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingSyncUidScannerTest {
    @Test
    fun `one hundred thousand logical users stay page bounded and the tail is reached before wrap`() {
        val repository = LogicalUidPageRepository(uidCount = 100_000)
        val scanner = PendingSyncUidScanner(repository, pageSize = 257)
        var expectedIndex = 0
        var pageCount = 0
        var completed = false

        while (!completed) {
            val page = scanner.loadNextPage(nowMillis = 42L)
            pageCount += 1
            assertTrue(page.uids.size <= 257)
            page.uids.forEach { uid ->
                assertEquals(logicalUid(expectedIndex), uid)
                expectedIndex += 1
            }
            completed = page.cycleCompleted
        }

        assertEquals(100_000, expectedIndex)
        assertEquals(logicalUid(99_999), repository.lastReturnedUid)
        assertEquals(390, pageCount)
        assertEquals(setOf(257), repository.requestedLimits.toSet())
        assertEquals(257, repository.maximumReturnedPageSize)

        val wrapped = scanner.loadNextPage(nowMillis = 43L)
        assertEquals((0 until 257).map(::logicalUid), wrapped.uids)
        assertTrue(!wrapped.cycleCompleted)
    }

    @Test
    fun `explicit recovery restart returns to the first key without growing a page`() {
        val repository = LogicalUidPageRepository(uidCount = 1_000)
        val scanner = PendingSyncUidScanner(repository, pageSize = 64)

        assertEquals(logicalUid(0), scanner.loadNextPage(1L).uids.first())
        assertEquals(logicalUid(64), scanner.loadNextPage(1L).uids.first())

        scanner.restartCycle()

        val restarted = scanner.loadNextPage(1L)
        assertEquals(logicalUid(0), restarted.uids.first())
        assertEquals(64, restarted.uids.size)
    }

    @Test
    fun `exact multiple completes on an empty sentinel page and then wraps`() {
        val scanner = PendingSyncUidScanner(
            repository = LogicalUidPageRepository(uidCount = 512),
            pageSize = 256,
        )

        assertTrue(!scanner.loadNextPage(1L).cycleCompleted)
        assertTrue(!scanner.loadNextPage(1L).cycleCompleted)
        val sentinel = scanner.loadNextPage(1L)
        assertTrue(sentinel.cycleCompleted)
        assertTrue(sentinel.uids.isEmpty())

        assertEquals(logicalUid(0), scanner.loadNextPage(1L).uids.first())
    }

    @Test
    fun `repository owned order may differ from Kotlin string comparison`() {
        val repositoryOrder = listOf("a", "K", "R", "y")
        val scanner = PendingSyncUidScanner(
            repository = PendingSyncUidPageRepository { _, afterUidExclusive, limit ->
                val startIndex = afterUidExclusive?.let(repositoryOrder::indexOf)?.plus(1) ?: 0
                repositoryOrder.drop(startIndex).take(limit)
            },
            pageSize = 2,
        )

        assertEquals(listOf("a", "K"), scanner.loadNextPage(1L).uids)
        assertEquals(listOf("R", "y"), scanner.loadNextPage(1L).uids)
        assertTrue(scanner.loadNextPage(1L).cycleCompleted)
    }

    private class LogicalUidPageRepository(
        private val uidCount: Int,
    ) : PendingSyncUidPageRepository {
        val requestedLimits = mutableListOf<Int>()
        var maximumReturnedPageSize = 0
            private set
        var lastReturnedUid: String? = null
            private set

        override fun loadPage(nowMillis: Long, afterUidExclusive: String?, limit: Int): List<String> {
            assertTrue(nowMillis > 0L)
            requestedLimits += limit
            val startIndex = afterUidExclusive?.removePrefix(UID_PREFIX)?.toInt()?.plus(1) ?: 0
            val endExclusive = min(uidCount, startIndex + limit)
            val page = (startIndex until endExclusive).map(::logicalUid)
            maximumReturnedPageSize = maxOf(maximumReturnedPageSize, page.size)
            page.lastOrNull()?.let { lastReturnedUid = it }
            return page
        }
    }

    private companion object {
        const val UID_PREFIX = "uid-"

        fun logicalUid(index: Int): String = UID_PREFIX + index.toString().padStart(6, '0')
    }
}

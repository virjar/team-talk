package com.virjar.tk.navigation.feature

import com.virjar.tk.model.ContactApplyRecord
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountFeatureStateTest {
    @Test
    fun `first history refresh keeps empty state in loading mode`() {
        val loading = FriendApplyHistoryState().startRefresh()

        assertTrue(loading.loading)
        assertEquals(emptyList(), loading.records)
        assertTrue(loading.hasMore)
    }

    @Test
    fun `failed history refresh preserves the previously rendered records`() {
        val existing = listOf(record(3), record(2))
        val before = FriendApplyHistoryState(
            records = existing,
            loading = false,
            hasMore = false,
        )

        val failed = before.startRefresh().failRefresh()

        assertEquals(existing, failed.records)
        assertFalse(failed.loading)
        assertFalse(failed.hasMore)
    }

    @Test
    fun `successful history refresh replaces records and updates pagination`() {
        val refreshed = FriendApplyHistoryState(records = listOf(record(9)))
            .startRefresh()
            .finishRefresh(records = listOf(record(5), record(4)), pageSize = 2)

        assertEquals(listOf(5L, 4L), refreshed.records.map { it.id })
        assertFalse(refreshed.loading)
        assertTrue(refreshed.hasMore)
    }

    @Test
    fun `profile apply lookup retries once after an event generation change`() = runTest {
        var generation = 0L
        var calls = 0

        val result = lookupWithGenerationRetry(
            currentGeneration = { generation },
            lookup = {
                calls++
                if (calls == 1) {
                    generation++
                    "stale"
                } else {
                    "fresh"
                }
            },
        )

        assertEquals(2, calls)
        assertEquals("fresh", result.value)
        assertTrue(result.isCurrent)
        assertEquals(1L, result.observedGeneration)
    }

    @Test
    fun `profile apply lookup marks the retry stale when another event arrives`() = runTest {
        var generation = 0L
        var calls = 0

        val result = lookupWithGenerationRetry(
            currentGeneration = { generation },
            lookup = {
                calls++
                generation++
                "response-$calls"
            },
        )

        assertEquals(2, calls)
        assertEquals("response-2", result.value)
        assertFalse(result.isCurrent)
    }

    private fun record(id: Long) = ContactApplyRecord(
        id = id,
        fromUid = "me",
        toUid = "peer",
        direction = ContactApplyRecord.DIRECTION_OUTGOING,
        status = ContactApplyRecord.STATUS_PENDING,
        createdAt = 10,
        updatedAt = 10,
    )
}

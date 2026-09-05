package com.virjar.tk.server.infra.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RocksDbTierReadCacheTest {
    @Test
    fun `oversized value is rejected without evicting the existing working set`() {
        val cache = RocksDbTier.ReadCache(maxSizeBytes = 10, cacheThreshold = 2)
        assertTrue(cache.put("first", ByteArray(4) { 1 }))
        assertTrue(cache.put("second", ByteArray(4) { 2 }))

        assertFalse(cache.put("oversized", ByteArray(11)))

        assertContentEquals(ByteArray(4) { 1 }, cache.get("first"))
        assertContentEquals(ByteArray(4) { 2 }, cache.get("second"))
        assertNull(cache.get("oversized"))
        assertEquals(
            RocksDbTier.ReadCacheSnapshot(
                entryCount = 2,
                sizeBytes = 8,
                keysInEvictionOrder = listOf("first", "second"),
            ),
            cache.snapshot(),
        )
    }

    @Test
    fun `replacement and LRU eviction preserve a strict aggregate byte bound`() {
        val cache = RocksDbTier.ReadCache(maxSizeBytes = 10, cacheThreshold = 2)
        assertTrue(cache.put("first", ByteArray(4)))
        assertTrue(cache.put("second", ByteArray(4)))
        cache.get("first") // second becomes the least-recently-used entry.

        assertTrue(cache.put("third", ByteArray(5)))
        assertNull(cache.get("second"))
        assertEquals(9, cache.snapshot().sizeBytes)

        assertTrue(cache.put("first", ByteArray(6)))
        assertNull(cache.get("third"))
        assertEquals(
            RocksDbTier.ReadCacheSnapshot(
                entryCount = 1,
                sizeBytes = 6,
                keysInEvictionOrder = listOf("first"),
            ),
            cache.snapshot(),
        )
    }
}

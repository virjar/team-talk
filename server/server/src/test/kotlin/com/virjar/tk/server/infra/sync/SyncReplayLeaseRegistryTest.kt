package com.virjar.tk.server.infra.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncReplayLeaseRegistryTest {
    @Test
    fun `checkpoint is bound to one connection and replacement invalidates the old id`() {
        val registry = SyncReplayLeaseRegistry()

        registry.reserveCheckpoint("user", "session", "checkpoint-1")
        assertTrue(registry.publishCheckpoint("user", "session", "checkpoint-1", 12L))
        registry.requireCheckpoint("user", "session", "checkpoint-1")
        registry.reserveCheckpoint("user", "session", "checkpoint-2")
        assertTrue(registry.publishCheckpoint("user", "session", "checkpoint-2", 20L))

        assertFailsWith<IllegalArgumentException> {
            registry.requireCheckpoint("user", "session", "checkpoint-1")
        }
        registry.requireCheckpoint("user", "session", "checkpoint-2")
        assertEquals(20L, registry.leaseFor("user", "session")?.protectedCursor)
    }

    @Test
    fun `replay acknowledgements only move a lease forward and preserve checkpoint identity`() {
        val registry = SyncReplayLeaseRegistry()
        registry.reserveCheckpoint("user", "session", "checkpoint")
        assertTrue(registry.publishCheckpoint("user", "session", "checkpoint", 10L))

        assertTrue(registry.advanceReplay("user", "session", 8L))
        assertTrue(registry.advanceReplay("user", "session", 15L))

        assertEquals(
            SyncReplayLeaseRegistry.LeaseSnapshot("checkpoint", 15L),
            registry.leaseFor("user", "session"),
        )
        registry.requireCheckpoint("user", "session", "checkpoint")
    }

    @Test
    fun `minimum protected cursor spans active connections and release removes it`() {
        val registry = SyncReplayLeaseRegistry()
        registry.reserveReplay("user", "first")
        registry.reserveReplay("user", "second")
        registry.reserveReplay("other", "third")
        assertTrue(registry.advanceReplay("user", "first", 30L))
        assertTrue(registry.advanceReplay("user", "second", 12L))
        assertTrue(registry.advanceReplay("other", "third", 1L))

        assertEquals(12L, registry.minimumProtectedCursor("user"))
        registry.release("user", "second")
        assertEquals(30L, registry.minimumProtectedCursor("user"))
        registry.release("user", "first")
        assertNull(registry.minimumProtectedCursor("user"))
    }

    @Test
    fun `disconnect removes reservations and late publication cannot resurrect a lease`() {
        val registry = SyncReplayLeaseRegistry()
        registry.reserveCheckpoint("user", "session", "checkpoint")

        registry.release("user", "session")

        assertFalse(registry.publishCheckpoint("user", "session", "checkpoint", 4L))
        assertFalse(registry.advanceReplay("user", "session", 4L))
        assertNull(registry.leaseFor("user", "session"))
    }
}

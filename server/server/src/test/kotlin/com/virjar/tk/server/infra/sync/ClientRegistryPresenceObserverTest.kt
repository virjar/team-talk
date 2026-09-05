package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.domain.auth.CredentialSessionAuthority
import com.virjar.tk.server.domain.contact.ContactPolicy
import com.virjar.tk.server.domain.presence.PresenceTransitionObserver
import com.virjar.tk.protocol.PresenceContractPolicy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientRegistryPresenceObserverTest {
    @Test
    fun `online count is computed by the registry owner without exporting uid snapshots`() = runTest {
        val registry = registry()
        try {
            assertEquals(0, registry.onlineCount())
        } finally {
            registry.stop()
        }
    }

    @Test
    fun `snapshot freezes one bounded candidate set and returns the owner epoch at revision zero`() = runTest {
        val registry = registry()
        try {
            val snapshot = registry.snapshot(linkedSetOf("zoe", "alice", "bob"))

            PresenceContractPolicy.requireServerEpoch(snapshot.serverEpoch)
            assertEquals(0L, snapshot.revision)
            assertEquals(listOf("alice", "bob", "zoe"), snapshot.friendUids)
            assertTrue(snapshot.onlineFriendUids.isEmpty())

            val oversized = (0..ContactPolicy.MAX_FRIENDS_PER_USER)
                .mapTo(linkedSetOf()) { "friend-$it" }
            assertFailsWith<IllegalArgumentException> { registry.snapshot(oversized) }
        } finally {
            registry.stop()
        }
    }

    @Test
    fun `snapshot R followed by a boundary transition produces R plus one`() {
        val state = RegistryPresenceState(TEST_EPOCH)

        val before = state.snapshot(listOf("alice")) { false }
        val transition = requireNotNull(
            state.onDeviceCountChanged("alice", 0, 1, occurredAt = { 101L }),
        )

        assertEquals(0L, before.revision)
        assertEquals(before.serverEpoch, transition.serverEpoch)
        assertEquals(before.revision + 1L, transition.revision)
        assertTrue(transition.online)
        assertEquals(101L, transition.occurredAt)
    }

    @Test
    fun `boundary transition R followed by snapshot returns the same R`() {
        val state = RegistryPresenceState(TEST_EPOCH)
        val transition = requireNotNull(
            state.onDeviceCountChanged("alice", 0, 1, occurredAt = { 202L }),
        )

        val after = state.snapshot(listOf("alice", "bob")) { it == "alice" }

        assertEquals(transition.revision, after.revision)
        assertEquals(transition.serverEpoch, after.serverEpoch)
        assertEquals(listOf("alice"), after.onlineFriendUids)
    }

    @Test
    fun `only first device online and last device offline advance revision and capture time`() {
        val state = RegistryPresenceState(TEST_EPOCH)
        var clockReads = 0
        val clock = { (++clockReads).toLong() }

        val firstOnline = state.onDeviceCountChanged("alice", 0, 1, clock)
        assertEquals(null, state.onDeviceCountChanged("alice", 1, 2, clock))
        assertEquals(null, state.onDeviceCountChanged("alice", 2, 1, clock))
        val lastOffline = state.onDeviceCountChanged("alice", 1, 0, clock)

        assertEquals(1L, requireNotNull(firstOnline).revision)
        assertTrue(firstOnline.online)
        assertEquals(1L, firstOnline.occurredAt)
        assertEquals(2L, requireNotNull(lastOffline).revision)
        assertFalse(lastOffline.online)
        assertEquals(2L, lastOffline.occurredAt)
        assertEquals(2, clockReads)
    }

    @Test
    fun `presence revision overflow fails before wraparound`() {
        val state = RegistryPresenceState(TEST_EPOCH, initialRevision = Long.MAX_VALUE)

        assertFailsWith<IllegalStateException> {
            state.onDeviceCountChanged("alice", 0, 1, occurredAt = { 1L })
        }
        assertEquals(Long.MAX_VALUE, state.snapshot(emptyList()) { false }.revision)
    }

    @Test
    fun `observer lease is exclusive idempotent and cannot detach its replacement`() {
        val registry = registry()
        try {
            val first = registry.installPresenceObserver(PresenceTransitionObserver { _ -> })
            assertFailsWith<IllegalStateException> {
                registry.installPresenceObserver(PresenceTransitionObserver { _ -> })
            }

            first.uninstall()
            first.uninstall()
            val second = registry.installPresenceObserver(PresenceTransitionObserver { _ -> })

            first.uninstall()
            assertFailsWith<IllegalStateException> {
                registry.installPresenceObserver(PresenceTransitionObserver { _ -> })
            }

            second.uninstall()
            registry.installPresenceObserver(PresenceTransitionObserver { _ -> }).uninstall()
        } finally {
            registry.stop()
        }
    }

    private fun registry() = ClientRegistry(
        CredentialSessionAuthority { _, _, _, _ -> true },
    )

    private companion object {
        const val TEST_EPOCH = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}

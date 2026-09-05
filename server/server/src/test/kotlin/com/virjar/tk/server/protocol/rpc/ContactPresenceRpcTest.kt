package com.virjar.tk.server.protocol.rpc

import com.virjar.tk.server.domain.contact.ContactRepository
import com.virjar.tk.server.domain.presence.FriendPresenceSnapshotReader
import com.virjar.tk.protocol.model.FriendPresenceSnapshot
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactPresenceRpcTest {
    @Test
    fun `presence query loads only the authenticated caller's complete authoritative friend set`() = runTest {
        val expectedFriends = linkedSetOf("friend-c", "friend-a", "friend-b")
        var repositoryUid: String? = null
        var snapshotCandidates: Set<String>? = null
        val contacts = contactRepository { uid ->
            repositoryUid = uid
            expectedFriends
        }
        val snapshots = FriendPresenceSnapshotReader { friendUids ->
            snapshotCandidates = friendUids
            FriendPresenceSnapshot(EPOCH, 4L, friendUids, setOf("friend-b"))
        }

        val result = authoritativeFriendPresenceSnapshot("authenticated-user", contacts, snapshots)

        assertEquals("authenticated-user", repositoryUid)
        assertEquals(expectedFriends, snapshotCandidates)
        assertEquals(listOf("friend-a", "friend-b", "friend-c"), result.friendUids)
        assertEquals(listOf("friend-b"), result.onlineFriendUids)
    }

    private companion object {
        const val EPOCH = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

        @Suppress("UNCHECKED_CAST")
        fun contactRepository(listFriendUids: (String) -> Set<String>): ContactRepository {
            val type = ContactRepository::class.java
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                when (method.name) {
                    "listFriendUids" -> listFriendUids(args!![0] as String)
                    "toString" -> "ContactPresenceRepository"
                    else -> error("Unexpected ContactRepository call: ${method.name}")
                }
            } as ContactRepository
        }
    }
}

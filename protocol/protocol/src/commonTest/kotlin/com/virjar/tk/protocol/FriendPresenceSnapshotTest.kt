package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.FriendPresenceSnapshot
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.ContactRpcContract
import com.virjar.tk.protocol.rpc.gen.ContactRpcProxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FriendPresenceSnapshotTest {
    @Test
    fun `versioned presence increment round trips and enforces online timestamp semantics`() {
        val online = PresencePayload(
            uid = "alice",
            status = PresencePayload.STATUS_ONLINE,
            lastSeenAt = 0L,
            serverEpoch = EPOCH,
            revision = 41L,
        )
        val offline = PresencePayload(
            uid = "alice",
            status = PresencePayload.STATUS_OFFLINE,
            lastSeenAt = 1_234L,
            serverEpoch = EPOCH,
            revision = 42L,
        )

        assertEquals(online, ProtoCodec.decode(PresencePayload, ProtoCodec.encode(online)))
        assertEquals(offline, ProtoCodec.decode(PresencePayload, ProtoCodec.encode(offline)))
        assertFailsWith<IllegalArgumentException> { online.copy(revision = 0L) }
        assertFailsWith<IllegalArgumentException> { online.copy(serverEpoch = EPOCH.uppercase()) }
        assertFailsWith<IllegalArgumentException> { online.copy(status = 2) }
        assertFailsWith<IllegalArgumentException> { online.copy(lastSeenAt = 1L) }
    }

    @Test
    fun `malformed presence increment is rejected as wire corruption`() {
        val invalid = ProtoCodec.encodePayload {
            writeString(EPOCH)
            writeVarLong(0L)
            writeString("alice")
            writeByte(PresencePayload.STATUS_ONLINE.toInt())
            writeVarLong(0L)
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(PresencePayload, invalid)
        }
    }

    @Test
    fun `snapshot canonicalizes outbound sets and round trips revision zero`() {
        val snapshot = FriendPresenceSnapshot(
            serverEpoch = EPOCH,
            revision = 0L,
            friendUids = listOf("zoe", "alice", "zoe", "bob"),
            onlineFriendUids = listOf("zoe", "alice", "alice"),
        )

        assertEquals(listOf("alice", "bob", "zoe"), snapshot.friendUids)
        assertEquals(listOf("alice", "zoe"), snapshot.onlineFriendUids)
        assertEquals(snapshot, ProtoCodec.decode(FriendPresenceSnapshot, ProtoCodec.encode(snapshot)))
    }

    @Test
    fun `snapshot rejects invalid epoch revision subset and raw collection overflow`() {
        assertFailsWith<IllegalArgumentException> {
            FriendPresenceSnapshot(EPOCH.uppercase(), 0L, emptyList(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            FriendPresenceSnapshot(EPOCH, -1L, emptyList(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            FriendPresenceSnapshot(EPOCH, 0L, listOf("alice"), listOf("mallory"))
        }
        assertFailsWith<IllegalArgumentException> {
            FriendPresenceSnapshot(
                EPOCH,
                0L,
                List(FriendPresenceSnapshot.MAX_FRIENDS + 1) { "alice" },
                emptyList(),
            )
        }
    }

    @Test
    fun `snapshot accepts the exact friend boundary and rejects a larger wire count before allocation`() {
        val friends = List(FriendPresenceSnapshot.MAX_FRIENDS) { index -> "friend-${index.toString().padStart(4, '0')}" }
        val snapshot = FriendPresenceSnapshot(EPOCH, 9L, friends, friends.takeLast(2))

        assertEquals(snapshot, ProtoCodec.decode(FriendPresenceSnapshot, ProtoCodec.encode(snapshot)))
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(
                FriendPresenceSnapshot,
                ProtoCodec.encodePayload {
                    writeString(EPOCH)
                    writeVarLong(0L)
                    writeVarInt(FriendPresenceSnapshot.MAX_FRIENDS + 1)
                },
            )
        }
    }

    @Test
    fun `snapshot wire requires sorted duplicate-free collections and an online subset`() {
        fun wire(friendUids: List<String>, onlineFriendUids: List<String>) = ProtoCodec.encodePayload {
            writeString(EPOCH)
            writeVarLong(3L)
            writeVarInt(friendUids.size)
            friendUids.forEach(::writeString)
            writeVarInt(onlineFriendUids.size)
            onlineFriendUids.forEach(::writeString)
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(FriendPresenceSnapshot, wire(listOf("bob", "alice"), emptyList()))
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(FriendPresenceSnapshot, wire(listOf("alice", "alice"), emptyList()))
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(FriendPresenceSnapshot, wire(listOf("alice"), listOf("bob")))
        }
    }

    @Test
    fun `contact presence snapshot RPC has no query uid or request payload`() {
        val expected = FriendPresenceSnapshot(EPOCH, 7L, listOf("alice"), listOf("alice"))
        var capturedService: String? = null
        var capturedMethod = 0
        var capturedPayload: ByteArray? = byteArrayOf(1)
        val proxy = ContactRpcProxy(
            object : RpcInvoker {
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                    capturedService = service
                    capturedMethod = methodId
                    capturedPayload = payload
                    return ResponsePayload(requestId = 1, status = 0, payload = ProtoCodec.encode(expected))
                }
            },
        )

        assertEquals(expected, runPresenceSuspend { proxy.getPresenceSnapshot() })
        assertEquals(ContactRpcContract.SERVICE, capturedService)
        assertEquals(ContactRpcContract.M_GET_PRESENCE_SNAPSHOT, capturedMethod)
        assertNull(capturedPayload)
    }

    private companion object {
        const val EPOCH = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}

private fun <T> runPresenceSuspend(block: suspend () -> T): T {
    var completed = false
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                completed = true
                result.fold(onSuccess = { value = it }, onFailure = { failure = it })
            }
        },
    )
    check(completed) { "Test RPC unexpectedly suspended" }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}

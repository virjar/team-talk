package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.model.Contact
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.rpc.RpcInvoker
import com.virjar.tk.testing.FakeLocalCache
import com.virjar.tk.testing.FakeRpcInvoker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ContactSnapshotRepositoryTest {
    @Test
    fun `authoritative friend snapshot removes contacts polluted by an older client`() = runBlocking {
        val cache = FakeLocalCache().apply {
            upsertContact(contact("polluted"))
        }
        val rpc = FakeRpcInvoker().apply {
            enqueueOk(ProtoCodec.encodeList(listOf(contact("real"))))
        }
        val repository = ContactRepository(rpc, cache)

        val result = repository.listFriends()

        assertIs<Outcome.Success<List<Contact>>>(result)
        assertEquals(listOf("real"), cache.getContacts().map(Contact::friendUid))
    }

    @Test
    fun `late snapshot cannot remove a contact accepted while rpc was in flight`() = runBlocking {
        val cache = FakeLocalCache().apply {
            upsertContact(contact("existing"))
        }
        val rpc = BlockingContactListRpc(listOf(contact("existing")))
        val repository = ContactRepository(rpc, cache)

        val request = async { repository.listFriends() }
        rpc.started.await()
        cache.upsertContact(contact("just-accepted"))
        rpc.release.complete(Unit)

        assertIs<Outcome.Success<List<Contact>>>(request.await())
        assertEquals(
            setOf("existing", "just-accepted"),
            cache.getContacts().map(Contact::friendUid).toSet(),
        )
    }

    @Test
    fun `late snapshot cannot resurrect a contact deleted while rpc was in flight`() = runBlocking {
        val stale = contact("just-deleted")
        val cache = FakeLocalCache().apply { upsertContact(stale) }
        val rpc = BlockingContactListRpc(listOf(stale))
        val repository = ContactRepository(rpc, cache)

        val request = async { repository.listFriends() }
        rpc.started.await()
        cache.deleteContact(stale.friendUid)
        rpc.release.complete(Unit)

        assertIs<Outcome.Success<List<Contact>>>(request.await())
        assertFalse(cache.getContacts().any { it.friendUid == stale.friendUid })
    }

    private fun contact(friendUid: String) = Contact(uid = "me", friendUid = friendUid)

    private class BlockingContactListRpc(
        private val contacts: List<Contact>,
    ) : RpcInvoker {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
            started.complete(Unit)
            release.await()
            return ResponsePayload(
                requestId = 1,
                status = 0,
                payload = ProtoCodec.encodeList(contacts),
            )
        }
    }
}

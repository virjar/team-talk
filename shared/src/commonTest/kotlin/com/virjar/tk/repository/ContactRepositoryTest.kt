package com.virjar.tk.repository

import com.virjar.tk.Outcome
import com.virjar.tk.model.ContactApplyLookup
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.rpc.gen.ContactRpcContract
import com.virjar.tk.testing.FakeLocalCache
import com.virjar.tk.testing.FakeRpcInvoker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ContactRepositoryTest {
    @Test
    fun `apply record history uses the bidirectional cursor rpc`() = runBlocking {
        val record = outgoingPendingRecord(targetUid = "peer-1")
        val rpc = FakeRpcInvoker().apply {
            enqueueOk(ProtoCodec.encodeList(listOf(record)))
        }
        val repository = ContactRepository(rpc, FakeLocalCache())

        val result = repository.listApplyRecords(beforeId = 99, limit = 20)

        assertIs<Outcome.Success<List<ContactApplyRecord>>>(result)
        assertEquals(listOf(record), result.value)
        assertEquals(ContactRpcContract.M_LIST_APPLY_RECORDS, rpc.calls.single().second)
    }

    @Test
    fun `profile pending lookup uses the exact target rpc and returns its record`() = runBlocking {
        val record = outgoingPendingRecord(targetUid = "peer-1")
        val rpc = FakeRpcInvoker().apply {
            enqueueOk(ProtoCodec.encode(ContactApplyLookup(record)))
        }
        val repository = ContactRepository(rpc, FakeLocalCache())

        val result = repository.getPendingApply("peer-1")

        assertIs<Outcome.Success<ContactApplyRecord?>>(result)
        assertEquals(record, result.value)
        assertEquals("contact", rpc.calls.single().first)
        assertEquals(ContactRpcContract.M_GET_PENDING_APPLY, rpc.calls.single().second)
    }

    @Test
    fun `profile pending lookup preserves an authoritative empty result`() = runBlocking {
        val rpc = FakeRpcInvoker().apply {
            enqueueOk(ProtoCodec.encode(ContactApplyLookup()))
        }
        val repository = ContactRepository(rpc, FakeLocalCache())

        val result = repository.getPendingApply("peer-1")

        assertIs<Outcome.Success<ContactApplyRecord?>>(result)
        assertNull(result.value)
    }

    private fun outgoingPendingRecord(targetUid: String) = ContactApplyRecord(
        id = 7,
        fromUid = "me",
        toUid = targetUid,
        direction = ContactApplyRecord.DIRECTION_OUTGOING,
        status = ContactApplyRecord.STATUS_PENDING,
        createdAt = 10,
        updatedAt = 10,
    )
}

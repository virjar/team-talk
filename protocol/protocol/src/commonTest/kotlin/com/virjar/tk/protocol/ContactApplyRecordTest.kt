package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.ContactApplyLookup
import com.virjar.tk.protocol.model.ContactApplyRecord
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.ProtoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactApplyRecordTest {
    @Test
    fun `record and optional lookup round trip`() {
        val record = ContactApplyRecord(
            id = 42,
            fromUid = "alice",
            toUid = "bob",
            direction = ContactApplyRecord.DIRECTION_INCOMING,
            token = "recipient-only-token",
            remark = "一起协作",
            status = ContactApplyRecord.STATUS_PENDING,
            createdAt = 100,
            updatedAt = 120,
            peerUser = User(uid = "alice", username = "alice", name = "Alice"),
        )

        assertEquals(record, ProtoCodec.decode(ContactApplyRecord, ProtoCodec.encode(record)))
        assertEquals(
            ContactApplyLookup(record),
            ProtoCodec.decode(ContactApplyLookup, ProtoCodec.encode(ContactApplyLookup(record))),
        )
        assertEquals(
            ContactApplyLookup(),
            ProtoCodec.decode(ContactApplyLookup, ProtoCodec.encode(ContactApplyLookup())),
        )
    }
}

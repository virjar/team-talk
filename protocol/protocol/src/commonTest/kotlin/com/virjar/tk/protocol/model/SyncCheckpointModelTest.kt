package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SyncCheckpointModelTest {

    @Test
    fun `checkpoint header and requests round trip with stable field order`() {
        val header = SyncCheckpointHeader(
            datasetId = DATASET_ID,
            checkpointId = CHECKPOINT_ID,
            baseEventId = 42L,
            currentUser = CURRENT_USER,
        )
        val firstPage = SyncCheckpointPageRequest(CHECKPOINT_ID)
        val followingPage = SyncCheckpointPageRequest(CHECKPOINT_ID, "next_cursor-1")

        assertEquals(header, ProtoCodec.decode(SyncCheckpointHeader, ProtoCodec.encode(header)))
        assertEquals(firstPage, ProtoCodec.decode(SyncCheckpointPageRequest, ProtoCodec.encode(firstPage)))
        assertEquals(
            followingPage,
            ProtoCodec.decode(SyncCheckpointPageRequest, ProtoCodec.encode(followingPage)),
        )
        assertNull(firstPage.cursor)
    }

    @Test
    fun `contact and chat pages round trip at the non-terminal cardinality boundary`() {
        val contacts = List(SyncCheckpointPolicy.MAX_PAGE_SIZE) { index ->
            Contact(uid = CURRENT_USER.uid, friendUid = stableId(index), remark = "friend-$index")
        }
        val chats = List(SyncCheckpointPolicy.MAX_PAGE_SIZE) { index ->
            Chat(chatId = stableId(index), chatType = 1, name = "chat-$index")
        }
        val contactPage = SyncCheckpointContactPage(contacts, contacts.last().friendUid)
        val chatPage = SyncCheckpointChatPage(chats, chats.last().chatId)

        assertEquals(
            contactPage,
            ProtoCodec.decode(SyncCheckpointContactPage, ProtoCodec.encode(contactPage)),
        )
        assertEquals(chatPage, ProtoCodec.decode(SyncCheckpointChatPage, ProtoCodec.encode(chatPage)))
    }

    @Test
    fun `checkpoint identities and cursors reject non-canonical or unbounded values`() {
        assertFailsWith<IllegalArgumentException> {
            SyncCheckpointHeader(DATASET_ID.uppercase(), CHECKPOINT_ID, 0L, CURRENT_USER)
        }
        assertFailsWith<IllegalArgumentException> {
            SyncCheckpointHeader(DATASET_ID, CHECKPOINT_ID.uppercase(), 0L, CURRENT_USER)
        }
        assertFailsWith<IllegalArgumentException> {
            SyncCheckpointHeader(DATASET_ID, CHECKPOINT_ID, -1L, CURRENT_USER)
        }
        assertFailsWith<IllegalArgumentException> {
            SyncCheckpointPageRequest(CHECKPOINT_ID, "not+base64url")
        }
        assertFailsWith<IllegalArgumentException> {
            SyncCheckpointPageRequest(
                CHECKPOINT_ID,
                "a".repeat(SyncCheckpointPolicy.MAX_CURSOR_BYTES + 1),
            )
        }

        val malformedRequest = ProtoCodec.encodePayload {
            writeString(CHECKPOINT_ID)
            writeString("not/base64url")
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(SyncCheckpointPageRequest, malformedRequest)
        }
    }

    @Test
    fun `checkpoint pages reject duplicate identities and incomplete continuation pages`() {
        val duplicateContact = Contact(uid = CURRENT_USER.uid, friendUid = stableId(1))
        assertFailsWith<ProtocolEncodingException> {
            SyncCheckpointContactPage(listOf(duplicateContact, duplicateContact), null)
        }

        val duplicateChat = Chat(chatId = stableId(2), chatType = 1)
        assertFailsWith<ProtocolEncodingException> {
            SyncCheckpointChatPage(listOf(duplicateChat, duplicateChat), null)
        }

        assertFailsWith<ProtocolEncodingException> {
            SyncCheckpointContactPage(listOf(duplicateContact), duplicateContact.friendUid)
        }
        assertFailsWith<ProtocolEncodingException> {
            SyncCheckpointChatPage(listOf(duplicateChat), duplicateChat.chatId)
        }
    }

    @Test
    fun `decoder rejects impossible counts and invalid page envelopes as corruption`() {
        val impossibleCount = ProtoCodec.encodePayload {
            writeVarInt(SyncCheckpointPolicy.MAX_PAGE_SIZE + 1)
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(SyncCheckpointContactPage, impossibleCount)
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(SyncCheckpointChatPage, impossibleCount)
        }

        val contact = Contact(uid = CURRENT_USER.uid, friendUid = stableId(3))
        val incompleteContinuation = ProtoCodec.encodePayload {
            writeVarInt(1)
            contact.writeTo(this)
            writeString(contact.friendUid)
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(SyncCheckpointContactPage, incompleteContinuation)
        }
    }

    @Test
    fun `contact page has an inner two MiB budget in both directions`() {
        val oversizedContact = Contact(
            uid = CURRENT_USER.uid,
            friendUid = stableId(4),
            user = CURRENT_USER.copy(name = "界".repeat(700_000)),
        )
        val page = SyncCheckpointContactPage(listOf(oversizedContact), null)

        assertFailsWith<ProtocolEncodingException> { ProtoCodec.encode(page) }

        val oversizedWire = ProtoCodec.encodePayload {
            writeVarInt(1)
            oversizedContact.writeTo(this)
            writeString(null)
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(SyncCheckpointContactPage, oversizedWire)
        }
    }

    private fun stableId(index: Int): String =
        "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private companion object {
        const val DATASET_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val CHECKPOINT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val CURRENT_USER = User(
            uid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            username = "checkpoint-user",
            name = "Checkpoint User",
        )
    }
}

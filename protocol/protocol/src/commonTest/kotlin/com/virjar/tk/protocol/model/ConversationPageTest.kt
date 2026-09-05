package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.payload.MAX_RPC_ENVELOPE_BODY_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationPageTest {
    @Test
    fun `sixteen maximum legal drafts fit the stricter page response budget`() {
        val maximumDraft = "界".repeat(MessageBodyPolicy.MAX_MARKDOWN_LENGTH)
        val page = ConversationPage(
            items = List(ConversationPage.MAX_PAGE_SIZE) { index ->
                Conversation(
                    chatId = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
                    chatType = 2,
                    draft = maximumDraft,
                )
            },
            nextCursor = null,
        )

        val encoded = ProtoCodec.encode(page)
        val decoded = ProtoCodec.decode(ConversationPage, encoded)

        assertEquals(page, decoded)
        assertTrue(encoded.size <= ConversationPage.MAX_ENCODED_BYTES)
        assertTrue(ConversationPage.MAX_ENCODED_BYTES < MAX_RPC_ENVELOPE_BODY_BYTES)
    }

    @Test
    fun `page decoder rejects a count above the domain maximum before allocating items`() {
        val malformed = ProtoCodec.encodePayload {
            writeVarInt(ConversationPage.MAX_PAGE_SIZE + 1)
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(ConversationPage, malformed)
        }
    }

    @Test
    fun `request decoder bounds opaque cursor bytes`() {
        val malformed = ProtoCodec.encodePayload {
            writeString("a".repeat(ConversationPagePolicy.MAX_CURSOR_BYTES + 1))
        }

        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(ConversationPageRequest, malformed)
        }
    }

    @Test
    fun `only a full page may advertise another page`() {
        assertFailsWith<IllegalArgumentException> {
            ConversationPage(
                items = listOf(
                    Conversation(chatId = "chat-1", chatType = 1, peerUid = "peer-1", peerRevision = 1),
                ),
                nextCursor = "bmV4dA",
            )
        }
        assertNull(ConversationPage(emptyList(), null).nextCursor)
    }
}

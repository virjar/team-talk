package com.virjar.tk.protocol

import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.ContentSearchAttachments
import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.ContentSearchPage
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentSearchModelTest {
    @Test
    fun `all search request kinds round trip with bounded filters and opaque cursor`() {
        for (kind in 1..3) {
            val request = ContentSearchRequest(kind, "中".repeat(1_000), "scope", if (kind == 1) 0 else 4, 50, "opaque")
            assertEquals(request, ProtoCodec.decode(ContentSearchRequest, ProtoCodec.encode(request)))
        }
        assertEquals(ContentSearchRequest(1, ""), ProtoCodec.decode(ContentSearchRequest, ProtoCodec.encode(ContentSearchRequest(1, ""))))
        assertFailsWith<IllegalArgumentException> { ContentSearchRequest(0, "") }
        assertFailsWith<IllegalArgumentException> { ContentSearchRequest(1, "x".repeat(1_001)) }
        assertFailsWith<IllegalArgumentException> { ContentSearchRequest(1, "line\nline") }
        assertFailsWith<IllegalArgumentException> { ContentSearchRequest(1, "", scopeId = " ") }
        assertFailsWith<IllegalArgumentException> { ContentSearchRequest(1, "", fileType = 1) }
        assertFailsWith<IllegalArgumentException> { ContentSearchRequest(2, "", limit = 0) }
        assertFailsWith<IllegalArgumentException> { ContentSearchRequest(3, "", limit = 51) }
        assertFailsWith<IllegalArgumentException> { ContentSearchRequest(3, "", cursor = "x".repeat(513)) }
    }

    @Test
    fun `maximum page round trips and attachment identities include message sequence`() {
        val item = document().copy(title = "名".repeat(512), snippet = "摘".repeat(500), scopeName = "域".repeat(512))
        val page = ContentSearchPage(List(50) { item.copy(targetId = "doc$it") }, "next")
        val wire = ProtoCodec.encode(page)
        assertTrue(wire.size < 1_048_576)
        assertEquals(page, ProtoCodec.decode(ContentSearchPage, wire))
        val attachment = attachmentHit()
        val distinctMessages = ContentSearchPage(listOf(attachment, attachment.copy(serverSeq = 2)), null)
        assertEquals(distinctMessages, ProtoCodec.decode(ContentSearchPage, ProtoCodec.encode(distinctMessages)))
        assertFailsWith<IllegalArgumentException> { ContentSearchPage(listOf(attachment, attachment), null) }
    }

    @Test
    fun `invalid payload identities and field lengths cannot become search hits`() {
        assertFailsWith<IllegalArgumentException> { document().copy(mimeType = "text/plain") }
        assertFailsWith<IllegalArgumentException> { document().copy(serverSeq = 1) }
        assertFailsWith<IllegalArgumentException> { document().copy(revision = 0) }
        assertFailsWith<IllegalArgumentException> { document().copy(scopeId = "x".repeat(37)) }
        assertFailsWith<IllegalArgumentException> { document().copy(title = "x".repeat(513)) }
        assertFailsWith<IllegalArgumentException> { attachmentHit().copy(targetId = "A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { attachmentHit().copy(size = -1) }
        assertFailsWith<IllegalArgumentException> { attachmentHit().copy(serverSeq = 0) }
    }

    @Test
    fun `decoder rejects oversized collections strings truncated bytes and damaged boolean`() {
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(ContentSearchPage, ProtoCodec.encodePayload { writeVarInt(51) })
        }
        assertFailsWith<ProtocolCorruptionException> {
            ProtoCodec.decode(ContentSearchRequest, ProtoCodec.encodePayload {
                writeVarInt(1)
                writeString("x".repeat(4_001))
            })
        }
        val encoded = ProtoCodec.encode(ContentSearchPage(listOf(attachmentHit()), null))
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(ContentSearchPage, encoded.dropLast(1).toByteArray()) }
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(ContentSearchPage, encoded + byteArrayOf(0)) }
        val malformed = ProtoCodec.encodePayload {
            writeVarInt(3)
            writeString("scope")
            writeString("a".repeat(64))
            writeString("file")
            writeString("")
            writeString("chat")
            writeVarLong(1)
            writeVarLong(1)
            writeString("text/plain")
            writeByte(2)
        }
        assertFailsWith<ProtocolCorruptionException> { ProtoCodec.decode(ContentSearchHit, malformed) }
    }

    @Test
    fun `shared attachment selection ignores thumbnails deduplicates canonical paths and hides revoked content`() {
        val main = Attachment("owner/main.png", "图.png", "Image/PNG; charset=binary", 10)
        val thumb = Attachment("owner/thumb.png", "thumb.png", "image/png", 2)
        val assets = listOf(
            EmbeddedAsset("00000000-0000-4000-8000-000000000001", main, thumb),
            EmbeddedAsset("00000000-0000-4000-8000-000000000002", main.copy(path = "/api/v1/files/owner/main.png")),
        )
        val message = Message("scope", "message", 1, "owner", MessageType.RICH_TEXT.code, 1,
            body = RichTextBody("", plainText = "", assets = assets))
        assertEquals(listOf(main), ContentSearchAttachments.attachments(message))
        assertEquals(emptyList(), ContentSearchAttachments.attachments(message.copy(flags = Message.FLAG_REVOKED)))
        assertEquals(1, ContentSearchAttachments.fileType(main))
        assertEquals(4, ContentSearchAttachments.fileType(main.copy(contentType = "application/pdf")))
        assertEquals(4, ContentSearchAttachments.fileType(main.copy(contentType = "image")))
        assertEquals(listOf(main), ContentSearchAttachments.attachments(message.copy(messageType = MessageType.FILE.code, body = FileBody(main))))
        assertEquals(emptyList(), ContentSearchAttachments.attachments(message.copy(messageType = MessageType.STICKER.code, body = FileBody(main))))
    }

    private fun document() = ContentSearchHit(1, "scope", "document", "title", "snippet", "space", 1, 2)
    private fun attachmentHit() = ContentSearchHit(3, "scope", "a".repeat(64), "file", "snippet", "chat", 1, 2, "text/plain", 10, 1)
}

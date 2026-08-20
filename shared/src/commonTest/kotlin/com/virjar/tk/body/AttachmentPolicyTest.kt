package com.virjar.tk.body

import com.virjar.tk.model.Message
import com.virjar.tk.model.Attachment
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.repository.FileOps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttachmentPolicyTest {

    @Test
    fun `relative and endpoint URLs canonicalize to the same path`() {
        val path = "u123/abc.pdf"
        assertEquals(path, AttachmentPolicy.canonicalPath(path))
        assertEquals(path, AttachmentPolicy.canonicalPath("/api/v1/files/$path"))
        assertEquals(path, AttachmentPolicy.canonicalPath("https://sdk.example/api/v1/files/$path?download=1"))
    }

    @Test
    fun `third party and traversal references are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentPolicy.canonicalPath("https://third-party.example/file.pdf")
        }
        assertFailsWith<IllegalArgumentException> {
            AttachmentPolicy.canonicalPath("u123/../secret")
        }
    }

    @Test
    fun `endpoint URL is always rebound to the active TeamTalk server`() {
        assertEquals(
            "https://current.example/api/v1/files/u123/a.pdf",
            FileOps.resolveUrl(
                "https://current.example/",
                "https://sdk-input.example/api/v1/files/u123/a.pdf?download=1",
            ),
        )
    }

    @Test
    fun `message canonicalization includes thumbnails`() {
        val message = message(
            MessageType.IMAGE,
            ImageBody(
                attachment = attachment("https://sdk.example/api/v1/files/u/main.png", "main.png", "image/png"),
                thumbnail = attachment("/api/v1/files/u/thumb.jpg", "thumb.jpg", "image/jpeg"),
            ),
        )
        val normalized = AttachmentPolicy.canonicalize(message)
        val body = normalized.body as ImageBody
        assertEquals("u/main.png", body.attachment.path)
        assertEquals("u/thumb.jpg", body.thumbnail?.path)
        assertEquals(listOf(body.attachment, body.thumbnail), AttachmentPolicy.attachments(normalized))
    }

    @Test
    fun `attachment body and message type must agree`() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentPolicy.canonicalize(message(MessageType.RICH_TEXT, FileBody(attachment("u/a.txt"))))
        }
        assertFailsWith<IllegalArgumentException> {
            AttachmentPolicy.canonicalize(message(MessageType.FILE, body = null))
        }
        assertFailsWith<IllegalArgumentException> {
            AttachmentPolicy.canonicalize(message(MessageType.FILE, FileBody(attachment("u/a.txt").copy(size = -1))))
        }
    }

    private fun message(type: MessageType, body: MessageBody?) = Message(
        chatId = "chat",
        clientMsgId = "client-message",
        senderUid = "u",
        messageType = type.code,
        timestamp = 1,
        body = body,
    )

    private fun attachment(
        path: String,
        name: String = "a.txt",
        contentType: String = "text/plain",
    ) = Attachment(path, name, contentType, 1)
}

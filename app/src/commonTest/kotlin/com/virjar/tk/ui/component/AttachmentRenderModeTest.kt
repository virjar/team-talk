package com.virjar.tk.ui.component

import com.virjar.tk.body.FileBody
import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.MessageBody
import com.virjar.tk.body.VideoBody
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentRenderModeTest {
    @Test
    fun `uploading file image and video stay non remote even if a path appears early`() {
        attachmentBodies(path = "").forEach { body ->
            assertEquals(
                AttachmentRenderMode.UPLOAD_PLACEHOLDER,
                attachmentRenderMode(message(body, Message.SEND_STATUS_UPLOADING)),
            )
        }
        attachmentBodies(path = "owner/object.bin").forEach { body ->
            assertEquals(
                AttachmentRenderMode.UPLOAD_PLACEHOLDER,
                attachmentRenderMode(message(body, Message.SEND_STATUS_UPLOADING)),
            )
        }
    }

    @Test
    fun `blank file image and video paths never activate remote consumers`() {
        attachmentBodies(path = "   ").forEach { body ->
            val failed = message(body, Message.SEND_STATUS_FAILED)
            assertEquals(AttachmentRenderMode.UNAVAILABLE_PLACEHOLDER, attachmentRenderMode(failed))
            assertFalse(failed.hasReadyEdgeToEdgeMedia())
        }
    }

    @Test
    fun `real attachment paths activate content only after upload state ends`() {
        val bodies = attachmentBodies(path = "owner/object.bin")
        bodies.forEach { body ->
            assertEquals(
                AttachmentRenderMode.REMOTE_CONTENT,
                attachmentRenderMode(message(body, Message.SEND_STATUS_SENDING)),
            )
        }
        assertFalse(message(bodies[0], Message.SEND_STATUS_SENDING).hasReadyEdgeToEdgeMedia())
        assertTrue(message(bodies[1], Message.SEND_STATUS_SENDING).hasReadyEdgeToEdgeMedia())
        assertTrue(message(bodies[2], Message.SEND_STATUS_SENDING).hasReadyEdgeToEdgeMedia())
    }

    private fun attachmentBodies(path: String): List<MessageBody> {
        val attachment = Attachment(
            path = path,
            name = "runtime-smoke.bin",
            contentType = "application/octet-stream",
            size = 129,
        )
        return listOf(
            FileBody(attachment),
            ImageBody(attachment),
            VideoBody(attachment),
        )
    }

    private fun message(body: MessageBody, sendStatus: Int): Message = Message(
        chatId = "chat",
        clientMsgId = "client-message",
        senderUid = "me",
        messageType = when (body) {
            is FileBody -> MessageType.FILE.code
            is ImageBody -> MessageType.IMAGE.code
            is VideoBody -> MessageType.VIDEO.code
            else -> error("unexpected body")
        },
        timestamp = 1,
        body = body,
        sendStatus = sendStatus,
        uploadProgress = 0.5f,
    )
}

package com.virjar.tk.infra.storage

import com.virjar.tk.body.FileBody
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageStoreTest {
    @Test
    fun `message and projection outbox survive restart until projection completes`() {
        val root = Files.createTempDirectory("tk-message-outbox-").toFile()
        var store = MessageStore(root.absolutePath)
        try {
            store.init()
            val message = message(seq = 7)
            assertEquals(7, store.storeMessage(message))
            assertTrue(store.isProjectionPending(message.chatId, message.serverSeq))
            store.close()

            store = MessageStore(root.absolutePath).also { it.init() }
            assertEquals(listOf(message), store.getPendingProjections())
            store.markProjectionComplete(message.chatId, message.serverSeq)
            assertFalse(store.isProjectionPending(message.chatId, message.serverSeq))
            assertTrue(store.getPendingProjections().isEmpty())
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `client message id dedup keeps one pending projection`() {
        val root = Files.createTempDirectory("tk-message-dedup-").toFile()
        val store = MessageStore(root.absolutePath)
        try {
            store.init()
            val first = message(seq = 3)
            val racingDuplicate = first.copy(serverSeq = 4)
            assertEquals(3, store.storeMessage(first))
            assertEquals(3, store.storeMessage(racingDuplicate))
            assertEquals(listOf(first), store.getPendingProjections())
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `attachment reverse index follows message store and edit atomically`() {
        val root = Files.createTempDirectory("tk-message-attachments-").toFile()
        val store = MessageStore(root.absolutePath)
        try {
            store.init()
            val firstAttachment = Attachment("owner/first.pdf", "first.pdf", "application/pdf", 7)
            val secondAttachment = Attachment("owner/second.pdf", "second.pdf", "application/pdf", 9)
            val message = Message(
                chatId = "chat-files",
                clientMsgId = "client-files",
                serverSeq = 11,
                senderUid = "owner",
                messageType = MessageType.FILE.code,
                timestamp = 1_700_000_000_000,
                body = FileBody(firstAttachment),
            )

            store.storeMessage(message)
            assertEquals(setOf("chat-files"), store.getAttachmentChatIds(firstAttachment.path))

            store.updateMessage(
                message.chatId,
                message.serverSeq,
                message.copy(body = FileBody(secondAttachment)),
            )
            assertTrue(store.getAttachmentChatIds(firstAttachment.path).isEmpty())
            assertEquals(setOf("chat-files"), store.getAttachmentChatIds(secondAttachment.path))
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    private fun message(seq: Long) = Message(
        chatId = "chat-1",
        clientMsgId = "client-1",
        serverSeq = seq,
        senderUid = "user-1",
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1_700_000_000_000,
        body = buildRichTextBody("durable message"),
    )
}

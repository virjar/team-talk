package com.virjar.tk.infra.storage

import com.virjar.tk.body.FileBody
import com.virjar.tk.body.GenericPayload
import com.virjar.tk.body.buildRichTextBody
import com.virjar.tk.domain.message.MessageOperationType
import com.virjar.tk.domain.message.MessageProjectionTarget
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessageStoreTest {
    @Test
    fun `unknown generic body survives durable storage and projection restart`() {
        val root = Files.createTempDirectory("tk-message-generic-").toFile()
        var store = MessageStore(root.absolutePath)
        val generic = GenericPayload(404, byteArrayOf(0, 1, 0, 2, 0x7f))
        val message = Message(
            chatId = "chat-generic",
            clientMsgId = "client-generic",
            serverSeq = 1,
            senderUid = "future-client",
            messageType = MessageType.GENERIC.code,
            timestamp = 1,
            body = generic,
        )
        try {
            store.init()
            store.storeTestMessage(message)
            store.close()

            store = MessageStore(root.absolutePath).also { it.init() }
            assertEquals(generic, store.getMessage(message.chatId, message.serverSeq)?.body)
            assertEquals(generic, store.getPendingProjectionOperations().single().message.body)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `message and projection outbox survive restart until projection completes`() {
        val root = Files.createTempDirectory("tk-message-outbox-").toFile()
        var store = MessageStore(root.absolutePath)
        try {
            store.init()
            val message = message(seq = 7)
            assertEquals(7, store.storeTestMessage(message))
            val operation = store.getPendingProjectionOperations().single()
            assertTrue(store.isProjectionPending(operation))
            store.close()

            store = MessageStore(root.absolutePath).also { it.init() }
            val restartedOperation = store.getPendingProjectionOperations().single()
            assertEquals(message, restartedOperation.message)
            store.markProjectionComplete(restartedOperation)
            assertFalse(store.isProjectionPending(restartedOperation))
            assertTrue(store.getPendingProjectionOperations().isEmpty())
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
            assertEquals(3, store.storeTestMessage(first))
            assertEquals(3, store.storeTestMessage(racingDuplicate))
            assertEquals(listOf(first), store.getPendingProjectionOperations().map { it.message })
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `versioned edit and revoke operations survive restart and exact ack cannot delete a later revision`() {
        val root = Files.createTempDirectory("tk-message-operation-revisions-").toFile()
        var store = MessageStore(root.absolutePath)
        try {
            store.init()
            val created = message(seq = 9)
            store.storeTestMessage(created)
            val edited = created.copy(body = buildRichTextBody("edited"), flags = Message.FLAG_EDITED)
            val editOperation = store.updateMessage(
                created.chatId,
                created.serverSeq,
                edited,
                MessageOperationType.EDIT,
                target(created),
            )
            val revoked = edited.copy(flags = edited.flags or Message.FLAG_REVOKED)
            val revokeOperation = store.updateMessage(
                created.chatId,
                created.serverSeq,
                revoked,
                MessageOperationType.REVOKE,
                target(created),
            )
            val createOperation = store.getPendingProjectionOperations(created.chatId, created.serverSeq)
                .single { it.operation == MessageOperationType.CREATE }

            assertEquals(listOf(1L, 2L, 3L), listOf(createOperation, editOperation, revokeOperation).map { it.revision })
            store.markProjectionComplete(createOperation)
            assertEquals(
                listOf(2L, 3L),
                store.getPendingProjectionOperations(created.chatId, created.serverSeq).map { it.revision },
            )

            store.close()
            store = MessageStore(root.absolutePath).also { it.init() }
            assertEquals(
                listOf(MessageOperationType.EDIT, MessageOperationType.REVOKE),
                store.getPendingProjectionOperations(created.chatId, created.serverSeq).map { it.operation },
            )
            store.markProjectionComplete(editOperation)
            assertTrue(store.isProjectionPending(revokeOperation))
            assertEquals(revoked, store.getMessage(created.chatId, created.serverSeq))
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `client message id is globally unique per chat and first request identity survives restart`() {
        val root = Files.createTempDirectory("tk-message-idempotency-scope-").toFile()
        var store = MessageStore(root.absolutePath)
        try {
            store.init()
            val first = message(seq = 1)
            val otherSender = message(seq = 2, senderUid = "user-2")
            val otherChat = message(seq = 1, chatId = "chat-2")

            assertEquals(1, store.storeTestMessage(first))
            assertFailsWith<IllegalArgumentException> { store.storeTestMessage(otherSender) }
            assertFailsWith<IllegalArgumentException> { store.findIdempotentMessage(otherSender) }
            assertEquals(1, store.storeTestMessage(otherChat))
            assertNotNull(store.findIdempotentMessage(first))
            assertNotNull(store.findIdempotentMessage(otherChat))

            store.updateMessage(
                first.chatId,
                first.serverSeq,
                first.copy(body = buildRichTextBody("edited later"), flags = Message.FLAG_EDITED),
                MessageOperationType.EDIT,
                target(first),
            )
            assertEquals(first.serverSeq, store.findIdempotentMessage(first)?.serverSeq)
            assertEquals(first.serverSeq, store.storeTestMessage(first.copy(serverSeq = 3)))

            assertFailsWith<IllegalArgumentException> {
                store.storeTestMessage(first.copy(serverSeq = 3, body = buildRichTextBody("different")))
            }

            store.close()
            store = MessageStore(root.absolutePath).also { it.init() }
            assertEquals(first.serverSeq, store.storeTestMessage(first.copy(serverSeq = 4)))
            assertFailsWith<IllegalArgumentException> {
                store.storeTestMessage(otherSender.copy(serverSeq = 5))
            }
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent senders cannot atomically claim the same chat message id`() {
        val root = Files.createTempDirectory("tk-message-global-id-race-").toFile()
        val store = MessageStore(root.absolutePath)
        val executor = Executors.newFixedThreadPool(2)
        try {
            store.init()
            val first = message(seq = 1, senderUid = "user-1")
            val second = message(seq = 2, senderUid = "user-2")
            val start = CountDownLatch(1)
            val attempts = listOf(first, second).map { candidate ->
                executor.submit<Pair<String, Result<Long>>> {
                    start.await()
                    candidate.senderUid to runCatching { store.storeTestMessage(candidate) }
                }
            }
            start.countDown()
            val results = attempts.map { it.get() }

            assertEquals(1, results.count { it.second.isSuccess })
            assertEquals(1, results.count { it.second.exceptionOrNull() is IllegalArgumentException })
            val stored = store.getHistory(first.chatId, 0, 10).single()
            assertEquals(results.single { it.second.isSuccess }.first, stored.senderUid)
        } finally {
            executor.shutdownNow()
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

            store.storeTestMessage(message)
            assertEquals(setOf("chat-files"), store.getAttachmentChatIds(firstAttachment.path))

            store.updateMessage(
                message.chatId,
                message.serverSeq,
                message.copy(body = FileBody(secondAttachment)),
                MessageOperationType.EDIT,
                target(message),
            )
            assertTrue(store.getAttachmentChatIds(firstAttachment.path).isEmpty())
            assertEquals(setOf("chat-files"), store.getAttachmentChatIds(secondAttachment.path))
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent edits leave only the stored attachment in reverse index`() {
        val root = Files.createTempDirectory("tk-message-concurrent-attachments-").toFile()
        val store = MessageStore(root.absolutePath)
        val executor = Executors.newFixedThreadPool(8)
        try {
            store.init()
            val initial = Attachment("owner/initial.pdf", "initial.pdf", "application/pdf", 1)
            val message = Message(
                chatId = "chat-concurrent-files",
                clientMsgId = "client-concurrent-files",
                serverSeq = 19,
                senderUid = "owner",
                messageType = MessageType.FILE.code,
                timestamp = 1_700_000_000_000,
                body = FileBody(initial),
            )
            store.storeTestMessage(message)
            val candidates = List(32) { index ->
                Attachment("owner/edit-$index.pdf", "edit-$index.pdf", "application/pdf", index.toLong() + 2)
            }
            val start = CountDownLatch(1)
            val futures = candidates.map { attachment ->
                executor.submit {
                    start.await()
                    store.updateMessage(
                        message.chatId,
                        message.serverSeq,
                        message.copy(body = FileBody(attachment)),
                        MessageOperationType.EDIT,
                        target(message),
                    )
                }
            }
            start.countDown()
            futures.forEach { it.get() }

            val storedAttachment = (store.getMessage(message.chatId, message.serverSeq)?.body as FileBody).attachment
            (listOf(initial) + candidates).forEach { attachment ->
                val indexedChats = store.getAttachmentChatIds(attachment.path)
                if (attachment.path == storedAttachment.path) {
                    assertEquals(setOf(message.chatId), indexedChats)
                } else {
                    assertTrue(indexedChats.isEmpty(), "过期附件仍残留反向索引: ${attachment.path}")
                }
            }
        } finally {
            executor.shutdownNow()
            store.close()
            root.deleteRecursively()
        }
    }

    private fun message(
        seq: Long,
        chatId: String = "chat-1",
        senderUid: String = "user-1",
    ) = Message(
        chatId = chatId,
        clientMsgId = "client-1",
        serverSeq = seq,
        senderUid = senderUid,
        messageType = MessageType.RICH_TEXT.code,
        timestamp = 1_700_000_000_000,
        body = buildRichTextBody("durable message"),
    )

    private fun MessageStore.storeTestMessage(
        message: Message,
        idempotencyCandidate: Message = message,
    ): Long = storeMessage(message, idempotencyCandidate, target(message))

    private fun target(message: Message): MessageProjectionTarget =
        MessageProjectionTarget(chatType = 2, recipientUids = listOf(message.senderUid))
}

package com.virjar.tk.server.infra.storage

import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.server.domain.message.MessageOperationType
import com.virjar.tk.server.domain.message.MessageArchiveCursor
import com.virjar.tk.server.domain.message.MessageProjectionTarget
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessageStoreTest {
    @Test
    fun `authoritative RocksDB batches sync their WAL before acknowledgement`() {
        authoritativeRocksWriteOptions().use { options ->
            assertTrue(options.sync())
            assertFalse(options.disableWAL())
        }
    }

    @Test
    fun `append allocates dense sequences atomically and exact replay consumes none`() {
        val root = Files.createTempDirectory("tk-message-append-sequence-").toFile()
        var store = MessageStore(root.absolutePath)
        try {
            store.init()
            val first = message(seq = 0).copy(clientMsgId = "append-first")
            val second = message(seq = 0).copy(
                clientMsgId = "append-second",
                body = buildRichTextBody("second"),
            )

            val storedFirst = store.appendMessage(first, first, target(first))
            val replayedFirst = store.appendMessage(first, first, target(first))
            val storedSecond = store.appendMessage(second, second, target(second))

            assertEquals(1L, storedFirst.serverSeq)
            assertEquals(storedFirst, replayedFirst)
            assertEquals(2L, storedSecond.serverSeq)

            store.close()
            store = MessageStore(root.absolutePath).also { it.init() }
            val third = message(seq = 0).copy(
                clientMsgId = "append-third",
                body = buildRichTextBody("third"),
            )
            assertEquals(3L, store.appendMessage(third, third, target(third)).serverSeq)
            assertEquals(listOf(3L, 2L, 1L), store.getHistory(first.chatId, 0L, 10).map(Message::serverSeq))
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent appends allocate every sequence exactly once without gaps`() {
        val root = Files.createTempDirectory("tk-message-append-concurrent-").toFile()
        val store = MessageStore(root.absolutePath)
        val executor = Executors.newFixedThreadPool(8)
        try {
            store.init()
            val futures = executor.invokeAll(
                (1..64).map { index ->
                    java.util.concurrent.Callable {
                        val candidate = message(seq = 0).copy(
                            clientMsgId = "append-concurrent-$index",
                            body = buildRichTextBody("concurrent $index"),
                        )
                        store.appendMessage(candidate, candidate, target(candidate)).serverSeq
                    }
                },
            )
            val sequences = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals((1L..64L).toSet(), sequences.toSet())
            assertEquals(
                (64L downTo 1L).toList(),
                store.getHistory("chat-1", 0L, 100).map(Message::serverSeq),
            )
        } finally {
            executor.shutdownNow()
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed append validation does not consume the next sequence`() {
        val root = Files.createTempDirectory("tk-message-append-rollback-").toFile()
        val store = MessageStore(root.absolutePath)
        try {
            store.init()
            val candidate = message(seq = 0).copy(clientMsgId = "append-after-failure")
            val invalidTarget = MessageProjectionTarget(
                chatType = 2,
                recipientUids = listOf("u".repeat(1_025)),
            )

            assertFailsWith<IllegalArgumentException> {
                store.appendMessage(candidate, candidate, invalidTarget)
            }

            assertEquals(1L, store.appendMessage(candidate, candidate, target(candidate)).serverSeq)
            assertEquals(listOf(1L), store.getHistory(candidate.chatId, 0L, 10).map(Message::serverSeq))
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
    fun `pending projection pages bound encoded bytes and oversized heads still advance`() {
        val root = Files.createTempDirectory("tk-message-outbox-page-budget-").toFile()
        val store = MessageStore(root.absolutePath)
        try {
            store.init()
            val largeMessages = (1L..3L).map { seq ->
                message(seq = seq).copy(
                    clientMsgId = "client-budget-$seq",
                    body = buildRichTextBody("large-$seq ${"x".repeat(64 * 1024)}"),
                )
            }
            largeMessages.forEach { store.storeTestMessage(it) }

            val drainedClientIds = mutableListOf<String>()
            val byteBoundedPage = store.getPendingProjectionOperations(
                limit = 10,
                maxEncodedBytes = 300L * 1024,
            )
            assertEquals(2, byteBoundedPage.size, "encoded bytes, not only count, must end the page")
            drainedClientIds += byteBoundedPage.map { it.message.clientMsgId }
            byteBoundedPage.forEach(store::markProjectionComplete)

            val oversizedHead = store.getPendingProjectionOperations(limit = 10, maxEncodedBytes = 1L)
            assertEquals(1, oversizedHead.size, "one over-budget head operation must still be returned")
            drainedClientIds += oversizedHead.single().message.clientMsgId
            store.markProjectionComplete(oversizedHead.single())
            assertEquals(largeMessages.map(Message::clientMsgId), drainedClientIds)
            assertTrue(store.getPendingProjectionOperations().isEmpty())

            val versioned = message(seq = 9L).copy(clientMsgId = "client-version-budget")
            store.storeTestMessage(versioned)
            val edited = versioned.copy(body = buildRichTextBody("edited ${"y".repeat(64 * 1024)}"))
            store.updateMessage(
                versioned.chatId,
                versioned.serverSeq,
                edited,
                MessageOperationType.EDIT,
                target(versioned),
            )
            store.updateMessage(
                versioned.chatId,
                versioned.serverSeq,
                edited.copy(flags = Message.FLAG_REVOKED),
                MessageOperationType.REVOKE,
                target(versioned),
            )

            val drainedRevisions = mutableListOf<Long>()
            repeat(3) {
                val page = store.getPendingProjectionOperations(
                    versioned.chatId,
                    versioned.serverSeq,
                    limit = 10,
                    maxEncodedBytes = 1L,
                )
                assertEquals(1, page.size)
                drainedRevisions += page.single().revision
                store.markProjectionComplete(page.single())
            }
            assertEquals(listOf(1L, 2L, 3L), drainedRevisions)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `authoritative archive scan is stable byte bounded and returns only latest revisions`() {
        val root = Files.createTempDirectory("tk-message-archive-scan-").toFile()
        val store = MessageStore(root.absolutePath)
        try {
            store.init()
            val messages = listOf(
                message(seq = 1L, chatId = "z").copy(clientMsgId = "archive-z"),
                message(seq = 1L, chatId = "longer-chat").copy(clientMsgId = "archive-long"),
                message(seq = 2L, chatId = "z").copy(
                    clientMsgId = "archive-z-two",
                    body = buildRichTextBody("large ${"x".repeat(64 * 1024)}"),
                ),
            )
            messages.forEach { stored -> store.storeTestMessage(stored) }
            val edited = messages.first().copy(
                body = buildRichTextBody("latest edited body"),
                flags = Message.FLAG_EDITED,
            )
            store.updateMessage(
                edited.chatId,
                edited.serverSeq,
                edited,
                MessageOperationType.EDIT,
                target(edited),
            )
            val revoked = messages[1].copy(flags = Message.FLAG_REVOKED)
            store.updateMessage(
                revoked.chatId,
                revoked.serverSeq,
                revoked,
                MessageOperationType.REVOKE,
                target(revoked),
            )

            var cursor: MessageArchiveCursor? = null
            val scanned = mutableListOf<Pair<Message, Long>>()
            do {
                val page = store.readArchivePage(
                    after = cursor,
                    limit = 2,
                    maxEncodedBytes = 1L,
                )
                assertTrue(page.entries.size <= 2)
                assertTrue(page.entries.isEmpty() || page.encodedBytes > 0L)
                assertTrue(page.entries.size <= 1, "one over-budget head must advance alone")
                scanned += page.entries.map { it.message to it.revision }
                cursor = page.nextCursor
            } while (cursor != null)

            assertEquals(3, scanned.size)
            assertEquals(3, scanned.map { it.first.chatId to it.first.serverSeq }.distinct().size)
            assertEquals(edited to 2L, scanned.single { it.first.chatId == edited.chatId && it.first.serverSeq == 1L })
            assertEquals(revoked to 2L, scanned.single { it.first.chatId == revoked.chatId })
            assertEquals(messages[2] to 1L, scanned.single { it.first.serverSeq == 2L })

            assertFailsWith<IllegalStateException> {
                store.readArchivePage(
                    after = MessageArchiveCursor("missing-chat", 99L),
                    limit = 1,
                    maxEncodedBytes = 1L,
                )
            }
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
            assertFailsWith<IllegalArgumentException> {
                store.updateMessage(
                    created.chatId,
                    created.serverSeq,
                    edited,
                    MessageOperationType.REVOKE,
                    target(created),
                )
            }
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
    fun `attachment reverse index follows create edit and revoke atomically`() {
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
            assertTrue(
                store.isAttachmentReferencedByAny(
                    firstAttachment.path,
                    setOf("chat-unrelated", "chat-files"),
                ),
            )
            assertFalse(
                store.isAttachmentReferencedByAny(firstAttachment.path, setOf("chat-unrelated")),
            )
            assertFalse(
                store.isAttachmentReferencedByAny(firstAttachment.path, setOf("chat")),
                "a shorter chat id prefix must not authorize a longer indexed chat id",
            )
            assertFalse(store.isAttachmentReferencedByAny(firstAttachment.path, emptySet()))
            assertEquals(
                setOf(firstAttachment.path),
                store.getReferencedAttachmentPaths(
                    setOf(firstAttachment.path, secondAttachment.path, "owner/missing.pdf"),
                ),
            )

            store.updateMessage(
                message.chatId,
                message.serverSeq,
                message.copy(body = FileBody(secondAttachment)),
                MessageOperationType.EDIT,
                target(message),
            )
            assertTrue(store.getAttachmentChatIds(firstAttachment.path).isEmpty())
            assertFalse(
                store.isAttachmentReferencedByAny(firstAttachment.path, setOf(message.chatId)),
            )
            assertEquals(setOf("chat-files"), store.getAttachmentChatIds(secondAttachment.path))
            assertTrue(
                store.isAttachmentReferencedByAny(secondAttachment.path, setOf(message.chatId)),
            )
            assertEquals(
                setOf(secondAttachment.path),
                store.getReferencedAttachmentPaths(setOf(firstAttachment.path, secondAttachment.path)),
            )

            store.updateMessage(
                message.chatId,
                message.serverSeq,
                message.copy(
                    body = FileBody(secondAttachment),
                    flags = Message.FLAG_REVOKED,
                ),
                MessageOperationType.REVOKE,
                target(message),
            )
            assertTrue(store.getAttachmentChatIds(secondAttachment.path).isEmpty())
            assertFalse(
                store.isAttachmentReferencedByAny(secondAttachment.path, setOf(message.chatId)),
            )
            assertTrue(
                store.getReferencedAttachmentPaths(setOf(firstAttachment.path, secondAttachment.path)).isEmpty(),
            )
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

    @Test
    fun `history scans preserve direction and bounds without crossing chat id prefixes`() {
        val root = Files.createTempDirectory("tk-message-chat-prefix-").toFile()
        val store = MessageStore(root.absolutePath)
        try {
            store.init()
            val exact = message(seq = 1, chatId = "chat")
            val latest = message(seq = 3, chatId = "chat").copy(clientMsgId = "client-latest")
            val longer = message(seq = 1, chatId = "chat-long").copy(clientMsgId = "client-long")
            store.storeTestMessage(exact)
            store.storeTestMessage(latest)
            store.storeTestMessage(longer)

            assertEquals(listOf(latest, exact), store.getHistory("chat", 0, 10))
            assertEquals(listOf(exact, latest), store.getHistory("chat", 0, 10, forward = true))
            assertEquals(listOf(latest), store.getHistory("chat", 0, 1))
            assertEquals(listOf(exact), store.getHistory("chat", 0, 1, forward = true))
            assertEquals(listOf(latest, exact), store.getHistory("chat", 3, 10))
            assertEquals(listOf(latest), store.getHistory("chat", 3, 10, forward = true))
            // 起点缺少记录时，分别定位到其前后最近的一条。
            assertEquals(listOf(exact), store.getHistory("chat", 2, 10))
            assertEquals(listOf(latest), store.getHistory("chat", 2, 10, forward = true))
            assertTrue(store.getHistory("chat", 4, 10, forward = true).isEmpty())
            assertEquals(listOf(longer), store.getHistory("chat-long", 0, 10))
            assertEquals(listOf(longer), store.getHistory("chat-long", 0, 10, forward = true))
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `different client identity cannot overwrite an occupied chat sequence`() {
        val root = Files.createTempDirectory("tk-message-seq-collision-").toFile()
        val store = MessageStore(root.absolutePath)
        try {
            store.init()
            val first = message(seq = 1)
            val colliding = first.copy(clientMsgId = "client-2", body = buildRichTextBody("collision"))
            store.storeTestMessage(first)

            assertFailsWith<IllegalStateException> { store.storeTestMessage(colliding) }
            assertEquals(first, store.getMessage(first.chatId, first.serverSeq))
            assertEquals(listOf(first), store.getPendingProjectionOperations().map { it.message })
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `outbox admission rejects an undecodable recipient field without partial message write`() {
        val root = Files.createTempDirectory("tk-message-outbox-budget-").toFile()
        val store = MessageStore(root.absolutePath)
        try {
            store.init()
            val message = message(seq = 1)
            val oversizedTarget = MessageProjectionTarget(
                chatType = 2,
                recipientUids = listOf("u".repeat(1_025)),
            )

            assertFailsWith<IllegalArgumentException> {
                store.storeMessage(message, message, oversizedTarget)
            }
            assertNull(store.getMessage(message.chatId, message.serverSeq))
            assertTrue(store.getPendingProjectionOperations().isEmpty())
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `close waits for an admitted native read before releasing RocksDB`() {
        val root = Files.createTempDirectory("tk-message-close-use-").toFile()
        val blockUse = AtomicBoolean(false)
        val useEntered = CountDownLatch(1)
        val allowUse = CountDownLatch(1)
        val databaseCloseCalls = AtomicInteger()
        val store = MessageStore(
            dbPath = root.absolutePath,
            beforeDatabaseUse = {
                if (blockUse.get()) {
                    useEntered.countDown()
                    allowUse.await()
                }
            },
            databaseCloser = MessageStoreDatabaseCloser { database ->
                databaseCloseCalls.incrementAndGet()
                closeMessageStoreDatabase(database)
            },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            store.init()
            val message = message(seq = 1)
            store.storeTestMessage(message)
            blockUse.set(true)
            val reader = executor.submit<Message?> { store.getMessage(message.chatId, message.serverSeq) }
            assertTrue(useEntered.await(3, TimeUnit.SECONDS))
            val closeStarted = CountDownLatch(1)
            val closing = executor.submit {
                closeStarted.countDown()
                store.close()
            }
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { closing.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(0, databaseCloseCalls.get(), "native close must wait for the admitted read")

            allowUse.countDown()
            assertEquals(message, reader.get(3, TimeUnit.SECONDS))
            closing.get(3, TimeUnit.SECONDS)
            assertFalse(store.isRunning)
            store.close()
            assertEquals(1, databaseCloseCalls.get(), "successful repeated close must not touch RocksDB again")
        } finally {
            allowUse.countDown()
            executor.shutdownNow()
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent and repeated close replay the same terminal failure`() {
        val root = Files.createTempDirectory("tk-message-close-failure-").toFile()
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val terminalFailure = IllegalStateException("controlled database close failure")
        val store = MessageStore(
            dbPath = root.absolutePath,
            beforeDatabaseUse = {},
            databaseCloser = MessageStoreDatabaseCloser { database ->
                closeCalls.incrementAndGet()
                closeEntered.countDown()
                allowClose.await()
                closeMessageStoreDatabase(database)
                throw terminalFailure
            },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            store.init()
            val leader = executor.submit<Throwable> { captureFailure { store.close() } }
            assertTrue(closeEntered.await(3, TimeUnit.SECONDS))
            val follower = executor.submit<Throwable> { captureFailure { store.close() } }
            assertFailsWith<TimeoutException> { follower.get(100, TimeUnit.MILLISECONDS) }

            allowClose.countDown()
            assertSame(terminalFailure, leader.get(3, TimeUnit.SECONDS))
            assertSame(terminalFailure, follower.get(3, TimeUnit.SECONDS))
            assertSame(terminalFailure, captureFailure { store.close() })
            assertSame(terminalFailure, captureFailure { store.init() })
            assertEquals(1, closeCalls.get(), "the failed native close attempt must never be retried")
            assertFalse(store.isRunning)
        } finally {
            allowClose.countDown()
            executor.shutdown()
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow()
            if (store.isRunning) captureFailure { store.close() }
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete MessageStore close-failure root: $root"
            }
        }
    }

    @Test
    fun `fatal close failure keeps its identity and terminalizes the store`() {
        val root = Files.createTempDirectory("tk-message-close-fatal-").toFile()
        val fatal = TestMessageStoreFatalError()
        val store = MessageStore(
            dbPath = root.absolutePath,
            beforeDatabaseUse = {},
            databaseCloser = MessageStoreDatabaseCloser { database ->
                closeMessageStoreDatabase(database)
                throw fatal
            },
        )
        try {
            store.init()
            assertSame(fatal, captureFailure { store.close() })
            assertSame(fatal, captureFailure { store.close() })
            assertSame(fatal, captureFailure { store.init() })
            assertFalse(store.isRunning)
        } finally {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete MessageStore fatal-close root: $root"
            }
        }
    }

    @Test
    fun `interrupted close failure is not swallowed`() {
        val root = Files.createTempDirectory("tk-message-close-interrupted-").toFile()
        val interrupted = InterruptedException("controlled database close interruption")
        val store = MessageStore(
            dbPath = root.absolutePath,
            beforeDatabaseUse = {},
            databaseCloser = MessageStoreDatabaseCloser { database ->
                closeMessageStoreDatabase(database)
                throw interrupted
            },
        )
        try {
            store.init()
            assertSame(interrupted, captureFailure { store.close() })
            assertSame(interrupted, captureFailure { store.close() })
            assertSame(interrupted, captureFailure { store.init() })
            assertFalse(store.isRunning)
        } finally {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete MessageStore interrupted-close root: $root"
            }
        }
    }

    private fun captureFailure(action: () -> Unit): Throwable {
        try {
            action()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected action to fail")
    }

    private class TestMessageStoreFatalError : Error("controlled fatal database close failure")

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

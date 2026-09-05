package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SessionLocalMutationQueueTest {
    @Test
    fun `admission is non-inline and FIFO coalescing retains the latest local facts`() {
        val executor = ManualLocalMutationExecutor()
        val calls = mutableListOf<String>()
        val queue = queue(
            executor = executor,
            operations = operations(
                setDraft = { chatId, draft ->
                    calls += "draft:$chatId:$draft"
                    calls.size.toLong()
                },
                draftCommitted = { chatId, generation -> calls += "draft-commit:$chatId:$generation" },
                markRead = { chatId, seq ->
                    calls += "read:$chatId:$seq"
                    seq
                },
                readCommitted = { chatId, seq -> calls += "read-commit:$chatId:$seq" },
                enqueueOutgoing = { message -> calls += "send:${message.clientMsgId}" },
            ),
        )

        assertTrue(queue.setDraft("chat-a", "first"))
        assertTrue(queue.setDraft("chat-a", "latest"))
        assertTrue(queue.markRead("chat-a", 3L))
        assertTrue(queue.markRead("chat-a", 8L))
        assertTrue(queue.enqueueOutgoing(message("send-a")) { error("unexpected failure: $it") })
        assertTrue(queue.setDraft("chat-b", "other"))

        assertTrue(calls.isEmpty(), "UI admission must not execute storage work inline")
        assertEquals(4, queue.pendingCountForTest())

        executor.runAll()

        assertEquals(
            listOf(
                "draft:chat-a:latest",
                "draft-commit:chat-a:1",
                "read:chat-a:8",
                "read-commit:chat-a:8",
                "send:send-a",
                "draft:chat-b:other",
                "draft-commit:chat-b:6",
            ),
            calls,
        )
        queue.closeAndDrain()
    }

    @Test
    fun `hard capacity rejects without executing or corrupting accepted FIFO`() {
        val executor = ManualLocalMutationExecutor()
        val sent = mutableListOf<String>()
        val rejected = mutableListOf<Throwable>()
        val queue = queue(
            executor = executor,
            operations = operations(enqueueOutgoing = { sent += it.clientMsgId }),
        )

        repeat(MAX_PENDING_SESSION_LOCAL_MUTATIONS) { index ->
            assertTrue(queue.enqueueOutgoing(message("accepted-$index")) { rejected += it })
        }
        assertFalse(queue.enqueueOutgoing(message("overflow")) { rejected += it })
        assertEquals(1, rejected.size)
        assertTrue(rejected.single() is LocalMutationRejectedException)
        assertTrue(sent.isEmpty())

        queue.closeAndDrain()

        assertEquals(MAX_PENDING_SESSION_LOCAL_MUTATIONS, sent.size)
        assertEquals("accepted-0", sent.first())
        assertEquals("accepted-${MAX_PENDING_SESSION_LOCAL_MUTATIONS - 1}", sent.last())
    }

    @Test
    fun `fatal worker failure fences admission and fails every accepted pending command`() {
        val executor = ManualLocalMutationExecutor()
        val fatal = AssertionError("storage worker corrupted")
        val failures = mutableListOf<Pair<String, Throwable>>()
        val queue = queue(
            executor = executor,
            operations = operations(
                setDraft = { _, _ -> throw fatal },
                enqueueOutgoing = { error("pending send must not execute") },
            ),
        )

        assertTrue(queue.setDraft("chat", "fatal") { failures += "draft" to it })
        assertTrue(queue.enqueueOutgoing(message("pending")) { failures += "send" to it })
        executor.runAll()

        assertEquals(listOf("draft", "send"), failures.map { it.first })
        failures.forEach { (_, failure) -> assertSame(fatal, failure) }
        assertEquals(0, queue.pendingCountForTest())

        var lateFailure: Throwable? = null
        assertFalse(queue.setDraft("chat", "late") { lateFailure = it })
        assertSame(fatal, lateFailure)
        assertSame(fatal, assertFailsWith<AssertionError> { queue.closeAndDrain() })
    }

    @Test
    fun `rejected callback may reenter close and throw without deadlock or breaking admission`() {
        val executor = ManualLocalMutationExecutor()
        val queue = queue(executor = executor)
        val callbackFailure = IllegalStateException("observer failed")
        queue.retireAdmission()
        var callbackReturnedFromClose = false

        val accepted = queue.setDraft("chat", "late") {
            queue.closeAndDrain()
            callbackReturnedFromClose = true
            throw callbackFailure
        }

        assertFalse(accepted)
        assertTrue(callbackReturnedFromClose)
        assertSame(
            callbackFailure,
            assertFailsWith<IllegalStateException> { queue.closeAndDrain() },
        )
    }

    @Test
    fun `close aggregates cleanup defects and preserves fatal identity`() {
        val executor = ManualLocalMutationExecutor()
        val pagerFailure = IllegalStateException("pager close failed")
        val rollbackFatal = AssertionError("rollback corrupted")
        val queue = queue(
            executor = executor,
            operations = operations(
                closePager = { throw pagerFailure },
                rollbackOptimisticEdit = { throw rollbackFatal },
            ),
        )

        assertTrue(queue.closePager(TestPager))
        assertTrue(queue.rollbackOptimisticEdit(TestOptimisticLease))

        val thrown = assertFailsWith<AssertionError> { queue.closeAndDrain() }

        assertSame(rollbackFatal, thrown)
        assertTrue(thrown.suppressedExceptions.any { it === pagerFailure })
    }

    @Test
    fun `cleanup diagnostics retain a fixed sample and count every omitted failure`() {
        val executor = ManualLocalMutationExecutor()
        val failures = List(MAX_RETAINED_SESSION_LOCAL_MUTATION_CLEANUP_FAILURES + 5) { index ->
            IllegalStateException("cleanup-$index")
        }
        var failureIndex = 0
        val queue = queue(
            executor = executor,
            operations = operations(closePager = { throw failures[failureIndex++] }),
        )
        failures.indices.forEach { assertTrue(queue.closePager(TestPager)) }

        val thrown = assertFailsWith<IllegalStateException> { queue.closeAndDrain() }
        val dropped = thrown.suppressedExceptions
            .filterIsInstance<LocalMutationCleanupFailuresDroppedException>()
            .single()

        assertSame(failures.first(), thrown)
        assertEquals(
            failures.drop(1).take(MAX_RETAINED_SESSION_LOCAL_MUTATION_CLEANUP_FAILURES - 1),
            thrown.suppressedExceptions.filter { it !is LocalMutationCleanupFailuresDroppedException },
        )
        assertEquals(5L, dropped.droppedCount)
        assertTrue(
            thrown.suppressedExceptions.size <=
                MAX_RETAINED_SESSION_LOCAL_MUTATION_CLEANUP_FAILURES,
        )
        assertSame(
            thrown,
            assertFailsWith<IllegalStateException> { queue.closeAndDrain() },
        )
        assertEquals(
            MAX_RETAINED_SESSION_LOCAL_MUTATION_CLEANUP_FAILURES,
            thrown.suppressedExceptions.size,
        )
    }

    @Test
    fun `bounded diagnostics still propagate a late terminal cleanup defect`() {
        val executor = ManualLocalMutationExecutor()
        val ordinaryFailures = List(MAX_RETAINED_SESSION_LOCAL_MUTATION_CLEANUP_FAILURES) { index ->
            IllegalStateException("ordinary-cleanup-$index")
        }
        val fatal = AssertionError("late cleanup corruption")
        val failures = ordinaryFailures + fatal
        var failureIndex = 0
        val queue = queue(
            executor = executor,
            operations = operations(closePager = { throw failures[failureIndex++] }),
        )
        failures.indices.forEach { assertTrue(queue.closePager(TestPager)) }

        val thrown = assertFailsWith<AssertionError> { queue.closeAndDrain() }
        val dropped = thrown.suppressedExceptions
            .filterIsInstance<LocalMutationCleanupFailuresDroppedException>()
            .single()

        assertSame(fatal, thrown)
        assertTrue(thrown.suppressedExceptions.any { it === ordinaryFailures.first() })
        assertEquals(1L, dropped.droppedCount)
    }

    @Test
    fun `retirement drains accepted fact and rejects late work before cache terminal`() {
        val executor = ManualLocalMutationExecutor()
        var cacheOpen = true
        val drafts = mutableListOf<String?>()
        val rejected = mutableListOf<Throwable>()
        val queue = queue(
            executor = executor,
            operations = operations(
                setDraft = { _, draft ->
                    check(cacheOpen) { "mutation crossed closed cache" }
                    drafts += draft
                    drafts.size.toLong()
                },
            ),
        )

        assertTrue(queue.setDraft("chat", "final"))
        queue.retireAdmission()
        assertFalse(queue.setDraft("chat", "late") { rejected += it })
        queue.closeAndDrain()
        cacheOpen = false
        assertFalse(queue.setDraft("chat", "after-close") { rejected += it })

        assertEquals(listOf<String?>("final"), drafts)
        assertEquals(2, rejected.size)
    }

    @Test
    fun `message admission is bound to the fixed session owner`() {
        val executor = ManualLocalMutationExecutor()
        val queue = queue(executor = executor)

        assertFailsWith<IllegalArgumentException> {
            queue.enqueueOutgoing(message("foreign").copy(senderUid = "other-owner")) {}
        }
        assertEquals(0, queue.pendingCountForTest())
        queue.closeAndDrain()
    }

    @Test
    fun `terminal failure recovery commands stay asynchronous and FIFO`() {
        val executor = ManualLocalMutationExecutor()
        val calls = mutableListOf<String>()
        val results = mutableListOf<String>()
        val queue = queue(
            executor = executor,
            operations = operations(
                discardTerminalFailure = { chatId, clientMsgId ->
                    calls += "discard:$chatId:$clientMsgId"
                    true
                },
                replaceTerminalFailure = { chatId, clientMsgId, replacement ->
                    calls += "replace:$chatId:$clientMsgId:${replacement.clientMsgId}"
                    null
                },
            ),
        )

        assertTrue(
            queue.discardTerminalFailure(
                "chat",
                "failed",
                onResult = { results += "discard:$it" },
            ),
        )
        assertTrue(
            queue.replaceTerminalFailure(
                "chat",
                "failed-2",
                message("replacement"),
                onResult = { results += "replace:${it?.message?.clientMsgId}" },
            ),
        )
        assertTrue(calls.isEmpty())
        assertTrue(results.isEmpty())

        executor.runAll()

        assertEquals(
            listOf("discard:chat:failed", "replace:chat:failed-2:replacement"),
            calls,
        )
        assertEquals(listOf("discard:true", "replace:null"), results)
        queue.closeAndDrain()
    }

    @Test
    fun `terminal failure recovery reports storage failure without result callback`() {
        val executor = ManualLocalMutationExecutor()
        val storageFailure = IllegalStateException("storage failed")
        var resultCalled = false
        var reported: Throwable? = null
        val queue = queue(
            executor = executor,
            operations = operations(
                discardTerminalFailure = { _, _ -> throw storageFailure },
            ),
        )

        assertTrue(
            queue.discardTerminalFailure(
                "chat",
                "failed",
                onResult = { resultCalled = true },
                onFailure = { reported = it },
            ),
        )
        executor.runAll()

        assertFalse(resultCalled)
        assertSame(storageFailure, reported)
        queue.closeAndDrain()
    }

    private fun queue(
        executor: ManualLocalMutationExecutor,
        operations: SessionLocalMutationOperations = operations(),
    ) = SessionLocalMutationQueue(
        ownerUid = OWNER_UID,
        operations = operations,
        executor = executor,
    )

    private fun operations(
        setDraft: (String, String?) -> Long = { _, _ -> 1L },
        draftCommitted: (String, Long) -> Unit = { _, _ -> },
        markRead: (String, Long) -> Long = { _, seq -> seq },
        readCommitted: (String, Long) -> Unit = { _, _ -> },
        insertMessage: (Message) -> Unit = {},
        updateUploadProgress: (String, String, Float) -> Unit = { _, _, _ -> },
        enqueueOutgoing: (Message) -> Unit = {},
        discardTerminalFailure: (String, String) -> Boolean = { _, _ -> false },
        replaceTerminalFailure: (String, String, Message) -> OutgoingMessage? = { _, _, _ -> null },
        markMessageFailed: (String, String) -> Unit = { _, _ -> },
        closePager: (MessagePager) -> Unit = { it.close() },
        rollbackOptimisticEdit: (OptimisticMessageEditLease) -> Unit = {},
    ) = SessionLocalMutationOperations(
        setDraft = setDraft,
        draftCommitted = draftCommitted,
        markRead = markRead,
        readCommitted = readCommitted,
        insertMessage = insertMessage,
        updateUploadProgress = updateUploadProgress,
        enqueueOutgoing = enqueueOutgoing,
        discardTerminalFailure = discardTerminalFailure,
        replaceTerminalFailure = replaceTerminalFailure,
        markMessageFailed = markMessageFailed,
        closePager = closePager,
        rollbackOptimisticEdit = rollbackOptimisticEdit,
    )

    private fun message(clientMsgId: String) = Message(
        chatId = "chat",
        clientMsgId = clientMsgId,
        senderUid = OWNER_UID,
        messageType = 1,
        timestamp = 1L,
    )

    private class ManualLocalMutationExecutor : SessionLocalMutationExecutor {
        private val tasks = ArrayDeque<() -> Unit>()
        private var closed = false

        override fun execute(task: () -> Unit): Boolean {
            if (closed) return false
            tasks.addLast(task)
            return true
        }

        override fun closeAndDrain() {
            closed = true
            runAll()
        }

        fun runAll() {
            while (true) tasks.removeFirstOrNull()?.invoke() ?: return
        }
    }

    private object TestPager : MessagePager {
        override val messages = flowOf(emptyList<Message>())
        override val hasMore = MutableStateFlow(false)
        override fun loadMore(pageSize: Int) = MessagePageLoadResult.Exhausted
        override fun close() = Unit
    }

    private object TestOptimisticLease : OptimisticMessageEditLease

    private companion object {
        const val OWNER_UID = "owner"
    }
}

package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Message
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionLocalMutationExecutorTest {
    @Test
    fun `Compose Main admission stays responsive while storage worker is blocked`() {
        val operationThreads = ConcurrentLinkedQueue<String>()
        val firstOperationEntered = CountDownLatch(1)
        val releaseStorage = CountDownLatch(1)
        val queue = SessionLocalMutationQueue(
            ownerUid = OWNER_UID,
            operations = operations(
                setDraft = { _, _ ->
                    operationThreads += Thread.currentThread().name
                    firstOperationEntered.countDown()
                    check(releaseStorage.await(5, TimeUnit.SECONDS)) { "storage test was not released" }
                    1L
                },
                markRead = { _, seq ->
                    operationThreads += Thread.currentThread().name
                    seq
                },
            ),
        )
        val composeMain = Executors.newSingleThreadExecutor { task ->
            Thread(task, COMPOSE_MAIN_THREAD).apply { isDaemon = true }
        }
        try {
            assertTrue(composeMain.submit<Boolean> { queue.setDraft("chat", "blocked") }.get(1, TimeUnit.SECONDS))
            assertTrue(firstOperationEntered.await(1, TimeUnit.SECONDS))

            // 一笔长 SQLite 事务已经持有 writer；第二条 UI 命令仍然只是
            // 追加到有界的内存 FIFO 并立即返回。
            assertTrue(composeMain.submit<Boolean> { queue.markRead("chat", 9L) }.get(1, TimeUnit.SECONDS))

            releaseStorage.countDown()
            queue.closeAndDrain()

            assertEquals(2, operationThreads.size)
            operationThreads.forEach { workerName ->
                assertNotEquals(COMPOSE_MAIN_THREAD, workerName)
                assertTrue(workerName.startsWith("teamtalk-local-writer-"))
            }
        } finally {
            releaseStorage.countDown()
            composeMain.shutdownNow()
        }
    }

    @Test
    fun `concurrent retirement either drains admission or rejects it exactly once`() {
        repeat(50) { iteration ->
            val executor = ConcurrentManualExecutor()
            val drafts = mutableListOf<String?>()
            val queue = SessionLocalMutationQueue(
                ownerUid = OWNER_UID,
                operations = operations(
                    setDraft = { _, draft ->
                        synchronized(drafts) { drafts += draft }
                        1L
                    },
                ),
                executor = executor,
            )
            val start = CountDownLatch(1)
            val accepted = AtomicBoolean()
            val rejected = AtomicReference<Throwable?>()
            val submitter = Thread({
                start.await()
                accepted.set(queue.setDraft("chat", "draft-$iteration") { rejected.set(it) })
            }, "mutation-submitter").apply { isDaemon = true }
            val retiree = Thread({
                start.await()
                queue.retireAdmission()
            }, "session-retiree").apply { isDaemon = true }
            submitter.start()
            retiree.start()
            start.countDown()
            submitter.join(2_000)
            retiree.join(2_000)
            assertFalse(submitter.isAlive)
            assertFalse(retiree.isAlive)

            queue.closeAndDrain()

            if (accepted.get()) {
                assertEquals(
                    listOf<String?>("draft-$iteration"),
                    synchronized(drafts) { drafts.toList() },
                )
                assertEquals(null, rejected.get())
            } else {
                assertTrue(synchronized(drafts) { drafts.isEmpty() })
                assertNotNull(rejected.get())
            }
        }
    }

    @Test
    fun `capacity rejection callback can close while worker starts and callback exception is contained`() {
        val executor = CloseStartsWorkerExecutor()
        val queue = SessionLocalMutationQueue(
            ownerUid = OWNER_UID,
            operations = operations(enqueueOutgoing = {}),
            executor = executor,
        )
        repeat(MAX_PENDING_SESSION_LOCAL_MUTATIONS) { index ->
            assertTrue(queue.enqueueOutgoing(message("accepted-$index")) { error("unexpected: $it") })
        }
        val callbackFailure = IllegalStateException("callback defect")
        val callbackClosed = AtomicBoolean()

        val accepted = queue.enqueueOutgoing(message("overflow")) {
            queue.closeAndDrain()
            callbackClosed.set(true)
            throw callbackFailure
        }

        assertFalse(accepted)
        assertTrue(callbackClosed.get())
        val replayed = runCatching { queue.closeAndDrain() }.exceptionOrNull()
        assertTrue(replayed === callbackFailure)
    }

    private fun operations(
        setDraft: (String, String?) -> Long = { _, _ -> 1L },
        markRead: (String, Long) -> Long = { _, seq -> seq },
        enqueueOutgoing: (Message) -> Unit = {},
    ) = SessionLocalMutationOperations(
        setDraft = setDraft,
        draftCommitted = { _, _ -> },
        markRead = markRead,
        readCommitted = { _, _ -> },
        insertMessage = {},
        updateUploadProgress = { _, _, _ -> },
        enqueueOutgoing = enqueueOutgoing,
        markMessageFailed = { _, _ -> },
        closePager = { it.close() },
        rollbackOptimisticEdit = {},
    )

    private fun message(clientMsgId: String) = Message(
        chatId = "chat",
        clientMsgId = clientMsgId,
        senderUid = OWNER_UID,
        messageType = 1,
        timestamp = 1L,
    )

    private class ConcurrentManualExecutor : SessionLocalMutationExecutor {
        private val lock = Any()
        private val tasks = ArrayDeque<() -> Unit>()
        private var closed = false

        override fun execute(task: () -> Unit): Boolean = synchronized(lock) {
            if (closed) false else {
                tasks.addLast(task)
                true
            }
        }

        override fun closeAndDrain() {
            synchronized(lock) { closed = true }
            while (true) {
                val task = synchronized(lock) { tasks.removeFirstOrNull() } ?: return
                task()
            }
        }
    }

    /** 只从 close 启动其排队的 worker，确定性地暴露回调与锁的顺序反转。 */
    private class CloseStartsWorkerExecutor : SessionLocalMutationExecutor {
        private val start = CountDownLatch(1)
        private val task = AtomicReference<(() -> Unit)?>(null)
        private val worker = Thread({
            start.await()
            task.get()?.invoke()
        }, "close-started-local-worker").apply {
            isDaemon = true
            start()
        }

        override fun execute(task: () -> Unit): Boolean = this.task.compareAndSet(null, task)

        override fun closeAndDrain() {
            start.countDown()
            worker.join(3_000)
            check(!worker.isAlive) { "local worker deadlocked with rejection callback" }
        }
    }

    private companion object {
        const val OWNER_UID = "owner"
        const val COMPOSE_MAIN_THREAD = "compose-main-test"
    }
}

package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.DocumentCommentRpcContract
import com.virjar.tk.protocol.rpc.gen.TaskRpcContract
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.DocumentCommentRepository
import com.virjar.tk.shared.repository.TaskRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.*
import kotlin.test.*

/** 真实 SQLite、生成 RPC 编解码与受控响应顺序；不依赖延迟猜测或实际网络。 */
class ReliableCommandReadConcurrencyIntegrationTest {
    @Test
    fun `task read waits do not block commands and later reads retain their order`() = runBlocking {
        withCache { cache ->
            val before = task()
            val accepted = before.copy(title = "服务端确认的修改", revision = 2)
            val oldPage = TaskPage(listOf(before), null)
            cache.tasks.applyPage(TASK_KEY, oldPage, cache.tasks.generation(), OWNER)
            val responses = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encode(oldPage))
                enqueueOk(ProtoCodec.encode(TaskCommandResult(accepted)))
                enqueueOk(ProtoCodec.encode(accepted))
            }
            val held = HeldRpcResponse(responses, TaskRpcContract.M_LIST)
            val repository = TaskRepository(held, cache.tasks, OWNER)
            repository.edit(before, draft(accepted.title)).getOrThrow()
            val command = requireNotNull(cache.tasks.pending().single().command)

            val oldRead = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(TASK_KEY) }
            held.started.await()
            val nextRead = async(start = CoroutineStart.UNDISPATCHED) { repository.get(ID) }
            assertEquals(listOf(TaskRpcContract.M_LIST), responses.calls.map { it.second })
            repository.retryPending().getOrThrow()
            assertEquals(accepted, cache.tasks.task(ID))
            assertTrue(cache.tasks.pending().isEmpty())
            assertTrue(cache.tasks.isPageStale(TASK_KEY))
            assertFalse(nextRead.isCompleted, "读之间仍排队，命令无需等旧页返回")
            assertContentEquals(TaskRpcContract.encodeMutate(command), responses.calls.last().third)

            held.release.complete(Unit)
            assertFalse(oldRead.await().getOrThrow(), "ACK 使此前 list 失效")
            assertEquals(accepted, nextRead.await().getOrThrow())
            assertEquals(accepted, cache.tasks.task(ID))
            assertTrue(cache.tasks.isPageStale(TASK_KEY))
        }
    }

    @Test
    fun `comment acknowledgement rejects the old page while a later page waits its turn`() = runBlocking {
        withCache { cache ->
            val before = comment()
            val accepted = before.copy(body = "服务端确认的修改", revision = 2)
            val oldPage = DocumentCommentPage(listOf(before), 0)
            val newPage = DocumentCommentPage(listOf(accepted), 0)
            cache.documentComments.applyPage(COMMENT_KEY, oldPage, cache.documentComments.generation())
            val responses = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encode(oldPage))
                enqueueOk(ProtoCodec.encode(accepted))
                enqueueOk(ProtoCodec.encode(newPage))
            }
            val held = HeldRpcResponse(responses, DocumentCommentRpcContract.M_LIST)
            val repository = DocumentCommentRepository(held, cache.documentComments)
            repository.update(before, accepted.body).getOrThrow()

            val oldRead = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(COMMENT_KEY) }
            held.started.await()
            val nextRead = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(COMMENT_KEY) }
            assertEquals(1, responses.calls.size)
            repository.retryPending().getOrThrow()
            assertEquals(newPage, cache.documentComments.page(COMMENT_KEY))
            assertTrue(cache.documentComments.pending().isEmpty())
            assertFalse(nextRead.isCompleted)
            assertContentEquals(
                DocumentCommentRpcContract.encodeUpdate(SPACE, DOCUMENT, ID, accepted.body, before.revision),
                responses.calls.last().third,
            )

            held.release.complete(Unit)
            assertFalse(oldRead.await().getOrThrow(), "旧 list 不能盖掉刚确认的正文")
            assertTrue(nextRead.await().getOrThrow())
            assertEquals(newPage, cache.documentComments.page(COMMENT_KEY))
        }
    }

    @Test
    fun `task audit changed by an acknowledgement fails without cancelling its caller`() = runBlocking {
        withCache { cache ->
            val responses = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encode(TaskAuditPage(emptyList(), null)))
                enqueueOk(ProtoCodec.encode(TaskCommandResult(task().copy(revision = 2))))
            }
            val held = HeldRpcResponse(responses, TaskRpcContract.M_AUDIT)
            val repository = TaskRepository(held, cache.tasks, OWNER)
            repository.edit(task(), draft()).getOrThrow()
            val audit = async(start = CoroutineStart.UNDISPATCHED) { repository.audit(ID) }
            held.started.await()
            repository.retryPending().getOrThrow()
            held.release.complete(Unit)

            val failure = assertIs<Outcome.Failure>(audit.await())
            assertIs<IllegalStateException>(assertIs<AppError.Unknown>(failure.error).cause)
            assertFalse(audit.isCancelled)
            assertTrue(currentCoroutineContext().isActive)
        }
    }

    @Test
    fun `task command confirmation still serializes competing recovery and discard`() = runBlocking {
        withCache { cache ->
            val responses = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(TaskCommandResult(task().copy(revision = 2)))) }
            val held = HeldRpcResponse(responses, TaskRpcContract.M_MUTATE)
            val repository = TaskRepository(held, cache.tasks, OWNER)
            repository.edit(task(), draft()).getOrThrow()
            val original = cache.tasks.pending(ID)
            val first = async(start = CoroutineStart.UNDISPATCHED) { repository.retryPending() }
            held.started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { repository.retryPending() }
            val discard = async(start = CoroutineStart.UNDISPATCHED) { repository.discardRejected(ID) }
            assertFalse(second.isCompleted)
            assertFalse(discard.isCompleted)
            assertEquals(1, responses.calls.size)
            assertEquals(original, cache.tasks.pending(ID))

            held.release.complete(Unit)
            first.await().getOrThrow()
            second.await().getOrThrow()
            discard.await()
            assertEquals(1, responses.calls.size)
            assertNull(cache.tasks.pending(ID))
        }
    }

    @Test
    fun `comment command confirmation still serializes competing recovery and discard`() = runBlocking {
        withCache { cache ->
            val responses = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(comment().copy(revision = 2))) }
            val held = HeldRpcResponse(responses, DocumentCommentRpcContract.M_UPDATE)
            val repository = DocumentCommentRepository(held, cache.documentComments)
            repository.update(comment(), "保留原始编辑").getOrThrow()
            val original = cache.documentComments.pending(ID)
            val first = async(start = CoroutineStart.UNDISPATCHED) { repository.retryPending() }
            held.started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { repository.retryPending() }
            val discard = async(start = CoroutineStart.UNDISPATCHED) { repository.discardRejected(ID) }
            assertFalse(second.isCompleted)
            assertFalse(discard.isCompleted)
            assertEquals(1, responses.calls.size)
            assertEquals(original, cache.documentComments.pending(ID))

            held.release.complete(Unit)
            first.await().getOrThrow()
            second.await().getOrThrow()
            discard.await()
            assertEquals(1, responses.calls.size)
            assertNull(cache.documentComments.pending(ID))
        }
    }

    @Test
    fun `task reset and revocation retire concurrent reads without losing an unknown command`() = runBlocking {
        for (reset in listOf(false, true)) withCache { cache ->
            val oldPage = TaskPage(listOf(task()), null)
            cache.tasks.applyPage(TASK_KEY, oldPage, cache.tasks.generation(), OWNER)
            val responses = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encode(oldPage))
                enqueueError(504, "unknown commit result")
            }
            val heldCommand = HeldRpcResponse(responses, TaskRpcContract.M_MUTATE)
            val heldRead = HeldRpcResponse(heldCommand, TaskRpcContract.M_LIST)
            val repository = TaskRepository(heldRead, cache.tasks, OWNER)
            repository.edit(task(), draft()).getOrThrow()
            val original = requireNotNull(cache.tasks.pending(ID))
            val read = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(TASK_KEY) }
            heldRead.started.await()
            val send = async(start = CoroutineStart.UNDISPATCHED) { repository.retryPending() }
            heldCommand.started.await()
            if (reset) cache.resetServerProjection(DATASET)
            else cache.tasks.invalidate(TaskChangedPayload(ID, 2, TaskChangedPayload.REVOKED))
            heldCommand.release.complete(Unit)
            heldRead.release.complete(Unit)

            assertEquals(Outcome.Failure(AppError.Timeout), send.await())
            assertFalse(read.await().getOrThrow())
            assertNull(cache.tasks.task(ID))
            assertNull(cache.tasks.page(TASK_KEY))
            assertEquals(original, cache.tasks.pending(ID))
            assertContentEquals(TaskRpcContract.encodeMutate(requireNotNull(original.command)), responses.calls.last().third)
        }
    }

    @Test
    fun `comment reset and revocation retire concurrent reads without losing an unknown command`() = runBlocking {
        for (reset in listOf(false, true)) withCache { cache ->
            val oldPage = DocumentCommentPage(listOf(comment()), 0)
            cache.documentComments.applyPage(COMMENT_KEY, oldPage, cache.documentComments.generation())
            val responses = FakeRpcInvoker().apply {
                enqueueOk(ProtoCodec.encode(oldPage))
                enqueueError(504, "unknown commit result")
            }
            val heldCommand = HeldRpcResponse(responses, DocumentCommentRpcContract.M_UPDATE)
            val heldRead = HeldRpcResponse(heldCommand, DocumentCommentRpcContract.M_LIST)
            val repository = DocumentCommentRepository(heldRead, cache.documentComments)
            repository.update(comment(), "保留原始编辑").getOrThrow()
            val original = cache.documentComments.pending(ID)
            val read = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(COMMENT_KEY) }
            heldRead.started.await()
            val send = async(start = CoroutineStart.UNDISPATCHED) { repository.retryPending() }
            heldCommand.started.await()
            if (reset) cache.resetServerProjection(DATASET)
            else cache.documentComments.invalidate(SPACE, DOCUMENT, purge = true)
            heldCommand.release.complete(Unit)
            heldRead.release.complete(Unit)

            assertEquals(Outcome.Failure(AppError.Timeout), send.await())
            assertFalse(read.await().getOrThrow())
            assertNull(cache.documentComments.page(COMMENT_KEY))
            assertEquals(original, cache.documentComments.pending(ID))
            assertContentEquals(
                DocumentCommentRpcContract.encodeUpdate(SPACE, DOCUMENT, ID, "保留原始编辑", 1),
                responses.calls.last().third,
            )
        }
    }

    /** 先取得服务器响应，再挂起第一次指定方法，模拟已读旧快照而响应尚未交给仓库。 */
    private class HeldRpcResponse(private val delegate: RpcInvoker, private val heldMethod: Int) : RpcInvoker {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
            val response = delegate.invoke(service, methodId, payload)
            if (methodId == heldMethod && started.complete(Unit)) release.await()
            return response
        }
    }

    private suspend fun withCache(block: suspend CoroutineScope.(LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try { withTimeout(5_000) { block(cache) } } finally { cache.close() }
    }

    private fun draft(title: String = "任务") = TaskDraft(title, "原始正文", OWNER)
    private fun task() = WorkTask(ID, OWNER, OWNER, "任务", "原始正文", TaskPolicy.TODO,
        TaskPolicy.CONTEXT_NONE, "", null, null, 1, 1, 1)
    private fun comment() = DocumentComment(ID, SPACE, DOCUMENT, 1, OWNER, "执行人", null, "原始正文", 1, 1, 1, false)

    private companion object {
        const val ID = "00000000-0000-4000-8000-000000000001"
        const val DATASET = "00000000-0000-4000-8000-000000000010"
        const val OWNER = "command-owner"
        const val SPACE = "space"
        const val DOCUMENT = "document"
        val TASK_KEY = TaskPageKey(TaskPolicy.VIEW_ASSIGNED)
        val COMMENT_KEY = DocumentCommentPageKey(SPACE, DOCUMENT)
    }
}

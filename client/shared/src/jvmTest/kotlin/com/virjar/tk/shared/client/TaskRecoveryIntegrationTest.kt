package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.TaskRpcContract
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.TaskRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** 真实 SQLite、生成 RPC 编解码和事件游标，覆盖任务跨进程恢复边界。 */
class TaskRecoveryIntegrationTest {
    @Test
    fun `unknown result survives reopen and replays exact command bytes`() = runBlocking {
        database { file ->
            lateinit var record: PendingTaskCommand
            lateinit var bytes: ByteArray
            cache(file, true) { cache ->
                val rpc = FakeRpcInvoker().apply { throwOnInvoke = AppError.Network }
                val repo = TaskRepository(rpc, cache.tasks, OWNER)
                repo.create(draft()).getOrThrow()
                record = cache.tasks.pending().single()
                assertEquals(Outcome.Failure(AppError.Network), repo.retryPending())
                bytes = checkNotNull(rpc.calls.single().third)
                assertEquals(TaskRpcContract.M_MUTATE, rpc.calls.single().second)
                assertFailsWith<IllegalStateException> { repo.discardRejected(record.command.taskId) }
            }
            cache(file) { cache ->
                assertEquals(record, cache.tasks.pending().single())
                val task = task(record.command.taskId)
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(TaskCommandResult(task))) }
                TaskRepository(rpc, cache.tasks, OWNER).retryPending().getOrThrow()
                assertContentEquals(bytes, rpc.calls.single().third)
                assertTrue(cache.tasks.pending().isEmpty())
                assertEquals(task, cache.tasks.task(task.taskId))
            }
            cache(file) { assertTrue(it.tasks.pending().isEmpty()); assertEquals(record.command.taskId, it.tasks.task(record.command.taskId)?.taskId) }
        }
    }

    @Test
    fun `every command kind and known rejection retains original intent across restart`() = runBlocking {
        database { file ->
            lateinit var saved: List<PendingTaskCommand>
            cache(file, true) { cache ->
                val rpc = FakeRpcInvoker().apply { enqueueError(409, "changed"); enqueueError(403, "denied"); enqueueError(410, "expired") }
                val repo = TaskRepository(rpc, cache.tasks, OWNER)
                repo.create(draft()).getOrThrow()
                repo.edit(task(id(2)), draft().copy(title = "保留编辑")).getOrThrow()
                repo.setStatus(task(id(3)), TaskPolicy.DONE).getOrThrow()
                assertIs<Outcome.Failure>(repo.retryPending())
                saved = cache.tasks.pending()
                assertEquals(setOf(TaskCommand.CREATE, TaskCommand.EDIT, TaskCommand.STATUS), saved.map { it.command.kind }.toSet())
                assertTrue(saved.all { it.failure != null })
                repo.retryPending().getOrThrow()
                assertEquals(3, rpc.calls.size)
            }
            cache(file) { cache ->
                val repo = TaskRepository(FakeRpcInvoker(), cache.tasks, OWNER)
                assertEquals(saved, cache.tasks.pending())
                val rejected = saved.first()
                repo.retry(rejected.command.taskId)
                assertEquals(rejected.command, cache.tasks.pending().first().command)
                assertNull(cache.tasks.pending().first().failure)
                saved.drop(1).forEach { repo.discardRejected(it.command.taskId) }
                assertEquals(1, cache.tasks.pending().size)
            }
        }
    }

    @Test
    fun `revoked or reset snapshots cannot resurrect content or delete pending intent`() = runBlocking {
        cache { cache ->
            cache.bindSyncDataset(DATASET)
            val task = task()
            val local = cache.tasks
            assertTrue(local.applyPage(KEY, TaskPage(listOf(task), null), local.generation(), OWNER))
            val repo = TaskRepository(FakeRpcInvoker(), local, OWNER)
            repo.edit(task, draft().copy(title = "保留本机修改")).getOrThrow()
            val oldGeneration = local.generation()
            local.invalidate(TaskChangedPayload(task.taskId, 2, TaskChangedPayload.REVOKED))
            assertNull(local.task(task.taskId)); assertNull(local.page(KEY))
            val revokedGeneration = local.generation()
            local.revoke(task.taskId)
            assertEquals(revokedGeneration, local.generation(), "Repeated denied reads must not trigger a refresh loop")
            assertFalse(local.applyTask(task, oldGeneration, OWNER))
            assertEquals("保留本机修改", local.pending().single().command.draft?.title)
            cache.resetServerProjection(DATASET)
            assertEquals(1, local.pending().size)
        }
    }

    @Test
    fun `late successful ack clears exact intent without restoring revoked data`() = runBlocking {
        cache { cache ->
            val responses = FakeRpcInvoker()
            val local = cache.tasks
            val rpc = object : RpcInvoker {
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                    val result = responses.invoke(service, methodId, payload)
                    local.invalidate(TaskChangedPayload(ID, 2, TaskChangedPayload.REVOKED))
                    return result
                }
            }
            val repo = TaskRepository(rpc, local, OWNER)
            repo.edit(task(), draft()).getOrThrow()
            responses.enqueueOk(ProtoCodec.encode(TaskCommandResult(task().copy(revision = 2))))
            repo.retryPending().getOrThrow()
            assertTrue(local.pending().isEmpty())
            assertNull(local.task(ID))
        }
    }

    @Test
    fun `due event is durable before cursor and needs fresh authorization before presentation`() = runBlocking {
        database { file ->
            cache(file, true) { cache ->
                cache.bindSyncDataset(DATASET)
                var wakes = 0
                val ep = EventProcessor(ImClient("127.0.0.1", 1), cache, ownerUid = OWNER, onTaskReminderDirty = { wakes++ })
                ep.processNotify(NotifyPayload(1, NotifyType.TASK_DUE.code, ProtoCodec.encode(TaskDuePayload(ID, 1, 200))))
                assertEquals(1L, cache.getSyncState()?.cursor)
                assertEquals(listOf(TaskDuePayload(ID, 1, 200)), cache.tasks.reminderHints())
                assertTrue(cache.tasks.reminders().isEmpty())
                assertEquals(1, wakes)
            }
            cache(file) { cache ->
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(task().copy(dueAt = 100, remindedAt = 200))) }
                TaskRepository(rpc, cache.tasks, OWNER).retryPending().getOrThrow()
                assertEquals(TaskRpcContract.M_GET, rpc.calls.single().second)
                assertEquals(TaskReminder(ID, 1, 200), cache.tasks.reminders().single())
                cache.tasks.markReminderNotified(ID, 200)
                assertFalse(cache.tasks.reminders().single().seen)
                cache.tasks.markReminderSeen(ID, 200)
            }
            cache(file) { cache ->
                val local = cache.tasks
                assertTrue(local.reminders().single().seen)
                assertTrue(local.reminders().single().notified)
                local.invalidate(TaskChangedPayload(ID, 2, TaskChangedPayload.UPDATED))
                assertTrue(local.reminders().isEmpty())
                val same = task().copy(title = "只改标题", revision = 2, dueAt = 100, remindedAt = 200)
                assertTrue(local.applyTask(same, local.generation(), OWNER))
                assertTrue(local.reminders().single().seen && local.reminders().single().notified)
                local.applyTask(same.copy(status = TaskPolicy.DONE, revision = 3, remindedAt = null), local.generation(), OWNER)
                assertTrue(local.reminders().isEmpty())
                local.applyTask(same.copy(status = TaskPolicy.TODO, revision = 4, dueAt = 300, remindedAt = 400), local.generation(), OWNER)
                assertFalse(local.reminders().single().seen)
                local.markReminderSeen(ID, 200)
                assertFalse(local.reminders().single().seen, "Old action must not mark the new due occurrence")
            }
        }
    }

    @Test
    fun `reassignment clears reminders and invalidation races do not cancel recovery`() = runBlocking {
        cache { cache ->
            val local = cache.tasks
            local.due(TaskDuePayload(ID, 1, 200))
            val responses = FakeRpcInvoker().apply {
                repeat(3) { enqueueOk(ProtoCodec.encode(task().copy(dueAt = 100, remindedAt = 200))) }
            }
            var invalidateReads = true
            val rpc = object : RpcInvoker {
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                    val result = responses.invoke(service, methodId, payload)
                    if (invalidateReads) local.invalidate(TaskChangedPayload(ID, 1, TaskChangedPayload.UPDATED))
                    return result
                }
            }
            val repo = TaskRepository(rpc, local, OWNER)
            val unstable = assertIs<Outcome.Failure>(repo.retryPending())
            assertEquals(503, assertIs<AppError.Business>(unstable.error).code)
            assertTrue(local.reminders().isEmpty())
            invalidateReads = false
            repo.retryPending().getOrThrow()
            assertEquals(1, local.reminders().size)
            local.applyTask(task().copy(assigneeUid = "other", revision = 2, dueAt = 100), local.generation(), OWNER)
            assertTrue(local.reminders().isEmpty())
        }
    }

    @Test
    fun `late read after ordinary close cannot write its projection`() = runBlocking {
        database { file ->
            cache(file, true) { cache ->
                val generation = cache.tasks.generation()
                cache.close()
                assertFalse(cache.tasks.applyTask(task(), generation, OWNER))
            }
            cache(file) { assertNull(it.tasks.task(ID)) }
        }
    }

    @Test
    fun `task tables survive single step migration replay beside pending comments`() = runBlocking {
        database { file ->
            cache(file, true) { cache ->
                cache.documentComments.prepare(PendingDocumentComment(ID, ID, ID, PendingDocumentComment.CREATE, "保留旧资料"))
            }
            // 旧库认领路径会在已有部分 v0.0.2 对象的库上重放整条迁移；重放必须幂等。
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
            try {
                listOf("task_projection", "task_pages", "pending_task_commands", "task_reminders").forEach {
                    driver.execute(null, "DROP TABLE $it", 0)
                }
                AppDatabase.Schema.migrate(driver, 1, 2)
            } finally { driver.close() }
            cache(file) { cache ->
                assertEquals("保留旧资料", cache.documentComments.pending().single().body)
                assertTrue(cache.tasks.pending().isEmpty())
                TaskRepository(FakeRpcInvoker(), cache.tasks, OWNER).create(draft()).getOrThrow()
                assertEquals(1, cache.tasks.pending().size)
            }
        }
    }

    private fun draft() = TaskDraft("需要完成的任务", "原描述", OWNER)
    private fun task(taskId: String = ID) = WorkTask(taskId, OWNER, OWNER, "需要完成的任务", "原描述",
        TaskPolicy.TODO, TaskPolicy.CONTEXT_NONE, "", null, null, 1, 1, 1)
    private fun id(value: Long) = UUID(0, value).toString()
    private suspend fun database(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-task-recovery-").toFile()
        try { block(directory.resolve("client.db")) } finally { directory.deleteRecursively() }
    }
    private suspend fun cache(file: File? = null, create: Boolean = file == null, block: suspend (LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver(file?.let { "jdbc:sqlite:${it.path}" } ?: JdbcSqliteDriver.IN_MEMORY)
        if (create) AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try { block(cache) } finally { cache.close() }
    }
    companion object {
        private const val ID = "00000000-0000-4000-8000-000000000001"
        private const val DATASET = "00000000-0000-4000-8000-000000000010"
        private const val OWNER = "task-owner"
        private val KEY = TaskPageKey(TaskPolicy.VIEW_ASSIGNED)
    }
}

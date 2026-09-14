package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.body.OfficeRefBody
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** 新旧任务共用同一 SQLite outbox；扩展数据只通过新 RPC 写入独立元数据投影。 */
class TaskDetailsRecoveryIntegrationTest {
    @Test
    fun `schema three migration preserves legacy task bytes and pending JSON`() = runBlocking {
        database { file ->
            val command = TaskCommand(id(90), 100, ID, 0, TaskCommand.CREATE, draft())
            val payload = """{"command":${Json.encodeToString(command)},"failure":"unknown result"}"""
            val raw = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
            AppDatabase.Schema.create(raw)
            val queries = AppDatabase(raw).appDatabaseQueries
            queries.upsertTask(ID, ProtoCodec.encode(task()), 1)
            queries.insertPendingTask(ID, command.operationId, payload)
            raw.execute(null, "DROP TABLE task_details", 0)
            raw.execute(null, "DROP TABLE task_query_pages", 0)
            raw.execute(null, "PRAGMA user_version = 3", 0)
            raw.close()

            val migrated = JdbcSqliteDriver("jdbc:sqlite:${file.path}")
            migrateJvmLocalCache(migrated)
            // 未标版本库认领可能重放新增对象；迁移不能改变可靠命令。
            AppDatabase.Schema.migrate(migrated, 3, AppDatabase.Schema.version)
            val cache = LocalCacheImpl(migrated)
            try {
                assertEquals(task(), cache.tasks.task(ID))
                assertNull(cache.tasks.details(ID))
                assertEquals(PendingTaskCommand(command, "unknown result"), cache.tasks.pending(ID))
                assertEquals(payload, AppDatabase(migrated).appDatabaseQueries.selectPendingTask(ID).executeAsOne())
                assertNull(cache.tasks.queryPage(KEY))
            } finally { cache.close() }
        }
    }

    @Test
    fun `full query counts and all details metadata survive reopen independently of page size`() = runBlocking {
        database { file ->
            val details = TaskDetails(task(), TaskOptions(TaskOptions.MARKDOWN, 10, documentRefs = listOf(
                OfficeRefBody(OfficeRefBody.REF_TYPE_DOCUMENT, id(40), id(41), "操作文档", "文档"),
            ), attachments = listOf(Attachment("docs/instructions.txt", "说明.txt", "text/plain", 12))),
                TaskMetrics(1_000, 2, 100, 10, null, true), startRemindedAt = 20,
                series = series(id(70)), occurrenceDate = "2026-09-14")
            val page = TaskQueryPage(listOf(details), null, TaskSummary(41, 40, 3, 1_000, 1, 500))
            cache(file, true) { cache ->
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(page)) }
                assertTrue(TaskRepository(rpc, cache.tasks, OWNER).queryRefresh(KEY).getOrThrow())
                assertEquals(TaskRpcContract.M_QUERY, rpc.calls.single().second)
                assertEquals(41L, cache.tasks.queryPage(KEY)?.summary?.totalCount)
                assertEquals(1, cache.tasks.queryPage(KEY)?.items?.size)
            }
            cache(file) { cache ->
                assertEquals(details, cache.tasks.details(ID))
                assertEquals(details.task, cache.tasks.task(ID))
                assertEquals(page, cache.tasks.queryPage(KEY))
            }
        }
    }

    @Test
    fun `legacy extended deferral and series commands replay exact original bytes from one queue`() = runBlocking {
        database { file ->
            lateinit var selected: List<PendingTaskCommand>
            lateinit var firstCalls: List<Triple<String, Int, ByteArray?>>
            cache(file, true) { cache ->
                val rpc = FakeRpcInvoker().apply { throwOnInvoke = AppError.Network }
                val repo = TaskRepository(rpc, cache.tasks, OWNER)
                repo.enqueue(TaskCommand(id(90), 100, id(1), 0, TaskCommand.CREATE, draft())).getOrThrow()
                repo.enqueue(TaskDetailsCommand(id(91), 100, id(2), 0, TaskDetailsCommand.CREATE, draft(),
                    TaskOptions(TaskOptions.MARKDOWN), weeklyRule = rule())).getOrThrow()
                repo.enqueue(TaskDetailsCommand(id(92), 100, id(3), 1, TaskDetailsCommand.EDIT, draft(), TaskOptions())).getOrThrow()
                repo.enqueue(TaskDetailsCommand(id(93), 100, id(4), 1, TaskDetailsCommand.DEFER, deferDueAt = 2_000, reason = "等待材料")).getOrThrow()
                repo.enqueue(TaskSeriesCommand(id(94), 100, id(5), 1, false)).getOrThrow()
                selected = cache.tasks.pending()
                assertEquals(5, selected.size)
                assertIs<Outcome.Failure>(repo.retryPending())
                assertEquals(selected, cache.tasks.pending())
                firstCalls = rpc.calls.toList()
            }
            cache(file) { cache ->
                assertEquals(selected, cache.tasks.pending())
                val rpc = FakeRpcInvoker()
                selected.forEach { record ->
                    when {
                        record.command != null -> rpc.enqueueOk(ProtoCodec.encode(TaskCommandResult(task(record.taskId))))
                        record.detailsCommand != null -> rpc.enqueueOk(ProtoCodec.encode(TaskDetailsCommandResult(TaskDetails(task(record.taskId)))))
                        else -> rpc.enqueueOk(ProtoCodec.encode(series(record.taskId).copy(enabled = false)))
                    }
                }
                TaskRepository(rpc, cache.tasks, OWNER).retryPending().getOrThrow()
                assertTrue(cache.tasks.pending().isEmpty())
                assertEquals(firstCalls.map { it.second }, rpc.calls.map { it.second })
                firstCalls.zip(rpc.calls).forEach { (first, replay) -> assertContentEquals(first.third, replay.third) }
                assertEquals(listOf(TaskRpcContract.M_MUTATE, TaskRpcContract.M_MODIFY, TaskRpcContract.M_MODIFY,
                    TaskRpcContract.M_MODIFY, TaskRpcContract.M_MODIFY_SERIES), rpc.calls.map { it.second })
            }
        }
    }

    @Test
    fun `an invalidated first query cannot publish partial or outdated totals`() = runBlocking {
        cache { cache ->
            val page = TaskQueryPage(listOf(TaskDetails(task())), null, TaskSummary(30, 30, 0, 1_000, 0, null))
            val rpc = object : RpcInvoker {
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                    cache.tasks.invalidate(TaskChangedPayload(ID, 2, TaskChangedPayload.UPDATED))
                    return ResponsePayload(1, 0, ProtoCodec.encode(page))
                }
            }
            assertFalse(TaskRepository(rpc, cache.tasks, OWNER).queryRefresh(KEY).getOrThrow())
            assertNull(cache.tasks.queryPage(KEY))
            assertTrue(cache.tasks.isQueryPageStale(KEY))
            assertNull(cache.tasks.details(ID))
        }
    }

    @Test
    fun `query scope mismatch is rejected before any metadata or count is published`() = runBlocking {
        cache { cache ->
            val escaped = TaskDetails(task().copy(assigneeUid = "someone-else"))
            val page = TaskQueryPage(listOf(escaped), null, TaskSummary(1, 1, 0, 1_000, 0, null))
            val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(page)) }
            assertIs<Outcome.Failure>(TaskRepository(rpc, cache.tasks, OWNER).queryRefresh(KEY))
            assertNull(cache.tasks.queryPage(KEY))
            assertNull(cache.tasks.task(ID))
            assertNull(cache.tasks.details(ID))
        }
    }

    @Test
    fun `deferral and series original commands survive reset and late ACK cannot restore metadata`() = runBlocking {
        cache { cache ->
            val local = cache.tasks
            val details = TaskDetails(task(), series = series(ID))
            local.applyDetails(details, local.generation(), OWNER)
            val command = TaskDetailsCommand(id(90), 100, ID, 1, TaskDetailsCommand.DEFER,
                deferDueAt = 2_000, reason = "等待材料")
            local.prepare(command)
            assertFailsWith<IllegalStateException> { local.prepare(command.copy(reason = "不同意图")) }
            assertFailsWith<IllegalStateException> { local.prepare(command.copy(deferDueAt = 3_000)) }
            val generation = local.generation()
            local.resetProjection()
            assertEquals(command, local.pending(ID)?.detailsCommand)
            local.acknowledge(command, TaskDetailsCommandResult(details.copy(task = task().copy(revision = 2))), generation, OWNER)
            assertNull(local.pending(ID))
            assertNull(local.task(ID))
            assertNull(local.details(ID))

            local.applyDetails(details, local.generation(), OWNER)
            val seriesCommand = TaskSeriesCommand(id(91), 100, ID, 1, false)
            local.prepare(seriesCommand)
            assertFailsWith<IllegalStateException> { local.prepare(seriesCommand.copy(enabled = true)) }
            val seriesGeneration = local.generation()
            local.resetProjection()
            assertEquals(seriesCommand, local.pending(ID)?.seriesCommand)
            local.acknowledge(seriesCommand, series(ID).copy(revision = 2, enabled = false), seriesGeneration)
            assertNull(local.pending(ID))
            assertNull(local.task(ID))
            assertNull(local.details(ID))
        }
    }

    @Test
    fun `membership events purge group-only reads but preserve participant tasks and original commands`() = runBlocking {
        cache { cache ->
            cache.bindSyncDataset(DATASET)
            val local = cache.tasks
            val private = TaskDetails(task(id(1)).copy(contextKind = TaskPolicy.CONTEXT_GROUP, contextId = GROUP))
            val shared = TaskDetails(task(id(2)).copy(creatorUid = "creator", assigneeUid = "assignee",
                contextKind = TaskPolicy.CONTEXT_GROUP, contextId = GROUP), TaskOptions(shareToGroup = true))
            local.applyDetails(private, local.generation(), OWNER)
            val key = TaskQueryKey(TaskQuery.GROUP, GROUP, openOnly = true)
            local.applyQueryPage(key, TaskQueryPage(listOf(shared), null, TaskSummary(1, 1, 0, 1_000, 0, null)), local.generation(), OWNER)
            val command = TaskDetailsCommand(id(90), 100, private.task.taskId, 1, TaskDetailsCommand.EDIT, draft(), TaskOptions())
            local.prepare(command)
            val generation = local.generation()

            EventProcessor(ImClient("127.0.0.1", 1), cache, ownerUid = OWNER).processNotify(
                NotifyPayload(1, NotifyType.MEMBER_REMOVED.code, ProtoCodec.encode(Chat(GROUP, 2))),
            )

            assertNull(local.queryPage(key))
            assertNull(local.details(shared.task.taskId))
            assertNull(local.task(shared.task.taskId))
            assertEquals(private, local.details(private.task.taskId))
            assertEquals(command, local.pending(private.task.taskId)?.detailsCommand)
            assertFalse(local.applyDetails(shared, generation, OWNER))
            assertEquals(1L, cache.getSyncState()?.cursor)
        }
    }

    @Test
    fun `repeated forbidden group query publishes only the first actual cache removal`() = runBlocking {
        cache { cache ->
            val local = cache.tasks
            val shared = TaskDetails(task().copy(creatorUid = "creator", assigneeUid = "assignee",
                contextKind = TaskPolicy.CONTEXT_GROUP, contextId = GROUP), TaskOptions(shareToGroup = true))
            val key = TaskQueryKey(TaskQuery.GROUP, GROUP)
            local.applyQueryPage(key, TaskQueryPage(listOf(shared), null, TaskSummary(1, 1, 0, 1_000, 0, null)), local.generation(), OWNER)
            val rpc = FakeRpcInvoker().apply { repeat(2) { enqueueError(403) } }
            val repository = TaskRepository(rpc, local, OWNER)
            val before = local.generation()
            assertIs<Outcome.Failure>(repository.queryRefresh(key))
            assertEquals(before + 1, local.generation())
            assertNull(local.queryPage(key))
            assertNull(local.details(ID))
            val changes = local.changes.value
            assertIs<Outcome.Failure>(repository.queryRefresh(key))
            assertEquals(before + 1, local.generation())
            assertEquals(changes, local.changes.value, "重复拒绝不能使 UI 自动刷新再次发起同一拒绝读取")
            assertEquals(2, rpc.calls.size)
        }
    }

    @Test
    fun `start reminder is durable before cursor and confirmed without altering the old due field`() = runBlocking {
        database { file ->
            cache(file, true) { cache ->
                cache.bindSyncDataset(DATASET)
                EventProcessor(ImClient("127.0.0.1", 1), cache, ownerUid = OWNER).processNotify(
                    NotifyPayload(1, NotifyType.TASK_STARTED.code, ProtoCodec.encode(TaskStartedPayload(ID, 1, 100))),
                )
                assertEquals(listOf(TaskReminder(ID, 1, 100)), cache.tasks.reminderHints())
                assertTrue(cache.tasks.reminders().isEmpty())
                assertEquals(1L, cache.getSyncState()?.cursor)
            }
            cache(file) { cache ->
                val confirmed = TaskDetails(task(), TaskOptions(startsAt = 100), startRemindedAt = 100)
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(confirmed)) }
                TaskRepository(rpc, cache.tasks, OWNER).retryPending().getOrThrow()
                assertNull(cache.tasks.task(ID)?.remindedAt, "开始提醒不伪造旧 wire 的截止提醒")
                assertEquals(listOf(TaskReminder(ID, 1, 100)), cache.tasks.reminders())
                cache.tasks.markReminderSeen(ID, 100)
                cache.tasks.markReminderNotified(ID, 100)
            }
            cache(file) { cache ->
                assertTrue(cache.tasks.reminders().single().seen && cache.tasks.reminders().single().notified)
                cache.tasks.due(TaskDuePayload(ID, 1, 1_000))
                assertTrue(cache.tasks.reminders().isEmpty())
                val due = TaskDetails(task().copy(remindedAt = 1_000), TaskOptions(startsAt = 100), startRemindedAt = 100)
                cache.tasks.applyDetails(due, cache.tasks.generation(), OWNER)
                assertEquals(listOf(TaskReminder(ID, 1, 1_000)), cache.tasks.reminders())
            }
        }
    }

    @Test
    fun `old server keeps single-task reads and commands without inventing query totals`() = runBlocking {
        cache { cache ->
            val responses = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(task())) }
            val rpc = object : RpcInvoker by responses {
                override val negotiatedProtocolVersion = ProtocolVersion(0, 2)
            }
            val repo = TaskRepository(rpc, cache.tasks, OWNER)
            assertFalse(repo.supportsTaskDetails)
            assertEquals(TaskDetails(task()), repo.getDetails(ID).getOrThrow())
            assertEquals(TaskRpcContract.M_GET, responses.calls.single().second)
            val unsupported = assertIs<Outcome.Failure>(repo.queryRefresh(KEY))
            assertEquals(426, assertIs<AppError.Business>(unsupported.error).code)
            assertNull(cache.tasks.queryPage(KEY))
            assertIs<Outcome.Failure>(repo.create(draft(), TaskOptions(TaskOptions.MARKDOWN)))
            val taskId = repo.create(draft()).getOrThrow()
            val command = requireNotNull(cache.tasks.pending(taskId)?.command)
            responses.enqueueOk(ProtoCodec.encode(TaskCommandResult(task(taskId))))
            repo.retryPending().getOrThrow()
            assertContentEquals(TaskRpcContract.encodeMutate(command), responses.calls.last().third)
            assertTrue(cache.tasks.pending().isEmpty())
        }
    }

    @Test
    fun `short disconnect retains confirmed capability and original offline command until reconnect`() = runBlocking {
        cache { cache ->
            var negotiated: ProtocolVersion? = null
            val responses = FakeRpcInvoker()
            val rpc = object : RpcInvoker by responses {
                override val negotiatedProtocolVersion: ProtocolVersion
                    get() = negotiated ?: throw TransportUnavailableException("offline")
            }
            val repo = TaskRepository(rpc, cache.tasks, OWNER)
            assertFalse(repo.supportsTaskDetails)
            negotiated = ProtocolVersion(0, 3)
            assertTrue(repo.supportsTaskDetails)
            negotiated = null
            assertTrue(repo.supportsTaskDetails)
            val taskId = repo.create(draft(), TaskOptions(TaskOptions.MARKDOWN)).getOrThrow()
            val original = requireNotNull(cache.tasks.pending(taskId)?.detailsCommand)
            negotiated = ProtocolVersion(0, 2)
            assertFalse(repo.supportsTaskDetails)
            assertIs<Outcome.Failure>(repo.retryPending())
            assertEquals(original, cache.tasks.pending(taskId)?.detailsCommand)
            assertNotNull(cache.tasks.pending(taskId)?.failure)
            assertTrue(responses.calls.isEmpty())
            negotiated = ProtocolVersion(0, 3)
            repo.retry(taskId)
            responses.enqueueOk(ProtoCodec.encode(TaskDetailsCommandResult(TaskDetails(task(taskId)))))
            repo.retryPending().getOrThrow()
            assertContentEquals(TaskRpcContract.encodeModify(original), responses.calls.single().third)
            assertNull(cache.tasks.pending(taskId))
        }
    }

    private fun id(value: Long) = UUID(0, value).toString()
    private fun draft() = TaskDraft("待办", "原始描述", OWNER, dueAt = 1_000)
    private fun task(taskId: String = ID) = WorkTask(taskId, OWNER, OWNER, "待办", "原始描述", TaskPolicy.TODO,
        TaskPolicy.CONTEXT_NONE, "", 1_000, null, 1, 1, 1)
    private fun rule() = TaskWeeklyRule(1, "09:00", "18:00", "Asia/Shanghai")
    private fun series(seriesId: String) = TaskSeries(seriesId, OWNER, 1, true, rule(), 2_000)
    private suspend fun database(block: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("teamtalk-task-details-").toFile()
        try { block(dir.resolve("client.db")) } finally { dir.deleteRecursively() }
    }
    private suspend fun cache(file: File? = null, create: Boolean = file == null, block: suspend (LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver(file?.let { "jdbc:sqlite:${it.path}" } ?: JdbcSqliteDriver.IN_MEMORY)
        if (create) AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try { block(cache) } finally { cache.close() }
    }
    private companion object {
        const val ID = "00000000-0000-0000-0000-000000000001"
        const val GROUP = "00000000-0000-0000-0000-000000000010"
        const val DATASET = "00000000-0000-4000-8000-000000000020"
        const val OWNER = "task-owner"
        val KEY = TaskQueryKey(TaskQuery.ASSIGNED)
    }
}

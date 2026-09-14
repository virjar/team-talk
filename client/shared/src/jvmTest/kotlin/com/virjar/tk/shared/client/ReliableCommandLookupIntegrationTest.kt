package com.virjar.tk.shared.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.TaskCommand
import com.virjar.tk.protocol.model.TaskCommandResult
import com.virjar.tk.protocol.model.TaskDraft
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.protocol.model.WorkTask
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.DocumentCommentRepository
import com.virjar.tk.shared.repository.TaskRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 无 UI 订阅时度量恢复内部读；正常 changes 通知和 UI 重新读取 pending 列表不计入这项约束。 */
class ReliableCommandLookupIntegrationTest {
    @Test
    fun `task recovery scans candidates once and sends each exact selected command`() = runBlocking {
        withCache { cache, driver ->
            val rpc = FakeRpcInvoker()
            val repository = TaskRepository(rpc, cache.tasks, OWNER)
            repeat(COMMANDS) { repository.create(TaskDraft("任务 $it", "保留完整正文 $it", OWNER)).getOrThrow() }
            assertEquals(0, driver.wholeQueueReads, "prepare only checks its task and the capacity count")
            val selected = cache.tasks.pending()
            selected.forEach { record ->
                val draft = requireNotNull(record.draft)
                val task = WorkTask(record.taskId, OWNER, OWNER, draft.title, draft.description,
                    TaskPolicy.TODO, TaskPolicy.CONTEXT_NONE, "", null, null, 1, 1, 1)
                rpc.enqueueOk(ProtoCodec.encode(TaskCommandResult(task)))
            }
            driver.wholeQueueReads = 0

            repository.retryPending().getOrThrow()

            assertEquals(1, driver.wholeQueueReads, "ACK and per-command admission must not decode the remaining queue")
            assertEquals(selected.map { it.command }, rpc.calls.map { ProtoCodec.decode(TaskCommand, requireNotNull(it.third)) })
            assertTrue(cache.tasks.pending().isEmpty())
        }
    }

    @Test
    fun `comment recovery scans candidates once while single-command changes retain original fields`() = runBlocking {
        withCache { cache, driver ->
            val rpc = FakeRpcInvoker()
            val repository = DocumentCommentRepository(rpc, cache.documentComments)
            repeat(COMMANDS) { repository.create("space", "document", "评论正文 $it").getOrThrow() }
            assertEquals(0, driver.wholeQueueReads, "prepare only checks its comment and the capacity count")
            val selected = cache.documentComments.pending()
            val first = selected.first()
            driver.wholeQueueReads = 0
            cache.documentComments.fail(first.commentId, "等待用户重试")
            assertEquals(first.copy(failure = "等待用户重试"), cache.documentComments.pending(first.commentId))
            repository.retry(first.commentId)
            assertEquals(first, cache.documentComments.pending(first.commentId))
            assertEquals(0, driver.wholeQueueReads, "failure and explicit retry must not scan sibling commands")
            selected.forEachIndexed { index, command ->
                rpc.enqueueOk(ProtoCodec.encode(DocumentComment(command.commentId, command.spaceId, command.documentId,
                    index + 1L, OWNER, "执行人", command.replyToId, command.body, 1, 1, 1, false)))
            }

            repository.retryPending().getOrThrow()

            assertEquals(1, driver.wholeQueueReads)
            assertEquals(COMMANDS, rpc.calls.size)
            assertTrue(cache.documentComments.pending().isEmpty())
        }
    }

    private suspend fun withCache(block: suspend (LocalCacheImpl, InspectingDriver) -> Unit) {
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(raw)
        val driver = InspectingDriver(raw)
        val cache = LocalCacheImpl(driver)
        try { block(cache, driver) } finally { cache.close() }
    }

    private class InspectingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var wholeQueueReads = 0
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            val normalized = sql.replace(Regex("\\s+"), " ")
            if (normalized.contains("SELECT payload FROM pending_task_commands ORDER BY") ||
                normalized.contains("SELECT payload FROM pending_document_comments ORDER BY")) {
                wholeQueueReads++
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private companion object {
        const val COMMANDS = 40
        const val OWNER = "command-owner"
    }
}

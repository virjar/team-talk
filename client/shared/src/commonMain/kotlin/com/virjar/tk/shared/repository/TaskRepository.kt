package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.ReliableCommandContract
import com.virjar.tk.protocol.model.*
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.RpcStatusException
import com.virjar.tk.protocol.rpc.gen.TaskRpcProxy
import com.virjar.tk.protocol.rpc.gen.TaskRpcContract
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.client.LocalTasks
import com.virjar.tk.shared.client.TaskPageKey
import com.virjar.tk.shared.client.TaskQueryKey
import com.virjar.tk.shared.client.TransportUnavailableException
import com.virjar.tk.shared.outcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 页面与会话恢复共用一个可靠命令 owner；入队成功不等于服务端已提交。 */
class TaskRepository(
    private val rpcClient: RpcInvoker,
    val local: LocalTasks,
    private val ownerUid: String,
    private val onPendingCommitted: () -> Unit = {},
) {
    private val rpc = TaskRpcProxy(rpcClient)
    // 读之间保持顺序；网络读取不占可靠命令通道，投影 generation 拒收 ACK 之前的旧页。
    private val readMutex = Mutex()
    private val commandMutex = Mutex()
    @Volatile private var lastKnownTaskDetailsSupport = false
    /** 短暂断线沿用本会话已确认的能力；尚未协商时不猜测服务端版本。 */
    val supportsTaskDetails: Boolean get() = try {
        TaskRpcContract.METHOD_VERSIONS.getValue(TaskRpcContract.M_DETAILS)
            .supports(rpcClient.negotiatedProtocolVersion).also { lastKnownTaskDetailsSupport = it }
    } catch (_: TransportUnavailableException) {
        lastKnownTaskDetailsSupport
    }

    suspend fun queryRefresh(key: TaskQueryKey): Outcome<Boolean> = outcome {
        requireTaskDetailsSupport()
        readMutex.withLock {
            val generation = local.generation()
            val page = try { rpc.query(key.query, key.cursor, TaskPolicy.DEFAULT_PAGE_SIZE) } catch (failure: Exception) {
                if (key.groupId != null && status(failure) in listOf(403, 404)) local.revokeGroup(key.groupId, ownerUid)
                throw failure
            }
            require(page.items.size <= TaskPolicy.DEFAULT_PAGE_SIZE && page.summary.totalCount >= page.items.size)
            require(page.nextCursor == null || page.nextCursor != key.cursor) { "任务分页游标未前进" }
            local.applyQueryPage(key, page, generation, ownerUid)
        }
    }

    suspend fun getDetails(taskId: String): Outcome<TaskDetails> {
        if (!supportsTaskDetails) return get(taskId).map { TaskDetails(it) }
        return outcome {
            TaskPolicy.requireId(taskId)
            readMutex.withLock {
                repeat(2) {
                    val generation = local.generation()
                    val details = try { rpc.details(taskId) } catch (failure: Exception) {
                        invalidateRejectedRead(taskId, failure)
                        throw failure
                    }
                    require(details.task.taskId == taskId)
                    if (local.applyDetails(details, generation, ownerUid)) return@withLock details
                }
                throw TaskProjectionInvalidatedException()
            }
        }
    }

    suspend fun history(taskId: String, cursor: String? = null, limit: Int = TaskPolicy.DEFAULT_PAGE_SIZE): Outcome<TaskHistoryPage> {
        if (!supportsTaskDetails) return audit(taskId, cursor, limit).map { page ->
            TaskHistoryPage(page.items.map { TaskHistoryEntry(it) }, page.nextCursor)
        }
        return outcome {
            TaskPolicy.requireId(taskId); TaskPolicy.requireCursor(cursor); require(limit in 1..TaskPolicy.MAX_PAGE_SIZE)
            readMutex.withLock {
                val generation = local.generation()
                val page = try { rpc.history(taskId, cursor, limit) } catch (failure: Exception) {
                    invalidateRejectedRead(taskId, failure)
                    throw failure
                }
                require(page.items.size <= limit && page.items.all { entry ->
                    val deferral = entry.deferral
                    entry.audit.taskId == taskId && (deferral == null ||
                        (deferral.taskId == taskId && deferral.revision == entry.audit.revision))
                })
                require(page.nextCursor == null || page.nextCursor != cursor)
                if (generation != local.generation()) throw TaskProjectionInvalidatedException()
                page
            }
        }
    }

    suspend fun refresh(key: TaskPageKey): Outcome<Boolean> = outcome {
        readMutex.withLock {
            val generation = local.generation()
            val page = rpc.list(key.view, key.cursor, TaskPolicy.DEFAULT_PAGE_SIZE)
            require(page.items.size <= TaskPolicy.DEFAULT_PAGE_SIZE)
            require(page.nextCursor == null || page.nextCursor != key.cursor) { "任务分页游标未前进" }
            local.applyPage(key, page, generation, ownerUid)
        }
    }

    suspend fun get(taskId: String): Outcome<WorkTask> = outcome {
        TaskPolicy.requireId(taskId)
        readMutex.withLock {
            repeat(2) {
                val generation = local.generation()
                val task = try { rpc.get(taskId) } catch (failure: Exception) {
                    invalidateRejectedRead(taskId, failure)
                    throw failure
                }
                require(task.taskId == taskId)
                if (local.applyTask(task, generation, ownerUid)) return@withLock task
            }
            throw TaskProjectionInvalidatedException()
        }
    }

    suspend fun audit(taskId: String, cursor: String? = null, limit: Int = TaskPolicy.DEFAULT_PAGE_SIZE): Outcome<TaskAuditPage> = outcome {
        TaskPolicy.requireId(taskId); TaskPolicy.requireCursor(cursor); require(limit in 1..TaskPolicy.MAX_PAGE_SIZE)
        readMutex.withLock {
            val generation = local.generation()
            val page = try { rpc.audit(taskId, cursor, limit) } catch (failure: Exception) {
                invalidateRejectedRead(taskId, failure)
                throw failure
            }
            require(page.items.size <= limit && page.items.all { it.taskId == taskId })
            require(page.nextCursor == null || page.nextCursor != cursor)
            if (generation != local.generation()) throw TaskProjectionInvalidatedException()
            page
        }
    }

    suspend fun create(draft: TaskDraft): Outcome<String> = enqueue(TaskCommand(
        id(), System.currentTimeMillis(), id(), 0L, TaskCommand.CREATE, draft,
    ))
    suspend fun edit(task: WorkTask, draft: TaskDraft): Outcome<String> = enqueue(TaskCommand(
        id(), System.currentTimeMillis(), task.taskId, task.revision, TaskCommand.EDIT, draft,
    ))
    suspend fun setStatus(task: WorkTask, status: Int): Outcome<String> = enqueue(TaskCommand(
        id(), System.currentTimeMillis(), task.taskId, task.revision, TaskCommand.STATUS, status = status,
    ))
    suspend fun create(draft: TaskDraft, options: TaskOptions, weeklyRule: TaskWeeklyRule? = null): Outcome<String> = enqueue(
        TaskDetailsCommand(id(), System.currentTimeMillis(), id(), 0L, TaskDetailsCommand.CREATE, draft, options, weeklyRule = weeklyRule),
    )
    suspend fun edit(details: TaskDetails, draft: TaskDraft, options: TaskOptions): Outcome<String> = enqueue(
        TaskDetailsCommand(id(), System.currentTimeMillis(), details.task.taskId, details.task.revision, TaskDetailsCommand.EDIT, draft, options),
    )
    suspend fun defer(details: TaskDetails, newDueAt: Long, reason: String): Outcome<String> = enqueue(
        TaskDetailsCommand(id(), System.currentTimeMillis(), details.task.taskId, details.task.revision,
            TaskDetailsCommand.DEFER, deferDueAt = newDueAt, reason = reason),
    )
    suspend fun setSeriesEnabled(series: TaskSeries, enabled: Boolean): Outcome<String> = enqueue(
        TaskSeriesCommand(id(), System.currentTimeMillis(), series.seriesId, series.revision, enabled),
    )
    /** 无头调用者可持有原 command，响应未知时复用全部字段。 */
    suspend fun enqueue(command: TaskCommand): Outcome<String> = outcome {
        local.prepare(command)
        onPendingCommitted()
        command.taskId
    }
    suspend fun enqueue(command: TaskDetailsCommand): Outcome<String> = outcome {
        requireTaskDetailsSupport()
        local.prepare(command)
        onPendingCommitted()
        command.taskId
    }
    suspend fun enqueue(command: TaskSeriesCommand): Outcome<String> = outcome {
        requireTaskDetailsSupport()
        local.prepare(command)
        onPendingCommitted()
        command.seriesId
    }
    fun retry(taskId: String) { local.retry(taskId); onPendingCommitted() }
    suspend fun discardRejected(taskId: String) = commandMutex.withLock { local.discard(taskId) }

    internal fun nextExpiryAt(): Long? = local.pending().filter { it.failure == null }
        .minOfOrNull { ReliableCommandContract.firstExpiredAt(it.issuedAt) }

    internal suspend fun retryPending(): Outcome<Unit> = retryIndependentPendingFamilies(
        ::retryCommands, ::refreshReminderHints,
    )
    private suspend fun retryCommands(): Outcome<Unit> = retryPendingMirrors(local.pending().filter { it.failure == null }) { record ->
        outcome {
            commandMutex.withLock {
                if (local.pending(record.taskId) != record) return@withLock
                val generation = local.generation()
                try {
                    val legacy = record.command
                    val details = record.detailsCommand
                    val series = record.seriesCommand
                    when {
                        legacy != null -> local.acknowledge(legacy, rpc.mutate(legacy), generation, ownerUid)
                        details != null -> {
                            requireTaskDetailsSupport()
                            local.acknowledge(details, rpc.modify(details), generation, ownerUid)
                        }
                        series != null -> {
                            requireTaskDetailsSupport()
                            local.acknowledge(series, rpc.modifySeries(series), generation)
                        }
                        else -> error("Pending task command has no original intent")
                    }
                } catch (failure: Exception) {
                    if (status(failure) == 403 || failure.isDefinitiveReliableCommandRejection()) {
                        val reason = when (status(failure)) {
                            403 -> "任务操作未获授权，原意图保留在本机"
                            404 -> "任务或关联对象已不存在，原意图保留在本机"
                            409 -> "任务已变化，请查看当前内容后重新操作"
                            410 -> "任务操作已超过可靠重试期限，请核对后重新操作"
                            else -> "任务操作未获接受，请检查内容后重试"
                        }
                        local.fail(record.taskId, reason)
                        if (status(failure) == 403) local.revoke(record.taskId)
                    }
                    throw failure
                }
            }
        }
    }

    private suspend fun refreshReminderHints(): Outcome<Unit> = retryPendingMirrors(local.reminderHints()) { hint ->
        when (val result = getDetails(hint.taskId)) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> when {
                status(result.error) in listOf(403, 404) -> Outcome.Success(Unit)
                (result.error as? AppError.Unknown)?.cause is TaskProjectionInvalidatedException ->
                    Outcome.Failure(AppError.Business(503, "任务视图正在变化，稍后重新核对提醒"))
                else -> result
            }
        }
    }

    private fun invalidateRejectedRead(taskId: String, failure: Exception) {
        if (status(failure) in listOf(403, 404)) local.revoke(taskId)
    }
    private fun requireTaskDetailsSupport() {
        if (!supportsTaskDetails) throw AppError.Business(426, "服务端需要升级后才能使用扩展待办功能")
    }
    private fun status(failure: Throwable): Int? = when (failure) {
        is RpcStatusException -> failure.status
        is AppError.Business -> failure.code
        else -> null
    }
    private fun id() = UUID.randomUUID().toString()
}

/** 数据变化是一次读取失败，不是持久恢复 worker 的协程取消。 */
private class TaskProjectionInvalidatedException : IllegalStateException("任务在读取期间已变化，请重试")

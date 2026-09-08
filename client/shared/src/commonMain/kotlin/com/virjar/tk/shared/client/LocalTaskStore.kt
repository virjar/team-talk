package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.TaskDuePayload
import com.virjar.tk.protocol.model.*
import com.virjar.tk.shared.database.AppDatabaseQueries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class LocalTaskStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) : LocalTasks {
    override val changes = MutableStateFlow(0L)
    private var currentGeneration = 0L
    private fun <T> locked(block: () -> T): T = cacheUseGate.use { synchronized(stateLock) { block() } }
    private fun changed() { changes.value += 1 }
    override fun generation(): Long = locked { currentGeneration }
    override fun task(taskId: String): WorkTask? = locked { taskLocked(taskId) }
    private fun taskLocked(taskId: String): WorkTask? = queries.selectTask(taskId).executeAsOneOrNull()?.let { ProtoCodec.decode(WorkTask, it) }
    override fun page(key: TaskPageKey): TaskPage? = locked {
        queries.selectTaskPage(key.view.toLong(), key.cursor.orEmpty()).executeAsOneOrNull()?.let { ProtoCodec.decode(TaskPage, it.payload) }
    }
    override fun isPageStale(key: TaskPageKey): Boolean = locked {
        queries.selectTaskPage(key.view.toLong(), key.cursor.orEmpty()).executeAsOneOrNull()?.stale != 0L
    }

    override fun applyTask(task: WorkTask, generation: Long, ownerUid: String): Boolean = cacheUseGate.runIfOpen { synchronized(stateLock) {
        if (generation != currentGeneration) return@synchronized false
        queries.transaction { applyTaskLocked(task, ownerUid) }
        changed()
        true
    } }

    override fun applyPage(key: TaskPageKey, page: TaskPage, generation: Long, ownerUid: String): Boolean = cacheUseGate.runIfOpen { synchronized(stateLock) {
        require(page.items.all { if (key.view == TaskPolicy.VIEW_ASSIGNED) it.assigneeUid == ownerUid else it.creatorUid == ownerUid })
        if (generation != currentGeneration) return@synchronized false
        queries.transaction {
            page.items.forEach { applyTaskLocked(it, ownerUid) }
            queries.upsertTaskPage(key.view.toLong(), key.cursor.orEmpty(), ProtoCodec.encode(page), System.currentTimeMillis())
            queries.pruneTaskPages()
        }
        changed()
        true
    } }

    private fun applyTaskLocked(task: WorkTask, ownerUid: String) {
        require(task.creatorUid == ownerUid || task.assigneeUid == ownerUid) { "Task escaped its account" }
        if ((taskLocked(task.taskId)?.revision ?: 0L) > task.revision) return
        queries.upsertTask(task.taskId, ProtoCodec.encode(task), System.currentTimeMillis())
        queries.pruneTasks()
        val previous = queries.selectTaskReminder(task.taskId).executeAsOneOrNull()
        if (previous != null && previous.revision > task.revision) return
        val activeReminder = task.assigneeUid == ownerUid && task.status in TaskPolicy.TODO..TaskPolicy.IN_PROGRESS && task.remindedAt != null
        if (activeReminder) {
            val same = previous?.reminded_at == task.remindedAt
            queries.upsertTaskReminder(task.taskId, task.revision, requireNotNull(task.remindedAt),
                if (same) previous!!.seen else 0L, if (same) previous!!.notified else 0L, 1L)
            queries.pruneTaskReminders()
        } else queries.deleteTaskReminder(task.taskId)
    }

    override fun invalidate(change: TaskChangedPayload?) = locked {
        currentGeneration += 1
        queries.transaction {
            queries.staleTaskPages()
            when {
                change == null -> queries.unconfirmAllTaskReminders()
                change.kind == TaskChangedPayload.REVOKED -> revokeLocked(change.taskId)
                else -> queries.unconfirmTaskReminder(change.taskId)
            }
        }
        changed()
    }

    override fun revoke(taskId: String) = locked {
        var removed = false
        queries.transaction { removed = revokeLocked(taskId) }
        // A repeated denied read is not a new invalidation; otherwise observers retry forever.
        if (removed) { currentGeneration += 1; changed() }
    }
    private fun revokeLocked(taskId: String): Boolean {
        val pages = queries.selectTaskPages().executeAsList().filter { row ->
            ProtoCodec.decode(TaskPage, row.payload).items.any { it.taskId == taskId }
        }
        val removed = taskLocked(taskId) != null || queries.selectTaskReminder(taskId).executeAsOneOrNull() != null || pages.isNotEmpty()
        queries.deleteTask(taskId)
        pages.forEach { queries.deleteTaskPage(it.view, it.cursor) }
        queries.deleteTaskReminder(taskId)
        return removed
    }

    override fun pending(): List<PendingTaskCommand> = locked { pendingLocked() }
    private fun pendingLocked(): List<PendingTaskCommand> = queries.selectPendingTasks().executeAsList()
        .map { Json.decodeFromString<PendingTaskCommand>(it) }
        .also { check(it.size <= MAX_PENDING) { "本地任务队列超出上限，请保留资料并检查数据库" } }
    override fun prepare(command: TaskCommand): PendingTaskCommand = locked {
        val pending = pendingLocked()
        pending.firstOrNull { it.command.taskId == command.taskId }?.let {
            check(it.command == command) { "此任务还有一项操作等待确认" }
            return@locked it
        }
        check(pending.size < MAX_PENDING) { "待确认任务操作数量已达上限" }
        val record = PendingTaskCommand(command)
        queries.insertPendingTask(command.taskId, command.operationId, Json.encodeToString(record))
        changed()
        record
    }
    override fun fail(taskId: String, reason: String) = updatePending(taskId) { it.copy(failure = reason.take(200)) }
    override fun retry(taskId: String) = updatePending(taskId) { it.copy(failure = null) }
    private fun updatePending(taskId: String, transform: (PendingTaskCommand) -> PendingTaskCommand) = locked {
        pendingLocked().firstOrNull { it.command.taskId == taskId }?.let {
            queries.updatePendingTask(Json.encodeToString(transform(it)), taskId)
            changed()
        }
        Unit
    }
    override fun discard(taskId: String) = locked {
        pendingLocked().firstOrNull { it.command.taskId == taskId }?.let {
            check(it.failure != null) { "任务操作尚未确认，不能丢弃未知结果" }
            queries.deletePendingTask(taskId, it.command.operationId)
            changed()
        }
        Unit
    }
    override fun acknowledge(command: TaskCommand, result: TaskCommandResult, generation: Long, ownerUid: String) {
        cacheUseGate.runIfOpen { synchronized(stateLock) {
            val confirmedTask = result.task
            require(confirmedTask == null || confirmedTask.taskId == command.taskId)
            if (pendingLocked().none { it.command == command }) return@synchronized false
            queries.transaction {
                if (confirmedTask == null) revokeLocked(command.taskId)
                else if (generation == currentGeneration) applyTaskLocked(confirmedTask, ownerUid)
                queries.deletePendingTask(command.taskId, command.operationId)
                queries.staleTaskPages()
            }
            currentGeneration += 1
            changed()
            true
        } }
    }

    override fun due(change: TaskDuePayload) = locked {
        if ((taskLocked(change.taskId)?.revision ?: 0L) > change.revision) return@locked
        val old = queries.selectTaskReminder(change.taskId).executeAsOneOrNull()
        if (old != null && (old.revision > change.revision || old.reminded_at > change.remindedAt)) return@locked
        val same = old?.reminded_at == change.remindedAt
        queries.transaction {
            queries.upsertTaskReminder(change.taskId, change.revision, change.remindedAt,
                if (same) old!!.seen else 0L, if (same) old!!.notified else 0L, 0L)
            queries.pruneTaskReminders()
            queries.staleTaskPages()
        }
        currentGeneration += 1
        changed()
    }
    override fun reminderHints(): List<TaskDuePayload> = locked {
        queries.selectTaskReminders().executeAsList().filter { it.confirmed == 0L }
            .map { TaskDuePayload(it.task_id, it.revision, it.reminded_at) }
    }
    override fun reminders(): List<TaskReminder> = locked {
        queries.selectTaskReminders().executeAsList().filter { it.confirmed != 0L }
            .map { TaskReminder(it.task_id, it.revision, it.reminded_at, it.seen != 0L, it.notified != 0L) }
    }
    override fun markReminderSeen(taskId: String, remindedAt: Long) = locked {
        queries.markTaskReminderSeen(taskId, remindedAt); changed()
    }
    override fun markReminderNotified(taskId: String, remindedAt: Long) = locked {
        queries.markTaskReminderNotified(taskId, remindedAt); changed()
    }
    override fun resetProjection() = locked {
        queries.transaction { queries.deleteAllTasks(); queries.deleteAllTaskPages(); queries.unconfirmAllTaskReminders() }
        currentGeneration += 1
        changed()
    }
    companion object { const val MAX_PENDING = 256 }
}

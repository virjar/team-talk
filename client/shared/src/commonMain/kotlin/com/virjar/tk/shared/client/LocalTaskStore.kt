package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.TaskDuePayload
import com.virjar.tk.protocol.TaskStartedPayload
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
    override fun details(taskId: String): TaskDetails? = locked { detailsLocked(taskId) }
    private fun detailsLocked(taskId: String): TaskDetails? {
        val task = taskLocked(taskId) ?: return null
        val row = queries.selectTaskDetails(taskId).executeAsOneOrNull() ?: return null
        if (row.revision != task.revision) return null
        return TaskDetails(task, ProtoCodec.decode(TaskOptions, row.options_payload),
            ProtoCodec.decode(TaskMetrics, row.metrics_payload), row.start_reminded_at,
            row.series_payload?.let { ProtoCodec.decode(TaskSeries, it) }, row.occurrence_date)
    }
    override fun queryPage(key: TaskQueryKey): TaskQueryPage? = locked {
        queries.selectTaskQueryPage(key.storageKey).executeAsOneOrNull()?.let { ProtoCodec.decode(TaskQueryPage, it.payload) }
    }
    override fun isQueryPageStale(key: TaskQueryKey): Boolean = locked {
        queries.selectTaskQueryPage(key.storageKey).executeAsOneOrNull()?.stale != 0L
    }

    override fun applyDetails(details: TaskDetails, generation: Long, ownerUid: String): Boolean = cacheUseGate.runIfOpen { synchronized(stateLock) {
        if (generation != currentGeneration) return@synchronized false
        queries.transaction { applyTaskLocked(details.task, ownerUid, details) }
        changed()
        true
    } }

    override fun applyQueryPage(key: TaskQueryKey, page: TaskQueryPage, generation: Long, ownerUid: String): Boolean = cacheUseGate.runIfOpen { synchronized(stateLock) {
        require(page.items.map { it.task.taskId }.distinct().size == page.items.size)
        require(page.items.all { details -> when (key.scope) {
            TaskQuery.ASSIGNED -> details.task.assigneeUid == ownerUid
            TaskQuery.CREATED -> details.task.creatorUid == ownerUid
            TaskQuery.GROUP -> details.options.shareToGroup && details.task.contextKind == TaskPolicy.CONTEXT_GROUP && details.task.contextId == key.groupId
            else -> false
        } }) { "Task query escaped its requested scope" }
        if (generation != currentGeneration) return@synchronized false
        queries.transaction {
            page.items.forEach { applyTaskLocked(it.task, ownerUid, it) }
            queries.upsertTaskQueryPage(key.storageKey, key.groupId, ProtoCodec.encode(page), System.currentTimeMillis())
            queries.pruneTaskQueryPages()
        }
        changed()
        true
    } }

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

    private fun applyTaskLocked(task: WorkTask, ownerUid: String, details: TaskDetails? = null) {
        require(task.creatorUid == ownerUid || task.assigneeUid == ownerUid ||
            (details?.options?.shareToGroup == true && task.contextKind == TaskPolicy.CONTEXT_GROUP)) { "Task escaped its account" }
        if ((taskLocked(task.taskId)?.revision ?: 0L) > task.revision) return
        val full = details ?: detailsLocked(task.taskId)?.takeIf { it.task.revision == task.revision }
        queries.upsertTask(task.taskId, ProtoCodec.encode(task), System.currentTimeMillis())
        if (full == null) queries.deleteTaskDetails(task.taskId) else writeDetailsLocked(full)
        queries.pruneTasks()
        queries.pruneTaskDetails()
        val previous = queries.selectTaskReminder(task.taskId).executeAsOneOrNull()
        if (previous != null && previous.revision > task.revision) return
        val remindedAt = listOfNotNull(task.remindedAt, full?.startRemindedAt).maxOrNull()
        val activeReminder = task.assigneeUid == ownerUid && task.status in TaskPolicy.TODO..TaskPolicy.IN_PROGRESS && remindedAt != null
        if (activeReminder) {
            val same = previous?.reminded_at == remindedAt
            queries.upsertTaskReminder(task.taskId, task.revision, requireNotNull(remindedAt),
                if (same) previous!!.seen else 0L, if (same) previous!!.notified else 0L, 1L)
            queries.pruneTaskReminders()
        } else queries.deleteTaskReminder(task.taskId)
    }

    private fun writeDetailsLocked(details: TaskDetails) {
        queries.upsertTaskDetails(details.task.taskId, details.task.revision, ProtoCodec.encode(details.options),
            ProtoCodec.encode(details.metrics), details.startRemindedAt, details.series?.let(ProtoCodec::encode), details.occurrenceDate)
    }

    override fun invalidate(change: TaskChangedPayload?) = locked {
        currentGeneration += 1
        queries.transaction {
            queries.staleTaskPages()
            queries.staleTaskQueryPages()
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
        val queryPages = queries.selectTaskQueryPages().executeAsList().filter { row ->
            ProtoCodec.decode(TaskQueryPage, row.payload).items.any { it.task.taskId == taskId }
        }
        val removed = taskLocked(taskId) != null || queries.selectTaskReminder(taskId).executeAsOneOrNull() != null || pages.isNotEmpty() || queryPages.isNotEmpty()
        queries.deleteTask(taskId)
        queries.deleteTaskDetails(taskId)
        pages.forEach { queries.deleteTaskPage(it.view, it.cursor) }
        queryPages.forEach { queries.deleteTaskQueryPage(it.query_key) }
        queries.deleteTaskReminder(taskId)
        return removed
    }

    override fun invalidateGroup(groupId: String, ownerUid: String) = locked {
        queries.transaction {
            revokeGroupLocked(groupId, ownerUid)
            queries.staleTaskQueryPages()
        }
        currentGeneration += 1
        changed()
    }

    override fun revokeGroup(groupId: String, ownerUid: String) = locked {
        var removed = false
        queries.transaction {
            removed = revokeGroupLocked(groupId, ownerUid)
            if (removed) queries.staleTaskQueryPages()
        }
        if (removed) { currentGeneration += 1; changed() }
    }

    private fun revokeGroupLocked(groupId: String, ownerUid: String): Boolean {
        val groupTasks = queries.selectTasks().executeAsList().map { ProtoCodec.decode(WorkTask, it) }
            .filter { it.contextKind == TaskPolicy.CONTEXT_GROUP && it.contextId == groupId && it.creatorUid != ownerUid && it.assigneeUid != ownerUid }
        val removed = groupTasks.isNotEmpty() || queries.selectTaskQueryPages().executeAsList().any { it.group_id == groupId }
        groupTasks.forEach { revokeLocked(it.taskId) }
        queries.deleteTaskGroupQueryPages(groupId)
        return removed
    }

    override fun pending(): List<PendingTaskCommand> = locked { pendingLocked() }
    private fun pendingLocked(): List<PendingTaskCommand> = queries.selectPendingTasks().executeAsList()
        .map { Json.decodeFromString<PendingTaskCommand>(it) }
        .also { check(it.size <= MAX_PENDING) { "本地任务队列超出上限，请保留资料并检查数据库" } }
    override fun pending(taskId: String): PendingTaskCommand? = locked { pendingLocked(taskId) }
    private fun pendingLocked(taskId: String): PendingTaskCommand? =
        queries.selectPendingTask(taskId).executeAsOneOrNull()?.let { Json.decodeFromString<PendingTaskCommand>(it) }
    override fun prepare(command: TaskCommand): PendingTaskCommand = prepareRecord(PendingTaskCommand(command))
    override fun prepare(command: TaskDetailsCommand): PendingTaskCommand = prepareRecord(PendingTaskCommand(detailsCommand = command))
    override fun prepare(command: TaskSeriesCommand): PendingTaskCommand = prepareRecord(PendingTaskCommand(seriesCommand = command))
    private fun prepareRecord(record: PendingTaskCommand): PendingTaskCommand = locked {
        pendingLocked(record.taskId)?.let {
            check(it.copy(failure = null) == record) { "此任务还有一项操作等待确认" }
            return@locked it
        }
        check(queries.countPendingTasks().executeAsOne() < MAX_PENDING) { "待确认任务操作数量已达上限" }
        queries.insertPendingTask(record.taskId, record.operationId, Json.encodeToString(record))
        changed()
        record
    }
    override fun fail(taskId: String, reason: String) = updatePending(taskId) { it.copy(failure = reason.take(200)) }
    override fun retry(taskId: String) = updatePending(taskId) { it.copy(failure = null) }
    private fun updatePending(taskId: String, transform: (PendingTaskCommand) -> PendingTaskCommand) = locked {
        pendingLocked(taskId)?.let {
            queries.updatePendingTask(Json.encodeToString(transform(it)), taskId)
            changed()
        }
        Unit
    }
    override fun discard(taskId: String) = locked {
        pendingLocked(taskId)?.let {
            check(it.failure != null) { "任务操作尚未确认，不能丢弃未知结果" }
            queries.deletePendingTask(taskId, it.operationId)
            changed()
        }
        Unit
    }
    override fun acknowledge(command: TaskCommand, result: TaskCommandResult, generation: Long, ownerUid: String) {
        cacheUseGate.runIfOpen { synchronized(stateLock) {
            val confirmedTask = result.task
            require(confirmedTask == null || confirmedTask.taskId == command.taskId)
            if (pendingLocked(command.taskId)?.command != command) return@synchronized false
            queries.transaction {
                if (confirmedTask == null) revokeLocked(command.taskId)
                else if (generation == currentGeneration) applyTaskLocked(confirmedTask, ownerUid)
                queries.deletePendingTask(command.taskId, command.operationId)
                queries.staleTaskPages()
                queries.staleTaskQueryPages()
            }
            currentGeneration += 1
            changed()
            true
        } }
    }

    override fun acknowledge(command: TaskDetailsCommand, result: TaskDetailsCommandResult, generation: Long, ownerUid: String) {
        cacheUseGate.runIfOpen { synchronized(stateLock) {
            val confirmed = result.task
            require(confirmed == null || confirmed.task.taskId == command.taskId)
            if (pendingLocked(command.taskId)?.detailsCommand != command) return@synchronized false
            queries.transaction {
                val details = confirmed
                if (details == null) revokeLocked(command.taskId)
                else if (generation == currentGeneration) applyTaskLocked(details.task, ownerUid, details)
                queries.deletePendingTask(command.taskId, command.operationId)
                queries.staleTaskPages()
                queries.staleTaskQueryPages()
            }
            currentGeneration += 1
            changed()
            true
        } }
    }

    override fun acknowledge(command: TaskSeriesCommand, result: TaskSeries, generation: Long) {
        cacheUseGate.runIfOpen { synchronized(stateLock) {
            require(result.seriesId == command.seriesId)
            if (pendingLocked(command.seriesId)?.seriesCommand != command) return@synchronized false
            queries.transaction {
                if (generation == currentGeneration) {
                    queries.selectTasks().executeAsList().map { ProtoCodec.decode(WorkTask, it) }
                        .mapNotNull { detailsLocked(it.taskId) }
                        .filter { it.series?.seriesId == command.seriesId }
                        .forEach { writeDetailsLocked(it.copy(series = result)) }
                }
                queries.deletePendingTask(command.seriesId, command.operationId)
                queries.staleTaskQueryPages()
            }
            currentGeneration += 1
            changed()
            true
        } }
    }

    override fun due(change: TaskDuePayload) = recordReminder(change.taskId, change.revision, change.remindedAt)
    override fun started(change: TaskStartedPayload) = recordReminder(change.taskId, change.revision, change.remindedAt)
    private fun recordReminder(taskId: String, revision: Long, remindedAt: Long) = locked {
        if ((taskLocked(taskId)?.revision ?: 0L) > revision) return@locked
        val old = queries.selectTaskReminder(taskId).executeAsOneOrNull()
        if (old != null && (old.revision > revision || old.reminded_at > remindedAt)) return@locked
        val same = old?.reminded_at == remindedAt
        queries.transaction {
            queries.upsertTaskReminder(taskId, revision, remindedAt,
                if (same) old!!.seen else 0L, if (same) old!!.notified else 0L, 0L)
            queries.pruneTaskReminders()
            queries.staleTaskPages()
            queries.staleTaskQueryPages()
        }
        currentGeneration += 1
        changed()
    }
    override fun reminderHints(): List<TaskReminder> = locked {
        queries.selectTaskReminders().executeAsList().filter { it.confirmed == 0L }
            .map { TaskReminder(it.task_id, it.revision, it.reminded_at) }
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
        queries.transaction {
            queries.deleteAllTasks(); queries.deleteAllTaskDetails(); queries.deleteAllTaskPages()
            queries.deleteAllTaskQueryPages(); queries.unconfirmAllTaskReminders()
        }
        currentGeneration += 1
        changed()
    }
    companion object { const val MAX_PENDING = 256 }
}

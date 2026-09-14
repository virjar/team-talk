package com.virjar.tk.shared.testkit

import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.TaskDuePayload
import com.virjar.tk.protocol.TaskStartedPayload
import com.virjar.tk.protocol.model.*
import com.virjar.tk.shared.client.*
import kotlinx.coroutines.flow.MutableStateFlow

/** E2E 的内存投影适配器；持久化与故障恢复测试使用真实 SQLite。 */
internal class FakeTasks : LocalTasks {
    override val changes = MutableStateFlow(0L)
    private var generation = 0L
    private val tasks = linkedMapOf<String, WorkTask>()
    private val details = linkedMapOf<String, TaskDetails>()
    private val queryPages = linkedMapOf<TaskQueryKey, TaskQueryPage>()
    private val staleQueries = mutableSetOf<TaskQueryKey>()
    private val pages = linkedMapOf<TaskPageKey, TaskPage>()
    private val stale = mutableSetOf<TaskPageKey>()
    private val pending = linkedMapOf<String, PendingTaskCommand>()
    private val reminders = linkedMapOf<String, TaskReminder>()
    private val hints = linkedMapOf<String, TaskReminder>()
    override fun generation() = generation
    override fun task(taskId: String) = tasks[taskId]
    override fun details(taskId: String) = details[taskId]
    override fun queryPage(key: TaskQueryKey) = queryPages[key]
    override fun isQueryPageStale(key: TaskQueryKey) = key !in queryPages || key in staleQueries
    override fun page(key: TaskPageKey) = pages[key]
    override fun isPageStale(key: TaskPageKey) = key !in pages || key in stale
    override fun applyTask(task: WorkTask, generation: Long, ownerUid: String): Boolean {
        if (generation != this.generation) return false
        if ((tasks[task.taskId]?.revision ?: 0) > task.revision) return true
        if (details[task.taskId]?.task?.revision != task.revision) details.remove(task.taskId)
        else details[task.taskId]?.let { details[task.taskId] = it.copy(task = task) }
        tasks[task.taskId] = task
        val remindedAt = listOfNotNull(task.remindedAt, details[task.taskId]?.startRemindedAt).maxOrNull()
        if (task.assigneeUid == ownerUid && task.status in TaskPolicy.TODO..TaskPolicy.IN_PROGRESS && remindedAt != null) {
            val previous = reminders[task.taskId]
            reminders[task.taskId] = if (previous != null && previous.remindedAt == remindedAt) previous.copy(revision = task.revision)
                else TaskReminder(task.taskId, task.revision, remindedAt)
        } else reminders.remove(task.taskId)
        hints.remove(task.taskId)
        changes.value++
        return true
    }
    override fun applyDetails(details: TaskDetails, generation: Long, ownerUid: String): Boolean {
        if (generation != this.generation) return false
        if ((tasks[details.task.taskId]?.revision ?: 0) > details.task.revision) return true
        this.details[details.task.taskId] = details
        return applyTask(details.task, generation, ownerUid)
    }
    override fun applyQueryPage(key: TaskQueryKey, page: TaskQueryPage, generation: Long, ownerUid: String): Boolean {
        if (generation != this.generation) return false
        page.items.forEach { applyDetails(it, generation, ownerUid) }
        queryPages[key] = page; staleQueries.remove(key); changes.value++
        return true
    }
    override fun applyPage(key: TaskPageKey, page: TaskPage, generation: Long, ownerUid: String): Boolean {
        if (generation != this.generation) return false
        page.items.forEach { applyTask(it, generation, ownerUid) }
        pages[key] = page; stale.remove(key); changes.value++
        return true
    }
    override fun invalidate(change: TaskChangedPayload?) {
        generation++; stale.addAll(pages.keys); staleQueries.addAll(queryPages.keys)
        if (change?.kind == TaskChangedPayload.REVOKED) revoke(change.taskId)
        else reminders.values.filter { change == null || it.taskId == change.taskId }.forEach {
            hints[it.taskId] = TaskReminder(it.taskId, it.revision, it.remindedAt)
        }
        changes.value++
    }
    override fun revoke(taskId: String) {
        if (revokeTask(taskId)) { generation++; changes.value++ }
    }
    private fun revokeTask(taskId: String): Boolean {
        val keys = pages.filterValues { it.items.any { task -> task.taskId == taskId } }.keys.toList()
        val queryKeys = queryPages.filterValues { it.items.any { details -> details.task.taskId == taskId } }.keys.toList()
        val removed = taskId in tasks || taskId in reminders || taskId in hints || keys.isNotEmpty() || queryKeys.isNotEmpty()
        tasks.remove(taskId); keys.forEach(pages::remove); reminders.remove(taskId); hints.remove(taskId)
        details.remove(taskId); queryKeys.forEach(queryPages::remove)
        return removed
    }
    override fun invalidateGroup(groupId: String, ownerUid: String) {
        revokeGroupProjection(groupId, ownerUid)
        generation++; staleQueries.addAll(queryPages.keys); changes.value++
    }
    override fun revokeGroup(groupId: String, ownerUid: String) {
        if (revokeGroupProjection(groupId, ownerUid)) {
            generation++; staleQueries.addAll(queryPages.keys); changes.value++
        }
    }
    private fun revokeGroupProjection(groupId: String, ownerUid: String): Boolean {
        val ids = tasks.values.filter { it.contextKind == TaskPolicy.CONTEXT_GROUP && it.contextId == groupId && it.creatorUid != ownerUid && it.assigneeUid != ownerUid }
            .map { it.taskId }
        val removed = ids.isNotEmpty() || queryPages.keys.any { it.groupId == groupId }
        ids.forEach { revokeTask(it) }
        queryPages.keys.removeAll { it.groupId == groupId }
        return removed
    }
    override fun pending() = pending.values.toList()
    override fun pending(taskId: String) = pending[taskId]
    override fun prepare(command: TaskCommand) = prepareRecord(PendingTaskCommand(command))
    override fun prepare(command: TaskDetailsCommand) = prepareRecord(PendingTaskCommand(detailsCommand = command))
    override fun prepare(command: TaskSeriesCommand) = prepareRecord(PendingTaskCommand(seriesCommand = command))
    private fun prepareRecord(record: PendingTaskCommand): PendingTaskCommand {
        pending[record.taskId]?.let { check(it.copy(failure = null) == record); return it }
        check(pending.size < 256)
        return record.also { pending[record.taskId] = it; changes.value++ }
    }
    override fun fail(taskId: String, reason: String) { pending[taskId]?.let { pending[taskId] = it.copy(failure = reason) }; changes.value++ }
    override fun retry(taskId: String) { pending[taskId]?.let { pending[taskId] = it.copy(failure = null) }; changes.value++ }
    override fun discard(taskId: String) {
        val record = pending[taskId] ?: return
        check(record.failure != null); pending.remove(taskId); changes.value++
    }
    override fun acknowledge(command: TaskCommand, result: TaskCommandResult, generation: Long, ownerUid: String) {
        if (pending[command.taskId]?.command != command) return
        val confirmedTask = result.task
        if (confirmedTask == null) revoke(command.taskId) else applyTask(confirmedTask, generation, ownerUid)
        pending.remove(command.taskId); this.generation++; stale.addAll(pages.keys); staleQueries.addAll(queryPages.keys); changes.value++
    }
    override fun acknowledge(command: TaskDetailsCommand, result: TaskDetailsCommandResult, generation: Long, ownerUid: String) {
        if (pending[command.taskId]?.detailsCommand != command) return
        val confirmed = result.task
        if (confirmed == null) revoke(command.taskId) else applyDetails(confirmed, generation, ownerUid)
        pending.remove(command.taskId); this.generation++; stale.addAll(pages.keys); staleQueries.addAll(queryPages.keys); changes.value++
    }
    override fun acknowledge(command: TaskSeriesCommand, result: TaskSeries, generation: Long) {
        if (pending[command.seriesId]?.seriesCommand != command) return
        if (generation == this.generation) details.entries.forEach { entry ->
            if (entry.value.series?.seriesId == command.seriesId) entry.setValue(entry.value.copy(series = result))
        }
        pending.remove(command.seriesId); this.generation++; staleQueries.addAll(queryPages.keys); changes.value++
    }
    override fun due(change: TaskDuePayload) = recordHint(TaskReminder(change.taskId, change.revision, change.remindedAt))
    override fun started(change: TaskStartedPayload) = recordHint(TaskReminder(change.taskId, change.revision, change.remindedAt))
    private fun recordHint(hint: TaskReminder) {
        val previous = hints[hint.taskId] ?: reminders[hint.taskId]
        if (previous != null && (previous.revision > hint.revision || previous.remindedAt > hint.remindedAt)) return
        hints[hint.taskId] = hint
        generation++; stale.addAll(pages.keys); staleQueries.addAll(queryPages.keys); changes.value++
    }
    override fun reminderHints() = hints.values.toList()
    override fun reminders() = reminders.values.filter { it.taskId !in hints }
    override fun markReminderSeen(taskId: String, remindedAt: Long) {
        reminders[taskId]?.takeIf { it.remindedAt == remindedAt }?.let { reminders[taskId] = it.copy(seen = true) }; changes.value++
    }
    override fun markReminderNotified(taskId: String, remindedAt: Long) {
        reminders[taskId]?.takeIf { it.remindedAt == remindedAt }?.let { reminders[taskId] = it.copy(notified = true) }; changes.value++
    }
    override fun resetProjection() { tasks.clear(); details.clear(); pages.clear(); queryPages.clear(); staleQueries.clear(); invalidate() }
}

package com.virjar.tk.shared.testkit

import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.TaskDuePayload
import com.virjar.tk.protocol.model.*
import com.virjar.tk.shared.client.*
import kotlinx.coroutines.flow.MutableStateFlow

/** E2E 的内存投影适配器；持久化与故障恢复测试使用真实 SQLite。 */
internal class FakeTasks : LocalTasks {
    override val changes = MutableStateFlow(0L)
    private var generation = 0L
    private val tasks = linkedMapOf<String, WorkTask>()
    private val pages = linkedMapOf<TaskPageKey, TaskPage>()
    private val stale = mutableSetOf<TaskPageKey>()
    private val pending = linkedMapOf<String, PendingTaskCommand>()
    private val reminders = linkedMapOf<String, TaskReminder>()
    private val hints = linkedMapOf<String, TaskDuePayload>()
    override fun generation() = generation
    override fun task(taskId: String) = tasks[taskId]
    override fun page(key: TaskPageKey) = pages[key]
    override fun isPageStale(key: TaskPageKey) = key !in pages || key in stale
    override fun applyTask(task: WorkTask, generation: Long, ownerUid: String): Boolean {
        if (generation != this.generation) return false
        tasks[task.taskId] = task
        if (task.assigneeUid == ownerUid && task.status in TaskPolicy.TODO..TaskPolicy.IN_PROGRESS && task.remindedAt != null) {
            val previous = reminders[task.taskId]
            reminders[task.taskId] = if (previous != null && previous.remindedAt == task.remindedAt) previous.copy(revision = task.revision)
                else TaskReminder(task.taskId, task.revision, requireNotNull(task.remindedAt))
        } else reminders.remove(task.taskId)
        hints.remove(task.taskId)
        changes.value++
        return true
    }
    override fun applyPage(key: TaskPageKey, page: TaskPage, generation: Long, ownerUid: String): Boolean {
        if (generation != this.generation) return false
        page.items.forEach { applyTask(it, generation, ownerUid) }
        pages[key] = page; stale.remove(key); changes.value++
        return true
    }
    override fun invalidate(change: TaskChangedPayload?) {
        generation++; stale.addAll(pages.keys)
        if (change?.kind == TaskChangedPayload.REVOKED) revoke(change.taskId)
        else reminders.values.filter { change == null || it.taskId == change.taskId }.forEach {
            hints[it.taskId] = TaskDuePayload(it.taskId, it.revision, it.remindedAt)
        }
        changes.value++
    }
    override fun revoke(taskId: String) {
        val keys = pages.filterValues { it.items.any { task -> task.taskId == taskId } }.keys.toList()
        val removed = taskId in tasks || taskId in reminders || taskId in hints || keys.isNotEmpty()
        tasks.remove(taskId); keys.forEach(pages::remove); reminders.remove(taskId); hints.remove(taskId)
        if (removed) { generation++; changes.value++ }
    }
    override fun pending() = pending.values.toList()
    override fun prepare(command: TaskCommand): PendingTaskCommand {
        pending[command.taskId]?.let { check(it.command == command); return it }
        check(pending.size < 256)
        return PendingTaskCommand(command).also { pending[command.taskId] = it; changes.value++ }
    }
    override fun fail(taskId: String, reason: String) { pending[taskId]?.let { pending[taskId] = it.copy(failure = reason) }; changes.value++ }
    override fun retry(taskId: String) { pending[taskId]?.let { pending[taskId] = it.copy(failure = null) }; changes.value++ }
    override fun discard(taskId: String) { check(pending[taskId]?.failure != null); pending.remove(taskId); changes.value++ }
    override fun acknowledge(command: TaskCommand, result: TaskCommandResult, generation: Long, ownerUid: String) {
        if (pending[command.taskId]?.command != command) return
        val confirmedTask = result.task
        if (confirmedTask == null) revoke(command.taskId) else applyTask(confirmedTask, generation, ownerUid)
        pending.remove(command.taskId); this.generation++; stale.addAll(pages.keys); changes.value++
    }
    override fun due(change: TaskDuePayload) { hints[change.taskId] = change; generation++; changes.value++ }
    override fun reminderHints() = hints.values.toList()
    override fun reminders() = reminders.values.filter { it.taskId !in hints }
    override fun markReminderSeen(taskId: String, remindedAt: Long) {
        reminders[taskId]?.takeIf { it.remindedAt == remindedAt }?.let { reminders[taskId] = it.copy(seen = true) }; changes.value++
    }
    override fun markReminderNotified(taskId: String, remindedAt: Long) {
        reminders[taskId]?.takeIf { it.remindedAt == remindedAt }?.let { reminders[taskId] = it.copy(notified = true) }; changes.value++
    }
    override fun resetProjection() { tasks.clear(); pages.clear(); invalidate() }
}

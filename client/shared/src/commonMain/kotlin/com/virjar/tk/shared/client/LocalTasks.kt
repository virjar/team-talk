package com.virjar.tk.shared.client

import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.TaskDuePayload
import com.virjar.tk.protocol.model.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

data class TaskPageKey(val view: Int, val cursor: String? = null) {
    init { require(view == TaskPolicy.VIEW_ASSIGNED || view == TaskPolicy.VIEW_CREATED); TaskPolicy.requireCursor(cursor) }
}

@Serializable
data class PendingTaskCommand(val command: TaskCommand, val failure: String? = null)

/** 已读与系统展示分开持久化；失效提示本身不能让过期任务弹出通知。 */
data class TaskReminder(
    val taskId: String,
    val revision: Long,
    val remindedAt: Long,
    val seen: Boolean = false,
    val notified: Boolean = false,
)

/** 账号拥有的有界任务工作集与可靠命令；投影失效或撤权不删除待确认意图。 */
interface LocalTasks {
    val changes: StateFlow<Long>
    fun generation(): Long
    fun task(taskId: String): WorkTask?
    fun page(key: TaskPageKey): TaskPage?
    fun isPageStale(key: TaskPageKey): Boolean
    fun applyTask(task: WorkTask, generation: Long, ownerUid: String): Boolean
    fun applyPage(key: TaskPageKey, page: TaskPage, generation: Long, ownerUid: String): Boolean
    fun invalidate(change: TaskChangedPayload? = null)
    fun revoke(taskId: String)
    fun pending(): List<PendingTaskCommand>
    fun prepare(command: TaskCommand): PendingTaskCommand
    fun fail(taskId: String, reason: String)
    fun retry(taskId: String)
    fun discard(taskId: String)
    fun acknowledge(command: TaskCommand, result: TaskCommandResult, generation: Long, ownerUid: String)
    fun due(change: TaskDuePayload)
    fun reminderHints(): List<TaskDuePayload>
    fun reminders(): List<TaskReminder>
    fun markReminderSeen(taskId: String, remindedAt: Long)
    fun markReminderNotified(taskId: String, remindedAt: Long)
    /** 清除服务端工作集，保留命令及精确提醒的已读/已展示收据。 */
    fun resetProjection()
}

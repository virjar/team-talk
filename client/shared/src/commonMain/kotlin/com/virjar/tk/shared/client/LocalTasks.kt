package com.virjar.tk.shared.client

import com.virjar.tk.protocol.TaskChangedPayload
import com.virjar.tk.protocol.TaskDuePayload
import com.virjar.tk.protocol.TaskStartedPayload
import com.virjar.tk.protocol.model.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

data class TaskPageKey(val view: Int, val cursor: String? = null) {
    init { require(view == TaskPolicy.VIEW_ASSIGNED || view == TaskPolicy.VIEW_CREATED); TaskPolicy.requireCursor(cursor) }
}

data class TaskQueryKey(
    val scope: Int,
    val groupId: String? = null,
    val openOnly: Boolean = false,
    val startedOnly: Boolean = false,
    val cursor: String? = null,
) {
    val query = TaskQuery(scope, groupId, openOnly, startedOnly)
    init { TaskPolicy.requireCursor(cursor) }
    internal val storageKey: String get() = "$scope|${groupId.orEmpty()}|$openOnly|$startedOnly|${cursor.orEmpty()}"
}

/** 同一个 task/series 槽保存一种原始 wire 命令；旧 JSON 的 command 字段原样可读。 */
@Serializable
data class PendingTaskCommand(
    val command: TaskCommand? = null,
    val failure: String? = null,
    val detailsCommand: TaskDetailsCommand? = null,
    val seriesCommand: TaskSeriesCommand? = null,
) {
    init { require(listOfNotNull(command, detailsCommand, seriesCommand).size == 1) }
    val taskId: String get() = command?.taskId ?: detailsCommand?.taskId ?: requireNotNull(seriesCommand).seriesId
    val operationId: String get() = command?.operationId ?: detailsCommand?.operationId ?: requireNotNull(seriesCommand).operationId
    val issuedAt: Long get() = command?.issuedAt ?: detailsCommand?.issuedAt ?: requireNotNull(seriesCommand).issuedAt
    val expectedRevision: Long get() = command?.expectedRevision ?: detailsCommand?.expectedRevision ?: requireNotNull(seriesCommand).expectedRevision
    val kind: Int? get() = command?.kind ?: detailsCommand?.kind
    val draft: TaskDraft? get() = command?.draft ?: detailsCommand?.draft
    val status: Int? get() = command?.status
}

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
    fun details(taskId: String): TaskDetails?
    fun queryPage(key: TaskQueryKey): TaskQueryPage?
    fun isQueryPageStale(key: TaskQueryKey): Boolean
    fun applyDetails(details: TaskDetails, generation: Long, ownerUid: String): Boolean
    fun applyQueryPage(key: TaskQueryKey, page: TaskQueryPage, generation: Long, ownerUid: String): Boolean
    fun applyTask(task: WorkTask, generation: Long, ownerUid: String): Boolean
    fun applyPage(key: TaskPageKey, page: TaskPage, generation: Long, ownerUid: String): Boolean
    fun invalidate(change: TaskChangedPayload? = null)
    fun revoke(taskId: String)
    /** 成员变化使群摘要及仅群授权的详情退出；个人参与者任务与可靠意图保留。 */
    fun invalidateGroup(groupId: String, ownerUid: String)
    /** 拒绝读取只在移除了本地内容时发布，重复拒绝不能触发观察者刷新循环。 */
    fun revokeGroup(groupId: String, ownerUid: String)
    fun pending(): List<PendingTaskCommand>
    /** 点查原始命令和本机失败状态，供发送前精确核对所选记录。 */
    fun pending(taskId: String): PendingTaskCommand?
    fun prepare(command: TaskCommand): PendingTaskCommand
    fun prepare(command: TaskDetailsCommand): PendingTaskCommand
    fun prepare(command: TaskSeriesCommand): PendingTaskCommand
    fun fail(taskId: String, reason: String)
    fun retry(taskId: String)
    fun discard(taskId: String)
    fun acknowledge(command: TaskCommand, result: TaskCommandResult, generation: Long, ownerUid: String)
    fun acknowledge(command: TaskDetailsCommand, result: TaskDetailsCommandResult, generation: Long, ownerUid: String)
    fun acknowledge(command: TaskSeriesCommand, result: TaskSeries, generation: Long)
    fun due(change: TaskDuePayload)
    fun started(change: TaskStartedPayload)
    fun reminderHints(): List<TaskReminder>
    fun reminders(): List<TaskReminder>
    fun markReminderSeen(taskId: String, remindedAt: Long)
    fun markReminderNotified(taskId: String, remindedAt: Long)
    /** 清除服务端工作集，保留命令及精确提醒的已读/已展示收据。 */
    fun resetProjection()
}

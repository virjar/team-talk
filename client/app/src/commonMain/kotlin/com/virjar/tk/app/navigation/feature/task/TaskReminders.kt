package com.virjar.tk.app.navigation.feature.task

import kotlinx.coroutines.*

import com.virjar.tk.protocol.model.WorkTask
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.repository.TaskRepository
import com.virjar.tk.protocol.model.TaskPolicy

/**
 * 开始与截止提醒共用业务过滤：未见未通知、仍指派给本账号且处于待处理/进行中状态才弹通知。
 * 两端壳共用，壳层只负责实际的系统通知呈现与“仍可弹出”判断。
 */
suspend fun forEachTaskReminder(
    repository: TaskRepository,
    ownerUid: String,
    stillEligible: () -> Boolean,
    notify: suspend (task: WorkTask, remindedAt: Long) -> Unit,
) {
    val reminders = withContext(Dispatchers.IO) {
        repository.local.reminders().filterNot { it.seen || it.notified }
    }
    for (reminder in reminders) {
        val details = (withContext(Dispatchers.IO) { repository.getDetails(reminder.taskId) } as? Outcome.Success)?.value ?: continue
        val task = details.task
        // 与本地收据保持一致：读取时若已有更新的提醒，不再弹出旧事件。
        val latestRemindedAt = listOfNotNull(task.remindedAt, details.startRemindedAt).maxOrNull()
        if (task.assigneeUid != ownerUid || latestRemindedAt != reminder.remindedAt ||
            task.status !in setOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS)
        ) continue
        if (!stillEligible()) break
        notify(task, reminder.remindedAt)
    }
}

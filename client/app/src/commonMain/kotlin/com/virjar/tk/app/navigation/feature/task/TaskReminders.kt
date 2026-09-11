package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.protocol.model.WorkTask
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.repository.TaskRepository
import com.virjar.tk.protocol.model.TaskPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 到期提醒的业务过滤：未见未通知、仍指派给本账号且处于待处理/进行中状态的任务才弹通知。
 * 两端壳共用，壳层只负责实际的系统通知呈现与“仍可弹出”判断。
 */
suspend fun forEachDueTaskReminder(
    repository: TaskRepository,
    ownerUid: String,
    stillEligible: () -> Boolean,
    notify: suspend (task: WorkTask, remindedAt: Long) -> Unit,
) {
    val reminders = withContext(Dispatchers.IO) {
        repository.local.reminders().filterNot { it.seen || it.notified }
    }
    for (reminder in reminders) {
        val task = (withContext(Dispatchers.IO) { repository.get(reminder.taskId) } as? Outcome.Success)?.value ?: continue
        if (task.assigneeUid != ownerUid || task.remindedAt != reminder.remindedAt ||
            task.status !in setOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS)
        ) continue
        if (!stillEligible()) break
        notify(task, reminder.remindedAt)
    }
}

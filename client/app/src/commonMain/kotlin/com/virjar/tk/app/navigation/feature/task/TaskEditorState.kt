package com.virjar.tk.app.navigation.feature.task

import com.virjar.tk.protocol.model.TaskDraft
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.protocol.model.WorkTask

internal data class TaskEditorState(
    val original: WorkTask?,
    val title: String,
    val description: String,
    val assigneeUid: String,
    val contextKind: Int = TaskPolicy.CONTEXT_NONE,
    val contextId: String = "",
    val deadline: TaskDeadlineInput = TaskDeadlineInput(),
) {
    fun draft(): TaskDraft {
        require(title.trim().isNotEmpty()) { "请填写任务标题" }
        require(title.trim().length <= TaskPolicy.MAX_TITLE_LENGTH) { "任务标题最多 ${TaskPolicy.MAX_TITLE_LENGTH} 字" }
        require(description.length <= TaskPolicy.MAX_DESCRIPTION_LENGTH) { "任务描述最多 ${TaskPolicy.MAX_DESCRIPTION_LENGTH} 字" }
        require(assigneeUid.isNotBlank()) { "请选择执行人" }
        require(contextKind == TaskPolicy.CONTEXT_NONE || contextId.isNotBlank()) { "请选择关联的群或部门" }
        val dueAt = if (original != null && deadline == TaskDeadlineInput.from(original.dueAt)) original.dueAt
            else deadline.epochMillis()
        return TaskDraft(title.trim(), description, assigneeUid, contextKind, contextId, dueAt)
    }

    companion object {
        fun from(task: WorkTask) = TaskEditorState(task, task.title, task.description, task.assigneeUid,
            task.contextKind, task.contextId, TaskDeadlineInput.from(task.dueAt))
        fun create(myUid: String) = TaskEditorState(null, "", "", myUid)
    }
}

internal fun taskStatusLabel(status: Int): String = when (status) {
    TaskPolicy.TODO -> "待处理"
    TaskPolicy.IN_PROGRESS -> "进行中"
    TaskPolicy.DONE -> "已完成"
    TaskPolicy.CANCELLED -> "已取消"
    else -> "未知状态"
}

internal fun taskIsOverdue(task: WorkTask, now: Long): Boolean =
    task.status in setOf(TaskPolicy.TODO, TaskPolicy.IN_PROGRESS) && task.dueAt?.let { it < now } == true

internal data class TaskContextOption(val kind: Int, val id: String, val name: String)
internal data class TaskShareOption(val chatId: String, val name: String)

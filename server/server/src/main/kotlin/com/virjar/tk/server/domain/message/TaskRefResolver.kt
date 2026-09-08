package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.body.TaskRefBody
import com.virjar.tk.protocol.model.TaskPolicy
import com.virjar.tk.server.domain.task.TaskService

/** Sending freezes a participant-authorized preview; receiving or forwarding does not grant task access. */
class TaskRefResolver(private val tasks: TaskService) {
    suspend fun resolve(uid: String, body: TaskRefBody): TaskRefBody {
        val task = tasks.get(uid, body.taskId)
        val status = when (task.status) {
            TaskPolicy.TODO -> "待处理"
            TaskPolicy.IN_PROGRESS -> "进行中"
            TaskPolicy.DONE -> "已完成"
            TaskPolicy.CANCELLED -> "已取消"
            else -> error("Unknown task status")
        }
        return TaskRefBody(task.taskId, task.title, "任务 · $status")
    }
}

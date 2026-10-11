package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.TaskPolicy

/** 开始提醒使用独立标记；已发布的 WorkTask.remindedAt 仍只表示到期提醒。 */
@SinceProtocol(3)
data class TaskStartedPayload(val taskId: String, val revision: Long, val remindedAt: Long) : IProto {
    init { TaskPolicy.requireId(taskId); require(revision > 0 && remindedAt >= 0) }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(taskId); buf.writeVarLong(revision); buf.writeVarLong(remindedAt) }
    companion object : IProtoReader<TaskStartedPayload> {
        override fun readFrom(buf: PacketBuffer) = TaskStartedPayload(buf.readRequiredString(36), buf.readVarLong(), buf.readVarLong())
    }
}

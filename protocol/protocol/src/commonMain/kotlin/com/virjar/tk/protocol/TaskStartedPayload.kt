package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.TaskPolicy

/** A start reminder has its own marker; released WorkTask.remindedAt remains a due reminder. */
@SinceProtocol(3)
data class TaskStartedPayload(val taskId: String, val revision: Long, val remindedAt: Long) : IProto {
    init { TaskPolicy.requireId(taskId); require(revision > 0 && remindedAt >= 0) }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(taskId); buf.writeVarLong(revision); buf.writeVarLong(remindedAt) }
    companion object : IProtoReader<TaskStartedPayload> {
        override fun readFrom(buf: PacketBuffer) = TaskStartedPayload(buf.readRequiredString(36), buf.readVarLong(), buf.readVarLong())
    }
}

package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.TaskPolicy

@SinceProtocol(2)
data class TaskDuePayload(val taskId: String, val revision: Long, val remindedAt: Long) : IProto {
    init { TaskPolicy.requireId(taskId); require(revision > 0); require(remindedAt >= 0) }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(taskId); buf.writeVarLong(revision); buf.writeVarLong(remindedAt) }
    companion object : IProtoReader<TaskDuePayload> {

        override fun readFrom(buf: PacketBuffer) = TaskDuePayload(buf.readRequiredString(36), buf.readVarLong(), buf.readVarLong())
    }
}

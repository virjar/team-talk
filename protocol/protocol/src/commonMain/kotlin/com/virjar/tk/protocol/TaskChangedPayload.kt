package com.virjar.tk.protocol

import com.virjar.tk.protocol.model.TaskPolicy

@SinceProtocol(2)
data class TaskChangedPayload(val taskId: String, val revision: Long, val kind: Int) : IProto {
    init { TaskPolicy.requireId(taskId); require(revision > 0); require(kind == UPDATED || kind == REVOKED) }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(taskId); buf.writeVarLong(revision); buf.writeVarInt(kind) }
    companion object : IProtoReader<TaskChangedPayload> {
        const val UPDATED = 1
        const val REVOKED = 2
        override fun readFrom(buf: PacketBuffer) = TaskChangedPayload(buf.readRequiredString(36), buf.readVarLong(), buf.readVarInt())
    }
}

package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** 独立任务资产；上下文不授予访问权，创建人与当前唯一执行人才是参与者。 */
@SinceProtocol(2)
@Serializable
data class WorkTask(
    val taskId: String,
    val creatorUid: String,
    val assigneeUid: String,
    val title: String,
    val description: String,
    val status: Int,
    val contextKind: Int,
    val contextId: String,
    val dueAt: Long?,
    val remindedAt: Long?,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
) : IProto {
    init {
        TaskPolicy.requireId(taskId); TaskPolicy.requireUid(creatorUid); TaskPolicy.requireStatus(status)
        TaskPolicy.requireDraft(title, description, assigneeUid, contextKind, contextId, dueAt)
        require(revision > 0 && createdAt >= 0 && updatedAt >= createdAt)
        require(remindedAt == null || (dueAt != null && remindedAt >= dueAt))
    }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(taskId); buf.writeString(creatorUid); buf.writeString(assigneeUid)
        buf.writeString(title); buf.writeString(description); buf.writeVarInt(status)
        buf.writeVarInt(contextKind); buf.writeString(contextId)
        buf.writeBoolean(dueAt != null); dueAt?.let(buf::writeVarLong)
        buf.writeBoolean(remindedAt != null); remindedAt?.let(buf::writeVarLong)
        buf.writeVarLong(revision); buf.writeVarLong(createdAt); buf.writeVarLong(updatedAt)
    }
    companion object : IProtoReader<WorkTask> {
        override fun readFrom(buf: PacketBuffer) = WorkTask(
            buf.readRequiredString(36), buf.readRequiredString(256), buf.readRequiredString(256),
            buf.readRequiredString(TaskPolicy.MAX_TITLE_LENGTH * 4), buf.readRequiredString(TaskPolicy.MAX_DESCRIPTION_LENGTH * 4),
            buf.readVarInt(), buf.readVarInt(), buf.readRequiredString(36),
            if (buf.readBoolean()) buf.readVarLong() else null, if (buf.readBoolean()) buf.readVarLong() else null,
            buf.readVarLong(), buf.readVarLong(), buf.readVarLong(),
        )
    }
}

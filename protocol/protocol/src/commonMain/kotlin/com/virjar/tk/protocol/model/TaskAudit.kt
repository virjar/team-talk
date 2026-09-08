package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** 每个任务每次业务修订一条审计；不复制任务描述或异常正文。 */
@SinceProtocol(2)
@Serializable
data class TaskAudit(
    val taskId: String,
    val revision: Long,
    val actorUid: String,
    val action: Int,
    val createdAt: Long,
    val fromStatus: Int?,
    val toStatus: Int?,
    val previousAssigneeUid: String?,
    val assigneeUid: String?,
) : IProto {
    init {
        TaskPolicy.requireId(taskId); TaskPolicy.requireUid(actorUid)
        require(revision > 0 && createdAt >= 0 && action in CREATED..STATUS_CHANGED)
        fromStatus?.let(TaskPolicy::requireStatus); toStatus?.let(TaskPolicy::requireStatus)
        previousAssigneeUid?.let(TaskPolicy::requireUid); assigneeUid?.let(TaskPolicy::requireUid)
    }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(taskId); buf.writeVarLong(revision); buf.writeString(actorUid); buf.writeVarInt(action); buf.writeVarLong(createdAt)
        buf.writeBoolean(fromStatus != null); fromStatus?.let(buf::writeVarInt)
        buf.writeBoolean(toStatus != null); toStatus?.let(buf::writeVarInt)
        buf.writeString(previousAssigneeUid); buf.writeString(assigneeUid)
    }
    companion object : IProtoReader<TaskAudit> {
        const val CREATED = 1
        const val EDITED = 2
        const val STATUS_CHANGED = 3
        override fun readFrom(buf: PacketBuffer) = TaskAudit(
            buf.readRequiredString(36), buf.readVarLong(), buf.readRequiredString(256), buf.readVarInt(), buf.readVarLong(),
            if (buf.readBoolean()) buf.readVarInt() else null, if (buf.readBoolean()) buf.readVarInt() else null,
            buf.readString(256), buf.readString(256),
        )
    }
}

package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

@SinceProtocol(2)
@Serializable
data class TaskDraft(
    val title: String,
    val description: String,
    val assigneeUid: String,
    val contextKind: Int = TaskPolicy.CONTEXT_NONE,
    val contextId: String = "",
    val dueAt: Long? = null,
) : IProto {
    init { TaskPolicy.requireDraft(title, description, assigneeUid, contextKind, contextId, dueAt) }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(title); buf.writeString(description); buf.writeString(assigneeUid)
        buf.writeVarInt(contextKind); buf.writeString(contextId)
        buf.writeBoolean(dueAt != null); dueAt?.let(buf::writeVarLong)
    }
    companion object : IProtoReader<TaskDraft> {
        override fun readFrom(buf: PacketBuffer) = TaskDraft(
            buf.readRequiredString(TaskPolicy.MAX_TITLE_LENGTH * 4),
            buf.readRequiredString(TaskPolicy.MAX_DESCRIPTION_LENGTH * 4), buf.readRequiredString(256),
            buf.readVarInt(), buf.readRequiredString(36), if (buf.readBoolean()) buf.readVarLong() else null,
        )
    }
}

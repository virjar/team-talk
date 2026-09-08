package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** 一条不可变可靠意图；所有字段参与服务端指纹，重试不能换 operationId 或 issuedAt。 */
@SinceProtocol(2)
@Serializable
data class TaskCommand(
    val operationId: String,
    val issuedAt: Long,
    val taskId: String,
    val expectedRevision: Long,
    val kind: Int,
    val draft: TaskDraft? = null,
    val status: Int? = null,
) : IProto {
    init {
        TaskPolicy.requireId(operationId); TaskPolicy.requireId(taskId); require(issuedAt >= 0)
        require(kind in CREATE..STATUS)
        require(if (kind == CREATE) expectedRevision == 0L else expectedRevision in 1 until Long.MAX_VALUE)
        require(if (kind == STATUS) draft == null && status != null else draft != null && status == null)
        status?.let(TaskPolicy::requireStatus)
    }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(operationId); buf.writeVarLong(issuedAt); buf.writeString(taskId)
        buf.writeVarLong(expectedRevision); buf.writeVarInt(kind)
        buf.writeBoolean(draft != null); draft?.writeTo(buf)
        buf.writeBoolean(status != null); status?.let(buf::writeVarInt)
    }
    companion object : IProtoReader<TaskCommand> {
        const val CREATE = 1
        const val EDIT = 2
        const val STATUS = 3
        override fun readFrom(buf: PacketBuffer) = TaskCommand(
            buf.readRequiredString(36), buf.readVarLong(), buf.readRequiredString(36), buf.readVarLong(), buf.readVarInt(),
            if (buf.readBoolean()) TaskDraft.readFrom(buf) else null, if (buf.readBoolean()) buf.readVarInt() else null,
        )
    }
}

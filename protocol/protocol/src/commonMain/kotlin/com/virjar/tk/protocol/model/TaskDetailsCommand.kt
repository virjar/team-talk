package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

@SinceProtocol(3)
@Serializable
data class TaskDetailsCommand(val operationId: String, val issuedAt: Long, val taskId: String, val expectedRevision: Long,
    val kind: Int, val draft: TaskDraft? = null, val options: TaskOptions? = null,
    val deferDueAt: Long? = null, val reason: String? = null, val recurrenceRule: TaskRecurrenceRule? = null) : IProto {
    init {
        TaskPolicy.requireId(operationId); TaskPolicy.requireId(taskId); require(issuedAt >= 0 && kind in CREATE..DEFER)
        require(if (kind == CREATE) expectedRevision == 0L else expectedRevision in 1 until Long.MAX_VALUE)
        require(if (kind == DEFER) draft == null && options == null && deferDueAt != null && reason != null
            else draft != null && options != null && deferDueAt == null && reason == null)
        require(recurrenceRule == null || kind == CREATE)
        require(deferDueAt == null || deferDueAt >= 0)
        require(reason == null || (reason.isNotBlank() && reason.length <= MAX_REASON && '\u0000' !in reason))
    }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(operationId); buf.writeVarLong(issuedAt); buf.writeString(taskId); buf.writeVarLong(expectedRevision); buf.writeVarInt(kind)
        buf.writeBoolean(draft != null); draft?.writeTo(buf); buf.writeBoolean(options != null); options?.writeTo(buf)
        buf.writeTaskLong(deferDueAt); buf.writeString(reason); buf.writeBoolean(recurrenceRule != null); recurrenceRule?.writeTo(buf)
    }
    companion object : IProtoReader<TaskDetailsCommand> {
        const val CREATE = 1
        const val EDIT = 2
        const val DEFER = 3
        const val MAX_REASON = 1000
        override fun readFrom(buf: PacketBuffer) = TaskDetailsCommand(buf.readRequiredString(36), buf.readVarLong(), buf.readRequiredString(36), buf.readVarLong(), buf.readVarInt(),
            if (buf.readBoolean()) TaskDraft.readFrom(buf) else null, if (buf.readBoolean()) TaskOptions.readFrom(buf) else null, buf.readTaskLong(), buf.readString(MAX_REASON * 4), if (buf.readBoolean()) TaskRecurrenceRule.readFrom(buf) else null)
    }
}

@SinceProtocol(3)
@Serializable
data class TaskDetailsCommandResult(val task: TaskDetails?) : IProto {
    override fun writeTo(buf: PacketBuffer) { buf.writeBoolean(task != null); task?.writeTo(buf) }
    companion object : IProtoReader<TaskDetailsCommandResult> {
        override fun readFrom(buf: PacketBuffer) = TaskDetailsCommandResult(if (buf.readBoolean()) TaskDetails.readFrom(buf) else null)
    }
}

@SinceProtocol(3)
@Serializable
data class TaskDeferral(val taskId: String, val revision: Long, val actorUid: String, val createdAt: Long,
    val previousDueAt: Long?, val newDueAt: Long, val reason: String) : IProto {
    init { TaskPolicy.requireId(taskId); require(revision > 0 && createdAt >= 0 && newDueAt >= 0); require(reason.isNotBlank() && reason.length <= TaskDetailsCommand.MAX_REASON) }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(taskId); buf.writeVarLong(revision); buf.writeString(actorUid); buf.writeVarLong(createdAt); buf.writeTaskLong(previousDueAt); buf.writeVarLong(newDueAt); buf.writeString(reason) }
    companion object : IProtoReader<TaskDeferral> {
        override fun readFrom(buf: PacketBuffer) = TaskDeferral(buf.readRequiredString(36), buf.readVarLong(), buf.readRequiredString(256), buf.readVarLong(), buf.readTaskLong(), buf.readVarLong(), buf.readRequiredString(TaskDetailsCommand.MAX_REASON * 4))
    }
}

@SinceProtocol(3)
@Serializable
data class TaskHistoryEntry(val audit: TaskAudit, val deferral: TaskDeferral? = null) : IProto {
    override fun writeTo(buf: PacketBuffer) { audit.writeTo(buf); buf.writeBoolean(deferral != null); deferral?.writeTo(buf) }
    companion object : IProtoReader<TaskHistoryEntry> {
        override fun readFrom(buf: PacketBuffer) = TaskHistoryEntry(TaskAudit.readFrom(buf), if (buf.readBoolean()) TaskDeferral.readFrom(buf) else null)
    }
}

@SinceProtocol(3)
@Serializable
data class TaskHistoryPage(val items: List<TaskHistoryEntry>, val nextCursor: String?) : IProto {
    init { require(items.size <= TaskPolicy.MAX_PAGE_SIZE); TaskPolicy.requireCursor(nextCursor) }
    override fun writeTo(buf: PacketBuffer) { buf.writeVarInt(items.size); items.forEach { it.writeTo(buf) }; buf.writeString(nextCursor) }
    companion object : IProtoReader<TaskHistoryPage> {
        override fun readFrom(buf: PacketBuffer) = TaskHistoryPage(List(buf.readCollectionSize(TaskPolicy.MAX_PAGE_SIZE, 5, "task history")) { TaskHistoryEntry.readFrom(buf) }, buf.readString(TaskPolicy.MAX_CURSOR_LENGTH * 4))
    }
}

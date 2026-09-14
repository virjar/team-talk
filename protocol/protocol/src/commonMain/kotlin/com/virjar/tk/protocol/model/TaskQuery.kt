package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

@SinceProtocol(3)
@Serializable
data class TaskQuery(val scope: Int = ASSIGNED, val groupId: String? = null, val openOnly: Boolean = false, val startedOnly: Boolean = false) : IProto {
    init { require(scope in ASSIGNED..GROUP); require((scope == GROUP) == (groupId != null)); groupId?.let(TaskPolicy::requireId) }
    override fun writeTo(buf: PacketBuffer) { buf.writeVarInt(scope); buf.writeString(groupId); buf.writeBoolean(openOnly); buf.writeBoolean(startedOnly) }
    companion object : IProtoReader<TaskQuery> {
        const val ASSIGNED = 1
        const val CREATED = 2
        const val GROUP = 3
        override fun readFrom(buf: PacketBuffer) = TaskQuery(buf.readVarInt(), buf.readString(36), buf.readBoolean(), buf.readBoolean())
    }
}

/** Counts cover the complete authorized, filtered query, independent of the requested page. */
@SinceProtocol(3)
@Serializable
data class TaskSummary(val totalCount: Long, val openCount: Long, val overdueCount: Long, val nextDueAt: Long?,
    val completedCount: Long, val averageProcessingMillis: Long?) : IProto {
    init { require(totalCount >= 0 && openCount in 0..totalCount && overdueCount in 0..openCount && completedCount in 0..totalCount) }
    override fun writeTo(buf: PacketBuffer) { buf.writeVarLong(totalCount); buf.writeVarLong(openCount); buf.writeVarLong(overdueCount); buf.writeTaskLong(nextDueAt); buf.writeVarLong(completedCount); buf.writeTaskLong(averageProcessingMillis) }
    companion object : IProtoReader<TaskSummary> {
        override fun readFrom(buf: PacketBuffer) = TaskSummary(buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readTaskLong(), buf.readVarLong(), buf.readTaskLong())
    }
}

@SinceProtocol(3)
@Serializable
data class TaskQueryPage(val items: List<TaskDetails>, val nextCursor: String?, val summary: TaskSummary) : IProto {
    init { require(items.size <= TaskPolicy.MAX_PAGE_SIZE); TaskPolicy.requireCursor(nextCursor); require(items.map { it.task.taskId }.distinct().size == items.size) }
    override fun writeTo(buf: PacketBuffer) { buf.writeVarInt(items.size); items.forEach { it.writeTo(buf) }; buf.writeString(nextCursor); summary.writeTo(buf) }
    companion object : IProtoReader<TaskQueryPage> {
        override fun readFrom(buf: PacketBuffer) = TaskQueryPage(List(buf.readCollectionSize(TaskPolicy.MAX_PAGE_SIZE, 10, "task details page")) { TaskDetails.readFrom(buf) }, buf.readString(TaskPolicy.MAX_CURSOR_LENGTH * 4), TaskSummary.readFrom(buf))
    }
}

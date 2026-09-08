package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

@SinceProtocol(2)
@Serializable
data class TaskPage(val items: List<WorkTask>, val nextCursor: String?) : IProto {
    init { require(items.size <= TaskPolicy.MAX_PAGE_SIZE); TaskPolicy.requireCursor(nextCursor); require(items.map { it.taskId }.distinct().size == items.size) }
    override fun writeTo(buf: PacketBuffer) {
        require(items.size <= TaskPolicy.MAX_PAGE_SIZE)
        buf.writeVarInt(items.size); items.forEach { it.writeTo(buf) }; buf.writeString(nextCursor)
    }
    companion object : IProtoReader<TaskPage> {
        override fun readFrom(buf: PacketBuffer) = TaskPage(
            List(buf.readCollectionSize(TaskPolicy.MAX_PAGE_SIZE, 10, "task page")) { WorkTask.readFrom(buf) },
            buf.readString(TaskPolicy.MAX_CURSOR_LENGTH * 4),
        )
    }
}

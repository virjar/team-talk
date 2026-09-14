package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** One weekday, with start and due on the same local day. Zone rules are resolved by the server. */
@SinceProtocol(3)
@Serializable
data class TaskWeeklyRule(val weekday: Int, val startLocalTime: String, val dueLocalTime: String, val timeZone: String) : IProto {
    init {
        require(weekday in 1..7)
        val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        require(startLocalTime.matches(time) && dueLocalTime.matches(time) && dueLocalTime > startLocalTime)
        require(timeZone.isNotBlank() && timeZone.length <= 100)
    }
    override fun writeTo(buf: PacketBuffer) { buf.writeVarInt(weekday); buf.writeString(startLocalTime); buf.writeString(dueLocalTime); buf.writeString(timeZone) }
    companion object : IProtoReader<TaskWeeklyRule> {
        override fun readFrom(buf: PacketBuffer) = TaskWeeklyRule(buf.readVarInt(), buf.readRequiredString(5), buf.readRequiredString(5), buf.readRequiredString(400))
    }
}

/** The template is fixed. Changes to an occurrence do not alter future occurrences. */
@SinceProtocol(3)
@Serializable
data class TaskSeries(val seriesId: String, val creatorUid: String, val revision: Long, val enabled: Boolean,
    val weeklyRule: TaskWeeklyRule, val nextOccurrenceAt: Long) : IProto {
    init { TaskPolicy.requireId(seriesId); require(revision > 0 && nextOccurrenceAt >= 0) }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(seriesId); buf.writeString(creatorUid); buf.writeVarLong(revision); buf.writeBoolean(enabled); weeklyRule.writeTo(buf); buf.writeVarLong(nextOccurrenceAt) }
    companion object : IProtoReader<TaskSeries> {
        override fun readFrom(buf: PacketBuffer) = TaskSeries(buf.readRequiredString(36), buf.readRequiredString(256), buf.readVarLong(), buf.readBoolean(), TaskWeeklyRule.readFrom(buf), buf.readVarLong())
    }
}

@SinceProtocol(3)
@Serializable
data class TaskSeriesCommand(val operationId: String, val issuedAt: Long, val seriesId: String, val expectedRevision: Long, val enabled: Boolean) : IProto {
    init { TaskPolicy.requireId(operationId); TaskPolicy.requireId(seriesId); require(issuedAt >= 0 && expectedRevision in 1 until Long.MAX_VALUE) }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(operationId); buf.writeVarLong(issuedAt); buf.writeString(seriesId); buf.writeVarLong(expectedRevision); buf.writeBoolean(enabled) }
    companion object : IProtoReader<TaskSeriesCommand> {
        override fun readFrom(buf: PacketBuffer) = TaskSeriesCommand(buf.readRequiredString(36), buf.readVarLong(), buf.readRequiredString(36), buf.readVarLong(), buf.readBoolean())
    }
}

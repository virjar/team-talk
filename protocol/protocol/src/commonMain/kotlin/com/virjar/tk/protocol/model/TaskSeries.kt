package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** 以首次日期为固定日历锚点；每 N 周或每 N 月生成一期，开始和截止都在同一本地日期。 */
@SinceProtocol(3)
@Serializable
data class TaskRecurrenceRule(
    val frequency: Int,
    val interval: Int,
    val firstDate: String,
    val startLocalTime: String,
    val dueLocalTime: String,
    val timeZone: String,
) : IProto {
    init {
        require(frequency == WEEKLY || frequency == MONTHLY)
        require(interval in 1..12)
        require(validDate(firstDate)) { "首次日期必须是有效的 YYYY-MM-DD 日期" }
        val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        require(startLocalTime.matches(time) && dueLocalTime.matches(time) && dueLocalTime > startLocalTime)
        require(timeZone.isNotBlank() && timeZone.length <= 100)
    }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(frequency); buf.writeVarInt(interval); buf.writeString(firstDate)
        buf.writeString(startLocalTime); buf.writeString(dueLocalTime); buf.writeString(timeZone)
    }
    companion object : IProtoReader<TaskRecurrenceRule> {
        const val WEEKLY = 1
        const val MONTHLY = 2
        override fun readFrom(buf: PacketBuffer) = TaskRecurrenceRule(buf.readVarInt(), buf.readVarInt(),
            buf.readRequiredString(10), buf.readRequiredString(5), buf.readRequiredString(5), buf.readRequiredString(400))

        private fun validDate(value: String): Boolean {
            if (!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return false
            val year = value.take(4).toInt()
            val month = value.substring(5, 7).toInt()
            val day = value.takeLast(2).toInt()
            if (year == 0 || month !in 1..12) return false
            val days = when (month) {
                2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
            return day in 1..days
        }
    }
}

/** The template is fixed. Changes to an occurrence do not alter future occurrences. */
@SinceProtocol(3)
@Serializable
data class TaskSeries(val seriesId: String, val creatorUid: String, val revision: Long, val enabled: Boolean,
    val recurrenceRule: TaskRecurrenceRule, val nextOccurrenceAt: Long) : IProto {
    init { TaskPolicy.requireId(seriesId); require(revision > 0 && nextOccurrenceAt >= 0) }
    override fun writeTo(buf: PacketBuffer) { buf.writeString(seriesId); buf.writeString(creatorUid); buf.writeVarLong(revision); buf.writeBoolean(enabled); recurrenceRule.writeTo(buf); buf.writeVarLong(nextOccurrenceAt) }
    companion object : IProtoReader<TaskSeries> {
        override fun readFrom(buf: PacketBuffer) = TaskSeries(buf.readRequiredString(36), buf.readRequiredString(256), buf.readVarLong(), buf.readBoolean(), TaskRecurrenceRule.readFrom(buf), buf.readVarLong())
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

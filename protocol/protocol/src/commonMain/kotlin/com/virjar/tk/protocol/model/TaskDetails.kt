package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.body.OfficeRefBody
import kotlinx.serialization.Serializable

/** Extension metadata lives outside the released WorkTask wire layout. */
@SinceProtocol(3)
@Serializable
data class TaskOptions(
    val descriptionFormat: Int = TEXT,
    val startsAt: Long? = null,
    val shareToGroup: Boolean = false,
    val documentRefs: List<OfficeRefBody> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
) : IProto {
    init {
        require(descriptionFormat == TEXT || descriptionFormat == MARKDOWN)
        require(startsAt == null || startsAt >= 0)
        require(documentRefs.size <= MAX_MATERIALS && attachments.size <= MAX_MATERIALS)
        require(documentRefs.all { it.isDocument })
        require(documentRefs.map { it.spaceId to it.targetId }.distinct().size == documentRefs.size)
        require(attachments.map { it.path }.distinct().size == attachments.size)
    }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(descriptionFormat); buf.writeTaskLong(startsAt); buf.writeBoolean(shareToGroup)
        buf.writeVarInt(documentRefs.size); documentRefs.forEach { it.writeTo(buf) }
        buf.writeVarInt(attachments.size); attachments.forEach { it.writeTo(buf) }
    }
    companion object : IProtoReader<TaskOptions> {
        const val TEXT = 0
        const val MARKDOWN = 1
        const val MAX_MATERIALS = 20
        override fun readFrom(buf: PacketBuffer) = TaskOptions(buf.readVarInt(), buf.readTaskLong(), buf.readBoolean(),
            List(buf.readCollectionSize(MAX_MATERIALS, 5, "task documents")) { OfficeRefBody.readFrom(buf) },
            List(buf.readCollectionSize(MAX_MATERIALS, 4, "task attachments")) { Attachment.readFrom(buf) })
    }
}

/** Unknown historical dates remain null; historyKnown distinguishes migrated tasks from new tasks. */
@SinceProtocol(3)
@Serializable
data class TaskMetrics(
    val originalDueAt: Long? = null,
    val deferralCount: Int = 0,
    val lastDeferredAt: Long? = null,
    val cycleStartedAt: Long? = null,
    val completedAt: Long? = null,
    val historyKnown: Boolean = false,
) : IProto {
    init { require(deferralCount >= 0); require(listOfNotNull(originalDueAt, lastDeferredAt, cycleStartedAt, completedAt).all { it >= 0 }) }
    val processingMillis: Long? get() = if (completedAt != null && cycleStartedAt != null) (completedAt - cycleStartedAt).coerceAtLeast(0) else null
    override fun writeTo(buf: PacketBuffer) {
        buf.writeTaskLong(originalDueAt); buf.writeVarInt(deferralCount); buf.writeTaskLong(lastDeferredAt)
        buf.writeTaskLong(cycleStartedAt); buf.writeTaskLong(completedAt); buf.writeBoolean(historyKnown)
    }
    companion object : IProtoReader<TaskMetrics> {
        override fun readFrom(buf: PacketBuffer) = TaskMetrics(buf.readTaskLong(), buf.readVarInt(), buf.readTaskLong(), buf.readTaskLong(), buf.readTaskLong(), buf.readBoolean())
    }
}

@SinceProtocol(3)
@Serializable
data class TaskDetails(
    val task: WorkTask,
    val options: TaskOptions = TaskOptions(),
    val metrics: TaskMetrics = TaskMetrics(),
    val startRemindedAt: Long? = null,
    val series: TaskSeries? = null,
    val occurrenceDate: String? = null,
) : IProto {
    init { require(startRemindedAt == null || startRemindedAt >= 0); require(occurrenceDate == null || occurrenceDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) }
    override fun writeTo(buf: PacketBuffer) {
        task.writeTo(buf); options.writeTo(buf); metrics.writeTo(buf); buf.writeTaskLong(startRemindedAt)
        buf.writeBoolean(series != null); series?.writeTo(buf); buf.writeString(occurrenceDate)
    }
    companion object : IProtoReader<TaskDetails> {
        override fun readFrom(buf: PacketBuffer) = TaskDetails(WorkTask.readFrom(buf), TaskOptions.readFrom(buf), TaskMetrics.readFrom(buf), buf.readTaskLong(),
            if (buf.readBoolean()) TaskSeries.readFrom(buf) else null, buf.readString(10))
    }
}

internal fun PacketBuffer.writeTaskLong(value: Long?) { writeBoolean(value != null); value?.let(::writeVarLong) }
internal fun PacketBuffer.readTaskLong(): Long? = if (readBoolean()) readVarLong() else null

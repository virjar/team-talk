package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 文档列表投影；正文不随列表传输，避免知识库增长后把所有 Markdown 拉入内存。 */
@Serializable
data class DocumentSummary(
    val documentId: String,
    val scopeType: Int,
    val scopeId: String,
    val title: String,
    val excerpt: String,
    val revision: Long,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(documentId)
        buf.writeVarInt(scopeType)
        buf.writeString(scopeId)
        buf.writeString(title)
        buf.writeString(excerpt)
        buf.writeVarLong(revision)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
        buf.writeString(updatedBy)
        buf.writeVarLong(updatedAt)
    }

    companion object : IProtoReader<DocumentSummary> {
        override fun readFrom(buf: PacketBuffer): DocumentSummary = DocumentSummary(
            documentId = buf.readString()!!,
            scopeType = buf.readVarInt(),
            scopeId = buf.readString()!!,
            title = buf.readString()!!,
            excerpt = buf.readString()!!,
            revision = buf.readVarLong(),
            createdBy = buf.readString()!!,
            createdAt = buf.readVarLong(),
            updatedBy = buf.readString()!!,
            updatedAt = buf.readVarLong(),
        )
    }
}

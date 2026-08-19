package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 文档空间中的 Markdown 当前快照；[revision] 是乐观并发坐标。 */
@Serializable
data class Document(
    val documentId: String,
    val spaceId: String,
    val parentId: String? = null,
    val title: String,
    val markdown: String,
    val revision: Long = 1,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(documentId)
        buf.writeString(spaceId)
        buf.writeString(parentId)
        buf.writeString(title)
        buf.writeString(markdown)
        buf.writeVarLong(revision)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
        buf.writeString(updatedBy)
        buf.writeVarLong(updatedAt)
    }

    companion object : IProtoReader<Document> {
        override fun readFrom(buf: PacketBuffer): Document = Document(
            documentId = buf.readString()!!,
            spaceId = buf.readString()!!,
            parentId = buf.readString(),
            title = buf.readString()!!,
            markdown = buf.readString()!!,
            revision = buf.readVarLong(),
            createdBy = buf.readString()!!,
            createdAt = buf.readVarLong(),
            updatedBy = buf.readString()!!,
            updatedAt = buf.readVarLong(),
        )
    }
}

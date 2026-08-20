package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 协作文档的不可变完整修订快照。 */
@Serializable
data class DocumentRevision(
    val documentId: String,
    val revision: Long,
    val title: String,
    val markdown: String,
    val editedBy: String,
    val editedAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(documentId)
        buf.writeVarLong(revision)
        buf.writeString(title)
        buf.writeString(markdown)
        buf.writeString(editedBy)
        buf.writeVarLong(editedAt)
    }

    companion object : IProtoReader<DocumentRevision> {
        override fun readFrom(buf: PacketBuffer): DocumentRevision = DocumentRevision(
            documentId = buf.readRequiredString(),
            revision = buf.readVarLong(),
            title = buf.readRequiredString(),
            markdown = buf.readRequiredString(),
            editedBy = buf.readRequiredString(),
            editedAt = buf.readVarLong(),
        )
    }
}

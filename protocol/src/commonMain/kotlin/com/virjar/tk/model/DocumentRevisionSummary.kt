package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 修订列表投影；完整 Markdown 只在用户打开某个修订时读取。 */
@Serializable
data class DocumentRevisionSummary(
    val documentId: String,
    val revision: Long,
    val title: String,
    val contentLength: Int,
    val editedBy: String,
    val editedAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(documentId)
        buf.writeVarLong(revision)
        buf.writeString(title)
        buf.writeVarInt(contentLength)
        buf.writeString(editedBy)
        buf.writeVarLong(editedAt)
    }

    companion object : IProtoReader<DocumentRevisionSummary> {
        override fun readFrom(buf: PacketBuffer): DocumentRevisionSummary = DocumentRevisionSummary(
            documentId = buf.readRequiredString(),
            revision = buf.readVarLong(),
            title = buf.readRequiredString(),
            contentLength = buf.readVarInt(),
            editedBy = buf.readRequiredString(),
            editedAt = buf.readVarLong(),
        )
    }
}

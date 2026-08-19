package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 具有稳定身份的协作文档。Markdown 是当前内容快照，[revision] 是乐观并发坐标。
 *
 * scope 先于具体群建模，避免未来组织知识库或个人空间再次复制文档领域；当前服务端只开放群空间。
 */
@Serializable
data class Document(
    val documentId: String,
    val scopeType: Int,
    val scopeId: String,
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
        buf.writeVarInt(scopeType)
        buf.writeString(scopeId)
        buf.writeString(title)
        buf.writeString(markdown)
        buf.writeVarLong(revision)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
        buf.writeString(updatedBy)
        buf.writeVarLong(updatedAt)
    }

    companion object : IProtoReader<Document> {
        const val SCOPE_GROUP_CHAT = 1

        override fun readFrom(buf: PacketBuffer): Document = Document(
            documentId = buf.readString()!!,
            scopeType = buf.readVarInt(),
            scopeId = buf.readString()!!,
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

package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 企业文档首页的轻量索引项。
 *
 * [accessedAt] 仅在“最近访问”列表中有值；“最近创建”列表使用 0。正文只返回摘要，
 * 客户端仍需通过 getDocument 按需读取完整 Markdown。
 */
@Serializable
data class DocumentHomeItem(
    val documentId: String,
    val spaceId: String,
    val spaceName: String,
    val title: String,
    val excerpt: String,
    val createdBy: String,
    val creatorName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val accessedAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(documentId)
        buf.writeString(spaceId)
        buf.writeString(spaceName)
        buf.writeString(title)
        buf.writeString(excerpt)
        buf.writeString(createdBy)
        buf.writeString(creatorName)
        buf.writeVarLong(createdAt)
        buf.writeVarLong(updatedAt)
        buf.writeVarLong(accessedAt)
    }

    companion object : IProtoReader<DocumentHomeItem> {
        override fun readFrom(buf: PacketBuffer): DocumentHomeItem = DocumentHomeItem(
            documentId = buf.readRequiredString(),
            spaceId = buf.readRequiredString(),
            spaceName = buf.readRequiredString(),
            title = buf.readRequiredString(),
            excerpt = buf.readRequiredString(),
            createdBy = buf.readRequiredString(),
            creatorName = buf.readRequiredString(),
            createdAt = buf.readVarLong(),
            updatedAt = buf.readVarLong(),
            accessedAt = buf.readVarLong(),
        )
    }
}

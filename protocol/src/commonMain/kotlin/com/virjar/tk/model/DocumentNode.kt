package com.virjar.tk.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 文档空间目录树节点。列表只携带摘要，Markdown 正文按需读取。 */
@Serializable
data class DocumentNode(
    val nodeId: String,
    val spaceId: String,
    val parentId: String? = null,
    val nodeType: Int,
    val name: String,
    val excerpt: String = "",
    val revision: Long,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(nodeId)
        buf.writeString(spaceId)
        buf.writeString(parentId)
        buf.writeVarInt(nodeType)
        buf.writeString(name)
        buf.writeString(excerpt)
        buf.writeVarLong(revision)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
        buf.writeString(updatedBy)
        buf.writeVarLong(updatedAt)
    }

    companion object : IProtoReader<DocumentNode> {
        const val TYPE_FOLDER = 1
        const val TYPE_DOCUMENT = 2

        override fun readFrom(buf: PacketBuffer): DocumentNode = DocumentNode(
            nodeId = buf.readString()!!,
            spaceId = buf.readString()!!,
            parentId = buf.readString(),
            nodeType = buf.readVarInt(),
            name = buf.readString()!!,
            excerpt = buf.readString()!!,
            revision = buf.readVarLong(),
            createdBy = buf.readString()!!,
            createdAt = buf.readVarLong(),
            updatedBy = buf.readString()!!,
            updatedAt = buf.readVarLong(),
        )
    }
}

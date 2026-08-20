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
    /** 当前文档所在目录链，顺序固定为 root → parent，不包含文档自身。 */
    val ancestorIds: List<String> = emptyList(),
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
        require(ancestorIds.size <= MAX_ANCESTOR_DEPTH) { "文档目录层级超过限制" }
        buf.writeVarInt(ancestorIds.size)
        ancestorIds.forEach(buf::writeString)
    }

    companion object : IProtoReader<Document> {
        const val MAX_ANCESTOR_DEPTH = 128

        override fun readFrom(buf: PacketBuffer): Document {
            val documentId = buf.readRequiredString()
            val spaceId = buf.readRequiredString()
            val parentId = buf.readString()
            val title = buf.readRequiredString()
            val markdown = buf.readRequiredString()
            val revision = buf.readVarLong()
            val createdBy = buf.readRequiredString()
            val createdAt = buf.readVarLong()
            val updatedBy = buf.readRequiredString()
            val updatedAt = buf.readVarLong()
            // String 的最短 wire 形态为 present + zero-length VarInt（2B）。数量与当前
            // payload 必须在建立 List 前同时验证，避免畸形小包触发大容量预分配。
            val ancestorCount = buf.readCollectionSize(
                maximum = MAX_ANCESTOR_DEPTH,
                minimumBytesPerEntry = 2,
                fieldName = "document ancestors",
            )
            return Document(
                documentId = documentId,
                spaceId = spaceId,
                parentId = parentId,
                title = title,
                markdown = markdown,
                revision = revision,
                createdBy = createdBy,
                createdAt = createdAt,
                updatedBy = updatedBy,
                updatedAt = updatedAt,
                ancestorIds = List(ancestorCount) { buf.readRequiredString() },
            )
        }
    }
}

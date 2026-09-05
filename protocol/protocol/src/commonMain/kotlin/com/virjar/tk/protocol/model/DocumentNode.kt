package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 文档空间树节点摘要。每个节点都是文档，Markdown 正文按需读取。 */
@Serializable
data class DocumentNode(
    val nodeId: String,
    val spaceId: String,
    val parentId: String? = null,
    /** 当前节点是否有活动子文档；仅用于驱动懒加载树的展开入口。 */
    val hasChildren: Boolean,
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
        buf.writeBoolean(hasChildren)
        buf.writeString(name)
        buf.writeString(excerpt)
        buf.writeVarLong(revision)
        buf.writeString(createdBy)
        buf.writeVarLong(createdAt)
        buf.writeString(updatedBy)
        buf.writeVarLong(updatedAt)
    }

    companion object : IProtoReader<DocumentNode> {
        override fun readFrom(buf: PacketBuffer): DocumentNode = DocumentNode(
            nodeId = buf.readRequiredString(),
            spaceId = buf.readRequiredString(),
            parentId = buf.readString(),
            hasChildren = buf.readBoolean("document node hasChildren"),
            name = buf.readRequiredString(),
            excerpt = buf.readRequiredString(),
            revision = buf.readVarLong(),
            createdBy = buf.readRequiredString(),
            createdAt = buf.readVarLong(),
            updatedBy = buf.readRequiredString(),
            updatedAt = buf.readVarLong(),
        )
    }
}

/**
 * 每个文档树投影的稳定兄弟排序。
 *
 * [DocumentNode.createdAt] 由服务一次性分配，重命名或移动都不会改变它。
 * [DocumentNode.nodeId] 使同一毫秒内的并发创建构成全序。
 */
val DOCUMENT_NODE_SIBLING_ORDER: Comparator<DocumentNode> = compareBy(
    DocumentNode::createdAt,
    DocumentNode::nodeId,
)

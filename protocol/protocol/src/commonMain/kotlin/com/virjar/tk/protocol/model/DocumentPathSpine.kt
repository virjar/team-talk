package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException
import kotlinx.serialization.Serializable

/** 一条完整的活跃文档路径，按从根节点到所请求目标的顺序排列。 */
@Serializable
data class DocumentPathSpine(
    val nodes: List<DocumentNode>,
) : IProto {
    init {
        validate(nodes, ::ProtocolEncodingException)
    }

    val spaceId: String get() = nodes.first().spaceId
    val targetNodeId: String get() = nodes.last().nodeId

    override fun writeTo(buf: PacketBuffer) {
        validate(nodes, ::ProtocolEncodingException)
        buf.writeVarInt(nodes.size)
        nodes.forEach { it.writeTo(buf) }
    }

    companion object : IProtoReader<DocumentPathSpine> {
        const val MAX_NODES: Int = Document.MAX_ANCESTOR_DEPTH + 1

        override fun readFrom(buf: PacketBuffer): DocumentPathSpine {
            val count = buf.readCollectionSize(
                maximum = MAX_NODES,
                minimumBytesPerEntry = 1,
                fieldName = "document path spine",
            )
            if (count == 0) throw ProtocolCorruptionException("Document path spine cannot be empty")
            val nodes = List(count) { DocumentNode.readFrom(buf) }
            validate(nodes, ::ProtocolCorruptionException)
            return DocumentPathSpine(nodes)
        }

        private inline fun validate(
            nodes: List<DocumentNode>,
            failure: (String) -> IllegalArgumentException,
        ) {
            if (nodes.isEmpty()) throw failure("Document path spine cannot be empty")
            if (nodes.size > MAX_NODES) throw failure("Document path spine exceeds the depth limit")
            val spaceId = nodes.first().spaceId
            if (spaceId.isBlank()) throw failure("Document path spine space id is invalid")
            if (nodes.first().parentId != null) {
                throw failure("Document path spine must start at a root node")
            }
            val nodeIds = hashSetOf<String>()
            nodes.forEachIndexed { index, node ->
                if (node.nodeId.isBlank() || node.spaceId != spaceId || !nodeIds.add(node.nodeId)) {
                    throw failure("Document path spine contains an invalid node identity")
                }
                if (index > 0 && node.parentId != nodes[index - 1].nodeId) {
                    throw failure("Document path spine parent chain is incomplete")
                }
                if (index < nodes.lastIndex && !node.hasChildren) {
                    throw failure("Document path spine contains a parent without children")
                }
            }
        }
    }
}

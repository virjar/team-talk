package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/** 一次移动命令提交的权威节点与根到父路径。 */
@Serializable
data class DocumentMoveResult(
    val node: DocumentNode,
    val ancestorIds: List<String>,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        node.writeTo(buf)
        require(ancestorIds.size <= Document.MAX_ANCESTOR_DEPTH) { "文档层级超过限制" }
        buf.writeVarInt(ancestorIds.size)
        ancestorIds.forEach(buf::writeString)
    }

    companion object : IProtoReader<DocumentMoveResult> {
        override fun readFrom(buf: PacketBuffer): DocumentMoveResult {
            val node = DocumentNode.readFrom(buf)
            val ancestorCount = buf.readCollectionSize(
                maximum = Document.MAX_ANCESTOR_DEPTH,
                minimumBytesPerEntry = 2,
                fieldName = "document move ancestors",
            )
            return DocumentMoveResult(
                node = node,
                ancestorIds = List(ancestorCount) { buf.readRequiredString() },
            )
        }
    }
}

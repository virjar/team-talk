package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** 最新在前、按不可变 sequence 翻页。beforeSequence=0 请求首页，nextBeforeSequence=0 表示末页。 */
@SinceProtocol(2)
@Serializable
data class DocumentCommentPage(val items: List<DocumentComment>, val nextBeforeSequence: Long) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        require(items.size <= MAX_PAGE_SIZE)
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        buf.writeVarLong(nextBeforeSequence)
    }

    companion object : IProtoReader<DocumentCommentPage> {
        const val MAX_PAGE_SIZE = 100
        const val DEFAULT_PAGE_SIZE = 50
        override fun readFrom(buf: PacketBuffer): DocumentCommentPage {
            val count = buf.readCollectionSize(MAX_PAGE_SIZE, 12, "document comments")
            return DocumentCommentPage(List(count) { DocumentComment.readFrom(buf) }, buf.readVarLong())
        }
    }
}

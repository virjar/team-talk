package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** 游标仅能原样用于同一个 kind、关键词、范围与文件类型的后续请求。 */
@SinceProtocol(2)
@Serializable
data class ContentSearchPage(val items: List<ContentSearchHit>, val nextCursor: String?) : IProto {
    init {
        validate()
    }

    private fun validate() {
        require(items.size <= ContentSearchRequest.MAX_PAGE_SIZE)
        require(nextCursor == null || nextCursor.length in 1..ContentSearchRequest.MAX_CURSOR_LENGTH)
        require(items.map { listOf(it.kind.toString(), it.scopeId, it.targetId, it.serverSeq.toString()) }.distinct().size == items.size)
    }

    override fun writeTo(buf: PacketBuffer) {
        validate()
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        buf.writeString(nextCursor)
    }

    companion object : IProtoReader<ContentSearchPage> {
        override fun readFrom(buf: PacketBuffer): ContentSearchPage {
            val count = buf.readCollectionSize(ContentSearchRequest.MAX_PAGE_SIZE, 12, "content search hits")
            return ContentSearchPage(
                List(count) { ContentSearchHit.readFrom(buf) },
                buf.readString(ContentSearchRequest.MAX_CURSOR_LENGTH * 4),
            )
        }
    }
}

package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import kotlinx.serialization.Serializable

/**
 * 一个有界的、最新在前的文档修订元数据页。
 *
 * [nextBeforeRevision] 是排他游标。值为 [END_CURSOR] 表示历史已耗尽；
 * 调用方使用 [FIRST_PAGE_CURSOR] 请求最新一页。
 */
@Serializable
data class DocumentRevisionPage(
    val items: List<DocumentRevisionSummary>,
    val nextBeforeRevision: Long,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        require(items.size <= MAX_PAGE_SIZE) { "Too many document revisions: ${items.size}" }
        buf.writeVarInt(items.size)
        items.forEach { it.writeTo(buf) }
        buf.writeVarLong(nextBeforeRevision)
    }

    companion object : IProtoReader<DocumentRevisionPage> {
        const val FIRST_PAGE_CURSOR = 0L
        const val END_CURSOR = 0L
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 100

        override fun readFrom(buf: PacketBuffer): DocumentRevisionPage {
            val count = buf.readCollectionSize(
                maximum = MAX_PAGE_SIZE,
                minimumBytesPerEntry = 9,
                fieldName = "document revision page",
            )
            return DocumentRevisionPage(
                items = List(count) { DocumentRevisionSummary.readFrom(buf) },
                nextBeforeRevision = buf.readVarLong(),
            )
        }
    }
}

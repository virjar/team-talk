package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** 每次只查询一个内容领域；空 scopeId 表示服务端裁决的全部可访问范围。 */
@SinceProtocol(2)
@Serializable
data class ContentSearchRequest(
    val kind: Int,
    val keyword: String,
    val scopeId: String = "",
    val fileType: Int = FILE_TYPE_ALL,
    val limit: Int = DEFAULT_PAGE_SIZE,
    val cursor: String? = null,
) : IProto {
    init {
        require(kind in KIND_DOCUMENT..KIND_CHAT_ATTACHMENT)
        require(keyword.length <= MAX_KEYWORD_LENGTH && keyword.none(Char::isISOControl))
        require(scopeId.length <= ContentSearchHit.MAX_SCOPE_ID_LENGTH &&
            scopeId.none { it.isWhitespace() || it.isISOControl() })
        require(fileType in FILE_TYPE_ALL..FILE_TYPE_OTHER)
        require(kind != KIND_DOCUMENT || fileType == FILE_TYPE_ALL)
        require(limit in 1..MAX_PAGE_SIZE)
        require(cursor == null || cursor.length in 1..MAX_CURSOR_LENGTH)
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(kind)
        buf.writeString(keyword)
        buf.writeString(scopeId)
        buf.writeVarInt(fileType)
        buf.writeVarInt(limit)
        buf.writeString(cursor)
    }

    companion object : IProtoReader<ContentSearchRequest> {
        const val KIND_DOCUMENT = 1
        const val KIND_GROUP_FILE = 2
        const val KIND_CHAT_ATTACHMENT = 3
        const val FILE_TYPE_ALL = 0
        const val FILE_TYPE_IMAGE = 1
        const val FILE_TYPE_VIDEO = 2
        const val FILE_TYPE_AUDIO = 3
        const val FILE_TYPE_OTHER = 4
        const val MAX_KEYWORD_LENGTH = 1_000
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 50
        const val MAX_CURSOR_LENGTH = 512

        override fun readFrom(buf: PacketBuffer): ContentSearchRequest = ContentSearchRequest(
            kind = buf.readVarInt(),
            keyword = buf.readRequiredString(MAX_KEYWORD_LENGTH * 4),
            scopeId = buf.readRequiredString(ContentSearchHit.MAX_SCOPE_ID_LENGTH * 4),
            fileType = buf.readVarInt(),
            limit = buf.readVarInt(),
            cursor = buf.readString(MAX_CURSOR_LENGTH * 4),
        )
    }
}

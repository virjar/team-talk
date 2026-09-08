package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/**
 * 当前可读内容的有界摘要，不携带正文或下载路径。
 * targetId 是文档/群文件的稳定 ID，或聊天附件 canonical path 的 SHA-256 小写 hex。
 * 聊天附件必须联合 scopeId、serverSeq、targetId 定位，不能仅按附件 hash 合并跨消息结果。
 */
@SinceProtocol(2)
@Serializable
data class ContentSearchHit(
    val kind: Int,
    val scopeId: String,
    val targetId: String,
    val title: String,
    val snippet: String,
    val scopeName: String,
    val revision: Long,
    val updatedAt: Long,
    val mimeType: String? = null,
    val size: Long? = null,
    val serverSeq: Long = 0,
) : IProto {
    init {
        require(kind in ContentSearchRequest.KIND_DOCUMENT..ContentSearchRequest.KIND_CHAT_ATTACHMENT)
        require(scopeId.length in 1..MAX_SCOPE_ID_LENGTH)
        require(targetId.length in 1..MAX_TARGET_ID_LENGTH)
        require(title.length <= MAX_TITLE_LENGTH)
        require(snippet.length <= MAX_SNIPPET_LENGTH)
        require(scopeName.length <= MAX_SCOPE_NAME_LENGTH)
        require(revision > 0 && updatedAt >= 0)
        if (kind == ContentSearchRequest.KIND_DOCUMENT) {
            require(mimeType == null && size == null && serverSeq == 0L)
        } else {
            require(mimeType != null && mimeType.length in 1..MAX_MIME_TYPE_LENGTH)
            require(size != null && size >= 0)
            if (kind == ContentSearchRequest.KIND_CHAT_ATTACHMENT) {
                require(serverSeq > 0)
                require(targetId.length == 64 && targetId.all { it in '0'..'9' || it in 'a'..'f' })
            } else {
                require(serverSeq == 0L)
            }
        }
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(kind)
        buf.writeString(scopeId)
        buf.writeString(targetId)
        buf.writeString(title)
        buf.writeString(snippet)
        buf.writeString(scopeName)
        buf.writeVarLong(revision)
        buf.writeVarLong(updatedAt)
        buf.writeString(mimeType)
        buf.writeBoolean(size != null)
        size?.let(buf::writeVarLong)
        buf.writeVarLong(serverSeq)
    }

    companion object : IProtoReader<ContentSearchHit> {
        const val MAX_SCOPE_ID_LENGTH = 36
        const val MAX_TARGET_ID_LENGTH = 64
        const val MAX_TITLE_LENGTH = 512
        const val MAX_SNIPPET_LENGTH = 500
        const val MAX_SCOPE_NAME_LENGTH = 512
        const val MAX_MIME_TYPE_LENGTH = 255

        override fun readFrom(buf: PacketBuffer): ContentSearchHit = ContentSearchHit(
            kind = buf.readVarInt(),
            scopeId = buf.readRequiredString(MAX_SCOPE_ID_LENGTH * 4),
            targetId = buf.readRequiredString(MAX_TARGET_ID_LENGTH * 4),
            title = buf.readRequiredString(MAX_TITLE_LENGTH * 4),
            snippet = buf.readRequiredString(MAX_SNIPPET_LENGTH * 4),
            scopeName = buf.readRequiredString(MAX_SCOPE_NAME_LENGTH * 4),
            revision = buf.readVarLong(),
            updatedAt = buf.readVarLong(),
            mimeType = buf.readString(MAX_MIME_TYPE_LENGTH * 4),
            size = if (buf.readBoolean("content search attachment size presence")) buf.readVarLong() else null,
            serverSeq = buf.readVarLong(),
        )
    }
}

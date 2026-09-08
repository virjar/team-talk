package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import kotlinx.serialization.Serializable

/** 文档级讨论。回复指向不可变评论身份，删除保留无正文的占位。 */
@SinceProtocol(2)
@Serializable
data class DocumentComment(
    val commentId: String,
    val spaceId: String,
    val documentId: String,
    val sequence: Long,
    val authorUid: String,
    val authorName: String,
    val replyToId: String?,
    val body: String,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
) : IProto {
    override fun writeTo(buf: PacketBuffer) {
        require(body.length <= MAX_BODY_LENGTH)
        buf.writeString(commentId)
        buf.writeString(spaceId)
        buf.writeString(documentId)
        buf.writeVarLong(sequence)
        buf.writeString(authorUid)
        buf.writeString(authorName)
        buf.writeString(replyToId)
        buf.writeString(body)
        buf.writeVarLong(revision)
        buf.writeVarLong(createdAt)
        buf.writeVarLong(updatedAt)
        buf.writeBoolean(deleted)
    }

    companion object : IProtoReader<DocumentComment> {
        const val MAX_BODY_LENGTH = 4_000
        const val MAX_COMMENTS_PER_DOCUMENT = 10_000

        override fun readFrom(buf: PacketBuffer) = DocumentComment(
            commentId = buf.readRequiredString(36),
            spaceId = buf.readRequiredString(36),
            documentId = buf.readRequiredString(36),
            sequence = buf.readVarLong(),
            authorUid = buf.readRequiredString(64),
            authorName = buf.readRequiredString(512),
            replyToId = buf.readString(36),
            body = buf.readRequiredString(MAX_BODY_LENGTH * 4).also { require(it.length <= MAX_BODY_LENGTH) },
            revision = buf.readVarLong(),
            createdAt = buf.readVarLong(),
            updatedAt = buf.readVarLong(),
            deleted = buf.readBoolean(),
        )
    }
}

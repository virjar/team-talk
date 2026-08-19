package com.virjar.tk.body

import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer

/**
 * 回复消息 Body。
 */
data class ReplyBody(
    val replyToMsgId: String,
    val replyToSenderUid: String,
    val replyToSenderName: String? = null,
    val replySnippet: String? = null,
    /** 回复者自己写的正文。 */
    val content: String = "",
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(replyToMsgId)
        buf.writeString(replyToSenderUid)
        buf.writeString(replyToSenderName)
        buf.writeString(replySnippet)
        buf.writeString(content)
    }

    companion object : IProtoReader<ReplyBody> {
        override fun readFrom(buf: PacketBuffer) = ReplyBody(
            replyToMsgId = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )!!,
            replyToSenderUid = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )!!,
            replyToSenderName = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH),
            ),
            replySnippet = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH),
            ),
            // 向后兼容：旧格式无 content 字段
            content = if (buf.readableBytes() > 0) {
                buf.readString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH)) ?: ""
            } else {
                ""
            },
        )
    }
}

/**
 * 转发消息 Body。
 */
data class ForwardBody(
    val forwardFromChatId: String? = null,
    val forwardFromMsgId: String? = null,
    val forwardFromSenderUid: String? = null,
    val forwardNote: String? = null,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(forwardFromChatId)
        buf.writeString(forwardFromMsgId)
        buf.writeString(forwardFromSenderUid)
        buf.writeString(forwardNote)
    }

    companion object : IProtoReader<ForwardBody> {
        override fun readFrom(buf: PacketBuffer) = ForwardBody(
            forwardFromChatId = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            ),
            forwardFromMsgId = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            ),
            forwardFromSenderUid = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            ),
            forwardNote = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH),
            ),
        )
    }
}

/**
 * 合并转发消息 Body。
 */
data class MergeForwardBody(
    val title: String? = null,
    val messageCount: Int = 0,
    // 合并转发的消息列表通过 MESSAGE RPC 单独拉取，这里只传摘要
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(title)
        buf.writeVarInt(messageCount)
    }

    companion object : IProtoReader<MergeForwardBody> {
        override fun readFrom(buf: PacketBuffer) = MergeForwardBody(
            title = buf.readString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH)),
            messageCount = buf.readVarInt(),
        )
    }
}

/**
 * 撤回消息 Body（无额外字段，撤回操作通过 RPC 触发）。
 */
data class RevokeBody(
    val revokedMsgId: String,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(revokedMsgId)
    }

    companion object : IProtoReader<RevokeBody> {
        override fun readFrom(buf: PacketBuffer) = RevokeBody(
            revokedMsgId = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )!!,
        )
    }
}

/**
 * 编辑消息 Body。
 */
data class EditBody(
    val editedMsgId: String,
    val newContent: String,
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(editedMsgId)
        buf.writeString(newContent)
    }

    companion object : IProtoReader<EditBody> {
        override fun readFrom(buf: PacketBuffer) = EditBody(
            editedMsgId = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )!!,
            newContent = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH),
            )!!,
        )
    }
}

/**
 * Reaction Body。
 */
data class ReactionBody(
    val targetMsgId: String,
    val emoji: String,
    val action: Int = 1,  // 1=add, 0=remove
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(targetMsgId)
        buf.writeString(emoji)
        buf.writeVarInt(action)
    }

    companion object : IProtoReader<ReactionBody> {
        override fun readFrom(buf: PacketBuffer) = ReactionBody(
            targetMsgId = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )!!,
            emoji = buf.readString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_EMOJI_LENGTH))!!,
            action = buf.readVarInt(),
        )
    }
}

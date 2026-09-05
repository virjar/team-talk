package com.virjar.tk.protocol.body

import com.virjar.tk.protocol.model.EmbeddedAsset
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
    /** [content] 中 `teamtalk-asset://` 引用的服务端权威伴随清单。 */
    val assets: List<EmbeddedAsset> = emptyList(),
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        MessageBodyPolicy.validateMarkdown(content)
        MarkdownAssetPolicy.requireCanonical(content, assets)
        buf.writeString(replyToMsgId)
        buf.writeString(replyToSenderUid)
        buf.writeString(replyToSenderName)
        buf.writeString(replySnippet)
        buf.writeString(content)
        buf.writeVarInt(assets.size)
        assets.forEach { it.writeTo(buf) }
    }

    companion object : IProtoReader<ReplyBody> {
        override fun readFrom(buf: PacketBuffer): ReplyBody {
            val replyToMsgId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )
            val replyToSenderUid = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            )
            val replyToSenderName = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_DISPLAY_NAME_LENGTH),
            )
            val replySnippet = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_SHORT_TEXT_LENGTH),
            )
            val content = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH),
            )
            MessageBodyPolicy.validateMarkdown(content)
            val assetCount = buf.readCollectionSize(
                maximum = EmbeddedAsset.MAX_ASSETS_PER_CONTENT,
                minimumBytesPerEntry = 1,
                fieldName = "reply embedded assets",
            )
            val assets = MarkdownAssetPolicy.canonicalize(
                content,
                List(assetCount) { EmbeddedAsset.readFrom(buf) },
            )
            return ReplyBody(
                replyToMsgId = replyToMsgId,
                replyToSenderUid = replyToSenderUid,
                replyToSenderName = replyToSenderName,
                replySnippet = replySnippet,
                content = content,
                assets = assets,
            )
        }
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
            revokedMsgId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            ),
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
        MarkdownAssetPolicy.requireNoInternalReferences(newContent, "编辑正文")
        buf.writeString(editedMsgId)
        buf.writeString(newContent)
    }

    companion object : IProtoReader<EditBody> {
        override fun readFrom(buf: PacketBuffer): EditBody {
            val body = EditBody(
                editedMsgId = buf.readRequiredString(
                    MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
                ),
                newContent = buf.readRequiredString(
                    MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH),
                ),
            )
            MarkdownAssetPolicy.requireNoInternalReferences(body.newContent, "编辑正文")
            return body
        }
    }
}

/**
 * Reaction Body。
 */
data class ReactionBody(
    val targetMsgId: String,
    val emoji: String,
    val action: Int = 1,  // 1=添加，0=移除
) : MessageBody {
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(targetMsgId)
        buf.writeString(emoji)
        buf.writeVarInt(action)
    }

    companion object : IProtoReader<ReactionBody> {
        override fun readFrom(buf: PacketBuffer) = ReactionBody(
            targetMsgId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_IDENTIFIER_LENGTH),
            ),
            emoji = buf.readRequiredString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_EMOJI_LENGTH)),
            action = buf.readVarInt(),
        )
    }
}

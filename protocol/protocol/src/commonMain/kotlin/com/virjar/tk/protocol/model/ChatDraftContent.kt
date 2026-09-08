package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.MessageBodyPolicy
import kotlinx.serialization.Serializable

/** Only server-ready assets cross devices. Local sources and upload jobs stay on their installation. */
@SinceProtocol(2)
@Serializable
data class ChatDraftContent(
    val markdown: String,
    val assets: List<EmbeddedAsset> = emptyList(),
    val mode: Int = 0,
    val replyToClientMsgId: String? = null,
    val replyToServerSeq: Long = 0,
) : IProto {
    init {
        MessageBodyPolicy.validateMarkdown(markdown)
        require(MarkdownAssetPolicy.canonicalize(markdown, assets) == assets) { "草稿资产清单不是规范形式" }
        require(mode in 0..2)
        require(replyToServerSeq >= 0 && ((replyToClientMsgId == null) == (replyToServerSeq == 0L)))
        replyToClientMsgId?.let { require(it.isNotBlank() && it.length <= MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH) }
    }
    override fun writeTo(buf: PacketBuffer) {
        buf.writeString(markdown); buf.writeVarInt(assets.size); assets.forEach { it.writeTo(buf) }
        buf.writeVarInt(mode); buf.writeString(replyToClientMsgId); buf.writeVarLong(replyToServerSeq)
    }
    companion object : IProtoReader<ChatDraftContent> {
        override fun readFrom(buf: PacketBuffer): ChatDraftContent {
            val markdown = buf.readRequiredString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH))
            val count = buf.readVarInt()
            require(count in 0..EmbeddedAsset.MAX_ASSETS_PER_CONTENT)
            return ChatDraftContent(markdown, List(count) { EmbeddedAsset.readFrom(buf) }, buf.readVarInt(),
                buf.readString(MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH)), buf.readVarLong())
        }
    }
}

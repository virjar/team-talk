package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.body.MessageBodyPolicy
import kotlinx.serialization.Serializable

/** 登录者持有邀请时的确认页快照；不授予消息、成员列表或群头像的读取权限。 */
@Serializable
@SinceProtocol(3)
data class InvitePreview(
    val status: Int,
    val chatId: String? = null,
    val name: String? = null,
    val memberCount: Int = 0,
    val alreadyJoined: Boolean = false,
) : IProto {
    init {
        require(status in VALID..GROUP_UNAVAILABLE)
        require((chatId == null) == (name == null))
        require(chatId == null || chatId.length == 36)
        require(name == null || name.length <= ConversationWirePolicy.MAX_CHAT_NAME_LENGTH)
        require(memberCount in 0..GroupPolicy.MAX_MEMBERS)
        require(chatId != null || (memberCount == 0 && !alreadyJoined))
        require(status != VALID || chatId != null)
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(status)
        buf.writeString(chatId)
        buf.writeString(name)
        buf.writeVarInt(memberCount)
        buf.writeBoolean(alreadyJoined)
    }

    companion object : IProtoReader<InvitePreview> {
        const val VALID = 1
        const val NOT_FOUND = 2
        const val REVOKED = 3
        const val EXPIRED = 4
        const val EXHAUSTED = 5
        const val GROUP_UNAVAILABLE = 6

        override fun readFrom(buf: PacketBuffer) = InvitePreview(
            status = buf.readVarInt(),
            chatId = buf.readString(36),
            name = buf.readString(MessageBodyPolicy.utf8WireLimit(ConversationWirePolicy.MAX_CHAT_NAME_LENGTH)),
            memberCount = buf.readVarInt(),
            alreadyJoined = buf.readBoolean("invite preview membership"),
        )
    }
}

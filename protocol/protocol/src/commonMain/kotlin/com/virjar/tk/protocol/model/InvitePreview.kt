package com.virjar.tk.protocol.model

import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.IProtoReader
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.SinceProtocol
import com.virjar.tk.protocol.body.MessageBodyPolicy
import kotlinx.serialization.Serializable
import com.virjar.tk.protocol.WireCoded

/** 登录者持有邀请时的确认页快照；不授予消息、成员列表或群头像的读取权限。 */
@Serializable
@SinceProtocol(3)
data class InvitePreview(
    val status: InvitePreviewStatus,
    val chatId: String? = null,
    val name: String? = null,
    val memberCount: Int = 0,
    val alreadyJoined: Boolean = false,
) : IProto {
    init {
        require(status !is InvitePreviewStatus.Unknown)
        require((chatId == null) == (name == null))
        require(chatId == null || chatId.length == 36)
        require(name == null || name.length <= ConversationWirePolicy.MAX_CHAT_NAME_LENGTH)
        require(memberCount in 0..GroupPolicy.MAX_MEMBERS)
        require(chatId != null || (memberCount == 0 && !alreadyJoined))
        require(status !is InvitePreviewStatus.Unknown || chatId != null)
    }

    override fun writeTo(buf: PacketBuffer) {
        buf.writeVarInt(status.code)
        buf.writeString(chatId)
        buf.writeString(name)
        buf.writeVarInt(memberCount)
        buf.writeBoolean(alreadyJoined)
    }

    companion object : IProtoReader<InvitePreview> {

        override fun readFrom(buf: PacketBuffer) = InvitePreview(
            status = InvitePreviewStatus.fromCode(buf.readVarInt()),
            chatId = buf.readString(36),
            name = buf.readString(MessageBodyPolicy.utf8WireLimit(ConversationWirePolicy.MAX_CHAT_NAME_LENGTH)),
            memberCount = buf.readVarInt(),
            alreadyJoined = buf.readBoolean("invite preview membership"),
        )
    }
}

/**
 * 邀请链接预览状态；未知取值承载为 [InvitePreviewStatus.Unknown]，预览页按不可用展示。
 */
@Serializable(with = InvitePreviewStatus.CodeSerializer::class)
sealed interface InvitePreviewStatus : WireCoded {
    data object VALID : InvitePreviewStatus {
        override val code = 1
    }
    data object NOT_FOUND : InvitePreviewStatus {
        override val code = 2
    }
    data object REVOKED : InvitePreviewStatus {
        override val code = 3
    }
    data object EXPIRED : InvitePreviewStatus {
        override val code = 4
    }
    data object EXHAUSTED : InvitePreviewStatus {
        override val code = 5
    }
    data object GROUP_UNAVAILABLE : InvitePreviewStatus {
        override val code = 6
    }

    data class Unknown(override val code: Int) : InvitePreviewStatus

    object CodeSerializer : com.virjar.tk.protocol.WireCodedSerializer<InvitePreviewStatus>("InvitePreviewStatus") {
        override fun fromCode(code: Int) = InvitePreviewStatus.fromCode(code)
    }

    companion object {
        fun fromCode(code: Int): InvitePreviewStatus = when (code) {
            VALID.code -> VALID
            NOT_FOUND.code -> NOT_FOUND
            REVOKED.code -> REVOKED
            EXPIRED.code -> EXPIRED
            EXHAUSTED.code -> EXHAUSTED
            GROUP_UNAVAILABLE.code -> GROUP_UNAVAILABLE
            else -> Unknown(code)
        }
    }
}

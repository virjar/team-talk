package com.virjar.tk.protocol.model

import kotlinx.serialization.Serializable

import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PacketBuffer
import com.virjar.tk.protocol.ProtocolCorruptionException
import com.virjar.tk.protocol.ProtocolEncodingException

@Serializable
data class Conversation(
    val chatId: String,
    val chatType: Int,
    /** 单聊的对端用户；群聊与删除事件哨兵为 null。 */
    val peerUid: String? = null,
    /** 单聊会话携带的对端快照的 revision。 */
    val peerRevision: Long? = null,
    val chatName: String? = null,
    /** 单聊对端头像。群头像不在范围内，返回 null。 */
    val chatAvatar: Attachment? = null,
    val lastMessage: String? = null,
    val lastMessageType: Int? = null,
    val lastMsgTimestamp: Long? = null,
    val lastSeq: Long = 0,
    val readSeq: Long = 0,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val peerReadSeq: Long = 0,
    val draft: String? = null,
) : IProto {

    override fun writeTo(buf: PacketBuffer) {
        ConversationWirePolicy.requireValid(this)
        buf.writeString(chatId)
        buf.writeVarInt(chatType)
        buf.writeString(peerUid)
        buf.writeBoolean(peerRevision != null)
        if (peerRevision != null) buf.writeVarLong(peerRevision)
        buf.writeString(chatName)
        buf.writeBoolean(chatAvatar != null)
        chatAvatar?.let(UserAvatarPolicy::requireCanonical)?.writeTo(buf)
        buf.writeString(lastMessage)
        // 可空的 Int/Long 使用显式的存在标记。
        buf.writeBoolean(lastMessageType != null)
        if (lastMessageType != null) {
            buf.writeVarInt(lastMessageType)
        }
        buf.writeBoolean(lastMsgTimestamp != null)
        if (lastMsgTimestamp != null) {
            buf.writeVarLong(lastMsgTimestamp)
        }
        buf.writeVarLong(lastSeq)
        buf.writeVarLong(readSeq)
        buf.writeVarInt(unreadCount)
        buf.writeBoolean(isPinned)
        buf.writeBoolean(isMuted)
        buf.writeVarLong(peerReadSeq)
        buf.writeString(draft)
    }

    companion object : com.virjar.tk.protocol.IProtoReader<Conversation> {
        override fun readFrom(buf: PacketBuffer): Conversation {
            val chatId = buf.readRequiredString(
                MessageBodyPolicy.utf8WireLimit(ConversationWirePolicy.MAX_CHAT_ID_LENGTH),
                "conversation chatId",
            )
            val chatType = buf.readVarInt()
            val peerUid = buf.readString(
                MessageBodyPolicy.utf8WireLimit(ConversationWirePolicy.MAX_PEER_UID_LENGTH),
            )
            val peerRevision = if (buf.readBoolean("conversation peer revision presence")) {
                buf.readVarLong()
            } else {
                null
            }
            val chatName = buf.readString(
                MessageBodyPolicy.utf8WireLimit(ConversationWirePolicy.MAX_CHAT_NAME_LENGTH),
            )
            val chatAvatar = if (buf.readBoolean("conversation avatar presence")) {
                UserAvatarPolicy.readFrom(buf, "conversation avatar")
            } else {
                null
            }
            val lastMessage = buf.readString(
                MessageBodyPolicy.utf8WireLimit(ConversationWirePolicy.MAX_LAST_MESSAGE_LENGTH),
            )
            val lastMessageType = if (buf.readBoolean("last message type presence")) buf.readVarInt() else null
            val lastMsgTimestamp = if (buf.readBoolean("last message timestamp presence")) buf.readVarLong() else null
            val lastSeq = buf.readVarLong()
            val readSeq = buf.readVarLong()
            val unreadCount = buf.readVarInt()
            val isPinned = buf.readBoolean("conversation pinned")
            val isMuted = buf.readBoolean("conversation muted")
            val peerReadSeq = buf.readVarLong()
            val draft = buf.readString(
                MessageBodyPolicy.utf8WireLimit(MessageBodyPolicy.MAX_MARKDOWN_LENGTH),
            )
            return Conversation(
                chatId = chatId,
                chatType = chatType,
                peerUid = peerUid,
                peerRevision = peerRevision,
                chatName = chatName,
                chatAvatar = chatAvatar,
                lastMessage = lastMessage,
                lastMessageType = lastMessageType,
                lastMsgTimestamp = lastMsgTimestamp,
                lastSeq = lastSeq,
                readSeq = readSeq,
                unreadCount = unreadCount,
                isPinned = isPinned,
                isMuted = isMuted,
                peerReadSeq = peerReadSeq,
                draft = draft,
            ).also(ConversationWirePolicy::requireDecoded)
        }
    }
}

/** 在分配或编码 Conversation 之前，先约束每个长度定界字段。 */
object ConversationWirePolicy {
    const val MAX_CHAT_ID_LENGTH = 36
    const val MAX_PEER_UID_LENGTH = 36
    const val MAX_CHAT_NAME_LENGTH = 200
    const val MAX_AVATAR_LENGTH = 500
    const val MAX_LAST_MESSAGE_LENGTH = 500

    fun requireValid(conversation: Conversation) {
        if (conversation.chatId.isEmpty() || conversation.chatId.length > MAX_CHAT_ID_LENGTH) {
            throw ProtocolEncodingException("Conversation chatId is invalid")
        }
        requirePeerUidForType(conversation, ::encodingFailure)
        requirePeerRevisionForType(conversation, ::encodingFailure)
        requireAvatarForType(conversation, ::encodingFailure)
        requireOptionalLength(conversation.chatName, MAX_CHAT_NAME_LENGTH, "chatName")
        conversation.chatAvatar?.let(UserAvatarPolicy::requireCanonical)
        requireOptionalLength(conversation.lastMessage, MAX_LAST_MESSAGE_LENGTH, "lastMessage")
        requireOptionalLength(conversation.draft, MessageBodyPolicy.MAX_MARKDOWN_LENGTH, "draft")
    }

    fun requireDecoded(conversation: Conversation) {
        if (conversation.chatId.isEmpty() || conversation.chatId.length > MAX_CHAT_ID_LENGTH) {
            throw ProtocolCorruptionException("Conversation chatId is invalid")
        }
        requirePeerUidForType(conversation, ::decodingFailure)
        requirePeerRevisionForType(conversation, ::decodingFailure)
        requireAvatarForType(conversation, ::decodingFailure)
        requireDecodedOptionalLength(conversation.chatName, MAX_CHAT_NAME_LENGTH, "chatName")
        try {
            conversation.chatAvatar?.let(UserAvatarPolicy::requireCanonical)
        } catch (_: IllegalArgumentException) {
            throw ProtocolCorruptionException("Conversation chatAvatar is invalid")
        }
        requireDecodedOptionalLength(conversation.lastMessage, MAX_LAST_MESSAGE_LENGTH, "lastMessage")
        requireDecodedOptionalLength(conversation.draft, MessageBodyPolicy.MAX_MARKDOWN_LENGTH, "draft")
    }

    private fun requireOptionalLength(value: String?, maximum: Int, fieldName: String) {
        if (value != null && value.length > maximum) {
            throw ProtocolEncodingException("Conversation $fieldName exceeds $maximum characters")
        }
    }

    private fun requireDecodedOptionalLength(value: String?, maximum: Int, fieldName: String) {
        if (value != null && value.length > maximum) {
            throw ProtocolCorruptionException("Conversation $fieldName exceeds $maximum characters")
        }
    }

    private fun requirePeerUidForType(
        conversation: Conversation,
        failure: (String) -> Nothing,
    ) {
        when (conversation.chatType) {
            1 -> if (
                conversation.peerUid.isNullOrEmpty() ||
                conversation.peerUid.length > MAX_PEER_UID_LENGTH
            ) {
                failure("Personal Conversation peerUid is invalid")
            }
            0, 2, 3 -> if (conversation.peerUid != null) {
                failure("Non-personal Conversation must not carry peerUid")
            }
            else -> failure("Conversation chatType is invalid")
        }
    }

    private fun requireAvatarForType(
        conversation: Conversation,
        failure: (String) -> Nothing,
    ) {
        if (conversation.chatType != 1 && conversation.chatAvatar != null) {
            failure("Non-personal Conversation must not carry chatAvatar")
        }
    }

    private fun requirePeerRevisionForType(
        conversation: Conversation,
        failure: (String) -> Nothing,
    ) {
        when (conversation.chatType) {
            1 -> if (conversation.peerRevision == null || conversation.peerRevision <= 0L) {
                failure("Personal Conversation peerRevision is invalid")
            }
            0, 2, 3 -> if (conversation.peerRevision != null) {
                failure("Non-personal Conversation must not carry peerRevision")
            }
            else -> Unit // chatType 的合法性由 requirePeerUidForType 优先报告。
        }
    }

    private fun encodingFailure(message: String): Nothing = throw ProtocolEncodingException(message)

    private fun decodingFailure(message: String): Nothing = throw ProtocolCorruptionException(message)
}

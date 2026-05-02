package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.*

/**
 * 新协议 PacketType — 语义拍平编码。
 *
 * 每种 PacketType 直接对应一种业务语义，payload 结构因 PacketType 而异。
 * 无 SEND/RECV 中间层，无 MessageType 二次分派。
 *
 * 编码范围：
 *   0         Reserved
 *   1-9       连接控制 (DISCONNECT, PING, PONG)
 *   10-19     会话管理 (SUBSCRIBE, UNSUBSCRIBE)
 *   20-36     消息（双向统一，Client ⇄ Server）
 *   80-81     确认应答 (SENDACK, RECVACK)
 *   90-98     系统消息（Server → Client）
 *   100-102   命令与控制（Server → Client）
 *   110-255   保留
 *
 * 注意：CONNECT/CONNACK 不在 PacketType 中，握手包有独立格式（见 Handshake.kt）。
 */
enum class PacketType(val code: Byte) {
    // 1-9 连接控制
    AUTH(1),
    AUTH_RESP(2),
    DISCONNECT(3),
    PING(4),
    PONG(5),

    // 10-19 会话管理
    SUBSCRIBE(10),
    UNSUBSCRIBE(11),
    HISTORY_LOAD(12),
    HISTORY_LOAD_END(13),

    // 20-36 消息（双向统一）
    TEXT(20),
    IMAGE(21),
    VOICE(22),
    VIDEO(23),
    FILE(24),
    LOCATION(25),
    CARD(26),
    REPLY(27),
    FORWARD(28),
    MERGE_FORWARD(29),
    REVOKE(30),
    EDIT(31),
    TYPING(32),
    STICKER(33),
    REACTION(34),
    INTERACTIVE(35),
    RICH(36),

    // 80-89 确认应答
    SENDACK(80),
    RECVACK(81),

    // 90-98 系统消息（Server → Client）
    CHANNEL_CREATED(90),
    CHANNEL_UPDATED(91),
    CHANNEL_DELETED(92),
    MEMBER_ADDED(93),
    MEMBER_REMOVED(94),
    MEMBER_MUTED(95),
    MEMBER_UNMUTED(96),
    MEMBER_ROLE_CHANGED(97),
    CHANNEL_ANNOUNCEMENT(98),

    // 100-109 命令与控制
    CMD(100),
    ACK(101),
    PRESENCE(102);

    companion object {
        private val codeMap = entries.associateBy { it.code }

        fun fromCode(code: Byte): PacketType? = codeMap[code]

        /** 消息类型 PacketType 集合（20-36），用于区分消息和非消息类型 */
        val messageTypes: Set<PacketType> by lazy {
            setOf(
                TEXT, IMAGE, VOICE, VIDEO, FILE, LOCATION, CARD, REPLY, FORWARD, MERGE_FORWARD,
                REVOKE, EDIT, TYPING, STICKER, REACTION, INTERACTIVE, RICH
            )
        }

        /** 判断 PacketType 是否为消息类型 */
        fun isMessageType(type: PacketType): Boolean = type in messageTypes

        /** PacketType → IProtoCreator 映射（非消息类型） */
        private val creatorMap by lazy {
            mapOf(
                AUTH to AuthRequestPayload.CREATOR,
                AUTH_RESP to AuthResponsePayload,
                DISCONNECT to DisconnectSignal.CREATOR,
                PING to PingSignal.CREATOR,
                PONG to PongSignal.CREATOR,
                SUBSCRIBE to SubscribePayload,
                UNSUBSCRIBE to UnsubscribePayload,
                HISTORY_LOAD to HistoryLoadPayload,
                HISTORY_LOAD_END to HistoryLoadEndPayload,
                SENDACK to SendAckPayload,
                RECVACK to RecvAckPayload,
                CHANNEL_CREATED to ChannelCreatedPayload,
                CHANNEL_UPDATED to ChannelUpdatedPayload,
                CHANNEL_DELETED to ChannelDeletedPayload,
                MEMBER_ADDED to MemberAddedPayload,
                MEMBER_REMOVED to MemberRemovedPayload,
                MEMBER_MUTED to MemberMutedPayload,
                MEMBER_UNMUTED to MemberUnmutedPayload,
                MEMBER_ROLE_CHANGED to MemberRoleChangedPayload,
                CHANNEL_ANNOUNCEMENT to ChannelAnnouncementPayload,
                CMD to CmdPayload,
                ACK to AckPayload,
                PRESENCE to PresencePayload,
            )
        }

        /** PacketType → MessageBodyCreator 映射（消息类型 20-36） */
        private val bodyCreatorMap by lazy {
            mapOf(
                TEXT to TextBody,
                IMAGE to ImageBody,
                VOICE to VoiceBody,
                VIDEO to VideoBody,
                FILE to FileBody,
                LOCATION to LocationBody,
                CARD to CardBody,
                REPLY to ReplyBody,
                FORWARD to ForwardBody,
                MERGE_FORWARD to MergeForwardBody,
                REVOKE to RevokeBody,
                EDIT to EditBody,
                TYPING to TypingBody,
                STICKER to StickerBody,
                REACTION to ReactionBody,
                INTERACTIVE to InteractiveBody,
                RICH to RichBody,
            )
        }

        /** 根据 PacketType 获取对应的 IProtoCreator（非消息类型） */
        @Suppress("UNCHECKED_CAST")
        fun <T : IProto> creatorFor(type: PacketType): IProtoCreator<T>? = creatorMap[type] as? IProtoCreator<T>

        /** 根据 PacketType 获取对应的 MessageBodyCreator（消息类型） */
        @Suppress("UNCHECKED_CAST")
        fun <T : MessageBody> bodyCreatorFor(type: PacketType): MessageBodyCreator<T>? =
            bodyCreatorMap[type] as? MessageBodyCreator<T>

        /** 根据 IProto 对象获取对应的 PacketType。 */
        fun typeFor(proto: IProto): PacketType = proto.packetType
    }
}

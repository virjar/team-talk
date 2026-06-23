package com.virjar.tk.protocol

/**
 * 通知类型枚举。
 * NOTIFY 包的 payload 内含 notifyType(1B)。
 */
enum class NotifyType(val code: Int) {
    // 联系人
    CONTACT_APPLY(1),
    CONTACT_ACCEPTED(2),
    CONTACT_DELETED(3),

    // 群组
    CHAT_CREATED(10),
    CHAT_UPDATED(11),
    CHAT_DELETED(12),
    MEMBER_ADDED(13),
    MEMBER_REMOVED(14),
    MEMBER_MUTED(15),
    MEMBER_UNMUTED(16),
    MEMBER_ROLE_CHANGED(17),

    // 消息
    MESSAGE_RECV(20),

    // 会话
    CONVERSATION_UPDATED(30),
    CONVERSATION_DELETED(31),

    // 在线状态
    PRESENCE(40),
    TYPING(41),

    // 多端同步
    READ_SYNC(50),

    // 用户
    USER_UPDATED(60),

    // 通用扩展入口：payload = GenericPayload(extensionType + data)
    GENERIC(99);

    companion object {
        private val codeMap = entries.associateBy { it.code }
        fun fromCode(code: Int): NotifyType = codeMap[code] ?: throw IllegalArgumentException("Unknown NotifyType: $code")
    }
}

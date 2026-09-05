package com.virjar.tk.protocol

/**
 * 通知类型枚举。
 * NOTIFY 包的 payload 内含 notifyType(1B)。
 */
@com.virjar.tk.protocol.SinceProtocol(0)
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
    MESSAGE_REACTION(21),
    GROUP_FILE_CHANGED(22),

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

    // 组织目录（瞬时失效提示；重连后的全量 RPC 刷新是最终兜底）
    ORGANIZATION_CHANGED(61),

    /** 连接降级投影保留原 eventId 的无 payload 标记；禁止把它持久化为新业务事件。 */
    EVENT_CURSOR_ADVANCED(62);

    companion object {
        private val codeMap = entries.associateBy { it.code }
        fun fromCode(code: Int): NotifyType = codeMap[code] ?: throw IllegalArgumentException("Unknown NotifyType: $code")
    }
}

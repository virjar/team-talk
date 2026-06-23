package com.virjar.tk.protocol

/**
 * 消息子类型枚举。
 * MESSAGE 包的 payload 内含 messageType(1B)，二级子类型设计提供 255 个空间。
 */
enum class MessageType(val code: Int) {
    TEXT(1),
    IMAGE(2),
    VOICE(3),
    VIDEO(4),
    FILE(5),
    LOCATION(6),
    CARD(7),
    REPLY(8),
    FORWARD(9),
    MERGE_FORWARD(10),
    REVOKE(11),
    EDIT(12),
    STICKER(13),
    REACTION(14),
    TYPING(15),

    // 通用扩展入口：body = GenericPayload(extensionType + data)
    GENERIC(99);

    companion object {
        private val codeMap = entries.associateBy { it.code }
        fun fromCode(code: Int): MessageType? = codeMap[code]
    }
}

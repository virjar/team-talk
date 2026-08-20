package com.virjar.tk.protocol

/**
 * 消息子类型枚举。
 * MESSAGE 包的 payload 内含 messageType(1B)，二级子类型设计提供 255 个空间。
 */
enum class MessageType(val code: Int) {
    /** Markdown 是文字消息的唯一权威源。 */
    RICH_TEXT(1),
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
    INTERACTIVE_CARD(16),

    /**
     * MESSAGE 通道的受控扩展入口，body 固定为 `GenericPayload(extensionType, opaque data)`。
     *
     * **这是与 RPC、NOTIFY 并列的刻意协议预留，不是模糊降级类型；禁止因为当前没有已登记的
     * [ExtensionType] 就删除。** 未知 extensionType 必须完整解码并保留 opaque bytes，客户端只
     * 显示安全占位；客户端创建未登记扩展时由服务端拒绝。
     */
    GENERIC(99);

    companion object {
        private val codeMap = entries.associateBy { it.code }
        fun fromCode(code: Int): MessageType? = codeMap[code]
    }
}

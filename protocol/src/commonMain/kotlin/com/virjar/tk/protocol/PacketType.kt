package com.virjar.tk.protocol

/**
 * 顶层包类型枚举。
 * 业务语义全部通过 payload 内容区分（二级子类型）。
 */
enum class PacketType(val code: Int) {
    // 连接控制 (1-5)
    AUTH(1),
    AUTH_RESP(2),
    DISCONNECT(3),
    PING(4),
    PONG(5),

    // 请求响应 (10-13)
    INVOKE(10),
    RESPONSE(11),
    STREAM_ITEM(12),
    STREAM_END(13),

    // 消息 (20-21)
    MESSAGE(20),
    MESSAGE_ACK(21),

    // 推送 (30)
    NOTIFY(30),

    // 订阅 (40-41)
    SUBSCRIBE(40),
    UNSUBSCRIBE(41);

    companion object {
        private val codeMap = entries.associateBy { it.code }
        fun fromCode(code: Int): PacketType = codeMap[code] ?: throw IllegalArgumentException("Unknown PacketType: $code")
    }
}

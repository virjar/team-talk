package com.virjar.tk.protocol

/**
 * 顶层包类型枚举。
 * 业务语义全部通过 payload 内容区分（二级子类型）。
 */
@com.virjar.tk.protocol.SinceProtocol(0)
enum class PacketType(val code: Int) {
    // 连接控制 (1-5)
    AUTH(1),
    AUTH_RESP(2),
    DISCONNECT(3),
    PING(4),
    PONG(5),

    // 认证后的持久事件同步 (6-9)
    SYNC_REQUEST(6),
    SYNC_BATCH(7),
    SYNC_READY(8),
    SYNC_RESET(9),

    // 请求响应 (10-13)
    INVOKE(10),
    RESPONSE(11),
    /**
     * 为未知长度 RPC 结果预留的 wire code；当前 client/server 没有可用的流式状态机。
     * 这是 reserved / not operational，禁止把“可解码 payload”误写成“已经支持流式 RPC”。
     */
    STREAM_ITEM(12),
    /** 与 [STREAM_ITEM] 配套的保留终止帧；当前未接入发送、聚合、取消或超时语义。 */
    STREAM_END(13),

    // 固定 bootstrap：先协商业务版本，再允许 AUTH。
    NEGOTIATE(14),
    NEGOTIATE_RESP(15),

    // 消息 (20-21)
    MESSAGE(20),
    MESSAGE_ACK(21),

    // 推送与仅连接存续期控制 (30-31)
    NOTIFY(30),
    /** 瞬时的客户端入站 trace 策略；它永远不属于持久事件同步。 */
    CONNECTION_TRACE_CONTEXT(31);

    companion object {
        private val codeMap = entries.associateBy { it.code }
        fun fromCode(code: Int): PacketType = codeMap[code] ?: throw IllegalArgumentException("Unknown PacketType: $code")
    }
}

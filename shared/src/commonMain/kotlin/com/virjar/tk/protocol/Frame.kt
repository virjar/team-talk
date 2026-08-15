package com.virjar.tk.protocol

/**
 * 帧格式常量。
 *
 * v3 帧头 = TYPE(1B) + LENGTH(4B)。magic/version 不在帧内（握手思维残留，已删）：
 * - 版本：连接级不变量，AUTH 序言魔第 3 字节校验（payload 内无重复字段）
 * - 误连/错位：TYPE 合法性 + LENGTH 上限 + 解码异常三层兜底（业界同层协议
 *   HTTP/2/MQTT/WebSocket 帧头均不带 magic）
 */
object Frame {
    const val HEADER_SIZE = 5  // type(1) + length(4)
    const val PROTOCOL_VERSION: Byte = 3
    const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024  // 16MB

    /** 客户端发送 PING 间隔（秒） */
    const val PING_INTERVAL_SECONDS: Long = 15

    /** 读空闲超时（秒），3 倍心跳间隔。超时后主动关闭触发重连 */
    const val READ_IDLE_TIMEOUT_SECONDS: Long = PING_INTERVAL_SECONDS * 3
}

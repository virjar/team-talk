package com.virjar.tk.protocol

import com.virjar.tk.protocol.Handshake.IDLE_TIMEOUT_SECONDS

/**
 * 握手包编解码。CONNECT/CONNACK 不在 PacketType 枚举中，握手是每条 TCP 连接的固定流程。
 */
object Handshake {
//    val MAGIC = byteArrayOf(0x54, 0x4B, 0x50, 0x52, 0x4F, 0x54, 0x4F) // "TKPROTO"
    const val VERSION: Byte = 1

    // ── 协议级常量（服务端和客户端必须对齐，不可作为配置参数修改） ──

    /** 单个 Packet 最大载荷字节数（16 MiB） */
    const val MAX_PACKET_SIZE = 16 * 1024 * 1024

    /** 握手包体最大字节数 */
    const val MAX_HANDSHAKE_BODY_LENGTH = 8192

    /** 握手阶段单个字符串字段最大字节长度 */
    const val MAX_HANDSHAKE_STRING_LENGTH = 1024

    // ── Request 固定头长度：MAGIC(7) + VERSION(1) + BODY_LENGTH(4) = 12 ──
    const val REQUEST_HEADER_SIZE = 12

    // ── Response 固定头长度：MAGIC(7) + VERSION(1) + CODE(1) + BODY_LENGTH(4) = 13 ──
    const val RESPONSE_HEADER_SIZE = 13

    /**
     * 服务端读空闲超时（秒）。超过此时间未收到任何数据将关闭连接。
     * 客户端应确保 PING 间隔小于此值（推荐不超过一半）。
     */
    const val IDLE_TIMEOUT_SECONDS = 60

    /**
     * 客户端写空闲超时（秒）。超过此时间未发送数据将触发 PING。
     * 必须小于 [IDLE_TIMEOUT_SECONDS] 以确保服务端不会因超时断开。
     */
    const val CLIENT_PING_INTERVAL_SECONDS = 30

    // CONNACK 状态码
    const val CODE_OK: Byte = 0
    const val CODE_AUTH_FAILED: Byte = 1
    const val CODE_VERSION_UNSUPPORTED: Byte = 2
    const val CODE_SERVER_MAINTENANCE: Byte = 3
    const val CODE_DEVICE_BANNED: Byte = 4
    const val CODE_TOO_MANY_CONNECTIONS: Byte = 5

}


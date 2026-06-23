package com.virjar.tk.protocol

/**
 * 帧格式常量
 */
object Frame {
    const val MAGIC_HIGH: Byte = 0x54  // 'T'
    const val MAGIC_LOW: Byte = 0x4B   // 'K'
    const val HEADER_SIZE = 8  // magic(2) + version(1) + type(1) + length(4)
    const val PROTOCOL_VERSION: Byte = 1
    const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024  // 16MB

    /** 客户端发送 PING 间隔（秒） */
    const val PING_INTERVAL_SECONDS: Long = 15

    /** 读空闲超时（秒），3 倍心跳间隔。超时后主动关闭触发重连 */
    const val READ_IDLE_TIMEOUT_SECONDS: Long = PING_INTERVAL_SECONDS * 3
}

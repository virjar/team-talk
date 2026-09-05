package com.virjar.tk.protocol

/**
 * 与传输层无关的线格式标识与分配上限。
 *
 * 这些值归 contract 模块所有；传输适配器可以在连接未认证期间执行更严格的上限，
 * 但绝不允许接纳超过 [MAX_PAYLOAD_SIZE] 的 payload。
 */
object ProtocolLimits {
    /** AUTH 固定序言中的错位识别字节；业务协议身份只通过 NEGOTIATE 协商。 */
    const val AUTH_PREAMBLE_MARKER: Byte = 0

    /** 编码后 payload 的最大值，不含传输帧头。 */
    const val MAX_PAYLOAD_SIZE: Int = 16 * 1024 * 1024

    /** 认证帧没有任何正当理由接近已认证连接的预算。 */
    const val MAX_UNAUTHENTICATED_PAYLOAD_SIZE: Int = 4 * 1024
}

/** 字节序列无法被解释为 canonical 的 TeamTalk 协议值。 */
open class ProtocolCorruptionException(message: String) : IllegalArgumentException(message)

/** 本地产生的值无法放进协议固定的 payload 预算内。 */
class ProtocolEncodingException(message: String) : IllegalArgumentException(message)

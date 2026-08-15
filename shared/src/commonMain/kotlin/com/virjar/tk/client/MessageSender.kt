package com.virjar.tk.client

import com.virjar.tk.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload

/**
 * 消息发送抽象。隔离上层（ViewModel/Repository）对 [ImClient] 的直接依赖——
 * 发送消息并等待服务端 ACK 是唯一需要直达连接层的操作（非 RPC invoke），
 * 抽出此接口让 owner-driven 的数据流单向：ViewModel → Repository → 本接口 → ImClient。
 */
fun interface MessageSender {
    /** 发送消息并阻塞等待服务端 ACK。 */
    suspend fun sendAndWaitAck(message: Message): MessageAckPayload
}

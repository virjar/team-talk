package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.payload.MessageAckPayload

/**
 * [SendQueue] 到连接层的窄端口：发送一条消息并等待服务端 ACK（非 RPC invoke）。
 * [ClientSession] 将它绑定到本次会话的 [ImClient]，UI 与无头 SDK 通过持久队列提交消息。
 */
fun interface MessageSender {
    /** 发送消息并挂起等待服务端 ACK。 */
    suspend fun sendAndWaitAck(message: Message): MessageAckPayload
}

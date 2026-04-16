package com.virjar.tk.tcp.agent

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.SendAckCode
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.SendAckPayload
import com.virjar.tk.service.MessageDeliveryService
import com.virjar.tk.service.MessageService
import com.virjar.tk.store.ChannelStore
import com.virjar.tk.tcp.IOExecutor
import com.virjar.tk.tcp.ImAgent
import java.util.*

/**
 * 消息处理器：处理所有 Message 类型（TEXT/IMAGE/VOICE 等）和 EDIT。
 *
 * 职责：禁言检查 → 消息存储 → SendAck → 调用 DeliveryService
 */
object MessageDispatcher {
    private lateinit var messageService: MessageService
    private lateinit var channelStore: ChannelStore

    fun init(messageService: MessageService, channelStore: ChannelStore) {
        this.messageService = messageService
        this.channelStore = channelStore
    }

    fun handleMessage(agent: ImAgent, message: Message) {
        val senderUid = agent.uid
        val packetType = message.packetType

        agent.recorder.record { "[SEND] type=$packetType channelId=${message.channelId} clientMsgNo=${message.clientMsgNo}" }

        // 群组频道禁言检查
        if (message.channelType == ChannelType.GROUP) {
            val mutedAll = channelStore.isMutedAll(message.channelId)
            val memberMuted = channelStore.isMemberMuted(message.channelId, senderUid)
            if (mutedAll || memberMuted) {
                val senderRole = channelStore.getMemberRole(message.channelId, senderUid) ?: 0
                if (senderRole < 1) {
                    agent.send(
                        SendAckPayload(
                            messageId = "",
                            clientMsgNo = message.clientMsgNo,
                            clientSeq = message.clientSeq,
                            serverSeq = 0,
                            code = SendAckCode.NO_PERMISSION,
                        )
                    )
                    agent.recorder.record { "[MUTED] blocked: channelId=${message.channelId}" }
                    return
                }
            }
        }

        // 补充服务端字段：messageId、senderUid、timestamp
        val enriched = Message(
            header = message.header.copy(
                messageId = message.messageId ?: UUID.randomUUID().toString().replace("-", ""),
                senderUid = senderUid,
                timestamp = if (message.timestamp == 0L) System.currentTimeMillis() else message.timestamp,
            ),
            body = message.body,
        )

        IOExecutor.launch {
            val stored = messageService.sendMessage(enriched)
            agent.send(
                SendAckPayload(
                    messageId = stored.messageId ?: "",
                    clientMsgNo = message.clientMsgNo,
                    clientSeq = message.clientSeq,
                    serverSeq = stored.serverSeq,
                    code = 0,
                )
            )

            agent.recorder.record { "[SENDACK] clientMsgNo=${message.clientMsgNo} serverSeq=${stored.serverSeq} msgId=${stored.messageId}" }
            MessageDeliveryService.deliver(stored, agent)
        }

    }

    fun handleEdit(agent: ImAgent, message: Message) {
        // todo 没看懂这里是啥意思
        agent.recorder.record { "[EDIT] received client-sent EDIT, ignoring (use HTTP API)" }
    }
}

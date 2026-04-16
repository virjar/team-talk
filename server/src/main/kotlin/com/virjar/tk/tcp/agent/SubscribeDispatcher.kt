package com.virjar.tk.tcp.agent

import com.virjar.tk.protocol.payload.CmdPayload
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.SubscribePayload
import com.virjar.tk.service.MessageService
import com.virjar.tk.tcp.ImAgent

/**
 * 订阅处理器：处理 SUBSCRIBE 请求。
 *
 * 职责：离线消息补拉、批量发送
 */
object SubscribeDispatcher {
    private lateinit var messageService: MessageService

    fun init(messageService: MessageService) {
        this.messageService = messageService
    }

    fun handleSubscribe(agent: ImAgent, subscribe: SubscribePayload) {
        val targetChannelId = subscribe.channelId
        val lastSeq = subscribe.lastSeq

        agent.recorder.record { "[SUBSCRIBE] channelId=$targetChannelId lastSeq=$lastSeq" }

        val messages: List<Message> = if (lastSeq > 0) {
            messageService.getMessagesAfterSeq(targetChannelId, lastSeq, 101)
        } else {
            messageService.getLatestMessages(targetChannelId, 101)
        }

        if (messages.isEmpty()) {
            agent.recorder.record { "[SUBSCRIBE] no offline messages for channel=$targetChannelId" }
            return
        }

        val truncated = messages.size > 100
        val toSend = if (truncated) messages.takeLast(100) else messages

        for (msg in toSend) {
            agent.write(msg)
        }

        if (truncated) {
            agent.write(CmdPayload("sync_truncate", """{"channelId":"$targetChannelId"}"""))
        }
        agent.flush()

        agent.recorder.record { "[SUBSCRIBE] sent ${toSend.size} messages for channel=$targetChannelId (truncated=$truncated)" }
    }
}

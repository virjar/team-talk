package com.virjar.tk.tcp.agent

import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.MessageHeader
import com.virjar.tk.protocol.payload.TypingBody
import com.virjar.tk.store.ChannelStore
import com.virjar.tk.tcp.ClientRegistry
import com.virjar.tk.tcp.ImAgent

/**
 * 输入状态处理器：处理 TYPING 消息。
 *
 * 职责：输入状态转发给频道其他成员
 */
object TypingDispatcher {
    private lateinit var channelStore: ChannelStore

    fun init(channelStore: ChannelStore) {
        this.channelStore = channelStore
    }

    fun handleTyping(agent: ImAgent, message: Message) {
        val senderUid = agent.uid
        val typingBody = message.body as TypingBody
        agent.recorder.record { "[TYPING] channelId=${message.channelId} action=${typingBody.action}" }

        val targetChannelId = message.channelId
        val memberUidList = channelStore.getMemberUids(targetChannelId)

        for (uid in memberUidList) {
            if (uid == senderUid) continue
            ClientRegistry.doWithUserAgentGroup(uid) { group ->
                val forwardMessage = Message(
                    MessageHeader(
                        channelId = targetChannelId,
                        clientMsgNo = message.clientMsgNo,
                        clientSeq = message.clientSeq,
                        senderUid = senderUid,
                        channelType = message.channelType,
                        timestamp = System.currentTimeMillis(),
                    ),
                    typingBody,
                )
                for (targetAgent in group.allAgent.values) {
                    if (!targetAgent.isActive) continue
                    targetAgent.send(forwardMessage)
                }
            }
        }
    }
}

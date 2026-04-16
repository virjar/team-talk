package com.virjar.tk.service

import com.virjar.tk.protocol.payload.EditBody
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.MessageHeader
import com.virjar.tk.store.ChannelStore
import com.virjar.tk.tcp.ClientRegistry
import com.virjar.tk.tcp.ImAgent
import org.slf4j.LoggerFactory

/**
 * 消息投递服务：将已存储的消息实时推送给频道中的在线成员。
 */
object MessageDeliveryService {
    private val logger = LoggerFactory.getLogger(MessageDeliveryService::class.java)
    private lateinit var channelStore: ChannelStore

    fun init(channelStore: ChannelStore) {
        this.channelStore = channelStore
    }

    /**
     * 将消息投递给频道中所有在线成员。
     *
     * 每个 agent 只写一条消息，直接 send（writeAndFlush），无需批量 flush。
     */
    fun deliver(message: Message, ignoreAgent: ImAgent) {
        val memberUidList = channelStore.getMemberUids(message.channelId)

        if (memberUidList.isEmpty()) return

        for (uid in memberUidList) {
            ClientRegistry.doWithUserAgentGroup(uid) { group ->
                for (agent in group.allAgent.values) {
                    if (agent == ignoreAgent) continue
                    if (!agent.isActive) continue
                    agent.send(message)
                }
            }
        }

        // 多端同步：发送者不在 memberUids 中时（理论上不应发生），也确保其他设备收到
        val senderUid = message.senderUid
        if (senderUid != null && senderUid !in memberUidList) {
            ClientRegistry.doWithUserAgentGroup(senderUid) { group ->
                for (agent in group.allAgent.values) {
                    if (agent == ignoreAgent) continue
                    if (!agent.isActive) continue
                    agent.send(message)
                }
            }
        }
    }

    /**
     * 将 EDIT 操作广播给频道中所有在线成员。
     */
    fun deliverEdit(message: Message) {
        val memberUidList = channelStore.getMemberUids(message.channelId)

        if (memberUidList.isEmpty()) return

        val editHeader = MessageHeader(
            channelId = message.channelId,
            messageId = message.messageId,
            senderUid = message.senderUid,
            channelType = message.channelType,
            serverSeq = message.serverSeq,
            timestamp = message.timestamp,
        )
        val editBody = EditBody(
            targetMessageId = message.messageId ?: "",
            newContent = kotlinx.serialization.json.Json.encodeToString(message.body.toJson()),
            editedAt = System.currentTimeMillis(),
        )
        val editMessage = Message(editHeader, editBody)

        for (uid in memberUidList) {
            ClientRegistry.doWithUserAgentGroup(uid) { group ->
                for (agent in group.allAgent.values) {
                    if (!agent.isActive) continue
                    agent.send(editMessage)
                }
            }
        }
    }
}

package com.virjar.tk.service

import com.virjar.tk.db.MessageStore
import com.virjar.tk.dto.ConversationDto
import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.payload.CmdPayload
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.store.ChannelStore
import com.virjar.tk.store.ConversationStore
import com.virjar.tk.store.UserStore
import com.virjar.tk.tcp.ClientRegistry
import org.slf4j.LoggerFactory

class ConversationService(
    private val messageStore: MessageStore? = null,
    private val channelStore: ChannelStore,
    private val userStore: UserStore,
    private val conversationStore: ConversationStore,
) {
    private val logger = LoggerFactory.getLogger(ConversationService::class.java)
    suspend fun syncConversations(uid: String, version: Long = 0): List<ConversationDto> {
        return conversationStore.findByUser(uid, version).map { row ->
            val channelName = resolveChannelName(row.channelId, row.channelType, uid)
            val lastMsg = if (row.lastMsgSeq > 0 && messageStore != null) {
                messageStore.getMessageBySeq(row.channelId, row.lastMsgSeq)
            } else null

            ConversationDto(
                channelId = row.channelId,
                channelType = row.channelType.code,
                lastMsgSeq = row.lastMsgSeq,
                unreadCount = row.unreadCount,
                readSeq = row.readSeq,
                isMuted = row.isMuted,
                isPinned = row.isPinned,
                draft = row.draft,
                version = row.version,
                channelName = channelName,
                lastMessage = lastMsg?.let { Message.extractPreviewText(it.body) } ?: "",
                lastMessageType = lastMsg?.packetType?.code?.toInt() ?: 0,
                lastMsgTimestamp = lastMsg?.timestamp ?: 0L,
            )
        }
    }

    private fun resolveChannelName(channelId: String, channelType: ChannelType, currentUid: String): String {
        return when (channelType) {
            ChannelType.PERSONAL -> {
                // Personal channel "p:uid1:uid2" -> resolve peer's name
                val parts = channelId.split(":")
                if (parts.size >= 3) {
                    val peerUid = parts.drop(1).first { it != currentUid }
                    userStore.findByUid(peerUid)?.name
                        ?: peerUid.take(12)
                } else channelId
            }
            ChannelType.GROUP -> {
                // Group channel -> get group name from ChannelStore
                channelStore.findByChannelId(channelId)?.name
                    ?.ifEmpty { channelId.removePrefix("group:").take(20) }
                    ?: channelId.take(20)
            }
            else -> channelId.take(20)
        }
    }

    suspend fun markRead(uid: String, channelId: String, readSeq: Long) {
        conversationStore.markRead(uid, channelId, readSeq)

        // 多设备已读同步：向同用户其他在线设备推送 CMD(read_sync)
        val agents = ClientRegistry.getAgentsByUid(uid)
        if (agents.isEmpty()) return
        val payload = """{"channelId":"$channelId","readSeq":$readSeq}"""
        val proto = CmdPayload("read_sync", payload)
        for (agent in agents) {
            agent.send(proto)
        }
    }

    suspend fun updateDraft(uid: String, channelId: String, draft: String) {
        conversationStore.updateDraft(uid, channelId, draft)
    }

    suspend fun deleteConversation(uid: String, channelId: String) {
        conversationStore.delete(uid, channelId)
    }

    suspend fun updatePin(uid: String, channelId: String, pinned: Boolean) {
        conversationStore.updatePin(uid, channelId, pinned)
    }

    suspend fun updateMute(uid: String, channelId: String, muted: Boolean) {
        conversationStore.updateMute(uid, channelId, muted)
    }
}

package com.virjar.tk.service

import com.virjar.tk.api.BusinessException
import com.virjar.tk.db.MessageStore
import com.virjar.tk.protocol.MessageErrorCode
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.Message
import com.virjar.tk.protocol.payload.MessageHeader
import com.virjar.tk.protocol.payload.TextBody
import com.virjar.tk.store.ChannelStore
import com.virjar.tk.store.ConversationStore
import com.virjar.tk.store.UserStore
import io.ktor.http.*
import org.slf4j.LoggerFactory

class MessageService(
    private val messageStore: MessageStore,
    private val searchIndex: SearchIndex? = null,
    private val channelStore: ChannelStore,
    private val userStore: UserStore,
    private val conversationStore: ConversationStore,
) {
    private val logger = LoggerFactory.getLogger(MessageService::class.java)

    suspend fun sendMessage(message: Message): Message {
        val stored = messageStore.storeMessage(message)

        try {
            channelStore.incrementMaxSeq(stored.channelId)
            val memberUidList = channelStore.getMemberUids(stored.channelId)
            for (uid in memberUidList) {
                conversationStore.createOrUpdate(uid, stored.channelId, stored.channelType, stored.serverSeq)
                if (uid != stored.senderUid) {
                    conversationStore.incrementUnread(uid, stored.channelId)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to update channel/conversation stats for {}: {}", stored.channelId, e.message)
        }

        logger.debug("Message stored: {} in channel {} seq {}", stored.messageId, stored.channelId, stored.serverSeq)

        // 写入搜索索引
        try {
            searchIndex?.indexMessage(stored)
        } catch (e: Exception) {
            logger.warn("Failed to index message {}: {}", stored.messageId, e.message)
        }

        return stored
    }

    fun getMessagesAfterSeq(channelId: String, afterSeq: Long, limit: Int): List<Message> {
        return messageStore.getMessagesAfterSeq(channelId, afterSeq, limit)
    }

    fun getLatestMessages(channelId: String, limit: Int): List<Message> {
        return messageStore.getLatestMessages(channelId, limit)
    }

    fun getMessagesBeforeSeq(channelId: String, beforeSeq: Long, limit: Int = 50): List<Message> {
        return messageStore.getMessagesBeforeSeq(channelId, beforeSeq, limit)
    }

    suspend fun revokeMessage(channelId: String, seq: Long, operatorUid: String) {
        val message = messageStore.getMessageBySeq(channelId, seq)
            ?: throw BusinessException(MessageErrorCode.MESSAGE_NOT_FOUND, "message not found", HttpStatusCode.NotFound)
        if (message.senderUid == operatorUid) {
            // 撤回自己的消息，始终允许
        } else {
            // 撤回他人消息，需要 admin+ 权限
            val operatorRole = channelStore.getMemberRole(channelId, operatorUid)
                ?: throw BusinessException(MessageErrorCode.REVOKE_NOT_SENDER, "not a member", HttpStatusCode.Forbidden)
            if (operatorRole < 1) {
                throw BusinessException(MessageErrorCode.REVOKE_NOT_SENDER, "insufficient permission to revoke others' messages", HttpStatusCode.Forbidden)
            }
            // admin 只能撤回 member(0) 的消息，不能撤回 admin/owner 的
            val senderRole = channelStore.getMemberRole(channelId, message.senderUid ?: "") ?: 0
            if (operatorRole < 2 && senderRole >= 1) {
                throw BusinessException(MessageErrorCode.REVOKE_NOT_SENDER, "cannot revoke admin or owner's messages", HttpStatusCode.Forbidden)
            }
        }
        messageStore.deleteMessage(channelId, seq)
        searchIndex?.deleteMessage(message.messageId ?: "")
    }

    /**
     * 编辑文本消息：接收纯文本 newText，更新 body 和 flags。
     */
    suspend fun editMessage(channelId: String, seq: Long, newText: String, operatorUid: String): Message {
        val message = messageStore.getMessageBySeq(channelId, seq)
            ?: throw BusinessException(MessageErrorCode.MESSAGE_NOT_FOUND, "message not found", HttpStatusCode.NotFound)
        if (message.senderUid != operatorUid)
            throw BusinessException(MessageErrorCode.EDIT_NOT_SENDER, "can only edit own messages", HttpStatusCode.Forbidden)
        if (message.packetType != PacketType.TEXT)
            throw BusinessException(MessageErrorCode.EDIT_NOT_TEXT, "can only edit text messages", HttpStatusCode.BadRequest)

        val newBody = TextBody(newText, emptyList())
        val newFlags = message.flags or 1 // edited

        val updated = Message(
            header = message.header.copy(flags = newFlags),
            body = newBody,
        )

        val result = messageStore.updateMessage(channelId, seq, updated)
            ?: throw BusinessException(MessageErrorCode.MESSAGE_NOT_FOUND, "message not found after update", HttpStatusCode.InternalServerError)

        try { searchIndex?.indexMessage(result) } catch (_: Exception) {}
        return result
    }

    fun getMessageBySeq(channelId: String, seq: Long): Message? {
        return messageStore.getMessageBySeq(channelId, seq)
    }

    // ================================================================
    // 搜索支持（供 MessageRoutes 使用）
    // ================================================================

    fun getUserChannelIds(uid: String): Set<String> {
        return channelStore.findByUser(uid).map { it.channelId }.toSet()
    }

    fun getChannelName(channelId: String): String {
        return channelStore.findByChannelId(channelId)?.name ?: ""
    }

    fun getUserName(uid: String): String {
        return userStore.findByUid(uid)?.name ?: ""
    }
}

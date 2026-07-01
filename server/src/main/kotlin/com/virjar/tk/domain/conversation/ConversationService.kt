package com.virjar.tk.domain.conversation

import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.model.Conversation
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ReadSyncPayload

class ConversationService(
    private val conversationRepo: ConversationRepository,
    private val chatRepo: ChatRepository,
    private val syncEventService: SyncEventService,
) {

    fun listConversations(uid: String): List<Conversation> {
        return conversationRepo.listConversations(uid)
    }

    fun syncConversations(uid: String, afterVersion: Long): List<Conversation> {
        return conversationRepo.getConversationsAfter(uid, afterVersion)
    }

    suspend fun setDraft(uid: String, chatId: String, draft: String?) {
        conversationRepo.setDraft(uid, chatId, draft)
        val conv = conversationRepo.getConversation(uid, chatId) ?: return
        syncEventService.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
    }

    suspend fun setPin(uid: String, chatId: String, pinned: Boolean) {
        conversationRepo.setPin(uid, chatId, pinned)
        val conv = conversationRepo.getConversation(uid, chatId) ?: return
        syncEventService.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
    }

    suspend fun setMute(uid: String, chatId: String, muted: Boolean) {
        conversationRepo.setMute(uid, chatId, muted)
        val conv = conversationRepo.getConversation(uid, chatId) ?: return
        syncEventService.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
    }

    suspend fun deleteConversation(uid: String, chatId: String) {
        conversationRepo.deleteConversation(uid, chatId)
        // 哨兵 Conversation：CONVERSATION_DELETED 通知客户端只用 chatId 定位删除目标，
        // chatType=0 是占位值（合法值为 1=PERSONAL/2=GROUP），客户端不应读取它。
        val conv = Conversation(chatId = chatId, chatType = 0)
        syncEventService.emitEvent(uid, NotifyType.CONVERSATION_DELETED, conv)
    }

    suspend fun markRead(uid: String, chatId: String, readSeq: Long) {
        conversationRepo.markRead(uid, chatId, readSeq)
        val conv = conversationRepo.getConversation(uid, chatId) ?: return
        syncEventService.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)

        // 通知会话中其他成员：uid 已读到 readSeq（实现已读回执）
        val members = chatRepo.getMemberUids(chatId)
        for (memberUid in members) {
            if (memberUid == uid) continue
            syncEventService.emitEvent(memberUid, NotifyType.READ_SYNC,
                ReadSyncPayload(uid, chatId, readSeq))
        }
    }

    /**
     * 消息到达时更新会话（由 MessageService 调用）。
     */
    suspend fun onMessageReceived(chatId: String, chatType: Int, lastMsgSeq: Long, lastMsgType: Int, lastMsgPreview: String?, memberUids: List<String>) {
        for (uid in memberUids) {
            conversationRepo.upsertConversation(uid, chatId, chatType, lastMsgSeq, lastMsgType, lastMsgPreview)
            val conv = conversationRepo.getConversation(uid, chatId) ?: continue
            syncEventService.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
        }
    }

    /**
     * 为所有成员预创建会话行（建群/建私聊时调用）。
     * 确保 markRead 有行可更新，readSeq 可靠持久化（多设备同步基础）。
     */
    fun ensureConversations(chatId: String, chatType: Int, memberUids: List<String>) {
        for (uid in memberUids) {
            conversationRepo.ensureConversation(uid, chatId, chatType)
        }
    }
}

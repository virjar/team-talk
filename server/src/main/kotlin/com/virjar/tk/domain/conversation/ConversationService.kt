package com.virjar.tk.domain.conversation

import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.domain.chat.ChatLifecycleGate
import com.virjar.tk.domain.transaction.PgUnitOfWork
import com.virjar.tk.model.Conversation
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ReadSyncPayload

class ConversationService(
    private val conversationRepo: ConversationRepository,
    private val lifecycleGate: ChatLifecycleGate,
    private val unitOfWork: PgUnitOfWork,
) {

    fun listConversations(uid: String): List<Conversation> {
        return conversationRepo.listConversations(uid)
    }

    suspend fun setDraft(uid: String, chatId: String, draft: String?) =
        lifecycleGate.withChat(chatId) { setDraftInternal(uid, chatId, draft) }

    private suspend fun setDraftInternal(uid: String, chatId: String, draft: String?) {
        // 草稿与最终消息共享 Markdown 资源预算。禁止截断：截断 fence/table 会制造
        // 无法预览的损坏源码；非法或超限草稿应明确拒绝，由本地缓存保留完整内容。
        val validatedDraft = draft?.let(MessageBodyPolicy::validateMarkdown)
        unitOfWork.write {
            val conv = conversationRepo.setDraft(transaction, uid, chatId, validatedDraft)
            appendEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
        }
    }

    suspend fun setPin(uid: String, chatId: String, pinned: Boolean) =
        lifecycleGate.withChat(chatId) { setPinInternal(uid, chatId, pinned) }

    private suspend fun setPinInternal(uid: String, chatId: String, pinned: Boolean) {
        unitOfWork.write {
            val conv = conversationRepo.setPin(transaction, uid, chatId, pinned)
            appendEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
        }
    }

    suspend fun setMute(uid: String, chatId: String, muted: Boolean) =
        lifecycleGate.withChat(chatId) { setMuteInternal(uid, chatId, muted) }

    private suspend fun setMuteInternal(uid: String, chatId: String, muted: Boolean) {
        unitOfWork.write {
            val conv = conversationRepo.setMute(transaction, uid, chatId, muted)
            appendEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
        }
    }

    suspend fun deleteConversation(uid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        unitOfWork.write {
            if (conversationRepo.deleteConversation(transaction, uid, chatId)) {
                appendEvent(uid, NotifyType.CONVERSATION_DELETED, deletedConversation(chatId))
            }
        }
    }

    /** Internal membership/lifecycle cleanup; caller already holds the chat lifecycle gate. */
    internal suspend fun deleteConversationProjection(uid: String, chatId: String) {
        unitOfWork.write {
            conversationRepo.deleteConversationProjection(transaction, uid, chatId)
            // This tombstone represents a committed membership/chat lifecycle change, not a
            // user retry. Chat currently may remove the projection with that upstream fact, but
            // the client still needs CONVERSATION_DELETED to clear its durable local view.
            appendEvent(uid, NotifyType.CONVERSATION_DELETED, deletedConversation(chatId))
        }
    }

    suspend fun markRead(uid: String, chatId: String, readSeq: Long) =
        lifecycleGate.withChat(chatId) { markReadInternal(uid, chatId, readSeq) }

    private suspend fun markReadInternal(uid: String, chatId: String, readSeq: Long) {
        unitOfWork.write {
            // maxSeq validation, the actor watermark, every peer watermark and all durable events
            // share one locked database snapshot. A concurrent lower cursor therefore cannot win.
            val mutation = conversationRepo.markRead(transaction, uid, chatId, readSeq)
            val authoritativeReadSeq = mutation.conversation.readSeq
            if (mutation.actorChanged) {
                appendEvent(uid, NotifyType.CONVERSATION_UPDATED, mutation.conversation)
            }
            mutation.advancedPeerUids.forEach { memberUid ->
                appendEvent(
                    memberUid,
                    NotifyType.READ_SYNC,
                    ReadSyncPayload(uid, chatId, authoritativeReadSeq),
                )
            }
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

    private fun deletedConversation(chatId: String): Conversation {
        // 哨兵 Conversation：CONVERSATION_DELETED 通知客户端只用 chatId 定位删除目标，
        // chatType=0 是占位值（合法值为 1=PERSONAL/2=GROUP），客户端不应读取它。
        return Conversation(chatId = chatId, chatType = 0)
    }
}

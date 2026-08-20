package com.virjar.tk.domain.conversation

import com.virjar.tk.body.MessageBodyPolicy
import com.virjar.tk.domain.chat.ChatAccess
import com.virjar.tk.domain.chat.ChatLifecycleGate
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.chat.ChatStore
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.model.Conversation
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ReadSyncPayload

class ConversationService(
    private val conversationRepo: ConversationRepository,
    private val chatRepo: ChatRepository,
    private val events: EventPublisher,
    private val access: ChatAccess,
    private val chatStore: ChatStore,
    private val lifecycleGate: ChatLifecycleGate,
) {

    fun listConversations(uid: String): List<Conversation> {
        return conversationRepo.listConversations(uid)
    }

    fun syncConversations(uid: String, afterVersion: Long): List<Conversation> {
        return conversationRepo.getConversationsAfter(uid, afterVersion)
    }

    suspend fun setDraft(uid: String, chatId: String, draft: String?) =
        lifecycleGate.withChat(chatId) { setDraftInternal(uid, chatId, draft) }

    private suspend fun setDraftInternal(uid: String, chatId: String, draft: String?) {
        access.requireMember(uid, chatId)
        // 草稿与最终消息共享 Markdown 资源预算。禁止截断：截断 fence/table 会制造
        // 无法预览的损坏源码；非法或超限草稿应明确拒绝，由本地缓存保留完整内容。
        conversationRepo.setDraft(uid, chatId, draft?.let(MessageBodyPolicy::validateMarkdown))
        val conv = conversationRepo.getConversation(uid, chatId) ?: return
        events.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
    }

    suspend fun setPin(uid: String, chatId: String, pinned: Boolean) =
        lifecycleGate.withChat(chatId) { setPinInternal(uid, chatId, pinned) }

    private suspend fun setPinInternal(uid: String, chatId: String, pinned: Boolean) {
        access.requireMember(uid, chatId)
        conversationRepo.setPin(uid, chatId, pinned)
        val conv = conversationRepo.getConversation(uid, chatId) ?: return
        events.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
    }

    suspend fun setMute(uid: String, chatId: String, muted: Boolean) =
        lifecycleGate.withChat(chatId) { setMuteInternal(uid, chatId, muted) }

    private suspend fun setMuteInternal(uid: String, chatId: String, muted: Boolean) {
        access.requireMember(uid, chatId)
        conversationRepo.setMute(uid, chatId, muted)
        val conv = conversationRepo.getConversation(uid, chatId) ?: return
        events.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
    }

    suspend fun deleteConversation(uid: String, chatId: String) = lifecycleGate.withChat(chatId) {
        access.requireMember(uid, chatId)
        deleteConversationProjection(uid, chatId)
    }

    /** Internal membership/lifecycle cleanup; caller already holds the chat lifecycle gate. */
    internal suspend fun deleteConversationProjection(uid: String, chatId: String) {
        conversationRepo.deleteConversation(uid, chatId)
        // 哨兵 Conversation：CONVERSATION_DELETED 通知客户端只用 chatId 定位删除目标，
        // chatType=0 是占位值（合法值为 1=PERSONAL/2=GROUP），客户端不应读取它。
        val conv = Conversation(chatId = chatId, chatType = 0)
        events.emitEvent(uid, NotifyType.CONVERSATION_DELETED, conv)
    }

    suspend fun markRead(uid: String, chatId: String, readSeq: Long) =
        lifecycleGate.withChat(chatId) { markReadInternal(uid, chatId, readSeq) }

    private suspend fun markReadInternal(uid: String, chatId: String, readSeq: Long) {
        access.requireMember(uid, chatId)
        val maxSeq = chatStore.getMaxSeq(chatId)
        require(readSeq in 0L..maxSeq) {
            "readSeq 必须在当前会话序号范围 0..$maxSeq 内"
        }
        conversationRepo.markRead(uid, chatId, readSeq)
        val conv = conversationRepo.getConversation(uid, chatId) ?: return
        events.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
        val authoritativeReadSeq = conv.readSeq

        // 通知会话中其他成员：广播持久化后的单调水位，而不是可能落后的请求值。
        val members = chatRepo.getMemberUids(chatId)
        for (memberUid in members) {
            if (memberUid == uid) continue
            // 持久化对方已读位置（peerReadSeq），让换设备登录后 ✓✓ 不丢
            conversationRepo.updatePeerReadSeq(memberUid, chatId, authoritativeReadSeq)
            events.emitEvent(memberUid, NotifyType.READ_SYNC,
                ReadSyncPayload(uid, chatId, authoritativeReadSeq))
        }
    }

    /**
     * 消息到达时更新会话（由 MessageService 调用）。
     */
    suspend fun onMessageReceived(
        chatId: String,
        chatType: Int,
        lastMsgSeq: Long,
        lastMsgType: Int,
        lastMsgPreview: String?,
        memberUids: List<String>,
        senderUid: String,
    ) {
        for (uid in memberUids) {
            conversationRepo.upsertConversation(uid, chatId, chatType, lastMsgSeq, lastMsgType, lastMsgPreview)
            // 自己发送的消息不能形成自己的未读数。单水位模型下推进到本次 seq，
            // 也符合“能发言即已进入并阅读当前会话”的客户端语义。
            if (uid == senderUid) conversationRepo.markRead(uid, chatId, lastMsgSeq)
            val conv = conversationRepo.getConversation(uid, chatId) ?: continue
            events.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
        }
    }

    /** 编辑/撤回最后一条消息后刷新会话摘要；非最后一条不改变列表预览。 */
    suspend fun onMessageChanged(
        chatId: String,
        serverSeq: Long,
        lastMsgType: Int,
        lastMsgPreview: String?,
        memberUids: List<String>,
    ) {
        for (uid in memberUids) {
            if (!conversationRepo.updateLastMessageIfCurrent(uid, chatId, serverSeq, lastMsgType, lastMsgPreview)) continue
            val conv = conversationRepo.getConversation(uid, chatId) ?: continue
            events.emitEvent(uid, NotifyType.CONVERSATION_UPDATED, conv)
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

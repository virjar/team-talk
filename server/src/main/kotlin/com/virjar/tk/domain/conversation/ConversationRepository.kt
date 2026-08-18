package com.virjar.tk.domain.conversation

import com.virjar.tk.model.Conversation

/** Persistence port owned by the conversation domain. */
interface ConversationRepository {
    fun listConversations(uid: String): List<Conversation>
    fun getConversation(uid: String, chatId: String): Conversation?
    fun upsertConversation(uid: String, chatId: String, chatType: Int, lastMsgSeq: Long, lastMsgType: Int = 0, lastMsgPreview: String? = null)
    fun updateLastMessageIfCurrent(uid: String, chatId: String, serverSeq: Long, lastMsgType: Int, lastMsgPreview: String?): Boolean
    fun markRead(uid: String, chatId: String, readSeq: Long)
    fun updatePeerReadSeq(uid: String, chatId: String, peerReadSeq: Long)
    fun ensureConversation(uid: String, chatId: String, chatType: Int)
    fun setPin(uid: String, chatId: String, pinned: Boolean)
    fun setMute(uid: String, chatId: String, muted: Boolean)
    fun setDraft(uid: String, chatId: String, draft: String?)
    fun deleteConversation(uid: String, chatId: String)
    fun getConversationsAfter(uid: String, afterVersion: Long, limit: Int = 100): List<Conversation>
}

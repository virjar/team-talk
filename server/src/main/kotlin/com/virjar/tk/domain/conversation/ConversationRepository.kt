package com.virjar.tk.domain.conversation

import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.Conversation

/** Persisted mark-read snapshot plus the active recipients that must observe its receipt. */
data class ConversationReadMutation(
    val conversation: Conversation,
    /** True only when this command advanced the actor's persisted readSeq. */
    val actorChanged: Boolean,
    /** Only peers whose persisted projection actually advanced; missing/deleted rows are excluded. */
    val advancedPeerUids: List<String>,
)

/** Persistence port owned by the conversation domain. */
interface ConversationRepository {
    /** Only rows backed by an active chat and active membership may be returned. */
    fun listConversations(uid: String): List<Conversation>
    fun getConversation(uid: String, chatId: String): Conversation?
    fun ensureConversation(uid: String, chatId: String, chatType: Int)

    /**
     * User-owned mutations must join a [PgUnitOfWork][com.virjar.tk.domain.transaction.PgUnitOfWork].
     * Returning the snapshot from the same database transaction prevents an event from describing
     * a later or partially committed row.
     */
    fun setPin(transaction: PgTransactionContext, uid: String, chatId: String, pinned: Boolean): Conversation
    fun setMute(transaction: PgTransactionContext, uid: String, chatId: String, muted: Boolean): Conversation
    fun setDraft(transaction: PgTransactionContext, uid: String, chatId: String, draft: String?): Conversation
    fun markRead(
        transaction: PgTransactionContext,
        uid: String,
        chatId: String,
        readSeq: Long,
    ): ConversationReadMutation

    /** Delete requested by an active member; membership is revalidated under the chat row lock. */
    fun deleteConversation(transaction: PgTransactionContext, uid: String, chatId: String): Boolean

    /**
     * Lifecycle cleanup after membership/chat removal, where active membership no longer exists.
     * The upstream lifecycle fact may already have deleted the projection in its own transaction.
     */
    fun deleteConversationProjection(transaction: PgTransactionContext, uid: String, chatId: String)
}

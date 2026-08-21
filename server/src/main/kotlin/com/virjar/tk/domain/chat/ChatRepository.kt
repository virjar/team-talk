package com.virjar.tk.domain.chat

import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import kotlinx.serialization.Serializable

/**
 * Persistence port for chat metadata and administrative chat queries.
 *
 * Chat creation atomically creates initial memberships and conversation rows. Callers must not
 * repeat those projections after this port returns.
 */
interface ChatRepository {
    /**
     * Command-side creation boundary. Required users, block facts, Chat, memberships and
     * Conversations are validated/mutated in the caller's PostgreSQL unit of work.
     */
    fun createPersonalChat(
        transaction: PgTransactionContext,
        uid1: String,
        uid2: String,
    ): ChatCreation = error("Transactional personal chat creation is not implemented")

    /** Fresh user-created groups use the documented User -> new Chat lock-order exception. */
    fun createGroupChat(
        transaction: PgTransactionContext,
        name: String,
        avatar: String?,
        creatorUid: String,
        memberUids: List<String>,
    ): ChatCreation = error("Transactional group chat creation is not implemented")
    /**
     * Atomically validates and consumes an invite, activates the membership and establishes the
     * user's conversation projection. The returned snapshot contains the committed recipients;
     * ChatStore may invalidate old cache state only after this method returns.
     */
    fun joinByInvite(
        transaction: PgTransactionContext,
        uid: String,
        token: String,
        nowMillis: Long,
    ): InviteJoinResult
    fun getChat(chatId: String): Chat?
    /** Existing-chat metadata mutation after the managed-chat authority row has been locked. */
    fun updateGroup(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        name: String? = null,
        avatar: String? = null,
        notice: String? = null,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("Transactional group update is not implemented")

    /**
     * Lock Chat then the optional human operator and re-read membership authority. Membership
     * rows are deliberately not locked here: dissolution must lock required User/Bot/grant facts
     * before Invite/Member/Mute/Conversation projections. Holding Chat keeps correct writers out.
     */
    fun lockForDeactivation(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): Chat = error("Transactional chat deactivation authority is not implemented")
    /**
     * Transaction-bound counterpart used after the caller locks the chat and deactivates required
     * external participants. Implementations return the pre-deactivation recipients for durable
     * tombstones.
     */
    fun deactivateChat(transaction: PgTransactionContext, chatId: String): ChatDeactivation =
        error("Transactional chat deactivation is not implemented")
    fun getMemberUids(chatId: String): List<String>
    fun findPersonalChatId(uid1: String, uid2: String): String?
    fun getChatById(chatId: String): Chat?
    fun listGroups(query: String?, page: Int, size: Int): AdminPage<Chat>
    fun countGroups(): Long
    fun countEventsSince(since: Long): Long
    fun listUserChats(uid: String): List<Chat>
}

data class ChatDeactivation(
    val chat: Chat,
    val memberUids: List<String>,
)

/** Fresh-create result; retries of an already-created personal pair are explicit event no-ops. */
data class ChatCreation(
    val chat: Chat,
    val created: Boolean,
    val recipientUids: List<String>,
)

/** Locked group snapshot supplied to domain authorization before its mutation is applied. */
data class GroupCommandFacts(
    val chat: Chat,
    val operator: Member?,
    val target: Member? = null,
    val activeMemberUids: List<String>,
)

/** Full committed projection snapshot and durable-event recipients for one group command. */
data class ChatMutation(
    val chat: Chat,
    val recipientUids: List<String>,
    val changed: Boolean = true,
)

data class InviteJoinResult(
    val chat: Chat,
    val joined: Boolean,
    val members: List<Member>,
)

/** Stable, order-independent identity stored by the fresh schema for a personal chat pair. */
internal fun personalChatKey(uid1: String, uid2: String): String {
    val (first, second) = if (uid1 <= uid2) uid1 to uid2 else uid2 to uid1
    return "${first.length}:$first${second.length}:$second"
}

@Serializable
data class AdminPage<T>(val total: Long, val items: List<T>)

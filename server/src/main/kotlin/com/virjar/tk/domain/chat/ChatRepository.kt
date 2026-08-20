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
    fun createPersonalChat(uid1: String, uid2: String): Chat
    fun createGroupChat(
        name: String,
        avatar: String?,
        creatorUid: String,
        memberUids: List<String>,
        requestedChatId: String? = null,
    ): Chat
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
    fun updateGroup(chatId: String, name: String? = null, avatar: String? = null, notice: String? = null)
    /** Atomically deactivates chat/members and removes conversation, mute and invite projections. */
    fun deactivateChat(chatId: String)
    fun getMemberUids(chatId: String): List<String>
    fun updateMaxSeq(chatId: String, seq: Long)
    fun findPersonalChatId(uid1: String, uid2: String): String?
    fun getChatById(chatId: String): Chat?
    fun listGroups(query: String?, page: Int, size: Int): AdminPage<Chat>
    fun countGroups(): Long
    fun countEventsSince(since: Long): Long
    fun listUserChats(uid: String): List<Chat>
}

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

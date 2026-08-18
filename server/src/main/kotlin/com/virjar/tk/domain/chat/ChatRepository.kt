package com.virjar.tk.domain.chat

import com.virjar.tk.model.Chat
import kotlinx.serialization.Serializable

/** Persistence port for chat metadata and administrative chat queries. */
interface ChatRepository {
    fun createPersonalChat(uid1: String, uid2: String): Chat
    fun createGroupChat(name: String, avatar: String?, creatorUid: String, memberUids: List<String>): Chat
    fun getChat(chatId: String): Chat?
    fun updateGroup(chatId: String, name: String? = null, avatar: String? = null, notice: String? = null)
    fun deleteChat(chatId: String)
    fun getMemberUids(chatId: String): List<String>
    fun updateMaxSeq(chatId: String, seq: Long)
    fun findPersonalChatId(uid1: String, uid2: String): String?
    fun getChatById(chatId: String): Chat?
    fun listGroups(query: String?, page: Int, size: Int): AdminPage<Chat>
    fun countGroups(): Long
    fun countEventsSince(since: Long): Long
    fun listUserChats(uid: String): List<Chat>
}

@Serializable
data class AdminPage<T>(val total: Long, val items: List<T>)

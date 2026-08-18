package com.virjar.tk.domain.message

import com.virjar.tk.model.Message

/** Authoritative message archive boundary. */
interface MessageRepository {
    fun storeMessage(message: Message): Long
    fun getMessage(chatId: String, seq: Long): Message?
    fun getSeqByClientMsgId(clientMsgId: String): Long?
    fun getHistory(chatId: String, fromSeq: Long, limit: Int, forward: Boolean = false): List<Message>
    fun updateMessage(chatId: String, seq: Long, message: Message)
    fun isProjectionPending(chatId: String, seq: Long): Boolean
    fun getPendingProjections(limit: Int = 100): List<Message>
    fun markProjectionComplete(chatId: String, seq: Long)
}

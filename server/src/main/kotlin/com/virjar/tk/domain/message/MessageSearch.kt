package com.virjar.tk.domain.message

import com.virjar.tk.model.Message

/** Search index boundary; Lucene-specific concepts do not cross this interface. */
interface MessageSearch {
    fun indexMessage(message: Message, text: String?)
    fun deleteMessage(clientMsgId: String)
    fun search(
        query: String,
        chatIds: Set<String>,
        senderUid: String? = null,
        startTimestamp: Long? = null,
        endTimestamp: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): MessageSearchPage
}

data class MessageSearchPage(
    val total: Int,
    val hits: List<MessageSearchHit>,
)

data class MessageSearchHit(
    val clientMsgId: String,
    val chatId: String,
    val senderUid: String,
    val messageType: Int,
    val seq: Long,
    val timestamp: Long,
    val highlight: String,
)

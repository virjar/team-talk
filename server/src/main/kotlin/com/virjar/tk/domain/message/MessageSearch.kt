package com.virjar.tk.domain.message

/** Search index boundary; Lucene-specific concepts do not cross this interface. */
interface MessageSearch {
    /**
     * Durably applies one immutable message projection.
     *
     * @return true when [operation] advanced the indexed revision, or false when the same/newer
     * revision was already durable.
     */
    fun applyProjection(operation: MessageProjectionOperation, text: String?): Boolean

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

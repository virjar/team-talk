package com.virjar.tk.server.domain.conversation

import com.virjar.tk.protocol.model.ChatDraftSnapshot
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

data class ChatDraftReceipt(val fingerprint: String, val applied: Boolean, val revision: Long)

interface ChatDraftRepository {
    fun requireAccess(transaction: PgReadTransactionContext, uid: String, chatId: String)
    /** Chat row, then the existing per-user conversation capacity row, then the draft row. */
    fun lock(transaction: PgWriteTransactionContext, uid: String, chatId: String, ownerClear: Boolean = false)
    fun get(transaction: PgReadTransactionContext, uid: String, chatId: String): ChatDraftSnapshot
    fun receipt(transaction: PgReadTransactionContext, uid: String, operationId: String): ChatDraftReceipt?
    fun requireReceiptCapacity(transaction: PgWriteTransactionContext, uid: String, now: Long)
    fun record(transaction: PgWriteTransactionContext, uid: String, operationId: String, issuedAt: Long, receipt: ChatDraftReceipt)
    fun save(transaction: PgWriteTransactionContext, uid: String, snapshot: ChatDraftSnapshot)
}

/** Exact accepted message lookup; never scans chat history or trusts client-declared sequence numbers. */
fun interface ChatDraftMessageLookup {
    fun find(chatId: String, clientMsgId: String): Message?
}

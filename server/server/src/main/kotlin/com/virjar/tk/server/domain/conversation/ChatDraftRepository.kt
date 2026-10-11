package com.virjar.tk.server.domain.conversation

import com.virjar.tk.protocol.model.ChatDraftSnapshot
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext

data class ChatDraftReceipt(val fingerprint: String, val applied: Boolean, val revision: Long)

interface ChatDraftRepository {
    fun requireAccess(transaction: PgReadTransactionContext, uid: String, chatId: String)
    /** 先锁 Chat 行，再锁每用户会话容量行，最后锁草稿行。 */
    fun lock(transaction: PgWriteTransactionContext, uid: String, chatId: String, ownerClear: Boolean = false)
    fun get(transaction: PgReadTransactionContext, uid: String, chatId: String): ChatDraftSnapshot
    fun receipt(transaction: PgReadTransactionContext, uid: String, operationId: String): ChatDraftReceipt?
    fun requireReceiptCapacity(transaction: PgWriteTransactionContext, uid: String, now: Long)
    fun record(transaction: PgWriteTransactionContext, uid: String, operationId: String, issuedAt: Long, receipt: ChatDraftReceipt)
    fun save(transaction: PgWriteTransactionContext, uid: String, snapshot: ChatDraftSnapshot)
}

/** 精确查找已接收的消息；绝不扫描聊天历史，也不信任客户端声明的序列号。 */
fun interface ChatDraftMessageLookup {
    fun find(chatId: String, clientMsgId: String): Message?
}

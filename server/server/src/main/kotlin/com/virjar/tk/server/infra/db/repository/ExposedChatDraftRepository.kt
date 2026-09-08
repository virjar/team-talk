package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.*
import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.conversation.*
import com.virjar.tk.server.domain.transaction.*
import com.virjar.tk.server.infra.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less

class ExposedChatDraftRepository(private val maxActiveDrafts: Int = 1_000) : ChatDraftRepository {
    init { require(maxActiveDrafts in 1..1_000) }
    override fun requireAccess(transaction: PgReadTransactionContext, uid: String, chatId: String) {
        transaction.requireExposedReadTransaction()
        val readable = GroupMembers.join(Chats, JoinType.INNER, GroupMembers.chatId, Chats.chatId)
            .join(OrganizationManagedChatProjections, JoinType.LEFT, Chats.chatId, OrganizationManagedChatProjections.chatId)
            .select(GroupMembers.uid).where {
                (Chats.chatId eq chatId) and (Chats.status eq 1) and (GroupMembers.uid eq uid) and
                    (GroupMembers.status eq 1) and (OrganizationManagedChatProjections.chatId.isNull() or (
                        (OrganizationManagedChatProjections.desiredActive eq true) and
                            (OrganizationManagedChatProjections.desiredRevision eq OrganizationManagedChatProjections.appliedRevision) and
                            OrganizationManagedChatProjections.lastFailure.isNull()))
            }.limit(1).any()
        if (!readable) throw ChatAccessDeniedException("无权访问此聊天草稿")
    }

    override fun lock(transaction: PgWriteTransactionContext, uid: String, chatId: String, ownerClear: Boolean) {
        transaction.requireExposedTransaction()
        Chats.select(Chats.chatId).where { Chats.chatId eq chatId }.forUpdate().singleOrNull()
            ?: throw ChatAccessDeniedException("聊天不存在")
        if (ownerClear) {
            val owned = ChatDrafts.select(ChatDrafts.uid).where { (ChatDrafts.uid eq uid) and (ChatDrafts.chatId eq chatId) }.limit(1).any() ||
                Conversations.select(Conversations.uid).where { (Conversations.uid eq uid) and (Conversations.chatId eq chatId) }.limit(1).any()
            if (!owned) throw ChatAccessDeniedException("没有可清理的本人草稿")
        } else requireAccess(transaction, uid, chatId)
        ConversationUsageLedger.lock(listOf(uid))
        ChatDrafts.selectAll().where { (ChatDrafts.uid eq uid) and (ChatDrafts.chatId eq chatId) }.forUpdate().singleOrNull()
    }

    override fun get(transaction: PgReadTransactionContext, uid: String, chatId: String): ChatDraftSnapshot {
        transaction.requireExposedReadTransaction()
        val row = ChatDrafts.selectAll().where { (ChatDrafts.uid eq uid) and (ChatDrafts.chatId eq chatId) }.singleOrNull()
        if (row != null) return ChatDraftSnapshot(chatId, row[ChatDrafts.revision], row[ChatDrafts.updatedAt],
            row[ChatDrafts.payload]?.let { ProtoCodec.decode(ChatDraftContent, it) })
        // First use may adopt an existing plain mirror. Once a draft row exists, legacy writes cannot replace it.
        val legacy = Conversations.select(Conversations.draft).where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.singleOrNull()?.get(Conversations.draft)
        val content = legacy?.takeIf { it.isNotEmpty() }?.let { runCatching { ChatDraftContent(it) }.getOrNull() }
        return ChatDraftSnapshot(chatId, 0, 0, content)
    }

    override fun receipt(transaction: PgReadTransactionContext, uid: String, operationId: String): ChatDraftReceipt? {
        transaction.requireExposedReadTransaction()
        return ChatDraftCommands.selectAll().where { (ChatDraftCommands.uid eq uid) and (ChatDraftCommands.operationId eq operationId) }
            .singleOrNull()?.let { ChatDraftReceipt(it[ChatDraftCommands.fingerprint], it[ChatDraftCommands.applied], it[ChatDraftCommands.revision]) }
    }

    override fun requireReceiptCapacity(transaction: PgWriteTransactionContext, uid: String, now: Long) {
        transaction.requireExposedTransaction()
        ChatDraftCommands.deleteWhere { (ChatDraftCommands.uid eq uid) and (expiresAt less now) }
        if (ChatDraftCommands.select(ChatDraftCommands.operationId).where { ChatDraftCommands.uid eq uid }.count() >= 16_384) {
            throw ReliableCommandCapacityException("草稿同步操作已达上限，请保留本地草稿稍后重试")
        }
    }

    override fun record(transaction: PgWriteTransactionContext, uid: String, operationId: String, issuedAt: Long, receipt: ChatDraftReceipt) {
        transaction.requireExposedTransaction()
        ChatDraftCommands.insert {
            it[ChatDraftCommands.uid] = uid; it[ChatDraftCommands.operationId] = operationId
            it[fingerprint] = receipt.fingerprint; it[applied] = receipt.applied; it[revision] = receipt.revision
            it[expiresAt] = ReliableCommandPolicy.expiresAt(issuedAt)
        }
    }

    override fun save(transaction: PgWriteTransactionContext, uid: String, snapshot: ChatDraftSnapshot) {
        transaction.requireExposedTransaction()
        val payload = snapshot.content?.let(ProtoCodec::encode)
        val currentSize = ChatDrafts.select(ChatDrafts.payloadSize).where {
            (ChatDrafts.uid eq uid) and (ChatDrafts.chatId eq snapshot.chatId)
        }.singleOrNull()?.get(ChatDrafts.payloadSize)
        val active = ChatDrafts.select(ChatDrafts.chatId).where { (ChatDrafts.uid eq uid) and (ChatDrafts.payloadSize greater 0) }.count()
        check(active - (if ((currentSize ?: 0) > 0) 1 else 0) + (if (payload != null) 1 else 0) <= maxActiveDrafts) { "聊天草稿数量已达上限" }
        val totalSize = ChatDrafts.payloadSize.sum()
        val bytes = ChatDrafts.select(totalSize).where { ChatDrafts.uid eq uid }.single()[totalSize] ?: 0
        check(bytes.toLong() - (currentSize ?: 0) + (payload?.size ?: 0) <= 48L * 1024 * 1024) { "聊天草稿容量已达上限" }
        if (currentSize != null) {
            ChatDrafts.update({ (ChatDrafts.uid eq uid) and (ChatDrafts.chatId eq snapshot.chatId) }) {
                it[revision] = snapshot.revision; it[updatedAt] = snapshot.updatedAt
                it[ChatDrafts.payload] = payload; it[payloadSize] = payload?.size ?: 0
            }
        } else ChatDrafts.insert {
            it[ChatDrafts.uid] = uid; it[chatId] = snapshot.chatId; it[revision] = snapshot.revision
            it[updatedAt] = snapshot.updatedAt; it[ChatDrafts.payload] = payload; it[payloadSize] = payload?.size ?: 0
        }
        ChatDraftAssets.deleteWhere { (ChatDraftAssets.uid eq uid) and (chatId eq snapshot.chatId) }
        snapshot.content?.assets.orEmpty().flatMap { it.attachments() }.map { it.path }.distinct().forEach { path ->
            ChatDraftAssets.insert { it[ChatDraftAssets.uid] = uid; it[chatId] = snapshot.chatId; it[ChatDraftAssets.path] = path }
        }
    }
}

package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.conversation.ConversationReadMutation
import com.virjar.tk.server.domain.conversation.ConversationPageSlice
import com.virjar.tk.server.domain.conversation.ConversationRepository
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.toUserAvatar
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Conversation
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction

internal enum class ConversationReadQueryStage {
    PAGE,
    GROUP_DISPLAY,
    PERSONAL_PEER,
    PEER_USER,
}

internal fun interface ConversationReadQueryObserver {
    fun beforeQuery(stage: ConversationReadQueryStage)

    object None : ConversationReadQueryObserver {
        override fun beforeQuery(stage: ConversationReadQueryStage) = Unit
    }
}

internal class ExposedConversationRepository(
    private val database: Database,
    private val readQueryObserver: ConversationReadQueryObserver = ConversationReadQueryObserver.None,
) : ConversationRepository {

    override fun listConversationPage(
        uid: String,
        afterChatId: String?,
        pageSize: Int,
    ): ConversationPageSlice = transaction(database) {
        require(pageSize in 1..com.virjar.tk.protocol.model.ConversationPage.MAX_PAGE_SIZE) {
            "Conversation page size is out of range"
        }
        readQueryObserver.beforeQuery(ConversationReadQueryStage.PAGE)
        val query = readableConversations(uid)
        if (afterChatId != null) {
            query.andWhere { Conversations.chatId less afterChatId }
        }
        val rows = query.orderBy(Conversations.chatId, SortOrder.DESC)
            .limit(pageSize + 1)
            .toList()

        val hasMore = rows.size > pageSize
        val selectedRows = if (hasMore) rows.subList(0, pageSize) else rows
        val items = enrichConversations(uid, selectedRows)
        ConversationPageSlice(
            items = items,
            nextChatId = if (hasMore) {
                selectedRows.last()[Conversations.chatId]
            } else {
                null
            },
        )
    }

    override fun getConversation(uid: String, chatId: String): Conversation? = transaction(database) {
        readQueryObserver.beforeQuery(ConversationReadQueryStage.PAGE)
        val row = readableConversations(uid)
            .andWhere { Conversations.chatId eq chatId }
            .singleOrNull()
            ?: return@transaction null
        enrichConversations(uid, listOf(row)).single()
    }

    override fun setPin(
        transaction: PgWriteTransactionContext,
        uid: String,
        chatId: String,
        pinned: Boolean,
    ): Conversation = inWriteTransaction(transaction) {
        val chat = requireActiveMembership(uid, chatId)
        val existing = requireConversation(uid, chatId)
        if (existing[Conversations.isPinned] != pinned || existing[Conversations.isHidden]) {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.isPinned] = pinned
                it[Conversations.isHidden] = false
                it[Conversations.version] = existing[Conversations.version] + 1L
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
        }
        requireCommittedSnapshot(uid, chatId, chat)
    }

    override fun setMute(
        transaction: PgWriteTransactionContext,
        uid: String,
        chatId: String,
        muted: Boolean,
    ): Conversation = inWriteTransaction(transaction) {
        val chat = requireActiveMembership(uid, chatId)
        val existing = requireConversation(uid, chatId)
        if (existing[Conversations.isMuted] != muted || existing[Conversations.isHidden]) {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.isMuted] = muted
                it[Conversations.isHidden] = false
                it[Conversations.version] = existing[Conversations.version] + 1L
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
        }
        requireCommittedSnapshot(uid, chatId, chat)
    }

    override fun setDraft(
        transaction: PgWriteTransactionContext,
        uid: String,
        chatId: String,
        draft: String?,
    ): Conversation = inWriteTransaction(transaction) {
        val chat = requireActiveMembership(uid, chatId)
        val observed = requireConversation(uid, chatId, forUpdate = false)
        val usage = ConversationUsageLedger.lock(listOf(uid))
        val existing = requireConversation(uid, chatId)
        check(existing[Conversations.id] == observed[Conversations.id]) {
            "Conversation projection changed while its Chat row was locked"
        }
        if (existing[Conversations.draft] != draft || existing[Conversations.isHidden]) {
            val draftDelta =
                (draft?.length?.toLong() ?: 0L) -
                    (existing[Conversations.draft]?.length?.toLong() ?: 0L)
            if (draftDelta != 0L) {
                ConversationUsageLedger.apply(
                    usage,
                    mapOf(uid to ConversationUsageDelta(draftCharacters = draftDelta)),
                )
            }
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.draft] = draft
                it[Conversations.isHidden] = false
                it[Conversations.version] = existing[Conversations.version] + 1L
                it[Conversations.updatedAt] = System.currentTimeMillis()
            }
        }
        requireCommittedSnapshot(uid, chatId, chat)
    }

    override fun markRead(
        transaction: PgWriteTransactionContext,
        uid: String,
        chatId: String,
        readSeq: Long,
    ): ConversationReadMutation = inWriteTransaction(transaction) {
        val chat = requireActiveMembership(uid, chatId)
        val maxSeq = chat[Chats.maxSeq]
        require(readSeq in 0L..maxSeq) {
            "readSeq 必须在当前会话序号范围 0..$maxSeq 内"
        }

        // 按 uid 顺序锁定每个活跃成员的投影。actor 的 readSeq 与所有
        // peerReadSeq 值都是最大水位线；后续事务只能观察并推进
        // 先前事务已提交的值。
        val memberUids = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
        }.orderBy(GroupMembers.uid, SortOrder.ASC).map { it[GroupMembers.uid] }
        val rowsByUid = Conversations.selectAll().where {
            (Conversations.chatId eq chatId) and (Conversations.uid inList memberUids)
        }.orderBy(Conversations.uid, SortOrder.ASC).forUpdate()
            .associateBy { it[Conversations.uid] }
        val actor = requireNotNull(rowsByUid[uid]) { "会话不存在" }
        val authoritativeReadSeq = maxOf(actor[Conversations.readSeq], readSeq)
        val now = System.currentTimeMillis()

        val actorChanged =
            authoritativeReadSeq > actor[Conversations.readSeq] || actor[Conversations.isHidden]
        if (actorChanged) {
            Conversations.update({
                (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.readSeq] = authoritativeReadSeq
                it[Conversations.isHidden] = false
                it[Conversations.version] = actor[Conversations.version] + 1L
                it[Conversations.updatedAt] = now
            }
        }

        val advancedPeerUids = mutableListOf<String>()
        for (peerUid in memberUids) {
            if (peerUid == uid) continue
            val peer = rowsByUid[peerUid] ?: continue
            if (peer[Conversations.isHidden]) continue
            if (authoritativeReadSeq <= peer[Conversations.peerReadSeq]) continue
            Conversations.update({
                (Conversations.uid eq peerUid) and (Conversations.chatId eq chatId)
            }) {
                it[Conversations.peerReadSeq] = authoritativeReadSeq
                it[Conversations.version] = peer[Conversations.version] + 1L
                it[Conversations.updatedAt] = now
            }
            advancedPeerUids += peerUid
        }

        val snapshot = requireCommittedSnapshot(uid, chatId, chat)
        ConversationReadMutation(
            conversation = snapshot,
            actorChanged = actorChanged,
            advancedPeerUids = advancedPeerUids,
        )
    }

    override fun deleteConversation(
        transaction: PgWriteTransactionContext,
        uid: String,
        chatId: String,
    ): Boolean = inWriteTransaction(transaction) {
        requireActiveMembership(uid, chatId)
        val observed = Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.singleOrNull() ?: return@inWriteTransaction false
        val usage = ConversationUsageLedger.lock(listOf(uid))
        val existing = requireConversation(uid, chatId)
        check(existing[Conversations.id] == observed[Conversations.id]) {
            "Conversation projection changed while its Chat row was locked"
        }
        if (existing[Conversations.isHidden]) return@inWriteTransaction false
        val draftCharacters = existing[Conversations.draft]?.length?.toLong() ?: 0L
        if (draftCharacters > 0L) {
            ConversationUsageLedger.apply(
                usage,
                mapOf(uid to ConversationUsageDelta(draftCharacters = -draftCharacters)),
            )
        }
        check(Conversations.update({
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }) {
            it[Conversations.isHidden] = true
            it[Conversations.isPinned] = false
            it[Conversations.draft] = null
            it[Conversations.version] = existing[Conversations.version] + 1L
            it[Conversations.updatedAt] = System.currentTimeMillis()
        } == 1) { "Locked Conversation projection changed before hiding" }
        true
    }

    /** 以最多三次批量查询解析展示元数据，与页面基数无关。 */
    private fun enrichConversations(uid: String, rows: List<ResultRow>): List<Conversation> {
        if (rows.isEmpty()) return emptyList()
        val groupChatIds = rows.asSequence()
            .filter { it[Chats.chatType] == GROUP_CHAT_TYPE }
            .map { it[Conversations.chatId] }
            .toList()
        val groupNames: Map<String, String> = if (groupChatIds.isEmpty()) {
            emptyMap()
        } else {
            readQueryObserver.beforeQuery(ConversationReadQueryStage.GROUP_DISPLAY)
            GroupChats.selectAll().where { GroupChats.chatId inList groupChatIds }
                .associate { row ->
                    row[GroupChats.chatId] to row[GroupChats.name]
                }
        }

        val personalChatIds = rows.asSequence()
            .filter { it[Chats.chatType] == PERSONAL_CHAT_TYPE }
            .map { it[Conversations.chatId] }
            .toList()
        val peerUidByChatId = linkedMapOf<String, String>()
        if (personalChatIds.isNotEmpty()) {
            readQueryObserver.beforeQuery(ConversationReadQueryStage.PERSONAL_PEER)
            GroupMembers.selectAll().where {
                (GroupMembers.chatId inList personalChatIds) and
                    (GroupMembers.status eq 1) and
                    (GroupMembers.uid neq uid)
            }.orderBy(
                GroupMembers.chatId to SortOrder.ASC,
                GroupMembers.uid to SortOrder.ASC,
            ).forEach { member ->
                peerUidByChatId.putIfAbsent(
                    member[GroupMembers.chatId],
                    member[GroupMembers.uid],
                )
            }
        }

        val peerUsers = if (peerUidByChatId.isEmpty()) {
            emptyMap()
        } else {
            readQueryObserver.beforeQuery(ConversationReadQueryStage.PEER_USER)
            Users.selectAll().where { Users.uid inList peerUidByChatId.values.distinct() }
                .associateBy { it[Users.uid] }
        }

        return rows.map { row ->
            val chatType = row[Chats.chatType]
            val chatId = row[Conversations.chatId]
            if (chatType == SAVED_CHAT_TYPE) {
                row.toConversation(chatType = chatType, chatName = SAVED_CHAT_DISPLAY_NAME)
            } else if (chatType == GROUP_CHAT_TYPE) {
                val name = checkNotNull(groupNames[chatId]) {
                    "Active group Conversation has no GroupChats display row"
                }
                row.toConversation(chatType = chatType, chatName = name)
            } else {
                val peerUid = checkNotNull(peerUidByChatId[chatId]) {
                    "Active personal Conversation has no opposite member"
                }
                val user = checkNotNull(peerUsers[peerUid]) {
                    "Active personal Conversation peer has no User row"
                }
                row.toConversation(
                    chatType = chatType,
                    chatName = user[Users.name],
                    chatAvatar = user.toUserAvatar(),
                    peerUid = peerUid,
                    peerRevision = user[Users.revision],
                )
            }
        }
    }

    /** 列表与单条读取共用同一组可见行条件；调用方只追加游标或 chatId。 */
    private fun readableConversations(uid: String): Query = Conversations.join(
        otherTable = GroupMembers,
        joinType = JoinType.INNER,
        onColumn = Conversations.chatId,
        otherColumn = GroupMembers.chatId,
    ).join(
        otherTable = Chats,
        joinType = JoinType.INNER,
        onColumn = Conversations.chatId,
        otherColumn = Chats.chatId,
    ).join(
        otherTable = OrganizationManagedChatProjections,
        joinType = JoinType.LEFT,
        onColumn = Conversations.chatId,
        otherColumn = OrganizationManagedChatProjections.chatId,
    ).selectAll().where {
        (Conversations.uid eq uid) and
            (Conversations.isHidden eq false) and
            (GroupMembers.uid eq uid) and
            (GroupMembers.status eq 1) and
            (Chats.status eq 1) and
            (
                OrganizationManagedChatProjections.chatId.isNull() or
                    (
                        (OrganizationManagedChatProjections.desiredActive eq true) and
                            (OrganizationManagedChatProjections.desiredRevision eq
                                OrganizationManagedChatProjections.appliedRevision) and
                            OrganizationManagedChatProjections.lastFailure.isNull()
                        )
                )
    }

    private fun requireActiveMembership(uid: String, chatId: String): ResultRow {
        val chat = Chats.selectAll().where {
            (Chats.chatId eq chatId) and (Chats.status eq 1)
        }.forUpdate().singleOrNull()
        require(chat != null) { "聊天不存在或已解散" }

        val member = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and
                (GroupMembers.uid eq uid) and
                (GroupMembers.status eq 1)
        }.forUpdate().singleOrNull()
        require(member != null) { "不是聊天成员" }
        return chat
    }

    private fun requireConversation(
        uid: String,
        chatId: String,
        forUpdate: Boolean = true,
    ): ResultRow {
        val query = Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }
        val row = if (forUpdate) query.forUpdate().singleOrNull() else query.singleOrNull()
        return requireNotNull(row) { "会话不存在" }
    }

    private fun requireCommittedSnapshot(uid: String, chatId: String, chat: ResultRow): Conversation =
        checkNotNull(committedSnapshot(uid, chatId, chat)) {
            "Conversation projection disappeared while its row lock was held"
        }

    /** 从活跃写事务内部可见的行状态构建事件载荷。 */
    private fun committedSnapshot(uid: String, chatId: String, chat: ResultRow): Conversation? {
        val persisted = Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.singleOrNull() ?: return null
        if (persisted[Conversations.isHidden]) return null
        val chatType = chat[Chats.chatType]
        val peerUid = if (chatType == PERSONAL_CHAT_TYPE) {
            GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and
                    (GroupMembers.status eq 1) and
                    (GroupMembers.uid neq uid)
            }.orderBy(GroupMembers.uid, SortOrder.ASC).limit(1)
                .firstOrNull()?.get(GroupMembers.uid)
        } else {
            null
        }
        return if (chatType == SAVED_CHAT_TYPE) {
            persisted.toConversation(chatType = chatType, chatName = SAVED_CHAT_DISPLAY_NAME)
        } else if (chatType == 2) {
            val group = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()
            persisted.toConversation(chatType = chatType, chatName = group?.get(GroupChats.name))
        } else {
            val oppositeUid = checkNotNull(peerUid) { "Active personal Conversation has no opposite member" }
            val peer = checkNotNull(
                Users.selectAll().where { Users.uid eq oppositeUid }.singleOrNull(),
            ) { "Active personal Conversation peer has no User row" }
            persisted.toConversation(
                chatType = chatType,
                chatName = peer[Users.name],
                chatAvatar = peer.toUserAvatar(),
                peerUid = peerUid,
                peerRevision = peer[Users.revision],
            )
        }
    }

    private inline fun <T> inWriteTransaction(
        context: PgWriteTransactionContext,
        block: () -> T,
    ): T {
        context.requireExposedTransaction()
        return block()
    }
}

/**
 * 列表查询、用户操作回执和消息投影共用的 Conversation 输出映射。
 * 只读取当前事务已经查出的行，不另查库；名称/头像/对端来自各调用方原有的批量查询。
 */
internal fun ResultRow.toConversation(
    chatType: Int,
    chatName: String?,
    chatAvatar: Attachment? = null,
    peerUid: String? = null,
    peerRevision: Long? = null,
): Conversation = Conversation(
    chatId = this[Conversations.chatId],
    chatType = chatType,
    peerUid = peerUid,
    peerRevision = peerRevision,
    chatName = chatName,
    chatAvatar = chatAvatar,
    lastMessage = this[Conversations.lastMessage],
    lastMessageType = this[Conversations.lastMessageType],
    lastMsgTimestamp = this[Conversations.lastMsgTimestamp],
    lastSeq = this[Conversations.lastMsgSeq],
    readSeq = this[Conversations.readSeq],
    unreadCount = (this[Conversations.lastMsgSeq] - this[Conversations.readSeq])
        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
    isPinned = this[Conversations.isPinned],
    isMuted = this[Conversations.isMuted],
    peerReadSeq = this[Conversations.peerReadSeq],
    draft = this[Conversations.draft],
)

private const val PERSONAL_CHAT_TYPE = 1
private const val GROUP_CHAT_TYPE = 2
private const val SAVED_CHAT_TYPE = 3

/** "保存的消息"会话的服务端固定展示名。 */
internal const val SAVED_CHAT_DISPLAY_NAME = "保存的消息"

/** saved 会话的幂等键：与私聊 pair key 同一唯一索引，单 uid 前缀避免与 pair 冲突。 */
internal fun savedChatKey(uid: String): String = "saved:${uid.length}:$uid"

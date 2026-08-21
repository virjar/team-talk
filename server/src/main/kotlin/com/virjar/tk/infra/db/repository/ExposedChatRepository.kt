package com.virjar.tk.infra.db.repository

import com.virjar.tk.domain.chat.AdminPage
import com.virjar.tk.domain.chat.ChatRepository
import com.virjar.tk.domain.chat.ChatDeactivation
import com.virjar.tk.domain.chat.ChatCreation
import com.virjar.tk.domain.chat.ChatMutation
import com.virjar.tk.domain.chat.GroupCommandFacts
import com.virjar.tk.domain.chat.InviteJoinResult
import com.virjar.tk.domain.chat.personalChatKey
import com.virjar.tk.domain.chat.requireJoinable
import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.infra.db.Chats
import com.virjar.tk.infra.db.Conversations
import com.virjar.tk.infra.db.Friends
import com.virjar.tk.infra.db.GroupChats
import com.virjar.tk.infra.db.GroupInviteLinks
import com.virjar.tk.infra.db.GroupMemberMutes
import com.virjar.tk.infra.db.GroupMembers
import com.virjar.tk.infra.db.Users
import com.virjar.tk.infra.db.requireExposedTransaction
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import com.virjar.tk.model.UserRole
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

enum class ChatRepositoryStage {
    BEFORE_CHAT_LOCK,
}

/** Deterministic test seam for proving commands wait on the authoritative Chat row fence. */
fun interface ChatRepositoryHooks {
    fun hit(stage: ChatRepositoryStage, chatId: String)

    object None : ChatRepositoryHooks {
        override fun hit(stage: ChatRepositoryStage, chatId: String) = Unit
    }
}

/**
 * Chats 表访问 + Chat 视图组装。
 *
 * 成员管理见 [ChatMemberRepository]，邀请链接见 [InviteLinkRepository]。
 *
 * [createPersonalChat] / [createGroupChat] atomically initialize GroupMembers and Conversations;
 * these rows are required parts of the newly created chat, not best-effort service projections.
 */
class ExposedChatRepository(
    private val hooks: ChatRepositoryHooks = ChatRepositoryHooks.None,
) : ChatRepository {

    // ── Chat CRUD ──

    override fun createPersonalChat(
        transaction: PgTransactionContext,
        uid1: String,
        uid2: String,
    ): ChatCreation = inWriteTransaction(transaction) {
        val participants = listOf(uid1, uid2).distinct().sorted()
        require(participants.size == 2) { "不能和自己创建私聊" }
        lockRequiredHumanUsers(participants)
        // Contact writers lock the same sorted User pair before Friends, so these rows cannot
        // change after the user locks have been acquired.
        val blocked = Friends.selectAll().where {
            (((Friends.uid eq uid1) and (Friends.friendUid eq uid2)) or
                ((Friends.uid eq uid2) and (Friends.friendUid eq uid1))) and
                (Friends.status eq 2)
        }.orderBy(Friends.id, SortOrder.ASC).forUpdate().any()
        require(!blocked) { "黑名单关系下不能创建私聊" }

        val existingChatId = findPersonalChatIdInternal(uid1, uid2)
        if (existingChatId != null) {
            val existing = getChatByIdInternal(existingChatId)
                ?: error("私聊索引指向了不存在的会话")
            return@inWriteTransaction ChatCreation(
                chat = existing,
                created = false,
                recipientUids = participants,
            )
        }

        val chatId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        Chats.insert {
            it[Chats.chatId] = chatId
            it[Chats.chatType] = 1
            it[Chats.personalKey] = personalChatKey(uid1, uid2)
            it[Chats.maxSeq] = 0
            it[Chats.status] = 1
            it[Chats.createdAt] = now
            it[Chats.updatedAt] = now
        }
        participants.forEach { uid ->
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[GroupMembers.chatType] = 1
                it[GroupMembers.uid] = uid
                it[GroupMembers.role] = 0
                it[GroupMembers.status] = 1
                it[GroupMembers.joinedAt] = now
            }
            ensureConversation(uid = uid, chatId = chatId, chatType = 1, now = now)
        }
        ChatCreation(
            chat = Chat(chatId = chatId, chatType = 1),
            created = true,
            recipientUids = participants,
        )
    }


    override fun createGroupChat(
        transaction: PgTransactionContext,
        name: String,
        avatar: String?,
        creatorUid: String,
        memberUids: List<String>,
    ): ChatCreation = inWriteTransaction(transaction) {
        val recipients = (memberUids + creatorUid).distinct().sorted()
        // The id does not exist yet, so this is the sole User -> new Chat lock-order exception.
        lockRequiredHumanUsers(recipients)
        val chatId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        Chats.insert {
            it[Chats.chatId] = chatId
            it[Chats.chatType] = 2
            it[Chats.maxSeq] = 0
            it[Chats.status] = 1
            it[Chats.createdAt] = now
            it[Chats.updatedAt] = now
        }
        GroupChats.insert {
            it[GroupChats.chatId] = chatId
            it[GroupChats.name] = name
            it[GroupChats.avatar] = avatar
            it[GroupChats.creator] = creatorUid
            it[GroupChats.mutedAll] = false
            it[GroupChats.updatedAt] = now
        }
        recipients.forEach { uid ->
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[GroupMembers.chatType] = 2
                it[GroupMembers.uid] = uid
                it[GroupMembers.role] = if (uid == creatorUid) 2 else 0
                it[GroupMembers.status] = 1
                it[GroupMembers.joinedAt] = now
            }
            ensureConversation(uid = uid, chatId = chatId, chatType = 2, now = now)
        }
        val chat = Chat(
            chatId = chatId,
            chatType = 2,
            name = name,
            avatar = avatar,
            creator = creatorUid,
            memberCount = recipients.size,
        )
        ChatCreation(chat = chat, created = true, recipientUids = recipients)
    }

    override fun joinByInvite(
        transaction: PgTransactionContext,
        uid: String,
        token: String,
        nowMillis: Long,
    ): InviteJoinResult = inWriteTransaction(transaction) {
        // Resolve the owning chat without a lock, then acquire the aggregate locks in the global
        // Chat -> User -> Invite -> Member order. The locked re-read below is authoritative.
        val resolvedChatId = GroupInviteLinks.selectAll()
            .where { GroupInviteLinks.token eq token }
            .singleOrNull()
            ?.get(GroupInviteLinks.chatId)
            ?: throw IllegalArgumentException("邀请链接不存在")
        val chatRow = Chats.selectAll()
            .where { Chats.chatId eq resolvedChatId }
            .forUpdate()
            .singleOrNull()
            ?: throw IllegalArgumentException("聊天不存在")
        lockRequiredHumanUsers(listOf(uid))
        val inviteRow = GroupInviteLinks.selectAll()
            .where { GroupInviteLinks.token eq token }
            .forUpdate()
            .singleOrNull()
            ?: throw IllegalArgumentException("邀请链接不存在")
        val chatId = inviteRow[GroupInviteLinks.chatId]
        require(chatId == resolvedChatId) { "邀请链接归属已变更" }
        require(chatRow[Chats.status] == 1) { "聊天不存在" }
        require(chatRow[Chats.chatType] == 2) { "邀请链接只能用于群聊" }
        require(GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull() != null) {
            "群聊数据不完整"
        }

        val membership = GroupMembers.selectAll().where {
            (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
        }.forUpdate().singleOrNull()
        val joined = membership?.get(GroupMembers.status) != 1
        if (joined) {
            // Apply mutable link policy only when this command changes membership. An already
            // active member may be retrying a response that was lost after the first commit; an
            // exhausted/revoked link must not turn that committed success into a later failure.
            inviteRow.toInviteLinkRecord().requireJoinable(nowMillis)
            if (membership == null) {
                GroupMembers.insert {
                    it[GroupMembers.chatId] = chatId
                    it[GroupMembers.chatType] = 2
                    it[GroupMembers.uid] = uid
                    it[GroupMembers.role] = 0
                    it[GroupMembers.status] = 1
                    it[GroupMembers.joinedAt] = nowMillis
                }
            } else {
                GroupMembers.update({
                    (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq uid)
                }) {
                    // Invite joins are always ordinary user membership. Managed reconciliation
                    // has a separate role-aware internal path (ensureActiveGroupMember/setRole).
                    it[GroupMembers.role] = 0
                    it[GroupMembers.status] = 1
                    it[GroupMembers.joinedAt] = nowMillis
                }
            }
            GroupInviteLinks.update({ GroupInviteLinks.token eq token }) {
                with(SqlExpressionBuilder) {
                    it[GroupInviteLinks.useCount] = GroupInviteLinks.useCount + 1
                }
            }
        }

        ensureConversation(uid = uid, chatId = chatId, chatType = 2, now = nowMillis)
        val chat = getChatByIdInternal(chatId) ?: throw IllegalArgumentException("聊天不存在")
        val members = GroupMembers.selectAll()
            .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
            .map(ResultRow::toMemberSnapshot)
        InviteJoinResult(chat = chat, joined = joined, members = members)
    }

    private inline fun <T> inWriteTransaction(context: PgTransactionContext, block: () -> T): T {
        context.requireExposedTransaction()
        return block()
    }

    override fun getChat(chatId: String): Chat? {
        return transaction { getChatByIdInternal(chatId) }
    }

    override fun updateGroup(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String,
        name: String?,
        avatar: String?,
        notice: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = inWriteTransaction(transaction) {
        val chatRow = lockActiveChat(chatId)
        require(chatRow[Chats.chatType] == 2) { "群聊不存在" }
        lockRequiredHumanUsers(listOf(operatorUid))
        val members = lockActiveMembers(chatId)
        val before = buildChatFromRow(chatRow)
        authorize(
            GroupCommandFacts(
                chat = before,
                operator = members.firstOrNull { it.uid == operatorUid },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        val now = System.currentTimeMillis()
        name?.let { value ->
            GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.name] = value }
        }
        avatar?.let { value ->
            GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.avatar] = value }
        }
        notice?.let { value ->
            GroupChats.update({ GroupChats.chatId eq chatId }) { it[GroupChats.notice] = value }
        }
        check(GroupChats.update({ GroupChats.chatId eq chatId }) {
            it[GroupChats.updatedAt] = now
        } == 1) { "群聊数据不完整" }
        Chats.update({ Chats.chatId eq chatId }) { it[Chats.updatedAt] = now }
        ChatMutation(
            chat = buildChatFromRow(chatRow),
            recipientUids = members.map(Member::uid),
        )
    }

    override fun lockForDeactivation(
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): Chat = inWriteTransaction(transaction) {
        val chatRow = lockActiveChat(chatId)
        operatorUid?.let { lockRequiredHumanUsers(listOf(it)) }
        // Chat is the aggregate fence. Read membership without taking its row lock because Bot
        // identity/grant locks must be acquired before member projection locks during teardown.
        val members = activeMembers(chatId, forUpdate = false)
        val chat = buildChatFromRow(chatRow)
        authorize(
            GroupCommandFacts(
                chat = chat,
                operator = operatorUid?.let { uid -> members.firstOrNull { it.uid == uid } },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        chat
    }

    override fun deactivateChat(transaction: PgTransactionContext, chatId: String): ChatDeactivation =
        inWriteTransaction(transaction) {
            deactivateChatInternal(chatId)
        }

    private fun deactivateChatInternal(chatId: String): ChatDeactivation {
        // The caller may already hold this row. Re-locking is safe and documents that no member or
        // Conversation mutation occurs without the chat aggregate fence.
        val chatRow = Chats.selectAll()
            .where { Chats.chatId eq chatId }
            .forUpdate()
            .singleOrNull()
        require(chatRow != null && chatRow[Chats.status] == 1) { "聊天不存在" }
        GroupInviteLinks.selectAll()
            .where { GroupInviteLinks.chatId eq chatId }
            .orderBy(GroupInviteLinks.id)
            .forUpdate()
            .toList()
        val memberRows = GroupMembers.selectAll()
            .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
            .orderBy(GroupMembers.uid, SortOrder.ASC)
            .forUpdate()
            .toList()
        GroupMemberMutes.selectAll()
            .where { GroupMemberMutes.chatId eq chatId }
            .orderBy(GroupMemberMutes.uid, SortOrder.ASC)
            .forUpdate()
            .toList()
        Conversations.selectAll()
            .where { Conversations.chatId eq chatId }
            .orderBy(Conversations.uid, SortOrder.ASC)
            .forUpdate()
            .toList()
        val chat = getChatByIdInternal(chatId) ?: throw IllegalStateException("聊天数据不完整")
        val memberUids = memberRows.map { it[GroupMembers.uid] }

        Chats.update({ Chats.id eq chatRow[Chats.id] }) {
            it[Chats.status] = 0
            it[Chats.updatedAt] = System.currentTimeMillis()
        }
        GroupMembers.update({ GroupMembers.chatId eq chatId }) {
            it[GroupMembers.status] = 0
        }
        GroupMemberMutes.deleteWhere { GroupMemberMutes.chatId eq chatId }
        Conversations.deleteWhere { Conversations.chatId eq chatId }
        GroupInviteLinks.deleteWhere { GroupInviteLinks.chatId eq chatId }
        return ChatDeactivation(chat, memberUids)
    }

    override fun getMemberUids(chatId: String): List<String> {
        return transaction {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.uid] }
        }
    }

    // ── 查询 ──

    override fun findPersonalChatId(uid1: String, uid2: String): String? {
        return transaction { findPersonalChatIdInternal(uid1, uid2) }
    }

    // ── 管理端查询（全局视图/分页）──

    override fun getChatById(chatId: String): Chat? = transaction {
        Chats.selectAll().where { Chats.chatId eq chatId }
            .map { buildChatFromRow(it) }.singleOrNull()
    }

    override fun listGroups(query: String?, page: Int, size: Int): AdminPage<Chat> = transaction {
        val condition = if (query.isNullOrBlank()) {
            Op.TRUE and (Chats.chatType eq 2)
        } else {
            (Chats.chatType eq 2) and (GroupChats.name like "%$query%")
        }
        val filtered = (Chats innerJoin GroupChats).selectAll().where { condition }
        val total = filtered.count()
        val items = filtered.orderBy(Chats.createdAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(size).offset(((page - 1) * size).toLong())
            .map { buildChatFromRow(it) }
        AdminPage(total, items)
    }

    override fun countGroups(): Long = transaction {
        Chats.selectAll().where { Chats.chatType eq 2 }.count()
    }

    override fun countEventsSince(since: Long): Long = transaction {
        com.virjar.tk.infra.db.SyncEvents.selectAll().where { com.virjar.tk.infra.db.SyncEvents.createdAt greater since }.count()
    }

    override fun listUserChats(uid: String): List<Chat> {
        return transaction {
            val chatIds = GroupMembers.selectAll()
                .where { (GroupMembers.uid eq uid) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.chatId] }.toSet()

            Chats.selectAll()
                .where { (Chats.chatId inList chatIds) and (Chats.status eq 1) }
                .map { row -> buildChatFromRow(row) }
        }
    }

    // ── 内部辅助（在 transaction 内调用） ──

    private fun findPersonalChatIdInternal(uid1: String, uid2: String): String? {
        return Chats.selectAll()
            .where {
                (Chats.personalKey eq personalChatKey(uid1, uid2)) and
                    (Chats.chatType eq 1) and
                    (Chats.status eq 1)
            }
            .singleOrNull()
            ?.get(Chats.chatId)
    }

    private fun ensureConversation(uid: String, chatId: String, chatType: Int, now: Long) {
        Conversations.insertIgnore {
            it[Conversations.uid] = uid
            it[Conversations.chatId] = chatId
            it[Conversations.chatType] = chatType
            it[Conversations.lastMsgSeq] = 0
            it[Conversations.updatedAt] = now
        }
    }

    private fun lockActiveChat(chatId: String): ResultRow {
        hooks.hit(ChatRepositoryStage.BEFORE_CHAT_LOCK, chatId)
        return Chats.selectAll()
            .where { Chats.chatId eq chatId }
            .forUpdate()
            .singleOrNull()
            ?.also { require(it[Chats.status] == 1) { "聊天不存在" } }
            ?: throw IllegalArgumentException("聊天不存在")
    }

    private fun lockRequiredHumanUsers(uids: Collection<String>) {
        val required = uids.distinct().sorted()
        if (required.isEmpty()) return
        val rows = Users.selectAll().where { Users.uid inList required }
            .orderBy(Users.uid, SortOrder.ASC)
            .forUpdate()
            .toList()
        require(rows.size == required.size) { "用户不存在" }
        require(rows.all { it[Users.status] == 1 }) { "用户已停用" }
        require(rows.all { it[Users.role] == UserRole.HUMAN }) {
            "机器人或系统成员只能通过对应的管理入口操作"
        }
    }

    private fun lockActiveMembers(chatId: String): List<Member> = activeMembers(chatId, forUpdate = true)

    private fun activeMembers(chatId: String, forUpdate: Boolean): List<Member> {
        val query = GroupMembers.selectAll()
            .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
            .orderBy(GroupMembers.uid, SortOrder.ASC)
        val rows = if (forUpdate) query.forUpdate().toList() else query.toList()
        return rows.map(ResultRow::toMemberSnapshot)
    }

    private fun getChatByIdInternal(chatId: String): Chat? {
        val row = Chats.selectAll()
            .where { (Chats.chatId eq chatId) and (Chats.status eq 1) }
            .singleOrNull() ?: return null
        return buildChatFromRow(row)
    }

    private fun buildChatFromRow(row: ResultRow): Chat {
        val chatId = row[Chats.chatId]
        val chatType = row[Chats.chatType]
        val maxSeq = row[Chats.maxSeq]

        if (chatType == 1) {
            return Chat(chatId = chatId, chatType = 1, maxSeq = maxSeq)
        }

        val gc = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull()
        val memberCount = GroupMembers.selectAll()
            .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
            .count().toInt()

        return Chat(
            chatId = chatId,
            chatType = 2,
            name = gc?.get(GroupChats.name) ?: "",
            avatar = gc?.get(GroupChats.avatar),
            creator = gc?.get(GroupChats.creator),
            memberCount = memberCount,
            maxSeq = maxSeq,
            notice = gc?.get(GroupChats.notice),
            mutedAll = gc?.get(GroupChats.mutedAll) ?: false,
        )
    }
}

private fun ResultRow.toMemberSnapshot() = Member(
    uid = this[GroupMembers.uid],
    chatId = this[GroupMembers.chatId],
    role = this[GroupMembers.role],
    nickname = this[GroupMembers.nickname],
    joinedAt = this[GroupMembers.joinedAt],
)

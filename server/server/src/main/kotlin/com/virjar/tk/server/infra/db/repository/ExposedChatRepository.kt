package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.chat.ChatRepository
import com.virjar.tk.server.domain.chat.ChatDeactivation
import com.virjar.tk.server.domain.chat.ChatCreation
import com.virjar.tk.server.domain.chat.ChatMutation
import com.virjar.tk.server.domain.chat.GroupCommandFacts
import com.virjar.tk.server.domain.chat.GroupCreationCommand
import com.virjar.tk.server.domain.chat.GroupCreationConflictException
import com.virjar.tk.server.domain.chat.InviteJoinResult
import com.virjar.tk.server.domain.chat.personalChatKey
import com.virjar.tk.server.domain.chat.requireJoinable
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.Friends
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupCreationCommands
import com.virjar.tk.server.infra.db.GroupInviteLinks
import com.virjar.tk.server.infra.db.GroupMemberMutes
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.GroupPolicy
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

enum class ChatRepositoryStage {
    BEFORE_CHAT_LOCK,
}

/** 证明命令在权威 Chat 行 fence 上等待的确定性测试缝隙。 */
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
 * [createPersonalChat] / [createGroupChat] 原子地初始化 GroupMembers 与 Conversations；
 * 这些行是新建聊天的必需组成部分，而不是尽力而为的服务投影。
 */
class ExposedChatRepository(
    private val database: Database,
    private val hooks: ChatRepositoryHooks = ChatRepositoryHooks.None,
) : ChatRepository {

    // ── 聊天 CRUD ──

    override fun createPersonalChat(
        transaction: PgWriteTransactionContext,
        uid1: String,
        uid2: String,
    ): ChatCreation = inWriteTransaction(transaction) {
        val participants = listOf(uid1, uid2).distinct().sorted()
        require(participants.size == 2) { "不能和自己创建私聊" }
        lockRequiredHumanUsers(participants)
        // 联系人写入器在 Friends 之前锁定同一个排序的 User 对，因此在获取
        // User 锁之后这些行不可能发生变化。
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

        val usage = ConversationUsageLedger.lock(participants)
        ConversationUsageLedger.apply(
            usage,
            participants.associateWith { conversationUsageDeltaForInsert() },
        )
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
            insertConversation(uid = uid, chatId = chatId, chatType = 1, now = now)
        }
        ChatCreation(
            chat = Chat(chatId = chatId, chatType = 1),
            created = true,
            recipientUids = participants,
        )
    }


    override fun getOrCreateSavedChat(
        transaction: PgWriteTransactionContext,
        uid: String,
    ): ChatCreation = inWriteTransaction(transaction) {
        lockRequiredHumanUsers(listOf(uid))
        val existingChatId = Chats.selectAll()
            .where {
                (Chats.personalKey eq savedChatKey(uid)) and
                    (Chats.chatType eq 3) and
                    (Chats.status eq 1)
            }
            .singleOrNull()
            ?.get(Chats.chatId)
        if (existingChatId != null) {
            val existing = getChatByIdInternal(existingChatId)
                ?: error("保存会话索引指向了不存在的会话")
            return@inWriteTransaction ChatCreation(
                chat = existing,
                created = false,
                recipientUids = listOf(uid),
            )
        }

        val usage = ConversationUsageLedger.lock(listOf(uid))
        ConversationUsageLedger.apply(usage, mapOf(uid to conversationUsageDeltaForInsert()))
        val chatId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        Chats.insert {
            it[Chats.chatId] = chatId
            it[Chats.chatType] = 3
            it[Chats.personalKey] = savedChatKey(uid)
            it[Chats.maxSeq] = 0
            it[Chats.status] = 1
            it[Chats.createdAt] = now
            it[Chats.updatedAt] = now
        }
        GroupMembers.insert {
            it[GroupMembers.chatId] = chatId
            it[GroupMembers.chatType] = 3
            it[GroupMembers.uid] = uid
            it[GroupMembers.role] = 0
            it[GroupMembers.status] = 1
            it[GroupMembers.joinedAt] = now
        }
        insertConversation(uid = uid, chatId = chatId, chatType = 3, now = now)
        ChatCreation(
            chat = Chat(chatId = chatId, chatType = 3),
            created = true,
            recipientUids = listOf(uid),
        )
    }

    override fun createGroupChat(
        transaction: PgWriteTransactionContext,
        command: GroupCreationCommand,
    ): ChatCreation = inWriteTransaction(transaction) {
        requireCanonicalGroupCreationCommand(command)
        findGroupCreationReplay(command)?.let { return@inWriteTransaction it }

        val recipients = command.memberUids
        // 该 id 尚不存在，因此这是唯一的 User -> 新 Chat 锁顺序例外。
        lockRequiredHumanUsers(recipients)
        // 每条命令都包含其创建者。排序的 User 行锁会序列化两次全新尝试，
        // 而不需要添加无界的进程本地锁映射；第二次读取会观察到获胜者。
        findGroupCreationReplay(command)?.let { return@inWriteTransaction it }
        val usage = ConversationUsageLedger.lock(recipients)
        ConversationUsageLedger.apply(
            usage,
            recipients.associateWith { conversationUsageDeltaForInsert() },
        )
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
            it[GroupChats.name] = command.name
            it[GroupChats.avatar] = command.avatar
            it[GroupChats.creator] = command.creatorUid
            it[GroupChats.mutedAll] = false
            it[GroupChats.updatedAt] = now
        }
        recipients.forEach { uid ->
            GroupMembers.insert {
                it[GroupMembers.chatId] = chatId
                it[GroupMembers.chatType] = 2
                it[GroupMembers.uid] = uid
                it[GroupMembers.role] = if (uid == command.creatorUid) 2 else 0
                it[GroupMembers.status] = 1
                it[GroupMembers.joinedAt] = now
            }
            insertConversation(uid = uid, chatId = chatId, chatType = 2, now = now)
        }
        GroupCreationCommands.insert {
            it[GroupCreationCommands.creatorUid] = command.creatorUid
            it[GroupCreationCommands.operationId] = command.operationId
            it[GroupCreationCommands.requestFingerprint] = command.requestFingerprint
            it[GroupCreationCommands.chatId] = chatId
            it[GroupCreationCommands.createdAt] = now
        }
        val chat = Chat(
            chatId = chatId,
            chatType = 2,
            name = command.name,
            avatar = command.avatar,
            creator = command.creatorUid,
            memberCount = recipients.size,
        )
        ChatCreation(chat = chat, created = true, recipientUids = recipients)
    }

    private fun findGroupCreationReplay(command: GroupCreationCommand): ChatCreation? {
        val receipt = GroupCreationCommands.selectAll().where {
            (GroupCreationCommands.creatorUid eq command.creatorUid) and
                (GroupCreationCommands.operationId eq command.operationId)
        }.singleOrNull() ?: return null
        if (receipt[GroupCreationCommands.requestFingerprint] != command.requestFingerprint) {
            throw GroupCreationConflictException()
        }
        val chatId = receipt[GroupCreationCommands.chatId]
        val chat = checkNotNull(getChatByIdInternal(chatId)) {
            "Group creation receipt points to an inactive or missing chat"
        }
        check(chat.chatType == 2 && chat.creator == command.creatorUid) {
            "Group creation receipt result identity is inconsistent"
        }
        return ChatCreation(chat = chat, created = false, recipientUids = emptyList())
    }

    private fun requireCanonicalGroupCreationCommand(command: GroupCreationCommand) {
        require(UUID.fromString(command.operationId).toString() == command.operationId) {
            "Group creation operation id is not canonical"
        }
        require(
            command.memberUids == GroupPolicy.canonicalInitialMemberUids(
                command.creatorUid,
                command.memberUids,
            ),
        ) {
            "Group creation members are not canonical"
        }
        require(command.name == command.name.trim() && command.name.isNotEmpty()) {
            "Group creation name is not canonical"
        }
        require(command.avatar == null || command.avatar == command.avatar.trim()) {
            "Group creation avatar is not canonical"
        }
        require(
            command.requestFingerprint.length == 64 &&
                command.requestFingerprint.all { it in '0'..'9' || it in 'a'..'f' },
        ) {
            "Group creation fingerprint is not canonical"
        }
    }

    override fun joinByInvite(
        transaction: PgWriteTransactionContext,
        uid: String,
        token: String,
        nowMillis: Long,
    ): InviteJoinResult = inWriteTransaction(transaction) {
        GroupPolicy.requireValidMemberUid(uid)
        // 先不加锁解析拥有该链接的聊天，再按全局 Chat -> User -> Invite -> Member
        // 顺序获取聚合锁。下面带锁的重新读取才是权威。
        val resolvedChatId = GroupInviteLinks.selectAll()
            .where { GroupInviteLinks.token eq token }
            .singleOrNull()
            ?.get(GroupInviteLinks.chatId)
            ?: throw IllegalArgumentException("邀请链接不存在")
        hooks.hit(ChatRepositoryStage.BEFORE_CHAT_LOCK, resolvedChatId)
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
            // 仅当此命令改变成员资格时才应用可变的链接策略。已经活跃的成员
            // 可能是在重试第一次提交后丢失响应的请求；已耗尽/已吊销的链接
            // 绝不能把那次已提交的成功变成之后的失败。
            inviteRow.toInviteLinkRecord().requireJoinable(nowMillis)
            // 每个成员资格写入器都先取 Chat 行。它会把此活跃成员数
            // 谓词与添加、另一次邀请、Bot 授权/恢复及托管对账序列化。
            val activeMemberCount = GroupMembers.selectAll().where {
                (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
            }.count()
            if (activeMemberCount > GroupPolicy.MAX_MEMBERS.toLong()) {
                throw IllegalArgumentException(GroupPolicy.CAPACITY_LIMIT_REASON)
            }
            GroupPolicy.requireAdditionalCapacity(activeMemberCount.toInt(), newMemberCount = 1)
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
                    // 邀请加入始终是普通用户成员资格。托管对账
                    // 有单独的感知角色的内部路径（ensureActiveGroupMember/setRole）。
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

    private inline fun <T> inWriteTransaction(context: PgWriteTransactionContext, block: () -> T): T {
        context.requireExposedTransaction()
        return block()
    }

    override fun getChat(chatId: String): Chat? {
        return transaction(database) { getChatByIdInternal(chatId) }
    }

    override fun updateGroup(
        transaction: PgWriteTransactionContext,
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
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): Chat = inWriteTransaction(transaction) {
        val chatRow = lockActiveChat(chatId)
        operatorUid?.let { lockRequiredHumanUsers(listOf(it)) }
        // Chat 是聚合 fence。读取成员资格而不取其行锁，因为在拆卸期间
        // Bot 身份/授权锁必须先于成员投影锁被获取。
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

    override fun deactivateChat(transaction: PgWriteTransactionContext, chatId: String): ChatDeactivation =
        inWriteTransaction(transaction) {
            deactivateChatInternal(chatId)
        }

    private fun deactivateChatInternal(chatId: String): ChatDeactivation {
        // 调用方可能已经持有此行。重新加锁是安全的，并且表明：没有聊天聚合
        // fence，就不会发生任何 Member 或 Conversation 变更。
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
        val observedConversationUids = Conversations.selectAll()
            .where { Conversations.chatId eq chatId }
            .orderBy(Conversations.uid, SortOrder.ASC)
            .map { it[Conversations.uid] }
        val usage = ConversationUsageLedger.lock(observedConversationUids)
        val conversationRows = Conversations.selectAll()
            .where { Conversations.chatId eq chatId }
            .orderBy(Conversations.uid, SortOrder.ASC)
            .forUpdate()
            .toList()
        check(conversationRows.map { it[Conversations.uid] } == observedConversationUids) {
            "Conversation projections changed while the Chat row was locked"
        }
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
        ConversationUsageLedger.apply(
            usage,
            conversationRows.associate { row ->
                row[Conversations.uid] to conversationUsageDeltaForDelete(row)
            },
        )
        check(Conversations.deleteWhere { Conversations.chatId eq chatId } == conversationRows.size) {
            "Locked Conversation projections changed before Chat deactivation"
        }
        GroupInviteLinks.deleteWhere { GroupInviteLinks.chatId eq chatId }
        return ChatDeactivation(chat, memberUids)
    }

    override fun getMemberUids(chatId: String): List<String> {
        return transaction(database) {
            GroupMembers.selectAll()
                .where { (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1) }
                .map { it[GroupMembers.uid] }
        }
    }

    // ── 查询 ──

    override fun listUserChats(uid: String): List<Chat> {
        return transaction(database) {
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
        val existing = Conversations.selectAll().where {
            (Conversations.uid eq uid) and (Conversations.chatId eq chatId)
        }.singleOrNull()
        val usage = ConversationUsageLedger.lock(listOf(uid))
        if (existing != null) return
        ConversationUsageLedger.apply(
            usage,
            mapOf(uid to conversationUsageDeltaForInsert()),
        )
        insertConversation(uid, chatId, chatType, now)
    }

    private fun insertConversation(uid: String, chatId: String, chatType: Int, now: Long) {
        Conversations.insert {
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

        if (chatType == 1 || chatType == 3) {
            return Chat(chatId = chatId, chatType = chatType, maxSeq = maxSeq)
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

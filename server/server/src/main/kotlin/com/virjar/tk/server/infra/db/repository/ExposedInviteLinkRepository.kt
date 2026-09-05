package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.chat.InviteLinkRecord
import com.virjar.tk.server.domain.chat.InviteLinkRepository
import com.virjar.tk.server.domain.chat.InviteLinkPolicy
import com.virjar.tk.server.domain.chat.InviteLinkCreationCommand
import com.virjar.tk.server.domain.chat.GroupCommandFacts
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupInviteLinks
import com.virjar.tk.server.infra.db.InviteLinkCreationReceipts
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/** GroupInviteLinks 表访问 + 邀请链接业务记录模型。 */
enum class InviteLinkRepositoryStage { BEFORE_CREATION_RESERVATION }

/** 过期收集器/准入边界的确定性缝隙。 */
fun interface InviteLinkRepositoryHooks {
    fun hit(stage: InviteLinkRepositoryStage, operationId: String)

    object None : InviteLinkRepositoryHooks {
        override fun hit(stage: InviteLinkRepositoryStage, operationId: String) = Unit
    }
}

class ExposedInviteLinkRepository internal constructor(
    private val database: Database,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val hooks: InviteLinkRepositoryHooks = InviteLinkRepositoryHooks.None,
) : InviteLinkRepository {

    override fun createInviteLink(
        transaction: PgWriteTransactionContext,
        command: InviteLinkCreationCommand,
        authorize: (GroupCommandFacts) -> Unit,
    ): String = inWriteTransaction(transaction) {
        requireCanonicalCreationCommand(command)
        val chatRow = lockActiveGroup(command.chatId)
        lockRequiredHumanUser(command.creatorUid)
        val members = lockActiveMembers(command.chatId)
        authorize(
            GroupCommandFacts(
                chat = chatSnapshot(chatRow, members.size),
                operator = members.firstOrNull { it.uid == command.creatorUid },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        // 创建者 User 行现在序列化 actor 作用域的身份与保留窗口。
        // 授权先于秘密回执访问与任何新的领域变更。
        val commandNow = wallClockMillis()
        ReliableCommandPolicy.requireActiveIssuedAt(command.issuedAt, commandNow, "邀请链接创建")
        pruneExpiredCreationReceipts(command.creatorUid, commandNow)
        hooks.hit(InviteLinkRepositoryStage.BEFORE_CREATION_RESERVATION, command.operationId)
        val firstCommit = reserveCreationIdentity(command)
        if (!firstCommit) {
            ReliableCommandPolicy.requireActiveIssuedAt(
                command.issuedAt,
                wallClockMillis(),
                "邀请链接创建",
            )
            val replay = findCreationReplay(command)
            if (replay == null) {
                // 过期收集器可能在插入冲突变得可见之后立即获胜。
                // 把该边界重分类为终结性过期；仍然活跃却缺失的
                // 回执仍属于持久化不变量失败。
                ReliableCommandPolicy.requireActiveIssuedAt(
                    command.issuedAt,
                    wallClockMillis(),
                    "邀请链接创建",
                )
                error("Invite-link operation uniqueness conflict has no receipt")
            }
            // 读取并授权一次重放本身就可能跨越有限期限。绝不能仅仅因为
            // 命令在回执查找之前仍处于活跃状态就返回秘密。
            ReliableCommandPolicy.requireActiveIssuedAt(
                command.issuedAt,
                wallClockMillis(),
                "邀请链接创建",
            )
            return@inWriteTransaction replay
        }
        // 全局过期维护刻意与 actor 锁无关。若命令在
        // 校验与身份预留之间越过了其边界，就在任何链接变更之前
        // 回滚该预留，而不是复活一个已被收集的操作。
        ReliableCommandPolicy.requireActiveIssuedAt(
            command.issuedAt,
            wallClockMillis(),
            "邀请链接创建",
        )
        requireCreationReceiptCapacity(command.creatorUid)
        val now = wallClockMillis()
        // 锁定的 Chat 行是 create/join/revoke 使用的聚合 fence。在检查固定基数预算
        // 之前，先退掉不能再接纳成员的链接；无需整行
        // 锁定或物化。
        GroupInviteLinks.deleteWhere {
            (GroupInviteLinks.chatId eq command.chatId) and (
                (GroupInviteLinks.revokedAt neq 0L) or
                    ((GroupInviteLinks.expiresAt neq 0L) and (GroupInviteLinks.expiresAt less now)) or
                    ((GroupInviteLinks.maxUses neq 0) and
                        (GroupInviteLinks.useCount greaterEq GroupInviteLinks.maxUses))
                )
        }
        val retained = GroupInviteLinks.selectAll()
            .where { GroupInviteLinks.chatId eq command.chatId }
            .count()
        require(retained < InviteLinkPolicy.MAX_LINKS_PER_CHAT.toLong()) {
            "群邀请链接数量已达上限"
        }
        val token = UUID.randomUUID().toString()
        GroupInviteLinks.insert {
            it[GroupInviteLinks.token] = token
            it[GroupInviteLinks.chatId] = command.chatId
            it[GroupInviteLinks.creatorUid] = command.creatorUid
            it[GroupInviteLinks.name] = command.name
            it[GroupInviteLinks.maxUses] = command.maxUses
            it[GroupInviteLinks.expiresAt] = command.expiresAt
            it[GroupInviteLinks.createdAt] = now
        }
        completeCreationReceipt(command, token)
        token
    }

    private fun reserveCreationIdentity(command: InviteLinkCreationCommand): Boolean =
        InviteLinkCreationReceipts.insertIgnore {
            it[actorUid] = command.creatorUid
            it[operationId] = command.operationId
            it[requestFingerprint] = command.requestFingerprint
            it[chatId] = command.chatId
            it[token] = null
            it[issuedAt] = command.issuedAt
            it[expiresAt] = ReliableCommandPolicy.expiresAt(command.issuedAt)
            it[createdAt] = wallClockMillis()
        }.insertedCount == 1

    private fun findCreationReplay(command: InviteLinkCreationCommand): String? {
        val receipt = InviteLinkCreationReceipts.selectAll().where {
            (InviteLinkCreationReceipts.actorUid eq command.creatorUid) and
                (InviteLinkCreationReceipts.operationId eq command.operationId)
        }.forUpdate().singleOrNull() ?: return null
        if (
            receipt[InviteLinkCreationReceipts.requestFingerprint] != command.requestFingerprint ||
            receipt[InviteLinkCreationReceipts.chatId] != command.chatId
        ) {
            throw ReliableCommandConflictException("邀请链接操作标识已用于不同请求")
        }
        return checkNotNull(receipt[InviteLinkCreationReceipts.token]) {
            "Committed invite-link creation receipt has no result"
        }
    }

    private fun completeCreationReceipt(command: InviteLinkCreationCommand, token: String) {
        check(InviteLinkCreationReceipts.update({
            (InviteLinkCreationReceipts.actorUid eq command.creatorUid) and
                (InviteLinkCreationReceipts.operationId eq command.operationId) and
                InviteLinkCreationReceipts.token.isNull()
        }) {
            it[InviteLinkCreationReceipts.token] = token
        } == 1) { "Reserved invite-link creation receipt was lost" }
    }

    /** 调用方持有创建者 User 行，序列化此 actor 作用域的保留窗口。 */
    private fun pruneExpiredCreationReceipts(creatorUid: String, nowMillis: Long) {
        InviteLinkCreationReceipts.deleteWhere {
            (InviteLinkCreationReceipts.actorUid eq creatorUid) and
                (InviteLinkCreationReceipts.expiresAt less nowMillis)
        }
    }

    /** 先检查精确重放；只有全新的预留才会在硬上限处被拒绝。 */
    private fun requireCreationReceiptCapacity(creatorUid: String) {
        val retained = InviteLinkCreationReceipts.selectAll().where {
            InviteLinkCreationReceipts.actorUid eq creatorUid
        }.count()
        if (retained > InviteLinkPolicy.MAX_CREATION_RECEIPTS_PER_ACTOR.toLong()) {
            throw ReliableCommandCapacityException("邀请链接可靠重试窗口已满")
        }
    }

    private fun requireCanonicalCreationCommand(command: InviteLinkCreationCommand) {
        check(command.operationId == UUID.fromString(command.operationId).toString()) {
            "邀请链接操作标识不是规范 UUID"
        }
        check(command.chatId == UUID.fromString(command.chatId).toString()) {
            "群聊标识不是规范 UUID"
        }
        check(command.issuedAt >= 0L) { "邀请链接操作签发时间非法" }
        check(command.name == command.name.trim() && command.name.length <= 200) {
            "邀请链接名称不是规范值"
        }
        check(command.name.none(Char::isISOControl)) { "邀请链接名称包含非法字符" }
        check(command.maxUses >= 0 && command.expiresAt >= 0L) { "邀请链接容量参数非法" }
        check(
            command.requestFingerprint.length == 64 &&
                command.requestFingerprint.all { it in '0'..'9' || it in 'a'..'f' },
        ) { "邀请链接操作指纹非法" }
    }

    override fun listInviteLinks(chatId: String): List<InviteLinkRecord> {
        return transaction(database) {
            val links = GroupInviteLinks.selectAll()
                .where { GroupInviteLinks.chatId eq chatId }
                .orderBy(GroupInviteLinks.createdAt, SortOrder.DESC)
                .limit(InviteLinkPolicy.MAX_LINKS_PER_CHAT + 1)
                .map { it.toInviteLinkRecord() }
            check(links.size <= InviteLinkPolicy.MAX_LINKS_PER_CHAT) {
                "群邀请链接持久化数量超出边界"
            }
            links
        }
    }

    override fun revokeInviteLink(
        transaction: PgWriteTransactionContext,
        expectedChatId: String,
        operatorUid: String,
        token: String,
        nowMillis: Long,
        authorize: (GroupCommandFacts) -> Unit,
    ): InviteLinkRecord = inWriteTransaction(transaction) {
        val chatRow = lockActiveGroup(expectedChatId)
        lockRequiredHumanUser(operatorUid)
        val inviteRow = GroupInviteLinks.selectAll().where { GroupInviteLinks.token eq token }
            .forUpdate()
            .singleOrNull()
            ?: throw IllegalArgumentException("邀请链接不存在")
        require(inviteRow[GroupInviteLinks.chatId] == expectedChatId) { "邀请链接归属已变更" }
        val members = lockActiveMembers(expectedChatId)
        authorize(
            GroupCommandFacts(
                chat = chatSnapshot(chatRow, members.size),
                operator = members.firstOrNull { it.uid == operatorUid },
                activeMemberUids = members.map(Member::uid),
            ),
        )
        GroupInviteLinks.update({ GroupInviteLinks.token eq token }) {
            it[GroupInviteLinks.revokedAt] = nowMillis
        }
        inviteRow.toInviteLinkRecord().copy(revokedAt = nowMillis)
    }

    override fun getInviteLink(token: String): InviteLinkRecord? {
        return transaction(database) {
            GroupInviteLinks.selectAll().where { GroupInviteLinks.token eq token }
                .map { it.toInviteLinkRecord() }.singleOrNull()
        }
    }

    private fun lockActiveGroup(chatId: String): ResultRow {
        val row = Chats.selectAll().where { Chats.chatId eq chatId }
            .forUpdate()
            .singleOrNull()
            ?: throw IllegalArgumentException("聊天不存在")
        require(row[Chats.status] == 1 && row[Chats.chatType] == 2) { "群聊不存在" }
        require(GroupChats.selectAll().where { GroupChats.chatId eq chatId }.singleOrNull() != null) {
            "群聊数据不完整"
        }
        return row
    }

    private fun lockRequiredHumanUser(uid: String) {
        val row = Users.selectAll().where { Users.uid eq uid }.forUpdate().singleOrNull()
            ?: throw IllegalArgumentException("用户不存在")
        require(row[Users.status] == 1) { "用户已停用" }
        require(row[Users.role] == UserRole.HUMAN) {
            "机器人或系统成员只能通过对应的管理入口操作"
        }
    }

    private fun lockActiveMembers(chatId: String): List<Member> = GroupMembers.selectAll().where {
        (GroupMembers.chatId eq chatId) and (GroupMembers.status eq 1)
    }.orderBy(GroupMembers.uid, SortOrder.ASC).forUpdate().map { row ->
        Member(
            uid = row[GroupMembers.uid],
            chatId = row[GroupMembers.chatId],
            role = row[GroupMembers.role],
            nickname = row[GroupMembers.nickname],
            joinedAt = row[GroupMembers.joinedAt],
        )
    }

    private fun chatSnapshot(chatRow: ResultRow, memberCount: Int): Chat {
        val chatId = chatRow[Chats.chatId]
        val group = GroupChats.selectAll().where { GroupChats.chatId eq chatId }.single()
        return Chat(
            chatId = chatId,
            chatType = chatRow[Chats.chatType],
            name = group[GroupChats.name],
            avatar = group[GroupChats.avatar],
            creator = group[GroupChats.creator],
            memberCount = memberCount,
            maxSeq = chatRow[Chats.maxSeq],
            notice = group[GroupChats.notice],
            mutedAll = group[GroupChats.mutedAll],
        )
    }

    private inline fun <T> inWriteTransaction(
        context: PgWriteTransactionContext,
        block: () -> T,
    ): T {
        context.requireExposedTransaction()
        return block()
    }

}

internal fun ResultRow.toInviteLinkRecord() = InviteLinkRecord(
    token = this[GroupInviteLinks.token],
    chatId = this[GroupInviteLinks.chatId],
    creatorUid = this[GroupInviteLinks.creatorUid],
    name = this[GroupInviteLinks.name],
    maxUses = this[GroupInviteLinks.maxUses],
    useCount = this[GroupInviteLinks.useCount],
    expiresAt = this[GroupInviteLinks.expiresAt],
    revokedAt = this[GroupInviteLinks.revokedAt],
    createdAt = this[GroupInviteLinks.createdAt],
)

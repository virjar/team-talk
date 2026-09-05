package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.domain.conversation.ConversationService
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.Friends
import com.virjar.tk.server.infra.db.GroupChats
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.OrganizationManagedChatProjections
import com.virjar.tk.server.infra.db.SyncStreams
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.toUserAvatar
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.ConversationPage
import com.virjar.tk.protocol.model.ConversationPageRequest
import com.virjar.tk.protocol.model.SyncCheckpointChatPage
import com.virjar.tk.protocol.model.SyncCheckpointContactPage
import com.virjar.tk.protocol.model.SyncCheckpointHeader
import com.virjar.tk.protocol.model.SyncCheckpointPageRequest
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.payload.SyncDatasetIdPolicy
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import java.util.UUID

/** 构建完整 checkpoint，它先于常规的持久事件尾部重放。 */
class SyncCheckpointService(
    private val database: Database,
    private val dispatcher: SyncEventDispatcher,
    private val leases: SyncReplayLeaseRegistry,
    private val conversationService: ConversationService,
    val datasetId: String,
) {
    init {
        SyncDatasetIdPolicy.requireValid(datasetId)
    }

    /**
     * 在持有持久化/投递门闩的同时，锚定事件尾部并发布其租约。
     * 之后的事件可能在此快照之前或之后提交，但其实时分发不能越过
     * 租约发布；因此客户端总能在尾部恢复它。
     */
    suspend fun beginCheckpoint(
        uid: String,
        sessionId: String,
        claimedDatasetId: String,
    ): SyncCheckpointHeader {
        require(claimedDatasetId == datasetId) { "同步数据集已变化，请重新连接" }
        return dispatcher.withDeliveryGate(uid) {
            val checkpointId = UUID.randomUUID().toString()
            leases.reserveCheckpoint(uid, sessionId, checkpointId)
            val snapshot = try {
                transaction(
                    transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
                    readOnly = true,
                    db = database,
                ) {
                    val user = Users.selectAll()
                        .where { Users.uid eq uid }
                        .limit(1)
                        .singleOrNull()
                        ?.toUser()
                        ?: throw IllegalArgumentException("用户不存在")
                    val baseEventId = SyncStreams.selectAll()
                        .where { SyncStreams.uid eq uid }
                        .limit(1)
                        .singleOrNull()
                        ?.get(SyncStreams.lastSeq)
                        ?: 0L
                    CheckpointAnchor(user, baseEventId)
                }
            } catch (error: Throwable) {
                leases.release(uid, sessionId)
                throw error
            }
            if (!leases.publishCheckpoint(uid, sessionId, checkpointId, snapshot.baseEventId)) {
                throw CancellationException("sync connection closed while anchoring checkpoint")
            }
            SyncCheckpointHeader(
                datasetId = datasetId,
                checkpointId = checkpointId,
                baseEventId = snapshot.baseEventId,
                currentUser = snapshot.user,
            )
        }
    }

    fun listContacts(
        uid: String,
        sessionId: String,
        request: SyncCheckpointPageRequest,
    ): SyncCheckpointContactPage {
        leases.requireCheckpoint(uid, sessionId, request.checkpointId)
        val cursor = requireIdentityCursor(request.cursor, "联系人")
        return transaction(
            transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
            readOnly = true,
            db = database,
        ) {
            val rows = Friends.join(
                otherTable = Users,
                joinType = JoinType.INNER,
                additionalConstraint = { Friends.friendUid eq Users.uid },
            ).selectAll().where {
                val active = (Friends.uid eq uid) and (Friends.status eq ACTIVE)
                if (cursor == null) active else active and (Friends.friendUid greater cursor)
            }.orderBy(Friends.friendUid, SortOrder.ASC)
                .limit(SyncCheckpointContactPage.MAX_PAGE_SIZE + 1)
                .toList()

            val hasMore = rows.size > SyncCheckpointContactPage.MAX_PAGE_SIZE
            val selected = if (hasMore) rows.take(SyncCheckpointContactPage.MAX_PAGE_SIZE) else rows
            SyncCheckpointContactPage(
                items = selected.map { row ->
                    Contact(
                        uid = uid,
                        friendUid = row[Friends.friendUid],
                        remark = row[Friends.remark],
                        status = ACTIVE,
                        user = row.toUser(),
                    )
                },
                nextCursor = if (hasMore) selected.last()[Friends.friendUid] else null,
            )
        }
    }

    fun listChats(
        uid: String,
        sessionId: String,
        request: SyncCheckpointPageRequest,
    ): SyncCheckpointChatPage {
        leases.requireCheckpoint(uid, sessionId, request.checkpointId)
        val cursor = requireIdentityCursor(request.cursor, "聊天")
        return transaction(
            transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ,
            readOnly = true,
            db = database,
        ) {
            val rows = activeChatJoin().selectAll().where {
                val activeAndReadable =
                    (GroupMembers.uid eq uid) and
                        (GroupMembers.status eq ACTIVE) and
                        (Chats.status eq ACTIVE) and
                        (
                            OrganizationManagedChatProjections.chatId.isNull() or
                                (
                                    (OrganizationManagedChatProjections.desiredActive eq true) and
                                        (OrganizationManagedChatProjections.desiredRevision eq
                                            OrganizationManagedChatProjections.appliedRevision) and
                                        OrganizationManagedChatProjections.lastFailure.isNull()
                                    )
                            )
                if (cursor == null) activeAndReadable else activeAndReadable and (Chats.chatId greater cursor)
            }.orderBy(Chats.chatId, SortOrder.ASC)
                .limit(SyncCheckpointChatPage.MAX_PAGE_SIZE + 1)
                .toList()

            val hasMore = rows.size > SyncCheckpointChatPage.MAX_PAGE_SIZE
            val selected = if (hasMore) rows.take(SyncCheckpointChatPage.MAX_PAGE_SIZE) else rows
            val memberCounts = activeMemberCounts(selected.map { it[Chats.chatId] })
            SyncCheckpointChatPage(
                items = selected.map { row -> row.toChat(memberCounts[row[Chats.chatId]] ?: 0) },
                nextCursor = if (hasMore) selected.last()[Chats.chatId] else null,
            )
        }
    }

    suspend fun listConversations(
        uid: String,
        sessionId: String,
        request: SyncCheckpointPageRequest,
    ): ConversationPage {
        leases.requireCheckpoint(uid, sessionId, request.checkpointId)
        return conversationService.listConversationPage(uid, ConversationPageRequest(request.cursor))
    }

    fun releaseSession(uid: String, sessionId: String) {
        leases.release(uid, sessionId)
    }

    private fun activeChatJoin() = GroupMembers
        .join(
            otherTable = Chats,
            joinType = JoinType.INNER,
            onColumn = GroupMembers.chatId,
            otherColumn = Chats.chatId,
        )
        .join(
            otherTable = GroupChats,
            joinType = JoinType.LEFT,
            onColumn = Chats.chatId,
            otherColumn = GroupChats.chatId,
        )
        .join(
            otherTable = OrganizationManagedChatProjections,
            joinType = JoinType.LEFT,
            onColumn = Chats.chatId,
            otherColumn = OrganizationManagedChatProjections.chatId,
        )

    private fun activeMemberCounts(chatIds: List<String>): Map<String, Int> {
        if (chatIds.isEmpty()) return emptyMap()
        val memberCount = GroupMembers.id.count()
        return GroupMembers.select(GroupMembers.chatId, memberCount)
            .where { (GroupMembers.chatId inList chatIds.distinct()) and (GroupMembers.status eq ACTIVE) }
            .groupBy(GroupMembers.chatId)
            .associate { row ->
                row[GroupMembers.chatId] to row[memberCount].coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
    }

    private fun ResultRow.toUser() = User(
        uid = this[Users.uid],
        username = this[Users.username],
        name = this[Users.name],
        avatar = toUserAvatar(),
        phone = this[Users.phone],
        sex = this[Users.sex],
        role = this[Users.role],
        status = this[Users.status],
        revision = this[Users.revision],
    )

    private fun ResultRow.toChat(memberCount: Int): Chat = Chat(
        chatId = this[Chats.chatId],
        chatType = this[Chats.chatType],
        name = getOrNull(GroupChats.name),
        avatar = getOrNull(GroupChats.avatar),
        creator = getOrNull(GroupChats.creator),
        memberCount = memberCount,
        maxSeq = this[Chats.maxSeq],
        notice = getOrNull(GroupChats.notice),
        mutedAll = getOrNull(GroupChats.mutedAll) ?: false,
    )

    private fun requireIdentityCursor(cursor: String?, label: String): String? {
        if (cursor == null) return null
        require(cursor.length <= IDENTITY_MAX_LENGTH) { "$label checkpoint 游标无效" }
        return cursor
    }

    private data class CheckpointAnchor(val user: User, val baseEventId: Long)

    private companion object {
        const val ACTIVE = 1
        const val IDENTITY_MAX_LENGTH = 36
    }
}

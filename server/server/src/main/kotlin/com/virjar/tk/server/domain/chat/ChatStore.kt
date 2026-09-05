package com.virjar.tk.server.domain.chat

import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Member
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Chat 领域热缓存。
 *
 * 缓存 chat 基础信息、成员列表/角色和全员禁言状态。
 * 读操作 cache miss 时从 Repository 加载并填充缓存。
 * 写命令由调用方的 PG 事务持有；只有提交后的回调可以发布缓存失效。
 *
 * 消息序号由 MessageStore 与消息事实原子分配，不进入本缓存。PostgreSQL Chat.maxSeq 只是完成
 * 消息投影后的派生水位；提交后必须失效本缓存，不能让旧水位遮蔽已经完成的投影。
 */
class ChatStore(
    private val repo: ChatRepository,
    private val memberRepo: ChatMemberRepository,
    private val inviteRepo: InviteLinkRepository,
    cacheStripeCount: Int = DEFAULT_CACHE_STRIPES,
    cacheEntriesPerStripe: Int = DEFAULT_ENTRIES_PER_STRIPE,
    private val maxCachedMemberRolesPerChat: Int = DEFAULT_MAX_CACHED_MEMBER_ROLES_PER_CHAT,
) : ManagedChatProjectionCache {
    init {
        require(cacheStripeCount > 0) { "cacheStripeCount must be positive" }
        require(cacheEntriesPerStripe > 0) { "cacheEntriesPerStripe must be positive" }
        require(maxCachedMemberRolesPerChat > 0) { "maxCachedMemberRolesPerChat must be positive" }
    }

    /**
     * 缓存加载与失效是每个聊天的一次可线性化操作。
     *
     * 没有这个闸门，一次缓存未命中可能读到旧的 DB 快照、暂停，然后在 remove/deactivate
     * 已经将其失效之后才回填缓存。固定分片避免无界的锁映射，同时无关聊天仍可并发推进。
     * 每个分片是一个具有固定条目数的访问有序 LRU，因此仅仅读取新聊天也无法在整个服务器
     * 生命周期内保留每一个聊天/成员快照。
     */
    private val cacheStripes = Array(cacheStripeCount) { ChatCacheStripe(cacheEntriesPerStripe) }

    // ══════════════════════════════════════
    // 读操作（缓存优先）
    // ══════════════════════════════════════

    fun getChat(chatId: String): Chat? {
        return withCacheGate(chatId) {
            getOrLoad(chatId)?.chat
        }
    }

    // ── 成员读 ──

    fun getMemberUids(chatId: String): List<String> {
        return withCacheGate(chatId) {
            val cached = getOrLoad(chatId) ?: return@withCacheGate emptyList()
            cached.memberRoles?.keys?.toList()
                ?: if (cached.memberSnapshotOversized) {
                    memberRepo.getMemberUids(chatId)
                } else {
                    loadMembersForList(chatId, cached).map(Member::uid)
                }
        }
    }

    fun isMember(chatId: String, uid: String): Boolean {
        return withCacheGate(chatId) {
            val cached = getOrLoad(chatId) ?: return@withCacheGate false
            cached.memberRoles?.containsKey(uid) ?: (memberRepo.getMember(chatId, uid) != null)
        }
    }

    fun getMember(chatId: String, uid: String): Member? {
        return withCacheGate(chatId) {
            val cached = getOrLoad(chatId) ?: return@withCacheGate null
            val cachedRoles = cached.memberRoles
            if (cachedRoles != null) {
                val role = cachedRoles[uid] ?: return@withCacheGate null
                return@withCacheGate Member(uid = uid, chatId = chatId, role = role)
            }
            memberRepo.getMember(chatId, uid)
        }
    }

    fun getMembers(chatId: String): List<Member> {
        return withCacheGate(chatId) {
            val cached = getOrLoad(chatId) ?: return@withCacheGate emptyList()
            cached.memberRoles?.let { roles -> membersFromRoles(chatId, roles) }
                ?: if (cached.memberSnapshotOversized) {
                    memberRepo.getMembers(chatId)
                } else {
                    loadMembersForList(chatId, cached)
                }
        }
    }

    fun getActiveChatIds(uid: String): Set<String> = memberRepo.getActiveChatIds(uid)

    internal fun getActiveChatIds(transaction: PgReadTransactionContext, uid: String): Set<String> =
        memberRepo.getActiveChatIds(transaction, uid)

    internal fun getProjectedChatIds(uid: String): Set<String> = memberRepo.getProjectedChatIds(uid)

    internal fun getProjectedChatIds(transaction: PgReadTransactionContext, uid: String): Set<String> =
        memberRepo.getProjectedChatIds(transaction, uid)

    internal fun lockChats(
        transaction: PgWriteTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> = memberRepo.lockChats(transaction, chatIds, requireActive)

    internal fun getActiveMember(
        transaction: PgReadTransactionContext,
        chatId: String,
        uid: String,
    ): Member? = memberRepo.getActiveMember(transaction, chatId, uid)

    /** 调用方事务中已排序的活跃成员 uid；先通过 [lockChats] 串行化。 */
    internal fun getActiveMemberUids(
        transaction: PgReadTransactionContext,
        chatId: String,
    ): List<String> = memberRepo.getActiveMemberUids(transaction, chatId)

    internal fun admitMessage(
        transaction: PgWriteTransactionContext,
        chatId: String,
        senderUid: String,
        nowMillis: Long,
        afterChatLocked: () -> Unit = {},
        authorize: (MessageAdmissionFacts) -> Unit,
    ): MessageAdmission = memberRepo.admitMessage(
        transaction,
        chatId,
        senderUid,
        nowMillis,
        afterChatLocked,
        authorize,
    )

    // ── 禁言读 ──

    fun isMuted(chatId: String, uid: String): Boolean {
        return withCacheGate(chatId) {
            if (getOrLoad(chatId) == null) return@withCacheGate false
            // Repository 在每次授权检查时都对当前时钟评估 expiresAt。缓存的布尔值会让
            // 一个已过期的禁言永远有效。
            memberRepo.isMuted(chatId, uid)
        }
    }

    // ══════════════════════════════════════
    // 写操作（Repository + 缓存更新）
    // ══════════════════════════════════════

    /** 事务绑定的创建；缓存发布推迟到工作单元提交之后。 */
    internal fun createPersonalChat(
        transaction: PgWriteTransactionContext,
        uid1: String,
        uid2: String,
    ): ChatCreation = repo.createPersonalChat(transaction, uid1, uid2)

    /** 事务绑定的保存消息会话 get-or-create；唯一的 personalKey 行是重放围栏。 */
    internal fun getOrCreateSavedChat(
        transaction: PgWriteTransactionContext,
        uid: String,
    ): ChatCreation = repo.getOrCreateSavedChat(transaction, uid)

    /** 事务绑定的创建；缓存发布推迟到工作单元提交之后。 */
    internal fun createGroupChat(
        transaction: PgWriteTransactionContext,
        command: GroupCreationCommand,
    ): ChatCreation = repo.createGroupChat(transaction, command)

    /** 事务绑定的邀请消耗；缓存失效仅在提交之后发布。 */
    internal fun joinByInvite(
        transaction: PgWriteTransactionContext,
        uid: String,
        token: String,
        nowMillis: Long,
    ): InviteJoinResult = repo.joinByInvite(transaction, uid, token, nowMillis)

    internal fun invalidateCommittedInviteJoin(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    internal fun updateGroup(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        name: String?,
        avatar: String?,
        notice: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = repo.updateGroup(
        transaction,
        chatId,
        operatorUid,
        name,
        avatar,
        notice,
        authorize,
    )

    internal fun lockForDeactivation(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): Chat = repo.lockForDeactivation(transaction, chatId, operatorUid, authorize)

    /** 事务绑定的停用；缓存发布推迟到提交之后。 */
    internal fun deactivateChat(
        transaction: PgWriteTransactionContext,
        chatId: String,
    ): ChatDeactivation = repo.deactivateChat(transaction, chatId)

    internal fun invalidateCommittedDeactivation(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    /** 事务绑定的添加。在调用方发布提交之前，缓存状态保持不动。 */
    internal fun addMembers(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        uids: List<String>,
        requiredHumanUids: Set<String> = emptySet(),
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition = memberRepo.addMembers(
        transaction = transaction,
        chatId = chatId,
        operatorUid = operatorUid,
        uids = uids,
        requiredHumanUids = requiredHumanUids,
        authorize = authorize,
    )

    /** 事务绑定的成员移除。缓存状态在提交前被刻意保持不动。 */
    internal fun removeMember(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval = memberRepo.removeMember(
        transaction = transaction,
        chatId = chatId,
        operatorUid = operatorUid,
        targetUid = targetUid,
        authorize = authorize,
    )

    /** 外部服务投影使用的事务绑定幂等移除。 */
    internal fun removeMemberIfPresent(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        requireActiveChat: Boolean = true,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval? = memberRepo.removeMemberIfPresent(
        transaction = transaction,
        chatId = chatId,
        operatorUid = operatorUid,
        targetUid = targetUid,
        requireActiveChat = requireActiveChat,
        authorize = authorize,
    )

    internal fun cleanupServiceMemberProjection(
        transaction: PgWriteTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup? = memberRepo.cleanupServiceMemberProjection(
        transaction = transaction,
        chatId = chatId,
        uid = uid,
        lockedChat = lockedChat,
    )

    /** 仅在事务绑定的成员添加/移除及其事件提交之后调用。 */
    internal fun invalidateCommittedMembershipChange(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    override fun invalidateManagedChat(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    internal fun transferOwner(
        transaction: PgWriteTransactionContext,
        chatId: String,
        oldOwnerUid: String,
        newOwnerUid: String,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = memberRepo.transferOwner(
        transaction,
        chatId,
        oldOwnerUid,
        newOwnerUid,
        authorize,
    )

    internal fun setRole(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        role: Int,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = memberRepo.setRole(
        transaction,
        chatId,
        operatorUid,
        targetUid,
        role,
        authorize,
    )

    internal fun setMemberMute(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        expiresAt: Long?,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = memberRepo.setMemberMute(
        transaction,
        chatId,
        operatorUid,
        targetUid,
        expiresAt,
        authorize,
    )

    internal fun setMuteAll(
        transaction: PgWriteTransactionContext,
        chatId: String,
        operatorUid: String?,
        mutedAll: Boolean,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = memberRepo.setMuteAll(
        transaction,
        chatId,
        operatorUid,
        mutedAll,
        authorize,
    )

    // ── 邀请链接（不缓存，直接委托 Repository） ──

    internal fun createInviteLink(
        transaction: PgWriteTransactionContext,
        command: InviteLinkCreationCommand,
        authorize: (GroupCommandFacts) -> Unit,
    ): String = inviteRepo.createInviteLink(
        transaction,
        command,
        authorize,
    )

    fun listInviteLinks(chatId: String) = inviteRepo.listInviteLinks(chatId)

    internal fun revokeInviteLink(
        transaction: PgWriteTransactionContext,
        expectedChatId: String,
        operatorUid: String,
        token: String,
        nowMillis: Long,
        authorize: (GroupCommandFacts) -> Unit,
    ): InviteLinkRecord = inviteRepo.revokeInviteLink(
        transaction,
        expectedChatId,
        operatorUid,
        token,
        nowMillis,
        authorize,
    )

    fun getInviteLink(token: String) = inviteRepo.getInviteLink(token)

    /** 在任何已提交的聚合命令之后不发布过期的聊天/成员状态。 */
    internal fun invalidateCommittedCommand(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    // ══════════════════════════════════════
    // 内部方法
    // ══════════════════════════════════════

    private fun ChatCacheStripe.getOrLoad(chatId: String): CachedChat? {
        entries[chatId]?.let { return it }
        val loaded = repo.getChat(chatId)?.let { chat -> CachedChat(chat) } ?: return null
        entries[chatId] = loaded
        evictEldestIfNeeded()
        return loaded
    }

    private fun loadMembersForList(chatId: String, cached: CachedChat): List<Member> {
        cached.memberRoles?.let { return membersFromRoles(chatId, it) }
        val members = memberRepo.getMembers(chatId)
        if (members.size > maxCachedMemberRolesPerChat) {
            // 不要仅仅为了决定不保留它就再构建一个完整的 Map。这个列表是调用方无法避免的
            // 响应分配，并在调用之后成为可回收的。
            cached.memberSnapshotOversized = true
            return members
        }
        val roles = linkedMapOf<String, Int>()
        members.forEach { member -> roles[member.uid] = member.role }
        cached.memberRoles = roles.toMap()
        return membersFromRoles(chatId, cached.memberRoles.orEmpty())
    }

    private fun membersFromRoles(chatId: String, roles: Map<String, Int>): List<Member> =
        roles.map { (uid, role) -> Member(uid = uid, chatId = chatId, role = role) }

    private fun ChatCacheStripe.invalidateChat(chatId: String) {
        entries.remove(chatId)
    }

    private inline fun <T> withCacheGate(chatId: String, block: ChatCacheStripe.() -> T): T {
        val stripe = cacheStripes[(chatId.hashCode() and Int.MAX_VALUE) % cacheStripes.size]
        return stripe.lock.withLock { stripe.block() }
    }

    internal fun cachedChatCountForTest(): Int = cacheStripes.sumOf { stripe ->
        stripe.lock.withLock { stripe.entries.size }
    }

    internal fun cachedMemberRoleCountForTest(): Int = cacheStripes.sumOf { stripe ->
        stripe.lock.withLock { stripe.entries.values.sumOf { it.memberRoles?.size ?: 0 } }
    }

    private data class CachedChat(
        val chat: Chat,
        var memberRoles: Map<String, Int>? = null,
        var memberSnapshotOversized: Boolean = false,
    )

    private class ChatCacheStripe(private val maxEntries: Int) {
        val lock = ReentrantLock()
        val entries = LinkedHashMap<String, CachedChat>(minOf(maxEntries, 64) + 1, 0.75f, true)

        fun evictEldestIfNeeded() {
            while (entries.size > maxEntries) {
                val iterator = entries.entries.iterator()
                check(iterator.hasNext()) { "Oversized chat cache stripe has no eldest entry" }
                iterator.next()
                iterator.remove()
            }
        }
    }

    private companion object {
        const val DEFAULT_CACHE_STRIPES = 256
        const val DEFAULT_ENTRIES_PER_STRIPE = 16
        const val DEFAULT_MAX_CACHED_MEMBER_ROLES_PER_CHAT = 64
    }
}

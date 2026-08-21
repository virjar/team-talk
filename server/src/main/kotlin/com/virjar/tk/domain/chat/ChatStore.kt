package com.virjar.tk.domain.chat

import com.virjar.tk.domain.transaction.PgTransactionContext
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Chat 领域热缓存。
 *
 * 缓存 chat 基础信息、成员列表/角色、禁言状态、maxSeq。
 * 读操作 cache miss 时从 Repository 加载并填充缓存。
 * 写命令由调用方的 PG 事务持有；只有提交后的回调可以发布缓存失效。
 *
 * 消息序号由 transaction-bound admission 在 DB 中分配；缓存只镜像已提交的 maxSeq。
 * DB 先于消息落库允许出现序号空洞，但绝不能在进程重启后复用旧序号覆盖消息。
 */
class ChatStore(
    private val repo: ChatRepository,
    private val memberRepo: ChatMemberRepository,
    private val inviteRepo: InviteLinkRepository,
) : ManagedChatProjectionCache {
    /**
     * Cache loads and invalidations are one per-chat linearizable operation.
     *
     * Without this gate a cache miss can read an old DB snapshot, pause, and refill the cache
     * after remove/deactivate has already invalidated it. Fixed stripes avoid an unbounded lock
     * map while unrelated chats can still make progress concurrently.
     */
    private val cacheGates = Array(CACHE_GATE_STRIPES) { ReentrantLock() }

    // ── 基础信息 ──
    private val chats = ConcurrentHashMap<String, Chat>()

    // ── maxSeq（原子递增，消息热路径） ──
    private val chatMaxSeq = ConcurrentHashMap<String, AtomicLong>()

    // ── 成员 ──
    private val memberUids = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val memberRoles = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val membersLoaded = ConcurrentHashMap<String, Boolean>()

    // ── 全员禁言（成员禁言带到期时间，不缓存布尔值） ──
    private val chatMutedAll = ConcurrentHashMap<String, Boolean>()

    // ══════════════════════════════════════
    // 读操作（缓存优先）
    // ══════════════════════════════════════

    fun getChat(chatId: String): Chat? {
        return withCacheGate(chatId) {
            chats[chatId] ?: repo.getChat(chatId)?.also(::indexChat)
        }
    }

    fun getMaxSeq(chatId: String): Long {
        return withCacheGate(chatId) {
            chatMaxSeq[chatId]?.get() ?: run {
                // 触发 chat 加载以初始化 maxSeq
                val chat = getChat(chatId) ?: return@withCacheGate 0L
                chatMaxSeq[chatId]?.get() ?: chat.maxSeq
            }
        }
    }

    // ── 成员读 ──

    fun getMemberUids(chatId: String): List<String> {
        return withCacheGate(chatId) {
            if (getChat(chatId) == null) return@withCacheGate emptyList()
            if (membersLoaded[chatId] != true) loadMembers(chatId)
            memberUids[chatId]?.toList() ?: emptyList()
        }
    }

    fun isMember(chatId: String, uid: String): Boolean {
        return withCacheGate(chatId) {
            if (getChat(chatId) == null) return@withCacheGate false
            ensureMembersLoaded(chatId)
            memberRoles[chatId]?.containsKey(uid) == true
        }
    }

    fun getMember(chatId: String, uid: String): Member? {
        return withCacheGate(chatId) {
            if (getChat(chatId) == null) return@withCacheGate null
            ensureMembersLoaded(chatId)
            val role = memberRoles[chatId]?.get(uid) ?: return@withCacheGate null
            Member(uid = uid, chatId = chatId, role = role)
        }
    }

    fun getMembers(chatId: String): List<Member> {
        return withCacheGate(chatId) {
            if (getChat(chatId) == null) return@withCacheGate emptyList()
            ensureMembersLoaded(chatId)
            val roles = memberRoles[chatId] ?: return@withCacheGate emptyList()
            roles.entries.map { (uid, role) -> Member(uid = uid, chatId = chatId, role = role) }
        }
    }

    fun getActiveChatIds(uid: String): Set<String> = memberRepo.getActiveChatIds(uid)

    internal fun getActiveChatIds(transaction: PgTransactionContext, uid: String): Set<String> =
        memberRepo.getActiveChatIds(transaction, uid)

    internal fun getProjectedChatIds(uid: String): Set<String> = memberRepo.getProjectedChatIds(uid)

    internal fun getProjectedChatIds(transaction: PgTransactionContext, uid: String): Set<String> =
        memberRepo.getProjectedChatIds(transaction, uid)

    internal fun lockChats(
        transaction: PgTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> = memberRepo.lockChats(transaction, chatIds, requireActive)

    internal fun getActiveMember(
        transaction: PgTransactionContext,
        chatId: String,
        uid: String,
    ): Member? = memberRepo.getActiveMember(transaction, chatId, uid)

    internal fun admitMessage(
        transaction: PgTransactionContext,
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
            if (getChat(chatId) == null) return@withCacheGate false
            // Repository evaluates expiresAt against the current clock on every authorization
            // check. A cached boolean would keep an already expired mute effective forever.
            memberRepo.isMuted(chatId, uid)
        }
    }

    fun isMutedAll(chatId: String): Boolean {
        return withCacheGate(chatId) {
            if (getChat(chatId) == null) return@withCacheGate false
            chatMutedAll[chatId] ?: false
        }
    }

    // ══════════════════════════════════════
    // 写操作（Repository + 缓存更新）
    // ══════════════════════════════════════

    /** Transaction-bound creation; cache publication is deferred until the UoW commits. */
    internal fun createPersonalChat(
        transaction: PgTransactionContext,
        uid1: String,
        uid2: String,
    ): ChatCreation = repo.createPersonalChat(transaction, uid1, uid2)

    /** Transaction-bound creation; cache publication is deferred until the UoW commits. */
    internal fun createGroupChat(
        transaction: PgTransactionContext,
        name: String,
        avatar: String?,
        creatorUid: String,
        memberUids: List<String>,
    ): ChatCreation = repo.createGroupChat(transaction, name, avatar, creatorUid, memberUids)

    /** Transaction-bound invite consumption; cache invalidation is published only after commit. */
    internal fun joinByInvite(
        transaction: PgTransactionContext,
        uid: String,
        token: String,
        nowMillis: Long,
    ): InviteJoinResult = repo.joinByInvite(transaction, uid, token, nowMillis)

    internal fun invalidateCommittedInviteJoin(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    internal fun updateGroup(
        transaction: PgTransactionContext,
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
        transaction: PgTransactionContext,
        chatId: String,
        operatorUid: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): Chat = repo.lockForDeactivation(transaction, chatId, operatorUid, authorize)

    /** Transaction-bound deactivation; cache publication is deferred until commit. */
    internal fun deactivateChat(
        transaction: PgTransactionContext,
        chatId: String,
    ): ChatDeactivation = repo.deactivateChat(transaction, chatId)

    internal fun invalidateCommittedDeactivation(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    /** Transaction-bound add. Cache state remains untouched until the caller publishes commit. */
    internal fun addMembers(
        transaction: PgTransactionContext,
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

    /** Transaction-bound member removal. Cache state is intentionally untouched before commit. */
    internal fun removeMember(
        transaction: PgTransactionContext,
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

    /** Transaction-bound idempotent removal used by external service projections. */
    internal fun removeMemberIfPresent(
        transaction: PgTransactionContext,
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
        transaction: PgTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup? = memberRepo.cleanupServiceMemberProjection(
        transaction = transaction,
        chatId = chatId,
        uid = uid,
        lockedChat = lockedChat,
    )

    /** Called only after a transaction-bound membership add/remove and its events commit. */
    internal fun invalidateCommittedMembershipChange(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    override fun invalidateManagedChat(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    internal fun transferOwner(
        transaction: PgTransactionContext,
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
        transaction: PgTransactionContext,
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
        transaction: PgTransactionContext,
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
        transaction: PgTransactionContext,
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
        transaction: PgTransactionContext,
        chatId: String,
        creatorUid: String,
        name: String,
        maxUses: Int,
        expiresAt: Long,
        authorize: (GroupCommandFacts) -> Unit,
    ): String = inviteRepo.createInviteLink(
        transaction,
        chatId,
        creatorUid,
        name,
        maxUses,
        expiresAt,
        authorize,
    )

    fun listInviteLinks(chatId: String) = inviteRepo.listInviteLinks(chatId)

    internal fun revokeInviteLink(
        transaction: PgTransactionContext,
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

    /** Publish no stale chat/member state after any committed aggregate command. */
    internal fun invalidateCommittedCommand(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    // ══════════════════════════════════════
    // 内部方法
    // ══════════════════════════════════════

    private fun indexChat(chat: Chat) {
        chats[chat.chatId] = chat
        chatMaxSeq.getOrPut(chat.chatId) { AtomicLong(chat.maxSeq) }
        chatMutedAll[chat.chatId] = chat.mutedAll
    }

    private fun invalidateChat(chatId: String) {
        chats.remove(chatId)
        chatMaxSeq.remove(chatId)
        chatMutedAll.remove(chatId)
        invalidateMembers(chatId)
    }

    private fun invalidateMembers(chatId: String) {
        memberUids.remove(chatId)
        memberRoles.remove(chatId)
        membersLoaded.remove(chatId)
    }

    private fun ensureMembersLoaded(chatId: String) {
        if (membersLoaded[chatId] != true) loadMembers(chatId)
    }

    private fun loadMembers(chatId: String) {
        if (membersLoaded[chatId] == true) return
        val members = memberRepo.getMembers(chatId)
        for (member in members) {
            memberUids.getOrPut(chatId) { CopyOnWriteArrayList() }.addIfAbsent(member.uid)
            memberRoles.getOrPut(chatId) { ConcurrentHashMap() }[member.uid] = member.role
        }
        membersLoaded[chatId] = true
    }

    private inline fun <T> withCacheGate(chatId: String, block: () -> T): T =
        cacheGates[(chatId.hashCode() and Int.MAX_VALUE) % cacheGates.size].withLock(block)

    private companion object {
        const val CACHE_GATE_STRIPES = 256
    }
}

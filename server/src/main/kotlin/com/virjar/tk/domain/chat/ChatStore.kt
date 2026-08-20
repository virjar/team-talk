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
 * 写操作先 Repository 后内存。
 *
 * [incrementMaxSeq] 在内存中原子递增，并在返回前持久化到 DB。
 * DB 先于消息落库允许出现序号空洞，但绝不能在进程重启后复用旧序号覆盖消息。
 */
class ChatStore(
    private val repo: ChatRepository,
    private val memberRepo: ChatMemberRepository,
    private val inviteRepo: InviteLinkRepository,
) : ActiveChatMembership, ChatAccessSource {
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

    override fun getChat(chatId: String): Chat? {
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

    /**
     * 原子递增 maxSeq，并在返回前持久化到 DB。
     *
     * 成员缓存和会话缓存相互独立。转发等路径可能只加载了成员，因此这里必须
     * 主动从 Repository 初始化 maxSeq，不能把“会话尚未进入热缓存”误判为 seq=0。
     */
    fun incrementMaxSeq(chatId: String): Long {
        return withCacheGate(chatId) {
            val seq = maxSeqCounter(chatId)
            val newSeq = seq.incrementAndGet()
            chats.computeIfPresent(chatId) { _, chat ->
                if (chat.maxSeq < newSeq) chat.copy(maxSeq = newSeq) else chat
            }
            repo.updateMaxSeq(chatId, newSeq)
            newSeq
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

    override fun getMember(chatId: String, uid: String): Member? {
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

    /** 用户当前仍有效的全部会话 ID，用于跨会话搜索等需要权限集合的读操作。 */
    override fun listUserChatIds(uid: String): Set<String> =
        repo.listUserChats(uid).mapTo(linkedSetOf()) { it.chatId }

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

    fun createPersonalChat(uid1: String, uid2: String): Chat {
        val chat = repo.createPersonalChat(uid1, uid2)
        withCacheGate(chat.chatId) {
            refreshCommittedChat(chat.chatId)
            refreshMembers(chat.chatId)
        }
        return chat
    }

    fun createGroupChat(
        name: String,
        avatar: String?,
        creatorUid: String,
        memberUids: List<String>,
        requestedChatId: String? = null,
    ): Chat {
        if (requestedChatId != null) {
            return withCacheGate(requestedChatId) {
                val chat = repo.createGroupChat(name, avatar, creatorUid, memberUids, requestedChatId)
                refreshCommittedChat(chat.chatId)
                refreshMembers(chat.chatId)
                chat
            }
        }
        val chat = repo.createGroupChat(name, avatar, creatorUid, memberUids, requestedChatId = null)
        withCacheGate(chat.chatId) {
            refreshCommittedChat(chat.chatId)
            refreshMembers(chat.chatId)
        }
        return chat
    }

    /** Drops pre-transaction cache state only after the aggregate transaction has committed. */
    fun joinByInvite(uid: String, token: String, nowMillis: Long): InviteJoinResult {
        val chatId = inviteRepo.getInviteLink(token)?.chatId
            ?: throw IllegalArgumentException("邀请链接不存在")
        return withCacheGate(chatId) {
            val result = repo.joinByInvite(uid, token, nowMillis)
            invalidateChat(result.chat.chatId)
            result
        }
    }

    fun updateGroup(chatId: String, name: String?, avatar: String?, notice: String?) {
        withCacheGate(chatId) {
            repo.updateGroup(chatId, name, avatar, notice)
            chats[chatId]?.let { cached ->
                chats[chatId] = cached.copy(
                    name = name ?: cached.name,
                    avatar = avatar ?: cached.avatar,
                    notice = notice ?: cached.notice,
                )
            }
        }
    }

    fun deactivateChat(chatId: String) {
        withCacheGate(chatId) {
            repo.deactivateChat(chatId)
            invalidateChat(chatId)
        }
    }

    fun addMembers(chatId: String, uids: List<String>) {
        withCacheGate(chatId) {
            memberRepo.addMembers(chatId, uids)
            chats.remove(chatId)
            invalidateMembers(chatId)
        }
    }

    fun removeMember(chatId: String, uid: String) {
        withCacheGate(chatId) {
            memberRepo.removeMember(chatId, uid)
            chats.remove(chatId)
            invalidateMembers(chatId)
        }
    }

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

    /** Called only through PgWriteScope.afterCommit after member/event state is durable. */
    internal fun invalidateCommittedMemberRemoval(chatId: String) {
        withCacheGate(chatId) { invalidateChat(chatId) }
    }

    fun transferOwner(chatId: String, oldOwnerUid: String, newOwnerUid: String) {
        withCacheGate(chatId) {
            memberRepo.transferOwner(chatId, oldOwnerUid, newOwnerUid)
            chats.remove(chatId)
            invalidateMembers(chatId)
        }
    }

    fun setRole(chatId: String, uid: String, role: Int) {
        withCacheGate(chatId) {
            memberRepo.setRole(chatId, uid, role)
            invalidateMembers(chatId)
        }
    }

    fun muteMember(chatId: String, uid: String, operatorUid: String, expiresAt: Long) {
        withCacheGate(chatId) {
            memberRepo.muteMember(chatId, uid, operatorUid, expiresAt)
        }
    }

    fun unmuteMember(chatId: String, uid: String) {
        withCacheGate(chatId) {
            memberRepo.unmuteMember(chatId, uid)
        }
    }

    fun setMuteAll(chatId: String, mutedAll: Boolean) {
        withCacheGate(chatId) {
            memberRepo.setMuteAll(chatId, mutedAll)
            chats.remove(chatId)
            chatMutedAll.remove(chatId)
        }
    }

    // ── 邀请链接（不缓存，直接委托 Repository） ──

    fun createInviteLink(chatId: String, creatorUid: String, name: String, maxUses: Int, expiresAt: Long) =
        inviteRepo.createInviteLink(chatId, creatorUid, name, maxUses, expiresAt)

    fun listInviteLinks(chatId: String) = inviteRepo.listInviteLinks(chatId)

    fun revokeInviteLink(token: String) = inviteRepo.revokeInviteLink(token)

    fun getInviteLink(token: String) = inviteRepo.getInviteLink(token)

    // ══════════════════════════════════════
    // 内部方法
    // ══════════════════════════════════════

    private fun indexChat(chat: Chat) {
        chats[chat.chatId] = chat
        chatMaxSeq.getOrPut(chat.chatId) { AtomicLong(chat.maxSeq) }
        chatMutedAll[chat.chatId] = chat.mutedAll
    }

    private fun refreshMembers(chatId: String) {
        invalidateMembers(chatId)
        if (getChat(chatId) != null) loadMembers(chatId)
    }

    private fun refreshCommittedChat(chatId: String) {
        invalidateChat(chatId)
        repo.getChat(chatId)?.let(::indexChat)
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

    private fun maxSeqCounter(chatId: String): AtomicLong {
        chatMaxSeq[chatId]?.let { return it }
        val chat = getChat(chatId) ?: throw IllegalArgumentException("聊天不存在")
        return chatMaxSeq.computeIfAbsent(chatId) { AtomicLong(chat.maxSeq) }
    }

    private inline fun <T> withCacheGate(chatId: String, block: () -> T): T =
        cacheGates[(chatId.hashCode() and Int.MAX_VALUE) % cacheGates.size].withLock(block)

    private companion object {
        const val CACHE_GATE_STRIPES = 256
    }
}

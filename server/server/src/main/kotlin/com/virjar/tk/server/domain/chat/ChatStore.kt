package com.virjar.tk.server.domain.chat

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Member
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Chat 领域热缓存。
 *
 * 缓存 chat 基础信息与有界成员角色快照；不承担命令或事务读取。
 * 读操作 cache miss 时从 Repository 加载并填充缓存。
 * 服务直接通过领域 Repository 执行命令与事务读取；只有提交后的回调可以调用 [invalidate]。
 *
 * 消息序号由 MessageStore 与消息事实原子分配，不进入本缓存。PostgreSQL Chat.maxSeq 只是完成
 * 消息投影后的派生水位；提交后必须失效本缓存，不能让旧水位遮蔽已经完成的投影。
 */
class ChatStore(
    private val repo: ChatRepository,
    private val memberRepo: ChatMemberRepository,
    cacheStripeCount: Int = DEFAULT_CACHE_STRIPES,
    cacheEntriesPerStripe: Int = DEFAULT_ENTRIES_PER_STRIPE,
    private val maxCachedMemberRolesPerChat: Int = DEFAULT_MAX_CACHED_MEMBER_ROLES_PER_CHAT,
) {
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

    /** 仅在聊天、成员或消息投影事务提交后调用；与缓存加载使用同一分片锁。 */
    fun invalidate(chatId: String) {
        withCacheGate(chatId) { entries.remove(chatId) }
    }

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

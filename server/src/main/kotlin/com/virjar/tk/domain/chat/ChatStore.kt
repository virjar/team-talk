package com.virjar.tk.domain.chat

import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Chat 领域热缓存。
 *
 * 缓存 chat 基础信息、成员列表/角色、禁言状态、maxSeq。
 * 读操作 cache miss 时从 Repository 加载并填充缓存。
 * 写操作先 Repository 后内存。
 *
 * [incrementMaxSeq] 非阻塞：原子内存递增 + 异步 DB 持久化。
 */
class ChatStore(
    private val repo: ChatRepository,
    private val memberRepo: ChatMemberRepository,
    private val inviteRepo: InviteLinkRepository,
) {
    private val logger = LoggerFactory.getLogger("ChatStore")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 基础信息 ──
    private val chats = ConcurrentHashMap<String, Chat>()

    // ── maxSeq（原子递增，消息热路径） ──
    private val chatMaxSeq = ConcurrentHashMap<String, AtomicLong>()

    // ── 成员 ──
    private val memberUids = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val memberRoles = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val membersLoaded = ConcurrentHashMap<String, Boolean>()

    // ── 禁言 ──
    private val chatMutedAll = ConcurrentHashMap<String, Boolean>()
    private val memberMutes = ConcurrentHashMap<String, MutableSet<String>>()
    private val mutesLoaded = ConcurrentHashMap<String, Boolean>()

    // ══════════════════════════════════════
    // 读操作（缓存优先）
    // ══════════════════════════════════════

    fun getChat(chatId: String): Chat? {
        chats[chatId]?.let { return it }
        return repo.getChat(chatId)?.also { chats[chatId] = it }
    }

    fun getMaxSeq(chatId: String): Long {
        chatMaxSeq[chatId]?.let { return it.get() }
        // 触发 chat 加载以初始化 maxSeq
        val chat = getChat(chatId) ?: return 0L
        return chatMaxSeq[chatId]?.get() ?: chat.maxSeq
    }

    /**
     * 原子递增 maxSeq：先内存递增，异步持久化到 DB。
     * 非 suspend，可在任何上下文安全调用（包括 EventLoop）。
     */
    fun incrementMaxSeq(chatId: String): Long {
        val seq = chatMaxSeq.getOrPut(chatId) { AtomicLong(getCachedMaxSeq(chatId)) }
        val newSeq = seq.incrementAndGet()
        ioScope.launch {
            try {
                repo.updateMaxSeq(chatId, newSeq)
            } catch (e: Exception) {
                logger.warn("Failed to persist maxSeq for {}: {}", chatId, e.message)
            }
        }
        return newSeq
    }

    // ── 成员读 ──

    fun getMemberUids(chatId: String): List<String> {
        if (membersLoaded[chatId] == true) {
            return memberUids[chatId]?.toList() ?: emptyList()
        }
        loadMembers(chatId)
        return memberUids[chatId]?.toList() ?: emptyList()
    }

    fun isMember(chatId: String, uid: String): Boolean {
        ensureMembersLoaded(chatId)
        return memberRoles[chatId]?.containsKey(uid) == true
    }

    fun getMember(chatId: String, uid: String): Member? {
        ensureMembersLoaded(chatId)
        val role = memberRoles[chatId]?.get(uid) ?: return null
        return Member(uid = uid, chatId = chatId, role = role)
    }

    fun getMembers(chatId: String): List<Member> {
        ensureMembersLoaded(chatId)
        val roles = memberRoles[chatId] ?: return emptyList()
        return roles.entries.map { (uid, role) -> Member(uid = uid, chatId = chatId, role = role) }
    }

    // ── 禁言读 ──

    fun isMuted(chatId: String, uid: String): Boolean {
        ensureMutesLoaded(chatId)
        return memberMutes[chatId]?.contains(uid) == true
    }

    fun isMutedAll(chatId: String): Boolean {
        if (chats.containsKey(chatId)) {
            return chatMutedAll[chatId] ?: false
        }
        getChat(chatId)
        return chatMutedAll[chatId] ?: false
    }

    // ══════════════════════════════════════
    // 写操作（Repository + 缓存更新）
    // ══════════════════════════════════════

    fun createPersonalChat(uid1: String, uid2: String): Chat {
        val chat = repo.createPersonalChat(uid1, uid2)
        indexChat(chat)
        // 个人聊天直接设置两个成员
        addMemberToCache(chat.chatId, uid1, 0)
        addMemberToCache(chat.chatId, uid2, 0)
        return chat
    }

    fun createGroupChat(name: String, avatar: String?, creatorUid: String, memberUids: List<String>): Chat {
        val chat = repo.createGroupChat(name, avatar, creatorUid, memberUids)
        indexChat(chat)
        addMemberToCache(chat.chatId, creatorUid, 2)
        for (uid in memberUids) {
            if (uid != creatorUid) addMemberToCache(chat.chatId, uid, 0)
        }
        return chat
    }

    fun updateGroup(chatId: String, name: String?, avatar: String?, notice: String?) {
        repo.updateGroup(chatId, name, avatar, notice)
        chats[chatId]?.let { cached ->
            chats[chatId] = cached.copy(
                name = name ?: cached.name,
                avatar = avatar ?: cached.avatar,
                notice = notice ?: cached.notice,
            )
        }
    }

    fun deleteChat(chatId: String) {
        repo.deleteChat(chatId)
        chats.remove(chatId)
        memberUids.remove(chatId)
        memberRoles.remove(chatId)
        membersLoaded.remove(chatId)
        chatMaxSeq.remove(chatId)
        chatMutedAll.remove(chatId)
        memberMutes.remove(chatId)
        mutesLoaded.remove(chatId)
    }

    fun addMembers(chatId: String, uids: List<String>) {
        memberRepo.addMembers(chatId, uids)
        for (uid in uids) {
            addMemberToCache(chatId, uid, 0)
        }
    }

    fun removeMember(chatId: String, uid: String) {
        memberRepo.removeMember(chatId, uid)
        memberUids[chatId]?.remove(uid)
        memberRoles[chatId]?.remove(uid)
    }

    fun transferOwner(chatId: String, oldOwnerUid: String, newOwnerUid: String) {
        memberRepo.transferOwner(chatId, oldOwnerUid, newOwnerUid)
        memberRoles[chatId]?.put(oldOwnerUid, 1)
        memberRoles[chatId]?.put(newOwnerUid, 2)
        chats[chatId]?.let { cached ->
            chats[chatId] = cached.copy(creator = newOwnerUid)
        }
    }

    fun setRole(chatId: String, uid: String, role: Int) {
        memberRepo.setRole(chatId, uid, role)
        memberRoles[chatId]?.put(uid, role)
    }

    fun muteMember(chatId: String, uid: String, operatorUid: String, expiresAt: Long) {
        memberRepo.muteMember(chatId, uid, operatorUid, expiresAt)
        memberMutes.getOrPut(chatId) { ConcurrentHashMap.newKeySet() }.add(uid)
    }

    fun unmuteMember(chatId: String, uid: String) {
        memberRepo.unmuteMember(chatId, uid)
        memberMutes[chatId]?.remove(uid)
    }

    fun setMuteAll(chatId: String, mutedAll: Boolean) {
        memberRepo.setMuteAll(chatId, mutedAll)
        chatMutedAll[chatId] = mutedAll
        chats[chatId]?.let { cached ->
            chats[chatId] = cached.copy(mutedAll = mutedAll)
        }
    }

    // ── 邀请链接（不缓存，直接委托 Repository） ──

    fun createInviteLink(chatId: String, creatorUid: String, name: String, maxUses: Int, expiresAt: Long) =
        inviteRepo.createInviteLink(chatId, creatorUid, name, maxUses, expiresAt)

    fun listInviteLinks(chatId: String) = inviteRepo.listInviteLinks(chatId)

    fun revokeInviteLink(token: String) = inviteRepo.revokeInviteLink(token)

    fun getInviteLink(token: String) = inviteRepo.getInviteLink(token)

    fun incrementInviteUseCount(token: String) = inviteRepo.incrementInviteUseCount(token)

    fun findPersonalChatId(uid1: String, uid2: String) = repo.findPersonalChatId(uid1, uid2)

    fun listUserChats(uid: String) = repo.listUserChats(uid)

    // ══════════════════════════════════════
    // 内部方法
    // ══════════════════════════════════════

    private fun indexChat(chat: Chat) {
        chats[chat.chatId] = chat
        chatMaxSeq.getOrPut(chat.chatId) { AtomicLong(chat.maxSeq) }
        if (chat.mutedAll) chatMutedAll[chat.chatId] = true
    }

    private fun addMemberToCache(chatId: String, uid: String, role: Int) {
        memberUids.getOrPut(chatId) { CopyOnWriteArrayList() }.addIfAbsent(uid)
        memberRoles.getOrPut(chatId) { ConcurrentHashMap() }[uid] = role
        membersLoaded[chatId] = true
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

    private fun ensureMutesLoaded(chatId: String) {
        if (mutesLoaded[chatId] == true) return
        val mutedUids = memberRepo.getMutedMembers(chatId)
        if (mutedUids.isNotEmpty()) {
            memberMutes.getOrPut(chatId) { ConcurrentHashMap.newKeySet() }.addAll(mutedUids)
        }
        mutesLoaded[chatId] = true
    }

    private fun getCachedMaxSeq(chatId: String): Long =
        chats[chatId]?.maxSeq ?: 0L
}

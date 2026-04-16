package com.virjar.tk.store

import com.virjar.tk.db.ChannelDao
import com.virjar.tk.db.ChannelMemberRow
import com.virjar.tk.db.ChannelRow
import com.virjar.tk.protocol.ChannelType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * 频道领域 Store：全量频道/成员/禁言内存缓存 + 异步 DB 写入。
 */
class ChannelStore {
    private val logger = LoggerFactory.getLogger(ChannelStore::class.java)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val channels = ConcurrentHashMap<String, ChannelRow>()
    private val channelMaxSeq = ConcurrentHashMap<String, AtomicLong>()
    private val memberUids = ConcurrentHashMap<String, MutableList<String>>()
    private val memberRoles = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val channelMutedAll = ConcurrentHashMap<String, Boolean>()
    private val memberMutes = ConcurrentHashMap<String, MutableSet<String>>()

    // ================================================================
    // 启动加载
    // ================================================================

    fun loadAll() {
        val startTime = System.currentTimeMillis()

        val allChannels = ChannelDao.loadAll()
        for (channel in allChannels) {
            channels[channel.channelId] = channel
            channelMaxSeq[channel.channelId] = AtomicLong(channel.maxSeq)
            if (channel.mutedAll) {
                channelMutedAll[channel.channelId] = true
            }
        }

        val allMembers = ChannelDao.loadAllMembers()
        for (member in allMembers) {
            memberUids.getOrPut(member.channelId) { CopyOnWriteArrayList() }.add(member.uid)
            memberRoles.getOrPut(member.channelId) { ConcurrentHashMap() }[member.uid] = member.role
        }

        val allMutes = ChannelDao.loadAllMutes()
        for ((chId, mutedUid, _) in allMutes) {
            memberMutes.getOrPut(chId) { ConcurrentHashMap.newKeySet() }.add(mutedUid)
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.info("ChannelStore loaded in {}ms: channels={}, members={}, mutes={}",
            elapsed, channels.size, allMembers.size, allMutes.size)
    }

    // ================================================================
    // 频道读操作
    // ================================================================

    fun findByChannelId(channelId: String): ChannelRow? = channels[channelId]

    fun findByUser(uid: String, version: Long = 0): List<ChannelRow> {
        val userChannelIds = memberUids.entries
            .filter { (_, uids) -> uid in uids }
            .map { it.key }
            .toSet()
        return channels.values.filter { ch ->
            ch.channelId in userChannelIds && ch.updatedAt > version
        }
    }

    fun getMaxSeq(channelId: String): Long = channelMaxSeq[channelId]?.get() ?: 0L

    /**
     * 原子递增 maxSeq：先内存递增，异步持久化到 DB。
     * 非suspend，可在任何上下文安全调用。
     */
    fun incrementMaxSeq(channelId: String): Long {
        val seq = channelMaxSeq.getOrPut(channelId) { AtomicLong(0) }
        val newSeq = seq.incrementAndGet()
        // 异步持久化到 DB
        ioScope.launch {
            try {
                ChannelDao.setMaxSeq(channelId, newSeq)
            } catch (e: Exception) {
                logger.warn("Failed to persist maxSeq for channel {}: {}", channelId, e.message)
            }
        }
        return newSeq
    }

    // ================================================================
    // 频道写操作
    // ================================================================

    suspend fun create(channelId: String, channelType: ChannelType, name: String, avatar: String, creator: String?): ChannelRow = withContext(Dispatchers.IO) {
        val channel = ChannelDao.create(channelId, channelType, name, avatar, creator)
        channels[channel.channelId] = channel
        channelMaxSeq[channel.channelId] = AtomicLong(0)
        channel
    }

    suspend fun update(channelId: String, name: String?, avatar: String?, notice: String?) = withContext(Dispatchers.IO) {
        ChannelDao.update(channelId, name, avatar, notice)
        val existing = channels[channelId] ?: return@withContext
        val updated = existing.copy(
            name = name ?: existing.name,
            avatar = avatar ?: existing.avatar,
            notice = notice ?: existing.notice,
        )
        channels[channelId] = updated
    }

    // ================================================================
    // 成员读操作
    // ================================================================

    fun getMemberUids(channelId: String): List<String> = memberUids[channelId]?.toList() ?: emptyList()

    fun isMember(channelId: String, uid: String): Boolean = memberRoles[channelId]?.containsKey(uid) == true

    /**
     * 纯内存添加成员（不写 DB）。仅供测试使用。
     */
    fun putMember(channelId: String, channelType: ChannelType, uid: String, role: Int = 0) {
        memberUids.getOrPut(channelId) { CopyOnWriteArrayList() }.add(uid)
        memberRoles.getOrPut(channelId) { ConcurrentHashMap() }[uid] = role
    }

    fun getMemberRole(channelId: String, uid: String): Int? = memberRoles[channelId]?.get(uid)

    fun getMembers(channelId: String, page: Int = 1, pageSize: Int = 50): List<ChannelMemberRow> {
        val roles = memberRoles[channelId] ?: return emptyList()
        val all = roles.entries.map { (uid, role) ->
            ChannelMemberRow(
                id = 0,
                channelId = channelId,
                channelType = channels[channelId]?.channelType ?: ChannelType.PERSONAL,
                uid = uid,
                role = role,
                nickname = "",
                status = 1,
                joinedAt = 0,
            )
        }
        val start = ((page - 1) * pageSize).coerceAtMost(all.size)
        val end = (start + pageSize).coerceAtMost(all.size)
        return all.subList(start, end)
    }

    // ================================================================
    // 成员写操作
    // ================================================================

    suspend fun addMember(channelId: String, channelType: ChannelType, uid: String, role: Int = 0): ChannelMemberRow = withContext(Dispatchers.IO) {
        val row = ChannelDao.addMember(channelId, channelType, uid, role)
        memberUids.getOrPut(channelId) { CopyOnWriteArrayList() }.add(uid)
        memberRoles.getOrPut(channelId) { ConcurrentHashMap() }[uid] = role
        row
    }

    suspend fun removeMember(channelId: String, uid: String) = withContext(Dispatchers.IO) {
        ChannelDao.removeMember(channelId, uid)
        memberUids[channelId]?.remove(uid)
        memberRoles[channelId]?.remove(uid)
    }

    suspend fun updateMemberRole(channelId: String, uid: String, role: Int) = withContext(Dispatchers.IO) {
        ChannelDao.updateMemberRole(channelId, uid, role)
        memberRoles[channelId]?.put(uid, role)
    }

    suspend fun transferOwner(channelId: String, oldOwnerUid: String, newOwnerUid: String) = withContext(Dispatchers.IO) {
        ChannelDao.transferOwner(channelId, oldOwnerUid, newOwnerUid)
        memberRoles[channelId]?.put(oldOwnerUid, 0)
        memberRoles[channelId]?.put(newOwnerUid, 2)
        channels[channelId]?.let { ch ->
            channels[channelId] = ch.copy(creator = newOwnerUid)
        }
    }

    // ================================================================
    // 禁言操作
    // ================================================================

    fun isMutedAll(channelId: String): Boolean = channelMutedAll[channelId] ?: false

    fun isMemberMuted(channelId: String, uid: String): Boolean =
        memberMutes[channelId]?.contains(uid) ?: false

    suspend fun muteMember(channelId: String, uid: String, operatorUid: String, expiresAt: Long) = withContext(Dispatchers.IO) {
        ChannelDao.muteMember(channelId, uid, operatorUid, expiresAt)
        memberMutes.getOrPut(channelId) { ConcurrentHashMap.newKeySet() }.add(uid)
    }

    suspend fun unmuteMember(channelId: String, uid: String) = withContext(Dispatchers.IO) {
        ChannelDao.unmuteMember(channelId, uid)
        memberMutes[channelId]?.remove(uid)
    }

    suspend fun setMutedAll(channelId: String, mutedAll: Boolean) = withContext(Dispatchers.IO) {
        ChannelDao.setMutedAll(channelId, mutedAll)
        channelMutedAll[channelId] = mutedAll
        channels[channelId]?.let { ch ->
            channels[channelId] = ch.copy(mutedAll = mutedAll)
        }
    }
}

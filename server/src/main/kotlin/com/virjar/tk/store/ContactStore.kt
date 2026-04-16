package com.virjar.tk.store

import com.virjar.tk.db.FriendApplyRow
import com.virjar.tk.db.FriendDao
import com.virjar.tk.db.FriendRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 联系人领域 Store：全量好友关系内存缓存 + 异步 DB 写入。
 * 好友申请为临时数据，不缓存，直接 DB 访问。
 */
class ContactStore {
    private val logger = LoggerFactory.getLogger(ContactStore::class.java)

    // uid → friendUid set
    private val friendUids = ConcurrentHashMap<String, MutableSet<String>>()
    // uid → (friendUid → FriendRow)
    private val friendDetails = ConcurrentHashMap<String, ConcurrentHashMap<String, FriendRow>>()

    // ================================================================
    // 启动加载
    // ================================================================

    fun loadAll() {
        val startTime = System.currentTimeMillis()
        val allFriendPairs = FriendDao.loadAllFriendPairs()
        for ((uid, friendUid) in allFriendPairs) {
            friendUids.getOrPut(uid) { ConcurrentHashMap.newKeySet() }.add(friendUid)
        }
        val elapsed = System.currentTimeMillis() - startTime
        logger.info("ContactStore loaded {} friend pairs in {}ms", allFriendPairs.size, elapsed)
    }

    // ================================================================
    // 好友读操作（零 DB）
    // ================================================================

    fun getFriendUids(uid: String): Set<String> = friendUids[uid]?.toSet() ?: emptySet()

    fun isFriend(uid: String, friendUid: String): Boolean = friendUids[uid]?.contains(friendUid) == true

    suspend fun getFriends(uid: String, version: Long = 0): List<FriendRow> = withContext(Dispatchers.IO) {
        FriendDao.getFriends(uid, version)
    }

    // ================================================================
    // 好友写操作
    // ================================================================

    fun addFriendPair(uid1: String, uid2: String) {
        friendUids.getOrPut(uid1) { ConcurrentHashMap.newKeySet() }.add(uid2)
        friendUids.getOrPut(uid2) { ConcurrentHashMap.newKeySet() }.add(uid1)
    }

    fun removeFriendPair(uid1: String, uid2: String) {
        friendUids[uid1]?.remove(uid2)
        friendUids[uid2]?.remove(uid1)
    }

    suspend fun removeFriend(uid: String, friendUid: String) = withContext(Dispatchers.IO) {
        FriendDao.removeFriend(uid, friendUid)
        friendUids[uid]?.remove(friendUid)
    }

    suspend fun updateRemark(uid: String, friendUid: String, remark: String) = withContext(Dispatchers.IO) {
        FriendDao.updateRemark(uid, friendUid, remark)
        friendDetails[uid]?.remove(friendUid)
    }

    // ================================================================
    // 好友申请（纯 DB，不缓存）
    // ================================================================

    suspend fun createApply(fromUid: String, toUid: String, remark: String): FriendApplyRow = withContext(Dispatchers.IO) {
        FriendDao.createApply(fromUid, toUid, remark)
    }

    suspend fun acceptApply(token: String): FriendApplyRow? = withContext(Dispatchers.IO) {
        val apply = FriendDao.acceptApply(token) ?: return@withContext null
        addFriendPair(apply.fromUid, apply.toUid)
        apply
    }

    suspend fun rejectApply(token: String): Boolean = withContext(Dispatchers.IO) {
        FriendDao.rejectApply(token)
    }

    suspend fun getApplies(uid: String, page: Int = 1, pageSize: Int = 20): List<FriendApplyRow> = withContext(Dispatchers.IO) {
        FriendDao.getApplies(uid, page, pageSize)
    }

    // ================================================================
    // 黑名单（纯 DB）
    // ================================================================

    suspend fun getBlacklist(uid: String): List<FriendRow> = withContext(Dispatchers.IO) {
        FriendDao.getBlacklist(uid)
    }

    suspend fun blockUser(uid: String, targetUid: String) = withContext(Dispatchers.IO) {
        FriendDao.blockUser(uid, targetUid)
        friendUids[uid]?.remove(targetUid)
    }

    suspend fun unblockUser(uid: String, targetUid: String) = withContext(Dispatchers.IO) {
        FriendDao.unblockUser(uid, targetUid)
    }
}

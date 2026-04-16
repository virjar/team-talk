package com.virjar.tk.store

import com.virjar.tk.db.UserDao
import com.virjar.tk.db.UserRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户领域 Store：全量内存缓存 + 异步 DB 写入。
 *
 * 读操作纯内存，写操作先 DB 后内存。DAO 是 Store 的私有实现细节。
 */
class UserStore {
    private val logger = LoggerFactory.getLogger(UserStore::class.java)

    private val byUid = ConcurrentHashMap<String, UserRow>()
    private val byUsername = ConcurrentHashMap<String, UserRow>()
    private val byPhone = ConcurrentHashMap<String, UserRow>()

    // ================================================================
    // 启动加载
    // ================================================================

    fun loadAll() {
        val startTime = System.currentTimeMillis()
        val allUsers = UserDao.loadAll()
        for (user in allUsers) {
            indexUser(user)
        }
        val elapsed = System.currentTimeMillis() - startTime
        logger.info("UserStore loaded {} users in {}ms", allUsers.size, elapsed)
    }

    // ================================================================
    // 读操作（零 DB）
    // ================================================================

    fun findByUid(uid: String): UserRow? = byUid[uid]

    fun findByUsername(username: String): UserRow? = byUsername[username]

    fun findByPhone(zone: String, phone: String): UserRow? = byPhone["$zone:$phone"]

    fun search(query: String, limit: Int = 20): List<UserRow> {
        val lowerQuery = query.lowercase()
        return byUid.values.asSequence()
            .filter { user ->
                user.name.lowercase().contains(lowerQuery) ||
                    user.username?.lowercase()?.contains(lowerQuery) == true ||
                    user.phone?.contains(query) == true ||
                    user.shortNo?.contains(query) == true
            }
            .take(limit)
            .toList()
    }

    // ================================================================
    // 写操作（DB + 内存）
    // ================================================================

    suspend fun create(
        uid: String,
        username: String?,
        name: String,
        phone: String?,
        zone: String,
        plainPassword: String,
    ): UserRow = withContext(Dispatchers.IO) {
        val user = UserDao.create(uid, username, name, phone, zone, plainPassword)
        indexUser(user)
        user
    }

    suspend fun updateProfile(uid: String, name: String?, avatar: String?, sex: Int?) = withContext(Dispatchers.IO) {
        UserDao.updateProfile(uid, name, avatar, sex)
        val existing = byUid[uid] ?: return@withContext
        val updated = existing.copy(
            name = name ?: existing.name,
            avatar = avatar ?: existing.avatar,
            sex = sex ?: existing.sex,
        )
        indexUser(updated)
    }

    suspend fun verifyPassword(uid: String, plainPassword: String): Boolean = withContext(Dispatchers.IO) {
        UserDao.verifyPassword(uid, plainPassword)
    }

    // ================================================================
    // 内部方法
    // ================================================================

    private fun indexUser(user: UserRow) {
        byUid[user.uid] = user
        user.username?.let { byUsername[it] = user }
        if (user.phone != null) {
            byPhone["${user.zone}:${user.phone}"] = user
        }
    }
}

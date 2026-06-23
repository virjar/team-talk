package com.virjar.tk.domain.user

import com.virjar.tk.model.User
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户领域热缓存。多索引缓存（uid、username、phone）。
 *
 * 读操作 cache miss 时从 Repository 加载并填充缓存。
 * 写操作先 Repository 后内存。
 */
class UserStore(private val repo: UserRepository) {
    private val byUid = ConcurrentHashMap<String, User>()
    private val byUsername = ConcurrentHashMap<String, User>()
    private val byPhone = ConcurrentHashMap<String, User>()

    // ── 读操作（缓存优先） ──

    fun findByUid(uid: String): User? {
        byUid[uid]?.let { return it }
        return repo.findByUid(uid)?.also { indexUser(it) }
    }

    fun findByUsername(username: String): User? {
        byUsername[username]?.let { return it }
        return repo.findByUsername(username)?.also { indexUser(it) }
    }

    fun findByPhone(phone: String): User? {
        byPhone[phone]?.let { return it }
        return repo.findByPhone(phone)?.also { indexUser(it) }
    }

    fun searchUsers(keyword: String, limit: Int = 20): List<User> =
        repo.searchUsers(keyword, limit)

    // ── 认证相关（不走缓存，包含密码） ──

    fun findInternalByUsername(username: String) = repo.findInternalByUsername(username)

    fun findInternalByUid(uid: String) = repo.findInternalByUid(uid)

    // ── 写操作（Repository + 缓存更新） ──

    fun create(uid: String, username: String, name: String, passwordHash: String, phone: String? = null): User {
        return repo.create(uid, username, name, passwordHash, phone).also { indexUser(it) }
    }

    fun updateProfile(uid: String, name: String? = null, avatar: String? = null, sex: Int? = null, phone: String? = null) {
        repo.updateProfile(uid, name, avatar, sex, phone)
        // 重新从 DB 加载以更新缓存
        byUid.remove(uid)
        repo.findByUid(uid)?.let { indexUser(it) }
    }

    fun updatePassword(uid: String, passwordHash: String) {
        repo.updatePassword(uid, passwordHash)
        // 密码更新不影响公开缓存数据
    }

    // ── 内部方法 ──

    private fun indexUser(user: User) {
        byUid[user.uid] = user
        user.username?.let { byUsername[it] = user }
        user.phone?.let { byPhone[it] = user }
    }
}

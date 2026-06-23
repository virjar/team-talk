package com.virjar.tk.domain.user

import com.virjar.tk.client.AuthRules
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.model.User
import com.virjar.tk.protocol.NotifyType
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.security.SecureRandom

class UserService(
    private val userStore: UserStore,
    private val syncEventService: SyncEventService,
) {
    private val logger = LoggerFactory.getLogger(UserService::class.java)
    fun register(username: String, password: String, name: String, phone: String? = null): User {
        // 校验规则与客户端 SDK（ImClient via AuthRules）保持一致，避免规则不一致
        require(username.length in AuthRules.USERNAME_MIN_LENGTH..AuthRules.USERNAME_MAX_LENGTH) {
            "用户名长度需在${AuthRules.USERNAME_MIN_LENGTH}-${AuthRules.USERNAME_MAX_LENGTH}之间"
        }
        require(password.length >= AuthRules.PASSWORD_MIN_LENGTH) {
            "密码长度至少${AuthRules.PASSWORD_MIN_LENGTH}位"
        }

        userStore.findByUsername(username)?.let { throw IllegalArgumentException("用户名已存在") }
        phone?.let {
            userStore.findByPhone(it)?.let { throw IllegalArgumentException("手机号已注册") }
        }

        val uid = generateShortUid()
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
        return userStore.create(uid, username, name, passwordHash, phone)
    }

    private fun generateShortUid(): String {
        // 最多重试 20 次，碰撞概率极低（8 位 base62 = 218 万亿组合）
        repeat(20) {
            val uid = ShortUidGenerator.next()
            if (userStore.findByUid(uid) == null) return uid
        }
        // 极端碰撞 fallback（20 次全碰撞概率 < 10^-120）
        return java.util.UUID.randomUUID().toString()
    }

    fun login(username: String, password: String): User {
        val internal = userStore.findInternalByUsername(username)
            ?: throw IllegalArgumentException("用户名或密码错误")

        if (!BCrypt.checkpw(password, internal.passwordHash)) {
            throw IllegalArgumentException("用户名或密码错误")
        }
        return internal.user
    }

    fun getProfile(uid: String): User {
        return userStore.findByUid(uid) ?: throw IllegalArgumentException("用户不存在")
    }

    suspend fun updateProfile(uid: String, name: String? = null, avatar: String? = null, sex: Int? = null, phone: String? = null) {
        userStore.updateProfile(uid, name, avatar, sex, phone)
        val updatedUser = userStore.findByUid(uid) ?: return
        syncEventService.emitEvent(uid, NotifyType.USER_UPDATED, updatedUser)
    }

    fun search(keyword: String, limit: Int = 20): List<User> {
        val results = userStore.searchUsers(keyword, limit)
        logger.info("search keyword='$keyword' → {} results", results.size)
        return results
    }

    fun findByUid(uid: String): User? = userStore.findByUid(uid)

    fun changePassword(uid: String, oldPassword: String, newPassword: String) {
        require(newPassword.length >= AuthRules.PASSWORD_MIN_LENGTH) {
            "新密码长度至少${AuthRules.PASSWORD_MIN_LENGTH}位"
        }
        val internal = userStore.findInternalByUid(uid)
            ?: throw IllegalArgumentException("用户不存在")
        if (!BCrypt.checkpw(oldPassword, internal.passwordHash)) {
            throw IllegalArgumentException("旧密码错误")
        }
        val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt())
        userStore.updatePassword(uid, newHash)
    }
}

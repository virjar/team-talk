package com.virjar.tk.domain.user

import com.virjar.tk.auth.AuthRules
import com.virjar.tk.domain.event.EventPublisher
import com.virjar.tk.model.User
import com.virjar.tk.model.UserRole
import com.virjar.tk.protocol.NotifyType
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.security.SecureRandom

class UserService(
    private val userStore: UserStore,
    private val events: EventPublisher,
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
        // 封禁 enforcement（管理后台 ban 后 status=2；此前该字段无人检查）
        if (internal.user.status == 2) {
            throw IllegalArgumentException("账号已被封禁")
        }
        if (internal.user.role == UserRole.BOT || internal.user.role == UserRole.SYSTEM) {
            throw IllegalArgumentException("服务账户不能使用客户端密码登录")
        }
        return internal.user
    }

    /** 创建没有客户端登录能力的服务账户。外部调用只能使用其独立应用凭据。 */
    fun createServiceAccount(name: String, role: Int = UserRole.BOT): User {
        require(role == UserRole.BOT || role == UserRole.SYSTEM) { "非法服务账户类型" }
        require(name.isNotBlank()) { "服务账户名称不能为空" }
        val uid = generateShortUid()
        val username = "bot-${uid.take(12)}"
        val passwordBytes = ByteArray(48).also(SecureRandom()::nextBytes)
        val unusablePasswordHash = BCrypt.hashpw(java.util.Base64.getEncoder().encodeToString(passwordBytes), BCrypt.gensalt())
        return userStore.create(uid, username, name.trim().take(100), unusablePasswordHash, role = role)
    }

    /** 认证续期路径的封禁复查（refresh 只验 token，不查用户状态——曾可绕过封禁）。 */
    fun requireActive(uid: String) {
        val user = userStore.findByUid(uid) ?: throw IllegalArgumentException("用户不存在")
        if (user.status == 2) throw IllegalArgumentException("账号已被封禁")
    }

    fun getProfile(uid: String): User {
        return userStore.findByUid(uid) ?: throw IllegalArgumentException("用户不存在")
    }

    suspend fun updateProfile(uid: String, name: String? = null, avatar: String? = null, sex: Int? = null, phone: String? = null) {
        userStore.updateProfile(uid, name, avatar, sex, phone)
        val updatedUser = userStore.findByUid(uid) ?: return
        events.emitEvent(uid, NotifyType.USER_UPDATED, updatedUser)
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

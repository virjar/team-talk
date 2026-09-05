package com.virjar.tk.server.domain.user

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.ProfilePatch
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.UserRole

/** 用户领域拥有的持久化端口。 */
interface UserRepository {
    fun findByUid(uid: String): User?
    /**
     * 解析一个有界的身份集合，而不把目录调用方拖入 N+1 查询循环。小的内存/测试适配器
     * 可以依赖这个默认实现；数据库适配器应该用一次集合查询覆盖它。未知身份不出现在
     * 结果中。
     */
    fun findByUids(uids: Set<String>): Map<String, User> =
        uids.asSequence().mapNotNull { uid -> findByUid(uid)?.let { uid to it } }.toMap()
    suspend fun findInternalByUsername(username: String): UserInternal?
    suspend fun findInternalByUid(uid: String): UserInternal?
    fun findByUsername(username: String): User?
    fun findByPhone(phone: String): User?
    /**
     * 在注册聚合事务内插入一个人类身份。
     *
     * 调用方必须通过同一事务创建第一个 Device 与凭证对；
     * 被刻意设计为不存在自治的人类注册写路径。
     */
    fun registerHuman(
        transaction: PgWriteTransactionContext,
        command: HumanRegistrationCommand,
    ): User

    /** 服务身份加入其所属应用的聚合事务。 */
    fun createServiceAccount(
        transaction: PgWriteTransactionContext,
        uid: String,
        username: String,
        name: String,
        role: Int,
    ): User
    /** 锁定 User 行，并仅应用 [patch] 中标记为存在的字段。 */
    fun updateProfile(
        transaction: PgWriteTransactionContext,
        uid: String,
        patch: ProfilePatch,
    ): UserProfileMutation
    /**
     * 搜索面向最终用户的公开目录。
     *
     * 结果只包含活跃的 [UserRole.HUMAN] 身份。适配器必须在 [limit] 之前应用该可见性谓词
     * 与确定性的 `(name, username, uid)` 顺序，使隐藏的服务/禁用行永远无法消耗调用方的
     * 结果槽位。全身份管理查询属于独立的管理目录端口。
     */
    fun searchPublicDirectory(keyword: String, limit: Int = 20): List<User>
}

class HumanRegistrationCommand(
    val uid: String,
    val username: String,
    val name: String,
    val passwordHash: String,
    val phone: String?,
) {
    init {
        require(uid.isNotBlank()) { "Registration uid must not be blank" }
    }

    override fun toString(): String =
        "HumanRegistrationCommand(uid=$uid, username=<redacted>, " +
            "name=<redacted>, passwordHash=<redacted>, hasPhone=${phone != null})"
}

class UsernameAlreadyRegisteredException : IllegalArgumentException("用户名已存在")

class PhoneAlreadyRegisteredException : IllegalArgumentException("手机号已注册")

/** 仅由有界注册重试循环使用的精确 uid 冲突。 */
internal class UserIdentityCollisionException : RuntimeException("Generated user identity collided")

class UserIdentityAllocationException : IllegalStateException("无法分配用户身份，请重试")

data class UserProfileMutation(
    val user: User,
    val changed: Boolean,
)

/** 在资料/事件事务内解析的有界活跃好友受众。 */
fun interface UserProfileAudience {
    fun getFriendUids(transaction: PgWriteTransactionContext, uid: String): Set<String>
}

class UserInternal(
    val user: User,
    val passwordHash: String,
    val credentialEpoch: Long,
) {
    override fun toString(): String =
        "UserInternal(uid=${user.uid}, credentialEpoch=$credentialEpoch, passwordHash=<redacted>)"
}

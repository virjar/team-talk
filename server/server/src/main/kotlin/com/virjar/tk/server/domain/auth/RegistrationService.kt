package com.virjar.tk.server.domain.auth

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.server.domain.transaction.PgUnitOfWork
import com.virjar.tk.server.domain.user.HumanRegistrationCommand
import com.virjar.tk.server.domain.user.ShortUidGenerator
import com.virjar.tk.server.domain.user.UserIdentityAllocationException
import com.virjar.tk.server.domain.user.UserIdentityCollisionException
import com.virjar.tk.server.domain.user.UserRepository
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 唯一的人类注册写路径的结果。原始凭证保持脱敏。 */
class HumanRegistrationResult(
    val user: User,
    val credentials: IssuedCredentials,
) {
    override fun toString(): String =
        "HumanRegistrationResult(uid=${user.uid}, credentials=<redacted>)"
}

/**
 * 作为一个 PostgreSQL 聚合，创建人类身份及其第一个已认证设备。
 *
 * BCrypt 工作与有界的 uid 生成在事务准入之前完成。每个被准入的尝试都通过一个
 * [PgUnitOfWork] 插入 User、Device 以及两份凭证哈希；任何失败都会回滚整个尝试。
 * 只有确切的生成 uid 冲突才会被重试。
 */
class RegistrationService(
    private val users: UserRepository,
    private val unitOfWork: PgUnitOfWork,
    private val passwordHasher: PasswordHasher,
    private val initialCredentials: InitialCredentialIssuer,
    private val uidGenerator: () -> String = ShortUidGenerator::next,
) {
    suspend fun register(
        username: String,
        password: String,
        name: String,
        device: CredentialDevice,
        phone: String? = null,
    ): HumanRegistrationResult {
        AuthRules.validateRegister(username, password, name)
        AuthRules.validateDevice(
            device.deviceId,
            device.deviceName,
            device.deviceModel,
            device.deviceFlag,
        )
        phone?.let { require(it.length <= MAX_PHONE_LENGTH) { "手机号不能超过 $MAX_PHONE_LENGTH 个字符" } }

        // 慢速的单向密码派生绝不持有 PostgreSQL 连接或行锁。
        val passwordHash = passwordHasher.hash(password)
        val uidCandidates = List(MAX_UID_ATTEMPTS) { uidGenerator() }
        currentCoroutineContext().ensureActive()

        uidCandidates.forEach { uid ->
            currentCoroutineContext().ensureActive()
            try {
                return unitOfWork.write {
                    val user = users.registerHuman(
                        transaction,
                        HumanRegistrationCommand(
                            uid = uid,
                            username = username,
                            name = name,
                            passwordHash = passwordHash,
                            phone = phone,
                        ),
                    )
                    val issued = initialCredentials.issueInitialCredentials(transaction, user, device)
                    HumanRegistrationResult(user, issued)
                }
            } catch (_: UserIdentityCollisionException) {
                // 失败的聚合事务不包含任何持久化行。只重试下一个预生成的 uid；
                // 用户名/手机号冲突以及其他所有失败直接抛出。
            }
        }
        throw UserIdentityAllocationException()
    }

    companion object {
        const val MAX_UID_ATTEMPTS = 20
        private const val MAX_PHONE_LENGTH = 20
    }
}

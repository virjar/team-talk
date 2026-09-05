package com.virjar.tk.server.domain.auth

import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.protocol.model.User

/** HTTP 路由使用的窄化令牌（bearer）校验边界。实现必须默认拒绝（fail closed）。 */
fun interface AccessTokenValidator {
    suspend fun validateAccessToken(token: String): TokenInfo?
}

data class CredentialDevice(
    val deviceId: String,
    val deviceName: String?,
    val deviceModel: String?,
    val deviceFlag: Int,
)

/** 密码已被验证恰好针对这一个用户凭证 epoch。 */
class CredentialIssueRequest(
    val uid: String,
    val expectedUserCredentialEpoch: Long,
    val expectedPasswordHash: String,
    val device: CredentialDevice,
) {
    override fun toString(): String =
        "CredentialIssueRequest(uid=$uid, expectedUserCredentialEpoch=$expectedUserCredentialEpoch, " +
            "expectedPasswordHash=<redacted>, device=$device)"
}

/** 原始凭证被有意地从日志与调试器的字符串渲染中脱敏。 */
data class CredentialSubject(
    val username: String,
    val name: String,
)

class IssuedCredentials(
    val accessToken: String,
    val refreshToken: String,
    val principal: TokenInfo,
    val subject: CredentialSubject,
) {
    override fun toString(): String =
        "IssuedCredentials(<redacted>, principal=$principal, subject=<redacted>)"
}

/**
 * 人类注册聚合的事务作用域首凭证写入器。
 *
 * 这个端口不能自己开启或提交事务。它与 [TokenRepository] 分离，以便正常的登录/刷新
 * 不会意外地变成第二条注册写路径。
 */
fun interface InitialCredentialIssuer {
    fun issueInitialCredentials(
        transaction: PgWriteTransactionContext,
        user: User,
        device: CredentialDevice,
    ): IssuedCredentials
}

/** 认证凭证持久化。原始令牌绝不能存储。 */
interface TokenRepository : AccessTokenValidator {
    suspend fun issueCredentials(request: CredentialIssueRequest): IssuedCredentials?

    /**
     * 用其稳定的刷新令牌（refresh bearer）重新认证一个设备。
     *
     * 成功调用只轮换访问令牌，推进设备凭证 epoch，并原样返回同一个 [refreshToken] 及其
     * 原始绝对过期时间。实现必须串行化重试，使丢失的响应可以安全重试，且最新返回的
     * 访问令牌是权威的。
     */
    suspend fun refreshCredentials(
        refreshToken: String,
        device: CredentialDevice,
    ): IssuedCredentials?

    /**
     * 撤回一个设备并推进其不可逆的凭证 epoch。调用方必须从已认证会话或已授权的
     * 管理员命令中推导出 [uid] 与 [deviceId]。
     *
     * @return 已提交的设备 epoch；设备不存在时返回 null。
     */
    suspend fun revokeDevice(uid: String, deviceId: String): Long?

    /**
     * 仅当 [deviceId] 仍处于调用会话所认证的那一代时才撤回它。如果更新的登录/刷新
     * 已经推进了该设备，则保留那组更新的凭证聚合不变，并返回其 epoch，使实时会话
     * 围栏仍能退役过期的调用方。
     */
    suspend fun revokeDeviceIfCurrent(
        uid: String,
        deviceId: String,
        expectedDeviceCredentialEpoch: Long,
    ): Long?

    /** 原子地比对已验证的旧哈希、替换它，并撤回该用户的全部凭证。 */
    suspend fun changePasswordAndRevoke(
        uid: String,
        expectedPasswordHash: String,
        newPasswordHash: String,
    ): Long?
}

/**
 * 凭证绑定 TCP 会话的权威准入检查。
 *
 * ClientRegistry 在调用本端口之前先临时索引连接。在此快照之前发生的凭证变更会在这里
 * 被观察到；在快照之后发生的，会通过 ClientRegistry 串行化的失效路径观察到临时连接。
 * 这样就关闭了过期认证的窗口，而无需为每个历史用户或设备保留一个进程本地的 epoch 围栏。
 */
fun interface CredentialSessionAuthority {
    suspend fun isCurrent(
        uid: String,
        deviceId: String,
        userCredentialEpoch: Long,
        deviceCredentialEpoch: Long,
    ): Boolean
}

/** 在发布任何实时会话围栏之前提交的管理类凭证变更。 */
interface CredentialAdministration {
    suspend fun banUser(uid: String): Long
    suspend fun unbanUser(uid: String)
    suspend fun resetPasswordAndRevoke(uid: String, passwordHash: String): Long
}

data class TokenInfo(
    val uid: String,
    val deviceId: String,
    val deviceFlag: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val userCredentialEpoch: Long,
    val deviceCredentialEpoch: Long,
)

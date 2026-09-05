package com.virjar.tk.app.client

import com.virjar.tk.shared.client.TokenStore
import com.virjar.tk.shared.client.AuthenticationAttemptAdmission
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.StoredLogin
import com.virjar.tk.shared.client.TokenStoreOwner
import com.virjar.tk.shared.client.UserSession
import kotlinx.coroutines.flow.StateFlow

/**
 * 一个组合式认证根的固定 credential owner。
 *
 * 这个类刻意独立于 Compose。它拥有进程级的 TokenStore 租约、从该租约恢复的用户身份，
 * 以及将持久凭据准入与注销或控制器销毁线性化的 AUTH 回调准入。
 * 传输回调绝不直接写入 Compose 状态。
 */
internal class AuthControllerCredentialOwner private constructor(
    private val tokenStore: TokenStore,
    val deploymentIdentity: DeploymentIdentity,
    private val tokenOwner: TokenStoreOwner,
    private val credentialSnapshot: AuthCredentialSnapshotHolder,
    private val authenticationAttempts: AuthenticationAttemptAdmission,
    val userSession: UserSession,
) : AuthLogoutIdentityOwner {
    val hasSavedLogin: Boolean get() = tokenOwner.savedLogin != null
    val rejectedProtocolVersions: StateFlow<Set<Int>>
        get() = tokenStore.rejectedProtocolVersions

    fun requiresProtocolUpgrade(protocolVersion: Int): Boolean {
        require(protocolVersion in PROTOCOL_VERSION_RANGE) { "Invalid client protocol version" }
        return protocolVersion in rejectedProtocolVersions.value
    }

    /** 协议拒绝是部署事实，与 refresh-token 的 owner generation 无关。 */
    fun markProtocolVersionRejected(protocolVersion: Int): Boolean {
        require(protocolVersion in PROTOCOL_VERSION_RANGE) { "Invalid client protocol version" }
        return tokenStore.markProtocolVersionRejected(protocolVersion)
    }

    fun savedLoginSnapshot(): StoredLogin? = credentialSnapshot.snapshot()

    /**
     * 把一次成功的 AUTH 结果作为一个被准入的用户身份事务提交。持久化在 [UserSession]
     * 持有其身份锁期间运行，因此失败/过期的 TokenStore 写入不可能发布一个从未持久化的内存身份。
     */
    fun acceptAuthResult(
        success: Boolean,
        uid: String?,
        username: String?,
        name: String?,
        refreshToken: String?,
        accessToken: String?,
        datasetId: String?,
        failureReason: String?,
    ) {
        if (!success) {
            // 类型化失败由 AuthController 在其 Compose owner 线程上分类。共享的 attempt 租约
            // 已经准入这个完整的响应处理器；只撤销 bearer，并为可能的离线延续保留固定的 LocalCache 身份。
            userSession.onAuthAttemptFailed(failureReason)
            return
        }

        val authenticatedUid = uid?.takeIf(String::isNotBlank)
            ?: error("Authentication response is missing uid")
        val admittedRefreshToken = refreshToken?.takeIf(String::isNotBlank)
            ?: error("Authentication response is missing refresh token")
        val admittedDatasetId = datasetId?.takeIf(String::isNotBlank)
            ?: error("Authentication response is missing dataset identity")
        userSession.onAuthSuccess(
            authenticatedUid,
            username,
            name,
            admittedRefreshToken,
            accessToken,
            admittedDatasetId,
        ) {
            val persisted = tokenStore.save(
                tokenOwner.generation,
                authenticatedUid,
                admittedRefreshToken,
                admittedDatasetId,
            ) ?: error("Authentication credential owner was superseded")
            credentialSnapshot.publish(persisted)
        }
    }

    /** 等待一个被准入的处理器，然后让所有旧的/延迟的 attempt 租约永久失效。 */
    fun <T> retireForAuthReplacement(cleanup: () -> T): T {
        authenticationAttempts.retire()
        return cleanup()
    }

    /** 此线性化点之后，迟到的 AUTH 回调不能再修改凭据或身份。 */
    override fun retireAuthResults() = authenticationAttempts.retire()

    /**
     * 只退役被 HTTP 拒绝的那个确切的 bearer。AUTH 结果安装使用同一个准入监视器，
     * 因此延迟到达的 401 不可能退役由重连安装的 token。
     */
    fun retireForHttpUnauthorized(
        rejectedAccessToken: String,
        ownerStillCurrent: () -> Boolean,
        retirement: () -> Unit,
    ): Boolean {
        require(rejectedAccessToken.isNotBlank()) { "Rejected HTTP credential must not be blank" }
        return authenticationAttempts.retireIf(
            predicate = {
                ownerStillCurrent() &&
                    userSession.httpCredentialsSnapshot().accessToken == rejectedAccessToken
            },
            retirement = retirement,
        )
    }

    /**
     * 只比较并清除这个 owner 自己的确切持久快照。本地快照在 finally 中清除，
     * 这样存储失败就不会让这个控制器误以为自己仍然拥有某个 token。
     */
    override fun clearStoredLogin() {
        val expected = credentialSnapshot.snapshot()
        try {
            expected?.let(tokenStore::compareAndClear)
        } finally {
            credentialSnapshot.clear()
        }
    }

    /** 过期的控制器只丢弃它的本地视图；它绝不能清除后继者的存储。 */
    fun forgetCredentialSnapshot() {
        credentialSnapshot.clear()
    }

    override fun clearUserIdentity(reason: String?) {
        userSession.onAuthFailed(reason)
    }

    /** 离线启动只能打开由这个仍然现行的 owner 租约固定的 cache 命名空间。 */
    fun ownsPersistedIdentity(expectedUid: String): Boolean {
        val persisted = credentialSnapshot.snapshot() ?: return false
        return persisted.ownerGeneration == tokenOwner.generation &&
            persisted.deploymentFingerprint == deploymentIdentity.fingerprint &&
            persisted.uid == expectedUid &&
            userSession.uid == expectedUid &&
            tokenStore.isCurrentOwner(tokenOwner.generation)
    }

    /** 纯策略结果，在 AUTH 之后创建或复用 session 资源图之前使用。 */
    fun admitAuthenticatedSession(
        existingSessionUid: String?,
        existingSessionDatasetId: String?,
    ): AuthenticatedSessionAdmission {
        val authenticatedUid = userSession.uid
        val admittedRefreshToken = userSession.refreshToken
        if (authenticatedUid.isBlank() || admittedRefreshToken.isNullOrBlank()) {
            return AuthenticatedSessionAdmission.MissingDurableIdentity
        }
        if (existingSessionUid != null && existingSessionUid != authenticatedUid) {
            return AuthenticatedSessionAdmission.ExistingSessionIdentityMismatch
        }
        if (existingSessionDatasetId != null && existingSessionDatasetId != userSession.datasetId) {
            return AuthenticatedSessionAdmission.ExistingSessionDatasetMismatch(authenticatedUid)
        }
        val persisted = credentialSnapshot.snapshot()
        if (
            persisted == null ||
            persisted.ownerGeneration != tokenOwner.generation ||
            persisted.deploymentFingerprint != deploymentIdentity.fingerprint ||
            persisted.uid != authenticatedUid ||
            persisted.refreshToken != admittedRefreshToken ||
            persisted.datasetId != userSession.datasetId ||
            !tokenStore.isCurrentOwner(tokenOwner.generation)
        ) {
            return AuthenticatedSessionAdmission.SupersededCredentialOwner
        }
        return AuthenticatedSessionAdmission.Owned(authenticatedUid)
    }

    companion object {
        private val PROTOCOL_VERSION_RANGE = 0..Int.MAX_VALUE

        fun claim(
            tokenStore: TokenStore,
            deploymentIdentity: DeploymentIdentity,
            tcpHost: String,
            tcpPort: Int,
            authenticationAttempts: AuthenticationAttemptAdmission =
                AuthenticationAttemptAdmission(),
        ): AuthControllerCredentialOwner {
            require(tokenStore.deploymentIdentity == deploymentIdentity) {
                "TokenStore belongs to a different TCP+HTTP deployment"
            }
            require(
                DeploymentIdentity.from(tcpHost, tcpPort, deploymentIdentity.httpBaseUrl) == deploymentIdentity,
            ) {
                "Authentication transport does not match the bound deployment"
            }

            val tokenOwner = tokenStore.claimOwner()
            val userSession = UserSession().apply {
                tokenOwner.savedLogin?.let { saved ->
                    restorePersistedLogin(saved.uid, saved.refreshToken, saved.datasetId)
                }
            }
            return AuthControllerCredentialOwner(
                tokenStore = tokenStore,
                deploymentIdentity = deploymentIdentity,
                tokenOwner = tokenOwner,
                credentialSnapshot = AuthCredentialSnapshotHolder(
                    ownerGeneration = tokenOwner.generation,
                    initial = tokenOwner.savedLogin,
                ),
                authenticationAttempts = authenticationAttempts,
                userSession = userSession,
            )
        }
    }
}

internal sealed interface AuthenticatedSessionAdmission {
    data class Owned(val uid: String) : AuthenticatedSessionAdmission

    data object MissingDurableIdentity : AuthenticatedSessionAdmission

    data object ExistingSessionIdentityMismatch : AuthenticatedSessionAdmission

    data class ExistingSessionDatasetMismatch(val uid: String) : AuthenticatedSessionAdmission

    data object SupersededCredentialOwner : AuthenticatedSessionAdmission
}

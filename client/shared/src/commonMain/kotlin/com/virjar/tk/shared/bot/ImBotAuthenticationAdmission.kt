package com.virjar.tk.shared.bot

import com.virjar.tk.shared.client.AuthenticationAttemptFailureKind
import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.SessionBoundaryReentrantCloseException
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.SessionResourceCloseException
import com.virjar.tk.shared.client.SessionWorkGateReentrantCloseException
import com.virjar.tk.shared.client.UserSession
import com.virjar.tk.shared.client.isRetryableServerState
import com.virjar.tk.shared.client.mergeSessionLifecycleFailures
import com.virjar.tk.shared.client.releaseAllSessionResources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 类型化的服务端拒绝，区别于传输故障与超时故障。 */
internal class ImBotAuthenticationRejectedException(
    val kind: AuthenticationFailureKind,
    reason: String,
) : IllegalStateException("authentication rejected: $reason") {
    val requiresOperatorIntervention: Boolean
        get() = kind !in setOf(
            AuthenticationFailureKind.SERVER_MAINTENANCE,
            AuthenticationFailureKind.TOO_MANY_CONNECTIONS,
        )
}

/** 一次未能取得 AUTH 响应的单次密码/注册尝试。 */
internal class ImBotAuthenticationTransportException(
    val kind: AuthenticationAttemptFailureKind,
    reason: String,
) : IllegalStateException("authentication transport failed: $reason")

/** 在原子性地发布实时身份之前，持久准入已认证的凭据。 */
internal fun admitImBotAuthentication(
    userSession: UserSession,
    uid: String,
    username: String,
    displayName: String?,
    refreshToken: String,
    accessToken: String?,
    datasetId: String,
    onRefreshCredentials: ((uid: String, username: String, refreshToken: String) -> Unit)?,
) {
    userSession.onAuthSuccess(
        uid = uid,
        username = username,
        name = displayName,
        refreshToken = refreshToken,
        accessToken = accessToken,
        datasetId = datasetId,
        durableCommit = {
            onRefreshCredentials?.invoke(uid, username, refreshToken)
        },
    )
}

/** 公开的、不带 bearer 的终局事实，无头 owner 无需解析文本即可等待。 */
sealed interface ImBotAuthenticationTerminal {
    data object HttpUnauthorized : ImBotAuthenticationTerminal

    data class AuthResponseRejected(
        val failureKind: AuthenticationFailureKind,
    ) : ImBotAuthenticationTerminal
}

/**
 * 将持久 AUTH 的安装、精确 bearer HTTP 的退场与 owner 的 shutdown 串行化。
 *
 * 这里刻意采用一个专用门禁，而不是先检查再 [close]：被拒绝的 bearer 比较必须与 [use]
 * 共享同一把监视锁，否则一次重连可能在比较与退场之间装上一个新的 access token。
 */
internal class ImBotAuthResultAdmission {
    private val lock = Any()
    private var open = true
    private var operationDepth = 0

    fun <T> use(block: () -> T): T = synchronized(lock) {
        check(open) { "ImBot authentication result is stopped" }
        operationDepth += 1
        try {
            block()
        } finally {
            operationDepth -= 1
        }
    }

    fun runIfActive(block: () -> Unit): Boolean = synchronized(lock) {
        if (!open) return@synchronized false
        operationDepth += 1
        try {
            block()
        } finally {
            operationDepth -= 1
        }
        true
    }

    /** 原子地校验外部身份状态，让 AUTH 退场，然后提交终局身份。 */
    fun retireIf(
        predicate: () -> Boolean,
        retirement: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (!open || !predicate()) return@synchronized false
        open = false
        if (operationDepth > 0) throw reentrantCloseFailure()
        operationDepth += 1
        try {
            retirement()
        } finally {
            operationDepth -= 1
        }
        true
    }

    fun close(): Boolean = synchronized(lock) {
        if (!open) return@synchronized false
        open = false
        if (operationDepth > 0) throw reentrantCloseFailure()
        true
    }

    private fun reentrantCloseFailure() =
        SessionWorkGateReentrantCloseException("ImBot authentication result")
}

/** 把所有 shutdown 调用方汇入一次清理与一个终局结果，且不发生锁顺序反转。 */
internal class ImBotShutdownLifecycle(
    private val authResultAdmission: ImBotAuthResultAdmission,
) {
    private val lock = Any()
    private var phase = Phase.OPEN
    private var terminalFailure: Throwable? = null

    fun shutdown(vararg releases: Pair<String, () -> Unit>) {
        // 持久钩子可能重入调用 shutdown，因此关闭 AUTH 时绝不能持有 [lock]。
        var boundaryFailure: SessionWorkGateReentrantCloseException? = null
        try {
            authResultAdmission.close()
        } catch (failure: SessionWorkGateReentrantCloseException) {
            boundaryFailure = failure
        }

        synchronized(lock) {
            when (phase) {
                Phase.CLOSED -> {
                    terminalFailure?.let { throw it }
                    return
                }
                Phase.CLOSING -> throw SessionBoundaryReentrantCloseException(
                    "ImBot shutdown cannot reenter its resource cleanup",
                )
                Phase.OPEN -> phase = Phase.CLOSING
            }

            var releaseFailure: Throwable? = null
            val failures = try {
                releaseAllSessionResources(*releases)
            } catch (failure: Throwable) {
                releaseFailure = failure
                emptyList()
            }
            val ordinaryFailure = failures
                .firstOrNull { it.second is SessionBoundaryReentrantCloseException }
                ?.second
                ?: failures.takeIf { it.isNotEmpty() }?.let {
                    SessionResourceCloseException("ImBot", failures.map { failure -> failure.second })
                }
            var combinedFailure: Throwable? = null
            listOfNotNull(boundaryFailure, ordinaryFailure, releaseFailure).forEach { failure ->
                combinedFailure = mergeSessionLifecycleFailures(combinedFailure, failure)
            }
            terminalFailure = combinedFailure
            phase = Phase.CLOSED
            terminalFailure?.let { throw it }
        }
    }

    private enum class Phase { OPEN, CLOSING, CLOSED }
}

/**
 * 把权威故障转换为一个类型化终局与一个清理回调。
 *
 * 终局在仍持有 [ImBotAuthResultAdmission] 时即被认领，但投递发生在两把监视锁之外。
 * 这样并发的公开 shutdown 可以在关闭 AUTH 门禁之后观察到 AUTH_REVOKED，
 * 而不会从凭据提交中重入调用资源图。
 */
internal class ImBotAuthenticationLifecycle(
    private val userSession: UserSession,
    private val authResultAdmission: ImBotAuthResultAdmission,
) {
    private val lock = Any()
    private val ownerUid: String
    private val ownerIdentityEpoch: Long

    init {
        val owner = userSession.httpCredentialsSnapshot()
        check(owner.uid.isNotBlank() && !owner.accessToken.isNullOrBlank()) {
            "ImBot authentication lifecycle requires an authenticated owner"
        }
        ownerUid = owner.uid
        ownerIdentityEpoch = owner.identityEpoch
    }
    private val _terminal = MutableStateFlow<ImBotAuthenticationTerminal?>(null)
    val terminal: StateFlow<ImBotAuthenticationTerminal?> = _terminal.asStateFlow()

    private var claimedTerminal: ImBotAuthenticationTerminal? = null
    private var terminalHandler: ((ImBotAuthenticationTerminal) -> Unit)? = null
    private var deliveryStarted = false
    private var completionPublished = false

    fun bindTerminalHandler(handler: (ImBotAuthenticationTerminal) -> Unit) {
        synchronized(lock) {
            check(terminalHandler == null) { "ImBot authentication terminal handler is already bound" }
            terminalHandler = handler
        }
        deliverClaimedTerminal()
    }

    /** 对精确 bearer 的最终 CAS 判定。延迟到达的 401 无法废掉重连后安装的 token。 */
    fun reportHttpUnauthorized(rejectedAccessToken: String) {
        require(rejectedAccessToken.isNotBlank()) { "Rejected HTTP credential must not be blank" }
        var claimed = false
        val retired = authResultAdmission.retireIf(
            predicate = {
                val current = userSession.httpCredentialsSnapshot()
                current.uid == ownerUid &&
                    current.identityEpoch == ownerIdentityEpoch &&
                    current.accessToken == rejectedAccessToken
            },
            retirement = {
                userSession.onAuthFailed("HTTP authentication was rejected")
                claimed = claimTerminal(ImBotAuthenticationTerminal.HttpUnauthorized)
            },
        )
        if (retired && claimed) deliverClaimedTerminal()
    }

    /** 可重试的服务端背压保留资源图；其余任何已建立的拒绝都会终结它。 */
    fun reportAuthenticationFailure(kind: AuthenticationFailureKind, reason: String) {
        if (kind.isRetryableServerState) {
            authResultAdmission.runIfActive { userSession.onAuthAttemptFailed(reason) }
            return
        }
        var claimed = false
        val retired = authResultAdmission.retireIf(
            predicate = { true },
            retirement = {
                userSession.onAuthFailed(reason)
                claimed = claimTerminal(
                    ImBotAuthenticationTerminal.AuthResponseRejected(kind),
                )
            },
        )
        if (retired && claimed) deliverClaimedTerminal()
    }

    /** 只在 shutdown 已让 AUTH 退场后才求值，从而封住公开 shutdown 与 401 的竞争。 */
    fun effectiveEndReason(requested: SessionEndReason): SessionEndReason = synchronized(lock) {
        if (claimedTerminal == null) requested else SessionEndReason.AUTH_REVOKED
    }

    /** 只在 ImBot 的全部资源排空后才发布，使 agent 唤醒时观察到的是已关闭的 bot。 */
    fun publishClaimedTerminalAfterCleanup() {
        val terminal = synchronized(lock) {
            if (completionPublished) return
            val claimed = claimedTerminal ?: return
            completionPublished = true
            claimed
        }
        _terminal.value = terminal
    }

    private fun claimTerminal(terminal: ImBotAuthenticationTerminal): Boolean = synchronized(lock) {
        if (claimedTerminal != null) return@synchronized false
        claimedTerminal = terminal
        true
    }

    private fun deliverClaimedTerminal() {
        val delivery = synchronized(lock) {
            if (deliveryStarted) return
            val terminal = claimedTerminal ?: return
            val handler = terminalHandler ?: return
            deliveryStarted = true
            handler to terminal
        }
        delivery.first(delivery.second)
    }
}

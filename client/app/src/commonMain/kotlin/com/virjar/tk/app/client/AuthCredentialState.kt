package com.virjar.tk.app.client

import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.shared.client.AuthenticationFailure
import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.StoredLogin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 传输 EventLoop 发布的持久凭据快照的非 Compose owner。
 * Generation 检查让过期的控制器即使在 TokenStore 实现意外宽松时也会本地失败；
 * Compose 只从它自己的 effect 线程读取这个 holder。
 */
internal class AuthCredentialSnapshotHolder(
    private val ownerGeneration: Long,
    initial: StoredLogin?,
) {
    private val lock = Any()
    private var value: StoredLogin? = initial?.also(::requireOwned)

    fun publish(next: StoredLogin) = synchronized(lock) {
        requireOwned(next)
        value = next
    }

    fun snapshot(): StoredLogin? = synchronized(lock) { value }

    fun clear(): StoredLogin? = synchronized(lock) {
        val previous = value
        value = null
        previous
    }

    private fun requireOwned(login: StoredLogin) {
        require(login.ownerGeneration == ownerGeneration) {
            "Credential snapshot belongs to a different TokenStore owner"
        }
    }
}

private const val IDENTITY_AUTO_LOGIN_TIMEOUT_MS = 12_000L
private const val SYNC_NO_PROGRESS_TIMEOUT_MS = 35_000L

internal enum class AutoLoginWatchdogOutcome {
    IDENTITY_TIMEOUT,
    SYNC_NO_PROGRESS_TIMEOUT,
    AUTHENTICATION_TERMINATED,
}

private enum class AutoLoginWatchdogSignal {
    RENEWED,
    AUTHENTICATION_TERMINATED,
}

private data class AutoLoginWatchdogSnapshot(
    val state: ConnectionState,
    val eventSyncCursor: Long,
    val eventSyncProgress: Long,
)

/**
 * 等待那一个自动登录 deadline，而不让传输状态抖动创建新的计时器。
 * 进入同步的首次转换启动更长的无进展窗口；此后只有新的游标/进展高水位才会续期它。
 * 游标重置和重连状态循环只是观察，而不是引导取得进展的证据。
 */
internal suspend fun awaitAutoLoginWatchdog(
    connectionState: StateFlow<ConnectionState>,
    eventSyncCursor: StateFlow<Long>,
    eventSyncProgress: StateFlow<Long>,
): AutoLoginWatchdogOutcome {
    var synchronizationStarted = false
    var cursorHighWater = eventSyncCursor.value
    var progressHighWater = eventSyncProgress.value
    val snapshots = combine(
        connectionState,
        eventSyncCursor,
        eventSyncProgress,
        ::AutoLoginWatchdogSnapshot,
    )

    while (true) {
        val timeoutMillis = if (synchronizationStarted) {
            SYNC_NO_PROGRESS_TIMEOUT_MS
        } else {
            IDENTITY_AUTO_LOGIN_TIMEOUT_MS
        }
        val signal = withTimeoutOrNull(timeoutMillis) {
            snapshots.mapNotNull { snapshot ->
                when {
                    snapshot.state == ConnectionState.AUTHENTICATED ||
                        snapshot.state == ConnectionState.AUTH_FAILED ->
                        AutoLoginWatchdogSignal.AUTHENTICATION_TERMINATED

                    !synchronizationStarted && snapshot.state == ConnectionState.SYNCHRONIZING -> {
                        synchronizationStarted = true
                        cursorHighWater = maxOf(cursorHighWater, snapshot.eventSyncCursor)
                        progressHighWater = maxOf(progressHighWater, snapshot.eventSyncProgress)
                        AutoLoginWatchdogSignal.RENEWED
                    }

                    synchronizationStarted -> snapshot.progressSignal(
                        cursorHighWater = cursorHighWater,
                        progressHighWater = progressHighWater,
                    )?.also {
                        cursorHighWater = maxOf(cursorHighWater, snapshot.eventSyncCursor)
                        progressHighWater = maxOf(progressHighWater, snapshot.eventSyncProgress)
                    }

                    else -> null
                }
            }.first()
        } ?: return if (synchronizationStarted) {
            AutoLoginWatchdogOutcome.SYNC_NO_PROGRESS_TIMEOUT
        } else {
            AutoLoginWatchdogOutcome.IDENTITY_TIMEOUT
        }

        if (signal == AutoLoginWatchdogSignal.AUTHENTICATION_TERMINATED) {
            return AutoLoginWatchdogOutcome.AUTHENTICATION_TERMINATED
        }
    }
}

private fun AutoLoginWatchdogSnapshot.progressSignal(
    cursorHighWater: Long,
    progressHighWater: Long,
): AutoLoginWatchdogSignal? = if (
    eventSyncCursor > cursorHighWater || eventSyncProgress > progressHighWater
) {
    AutoLoginWatchdogSignal.RENEWED
} else {
    null
}

/** 一次控制器退役是否可以修改其确切的持久 [StoredLogin] owner。 */
internal enum class StoredLoginRetirementDisposition {
    PRESERVE,
    CLEAR,
}

/**
 * 类型化的控制器终止边界。
 *
 * 本地资源失败仍然会退役内存中的 session 图和传输，但它不是服务器撤销了 refresh 凭据的证据。
 * 用户注销在单独拥有的 [AuthUserLogoutRetirement] 中清除凭据，而显式的新登录会单独退役
 * 上一个账号。在由失败驱动的终止边界中，只有权威的服务器拒绝才能清除凭据。
 */
internal enum class AuthControllerRetirementCause(
    val sessionEndReason: SessionEndReason,
    val storedLoginDisposition: StoredLoginRetirementDisposition,
) {
    AUTO_LOGIN_TIMEOUT(
        SessionEndReason.SHUTDOWN,
        StoredLoginRetirementDisposition.PRESERVE,
    ),
    PROTOCOL_UPGRADE(
        SessionEndReason.PROTOCOL_UPGRADE,
        StoredLoginRetirementDisposition.PRESERVE,
    ),
    MISSING_DURABLE_IDENTITY(
        SessionEndReason.SHUTDOWN,
        StoredLoginRetirementDisposition.PRESERVE,
    ),
    RETRYABLE_SERVER_AUTHENTICATION_FAILURE(
        SessionEndReason.SHUTDOWN,
        StoredLoginRetirementDisposition.PRESERVE,
    ),
    SERVER_AUTHENTICATION_REVOKED(
        SessionEndReason.AUTH_REVOKED,
        StoredLoginRetirementDisposition.CLEAR,
    ),
    LOCAL_CREDENTIAL_COMMIT_FAILURE(
        SessionEndReason.SHUTDOWN,
        StoredLoginRetirementDisposition.PRESERVE,
    ),
    OFFLINE_SESSION_INITIALIZATION_FAILURE(
        SessionEndReason.SHUTDOWN,
        StoredLoginRetirementDisposition.PRESERVE,
    ),
    AUTHENTICATED_SESSION_INITIALIZATION_FAILURE(
        SessionEndReason.SHUTDOWN,
        StoredLoginRetirementDisposition.PRESERVE,
    ),
    PLATFORM_AUTHENTICATED_CALLBACK_FAILURE(
        SessionEndReason.SHUTDOWN,
        StoredLoginRetirementDisposition.PRESERVE,
    ),
    ;

    fun retireStoredLogin(clear: () -> Unit) {
        if (storedLoginDisposition == StoredLoginRetirementDisposition.CLEAR) clear()
    }
}

/**
 * 分类 AUTH_FAILED 边界，而不把本地凭据提交异常与服务器响应混为一谈。
 * SDK 刻意对前者保留 [AuthenticationFailure] 为 null。
 */
internal fun authControllerRetirementCause(
    failure: AuthenticationFailure?,
): AuthControllerRetirementCause = when (failure?.kind) {
    AuthenticationFailureKind.REJECTED,
    AuthenticationFailureKind.DEVICE_BANNED,
    -> AuthControllerRetirementCause.SERVER_AUTHENTICATION_REVOKED
    AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED ->
        AuthControllerRetirementCause.PROTOCOL_UPGRADE
    AuthenticationFailureKind.SERVER_MAINTENANCE,
    AuthenticationFailureKind.TOO_MANY_CONNECTIONS,
    -> AuthControllerRetirementCause.RETRYABLE_SERVER_AUTHENTICATION_FAILURE
    null -> AuthControllerRetirementCause.LOCAL_CREDENTIAL_COMMIT_FAILURE
}

/** 可重试的 AUTH 边界只能保留一个由同一个持久 uid 拥有的、已经挂载的图。 */
internal fun AuthControllerRetirementCause.mayContinueOffline(
    hasLocalSessionOwner: Boolean,
    persistedIdentityOwned: Boolean,
): Boolean = hasLocalSessionOwner && persistedIdentityOwned && when (this) {
    AuthControllerRetirementCause.RETRYABLE_SERVER_AUTHENTICATION_FAILURE,
    AuthControllerRetirementCause.LOCAL_CREDENTIAL_COMMIT_FAILURE,
    -> true
    AuthControllerRetirementCause.AUTO_LOGIN_TIMEOUT,
    AuthControllerRetirementCause.PROTOCOL_UPGRADE,
    AuthControllerRetirementCause.MISSING_DURABLE_IDENTITY,
    AuthControllerRetirementCause.SERVER_AUTHENTICATION_REVOKED,
    AuthControllerRetirementCause.OFFLINE_SESSION_INITIALIZATION_FAILURE,
    AuthControllerRetirementCause.AUTHENTICATED_SESSION_INITIALIZATION_FAILURE,
    AuthControllerRetirementCause.PLATFORM_AUTHENTICATED_CALLBACK_FAILURE,
    -> false
}

/** 可重试的服务器背压已经拥有传输重连；本地提交失败则没有。 */
internal fun AuthControllerRetirementCause.disconnectAfterOfflineContinuation(): Boolean =
    this != AuthControllerRetirementCause.RETRYABLE_SERVER_AUTHENTICATION_FAILURE

internal fun requiresForcedProtocolUpgrade(failure: AuthenticationFailure?): Boolean =
    authControllerRetirementCause(failure) == AuthControllerRetirementCause.PROTOCOL_UPGRADE

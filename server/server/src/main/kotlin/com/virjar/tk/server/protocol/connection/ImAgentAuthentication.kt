package com.virjar.tk.server.protocol.connection

import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import com.virjar.tk.server.domain.auth.TokenInfo
import com.virjar.tk.server.infra.sync.AuthenticatedAgentAdmission
import com.virjar.tk.server.infra.sync.AuthenticatedAgentAdmissionPlan
import com.virjar.tk.server.infra.sync.ClientRegistry
import com.virjar.tk.server.domain.auth.AuthService
import com.virjar.tk.server.domain.auth.AuthenticationAttempt
import com.virjar.tk.server.domain.auth.AuthenticationAttemptKeys
import com.virjar.tk.server.domain.auth.AuthenticationOperation
import com.virjar.tk.server.domain.auth.AuthenticationResult
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.protocol.payload.AuthResponsePayload
import kotlinx.coroutines.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 连接级失败映射；拥有者取消是终结控制信号，不是鉴权失败。 */
internal suspend fun executeAuthenticationBoundary(
    authenticate: suspend () -> AuthenticationResult,
    reportInternalFailure: (Exception) -> Unit,
): AuthenticationResult = try {
    authenticate()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    reportInternalFailure(failure)
    AuthenticationResult(
        response = AuthResponsePayload(code = AuthService.CODE_AUTH_FAILED, reason = "Internal error"),
        principal = null,
    )
}

/** TCP socket 对端是唯一可信的来源身份；载荷与代理头被忽略。 */
internal fun authenticationAttempt(
    remoteAddress: SocketAddress?,
    payload: AuthRequestPayload,
): AuthenticationAttempt {
    val operation = when (payload.authType) {
        0 -> AuthenticationOperation.LOGIN
        1 -> AuthenticationOperation.REGISTER
        2 -> AuthenticationOperation.REFRESH
        else -> AuthenticationOperation.UNSUPPORTED
    }
    val directPeer = (remoteAddress as? InetSocketAddress)
        ?.address
        ?.hostAddress
    val accountKey = when (operation) {
        AuthenticationOperation.LOGIN, AuthenticationOperation.REGISTER ->
            AuthenticationAttemptKeys.username("human", payload.username)
        AuthenticationOperation.REFRESH ->
            AuthenticationAttemptKeys.bearer("refresh", payload.refreshToken)
        AuthenticationOperation.ADMIN, AuthenticationOperation.UNSUPPORTED ->
            AuthenticationAttemptKeys.unsupported()
    }
    return AuthenticationAttempt(
        operation = operation,
        sourceKey = AuthenticationAttemptKeys.directSource(directPeer),
        accountKey = accountKey,
    )
}

/** 所有守卫维度与容量失败共享同一个可重试、凭据中立的响应。 */
internal fun authenticationGuardDenialResponse() = AuthResponsePayload(
    code = AuthResponsePayload.CODE_SERVER_MAINTENANCE,
    reason = "Authentication temporarily unavailable",
)

/** 单连接认证状态机；CAS 保证同一 TCP 连接至多受理一个认证请求。 */
internal enum class ImAgentAuthAdmission { ACCEPT, REJECT_AND_CLOSE }

internal class ImAgentAuthState {
    private val state = AtomicReference(ImAgent.State.CONNECTED)

    val current: ImAgent.State get() = state.get()

    fun admitAuthentication(): ImAgentAuthAdmission =
        if (state.compareAndSet(ImAgent.State.CONNECTED, ImAgent.State.AUTHENTICATING)) {
            ImAgentAuthAdmission.ACCEPT
        } else {
            ImAgentAuthAdmission.REJECT_AND_CLOSE
        }

    fun markSynchronizing(): Boolean =
        state.compareAndSet(ImAgent.State.AUTHENTICATING, ImAgent.State.SYNCHRONIZING)

    fun markReady(): Boolean =
        state.compareAndSet(ImAgent.State.SYNCHRONIZING, ImAgent.State.AUTHENTICATED)

    fun disconnect(): ImAgent.State = state.getAndSet(ImAgent.State.DISCONNECTED)
}

/**
 * 同一连接的同步请求游标准入。正常分页必须严格递增；当服务端确认游标不属于当前用户时，
 * RESET 把门槛恢复到 -1，使客户端可以在不重新认证的情况下从 0 重新开始。
 *
 * 无效游标的判定运行在 IO worker，而下一条请求在 Netty EventLoop，因此这里必须是原子状态。
 */
internal class ImAgentSyncCursor {
    private val admitted = AtomicLong(-1L)

    val current: Long get() = admitted.get()

    fun admit(cursor: Long): Boolean {
        require(cursor >= 0L) { "sync cursor must be non-negative" }
        while (true) {
            val previous = admitted.get()
            if (cursor <= previous) return false
            if (admitted.compareAndSet(previous, cursor)) return true
        }
    }

    fun reset() {
        admitted.set(-1L)
    }
}

/** 一条连接可以运行 checkpoint RPC 或尾部重放请求之一，绝不能两者同时。 */
internal class ImAgentSyncOperationAdmission {
    private val inFlight = AtomicBoolean(false)

    fun tryAcquire(): Lease? =
        if (inFlight.compareAndSet(false, true)) Lease(this) else null

    private fun release() {
        check(inFlight.compareAndSet(true, false)) { "Synchronization operation was not active" }
    }

    class Lease internal constructor(
        private val owner: ImAgentSyncOperationAdmission,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) owner.release()
        }
    }
}

/**
 * 认证成功后的会话建立段（IO 协程内）：完成状态迁移、计入每用户/全局 socket 预算、
 * 经注册表准入（票据校验 / 已完成 / 半会话三路）并应用初始连接追踪策略。
 *
 * 返回补充了追踪上下文、可直接发送的 AUTH_RESP；任何一步关闭或拒绝了连接时返回
 * null，调用方不再发送响应（限制响应已由 [ImAgentFacade.sendAndClose] 刷出或连接已显式关闭）。
 */
internal suspend fun establishAuthenticatedSession(
    facade: com.virjar.tk.server.protocol.executor.ImAgentFacade,
    registry: ClientRegistry,
    principal: TokenInfo,
    response: AuthResponsePayload,
): AuthResponsePayload? {
    val authenticatedUid = principal.uid
    val completed = facade.completeAuthentication(
        uid = authenticatedUid,
        deviceId = principal.deviceId,
        userCredentialEpoch = principal.userCredentialEpoch,
        deviceCredentialEpoch = principal.deviceCredentialEpoch,
    )
    if (!completed) return null
    // 身份现在是临时的，计入每用户/全局 socket
    // 预算。在权威快照之前启动其有界的同步生命周期，
    // 使停滞的数据库读取无法无限期占用该名额。
    facade.refreshSyncStallTimeout()
    val admissionPlan = try {
        facade.admitAuthenticated { registry.beginAuthenticatedAdmission(it) }
    } catch (_: RejectedExecutionException) {
        // 注册表从不保留被拒绝的续延。此连接已经
        // 移到 SYNCHRONIZING，因此显式关闭它，而不是留下一个
        // 由心跳维持的半会话在没有 AUTH_RESP 的情况下永远等待。
        facade.recorder.trace(
            ConnectionTracePhase.AUTHENTICATION,
            ConnectionTraceOutcome.REJECTED,
        ) { "event=registryAdmission" }
        facade.closeConnection()
        return null
    }
    val admitted = when (admissionPlan) {
        is AuthenticatedAgentAdmissionPlan.Validate -> try {
            registry.completeAuthenticatedAdmission(admissionPlan.ticket)
        } catch (_: RejectedExecutionException) {
            facade.recorder.trace(
                ConnectionTracePhase.AUTHENTICATION,
                ConnectionTraceOutcome.REJECTED,
            ) { "event=credentialValidation" }
            facade.closeConnection()
            return null
        }
        is AuthenticatedAgentAdmissionPlan.Finished -> admissionPlan.result
        null -> return null
    }
    when (admitted) {
        AuthenticatedAgentAdmission.ADMITTED -> Unit
        AuthenticatedAgentAdmission.USER_LIMIT_REACHED -> {
            facade.sendAndClose(
                AuthResponsePayload(
                    code = AuthService.CODE_TOO_MANY_CONNECTIONS,
                    reason = AuthService.DEVICE_LIMIT_RESPONSE_REASON,
                ),
            )
            return null
        }
        AuthenticatedAgentAdmission.REJECTED -> return null
    }
    val effectivePolicy = try {
        registry.effectiveConnectionTracePolicy(
            uid = authenticatedUid,
            deviceId = principal.deviceId,
        )
    } catch (failure: Exception) {
        facade.recorder.trace(
            ConnectionTracePhase.POLICY,
            ConnectionTraceOutcome.FAILED,
            throwable = failure,
        ) { "event=lookup" }
        null
    }
    facade.recorder.applyInitialConnectionTracePolicy(
        uid = authenticatedUid,
        deviceId = principal.deviceId,
        policy = effectivePolicy,
    )
    val established = response.copy(connectionTraceContext = facade.recorder.context())
    facade.recorder.trace(
        ConnectionTracePhase.AUTHENTICATION,
        ConnectionTraceOutcome.SUCCEEDED,
    ) { "event=identityAccepted state=synchronizing" }
    facade.refreshSyncStallTimeout()
    return established
}

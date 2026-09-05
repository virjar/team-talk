package com.virjar.tk.shared.client

import com.virjar.tk.protocol.payload.AuthResponsePayload

/** 服务器声明的认证失败。transport 失败永远不会填充该值。 */
data class AuthenticationFailure(
    val kind: AuthenticationFailureKind,
    val reason: String,
    /**
     * 服务器已淘汰当前客户端版本，必须由新客户端接管。服务器自身落后时为 false：
     * 本次仍阻止工作区，但不能把可由服务器升级恢复的失败永久写成客户端升级围栏。
     */
    val requiresClientUpgrade: Boolean = kind == AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED,
)

/**
 * 在服务器能够应答之前就已结束的用户提交的登录/注册尝试。
 *
 * 它刻意与 [AuthenticationFailure] 分开：网络可达性不是权威的服务器拒绝，绝不能触发凭据吊销策略。
 */
data class AuthenticationAttemptFailure(
    val kind: AuthenticationAttemptFailureKind,
    val reason: String,
)

enum class AuthenticationAttemptFailureKind {
    TRANSPORT_UNAVAILABLE,
}

enum class AuthenticationFailureKind {
    REJECTED,
    PROTOCOL_VERSION_UNSUPPORTED,
    SERVER_MAINTENANCE,
    DEVICE_BANNED,
    TOO_MANY_CONNECTIONS,
}

/** 保留同一 refresh-auth owner、可通过受限重试收敛的服务器状态。 */
internal val AuthenticationFailureKind.isRetryableServerState: Boolean
    get() = this == AuthenticationFailureKind.SERVER_MAINTENANCE ||
        this == AuthenticationFailureKind.TOO_MANY_CONNECTIONS

internal fun AuthResponsePayload.toAuthenticationFailure(): AuthenticationFailure? {
    if (code == AuthResponsePayload.CODE_OK) return null
    val kind = when (code) {
        AuthResponsePayload.CODE_VERSION_UNSUPPORTED ->
            AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED
        AuthResponsePayload.CODE_SERVER_MAINTENANCE ->
            AuthenticationFailureKind.SERVER_MAINTENANCE
        AuthResponsePayload.CODE_DEVICE_BANNED ->
            AuthenticationFailureKind.DEVICE_BANNED
        AuthResponsePayload.CODE_TOO_MANY_CONNECTIONS ->
            AuthenticationFailureKind.TOO_MANY_CONNECTIONS
        else -> AuthenticationFailureKind.REJECTED
    }
    return AuthenticationFailure(
        kind = kind,
        reason = reason ?: "认证失败(code=$code)",
    )
}

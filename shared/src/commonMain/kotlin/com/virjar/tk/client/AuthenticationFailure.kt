package com.virjar.tk.client

import com.virjar.tk.protocol.payload.AuthResponsePayload

/** A server-declared authentication failure. Transport failures never populate this value. */
data class AuthenticationFailure(
    val kind: AuthenticationFailureKind,
    val reason: String,
)

enum class AuthenticationFailureKind {
    REJECTED,
    PROTOCOL_VERSION_UNSUPPORTED,
    SERVER_MAINTENANCE,
    DEVICE_BANNED,
    TOO_MANY_CONNECTIONS,
}

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

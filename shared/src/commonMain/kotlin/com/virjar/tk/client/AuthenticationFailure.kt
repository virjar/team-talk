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
}

internal fun AuthResponsePayload.toAuthenticationFailure(): AuthenticationFailure? {
    if (code == AuthResponsePayload.CODE_OK) return null
    val kind = if (code == AuthResponsePayload.CODE_VERSION_UNSUPPORTED) {
        AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED
    } else {
        AuthenticationFailureKind.REJECTED
    }
    return AuthenticationFailure(
        kind = kind,
        reason = reason ?: "认证失败(code=$code)",
    )
}

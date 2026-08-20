package com.virjar.tk.client

import com.virjar.tk.protocol.payload.AuthResponsePayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthenticationFailureTest {
    @Test
    fun `only explicit version response is classified as protocol upgrade`() {
        val versionFailure = AuthResponsePayload(
            code = AuthResponsePayload.CODE_VERSION_UNSUPPORTED,
            reason = "upgrade",
        ).toAuthenticationFailure()
        val ordinaryFailure = AuthResponsePayload(
            code = AuthResponsePayload.CODE_AUTH_FAILED,
            reason = "bad credentials",
        ).toAuthenticationFailure()

        assertEquals(AuthenticationFailureKind.PROTOCOL_VERSION_UNSUPPORTED, versionFailure?.kind)
        assertEquals(AuthenticationFailureKind.REJECTED, ordinaryFailure?.kind)
        assertNull(AuthResponsePayload(code = AuthResponsePayload.CODE_OK).toAuthenticationFailure())
    }

    @Test
    fun `unknown server rejection stays an ordinary authentication failure`() {
        val failure = AuthResponsePayload(code = 99).toAuthenticationFailure()

        assertEquals(AuthenticationFailureKind.REJECTED, failure?.kind)
        assertEquals("认证失败(code=99)", failure?.reason)
    }

    @Test
    fun `operational auth responses retain their retry classification`() {
        assertEquals(
            AuthenticationFailureKind.SERVER_MAINTENANCE,
            AuthResponsePayload(code = AuthResponsePayload.CODE_SERVER_MAINTENANCE)
                .toAuthenticationFailure()?.kind,
        )
        assertEquals(
            AuthenticationFailureKind.DEVICE_BANNED,
            AuthResponsePayload(code = AuthResponsePayload.CODE_DEVICE_BANNED)
                .toAuthenticationFailure()?.kind,
        )
        assertEquals(
            AuthenticationFailureKind.TOO_MANY_CONNECTIONS,
            AuthResponsePayload(code = AuthResponsePayload.CODE_TOO_MANY_CONNECTIONS)
                .toAuthenticationFailure()?.kind,
        )
    }
}

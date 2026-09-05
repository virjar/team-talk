package com.virjar.tk.server.e2e

import com.virjar.tk.server.domain.auth.AuthenticationAttempt
import com.virjar.tk.server.domain.auth.AuthenticationOperation
import kotlin.test.Test
import kotlin.test.assertNotNull

class TcpE2eEnvironmentAuthenticationGuardTest {
    @Test
    fun `trusted fixture admits a suite sized registration burst without cooling down`() {
        val guard = trustedE2eAuthenticationAttemptGuard(monotonicNanos = { 0L })

        repeat(64) { index ->
            val lease = assertNotNull(
                guard.tryAcquire(
                    AuthenticationAttempt(
                        operation = AuthenticationOperation.REGISTER,
                        sourceKey = "loopback-source",
                        accountKey = "e2e-account-$index",
                    ),
                ),
            )
            lease.close()
        }
    }
}

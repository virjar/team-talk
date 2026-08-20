package com.virjar.tk.e2e

import com.virjar.tk.client.ConnectionState
import com.virjar.tk.client.ImClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * 封禁 enforcement：PG 状态、credential epoch、同步中 TCP fence 与 bearer 失效必须闭环。
 */
class BanEnforcementTest {

    @Test
    fun `封禁覆盖同步中连接且解封不复活旧凭据`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                var failReason: String? = null
                var registeredUid: String? = null
                var oldRefresh: String? = null
                var oldAccess: String? = null
                val c1 = ImClient(onAuthResult = { ok, uid, _, _, refresh, access, reason ->
                    if (ok) {
                        registeredUid = uid
                        oldRefresh = refresh
                        oldAccess = access
                    } else {
                        failReason = reason
                    }
                })
                val username = "ban-${System.nanoTime()}"
                c1.register(username, "password123", "B", "d1", "T", "127.0.0.1", env.tcpPort)
                // Deliberately install no event projection. AUTH succeeds, but the connection
                // remains in SYNCHRONIZING and therefore exercises pre-live credential admission.
                withTimeout(10_000) { c1.state.first { it == ConnectionState.SYNCHRONIZING } }
                val uid = assertNotNull(registeredUid)
                val refresh = assertNotNull(oldRefresh)
                val access = assertNotNull(oldAccess)
                assertEquals(uid, env.uidOf(username))
                assertNotNull(env.accessTokenValidator.validateAccessToken(access))

                env.adminService.banUser(uid)

                withTimeout(10_000) {
                    c1.state.first { it == ConnectionState.DISCONNECTED || it == ConnectionState.AUTH_FAILED }
                }
                assertNull(env.accessTokenValidator.validateAccessToken(access))
                c1.destroy()

                // Unban changes account status only. Neither old access nor refresh may revive.
                env.adminService.unbanUser(uid)
                assertNull(env.accessTokenValidator.validateAccessToken(access))
                val c2 = ImClient(onAuthResult = { ok, _, _, _, _, _, reason -> if (!ok) failReason = reason })
                c2.authenticate(uid, refresh, "d1", "T", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { c2.state.first { it == ConnectionState.AUTH_FAILED } }
                assertEquals("Invalid or expired refresh token", failReason)
                c2.destroy()

                // Password proof is the only recovery path after unban.
                val c3 = ImClient(onAuthResult = { ok, _, _, _, _, _, reason -> if (!ok) failReason = reason })
                val c3Events = c3.installE2eEventProjection()
                c3.login(username, "password123", "d1", "T", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { c3.state.first { it == ConnectionState.AUTHENTICATED } }
                c3Events.close()
                c3.destroy()
            }
        }
    }
}

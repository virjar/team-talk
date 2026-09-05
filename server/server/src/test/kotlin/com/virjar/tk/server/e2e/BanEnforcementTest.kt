package com.virjar.tk.server.e2e

import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ImClient
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
                val c1 = ImClient(onAuthResult = { ok, uid, _, _, refresh, access, _, reason ->
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
                // 故意不安装任何事件投影。AUTH 会成功，但连接
                // 保持 SYNCHRONIZING，从而覆盖上线前凭据准入的路径。
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

                // 解封只改变账号状态。旧的 access 与 refresh 都不允许复活。
                env.adminService.unbanUser(uid)
                assertNull(env.accessTokenValidator.validateAccessToken(access))
                val c2 = ImClient(onAuthResult = { ok, _, _, _, _, _, _, reason -> if (!ok) failReason = reason })
                c2.authenticate(uid, refresh, "d1", "T", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { c2.state.first { it == ConnectionState.AUTH_FAILED } }
                assertEquals("Invalid or expired refresh token", failReason)
                c2.destroy()

                // 密码证明是解封后唯一的恢复路径。
                val c3 = ImClient(onAuthResult = { ok, _, _, _, _, _, _, reason -> if (!ok) failReason = reason })
                val c3Events = c3.installE2eEventProjection(env.syncDatasetId)
                c3.login(username, "password123", "d1", "T", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { c3.state.first { it == ConnectionState.AUTHENTICATED } }
                c3Events.close()
                c3.destroy()
            }
        }
    }
}

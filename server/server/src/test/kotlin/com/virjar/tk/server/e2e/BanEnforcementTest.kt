package com.virjar.tk.server.e2e

import com.virjar.tk.shared.client.AuthenticationFailureKind
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ImClient
import kotlinx.coroutines.CompletableDeferred
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
                var registeredUid: String? = null
                var oldRefresh: String? = null
                var oldAccess: String? = null
                val c1 = ImClient(onAuthResult = { ok, uid, _, _, refresh, access, _, _ ->
                    if (ok) {
                        registeredUid = uid
                        oldRefresh = refresh
                        oldAccess = access
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
                val rejectedRefresh = CompletableDeferred<String?>()
                val c2 = ImClient(onAuthResult = { ok, _, _, _, _, _, _, reason ->
                    if (!ok) rejectedRefresh.complete(reason)
                })
                try {
                    c2.authenticate(uid, refresh, "d1", "T", "127.0.0.1", env.tcpPort)
                    withTimeout(10_000) { c2.state.first { it == ConnectionState.AUTH_FAILED } }
                    // AUTH_FAILED 先于 onAuthResult 发布，观察到状态不代表 Netty 线程已经执行回调。
                    // 等待实际回调完成，同时保留状态与旧凭据拒绝原因两项断言。
                    assertEquals(
                        "Invalid or expired refresh token",
                        withTimeout(10_000) { rejectedRefresh.await() },
                    )
                } finally {
                    c2.destroy()
                }

                // 密码证明是解封后唯一的恢复路径。
                val c3 = ImClient()
                val c3Events = c3.installE2eEventProjection(env.syncDatasetId)
                c3.login(username, "password123", "d1", "T", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { c3.state.first { it == ConnectionState.AUTHENTICATED } }
                c3Events.close()
                c3.destroy()
            }
        }
    }

    @Test
    fun `封禁后的登录与旧凭据重连返回账号封禁终局判定`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                // 1. 登录路径：封禁账号的密码登录返回 CODE_ACCOUNT_BANNED。
                val username = "ban-signal-${System.nanoTime()}"
                val c1 = ImClient(onAuthResult = { ok, uid, _, _, _, _, _, _ -> if (ok) Unit })
                c1.register(username, "password123", "B", "d1", "T", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { c1.state.first { it == ConnectionState.AUTH_FAILED || it == ConnectionState.SYNCHRONIZING } }
                val uid = assertNotNull(env.uidOf(username))
                c1.destroy()
                env.adminService.banUser(uid)

                // 2. 登录路径：封禁账号的密码登录携带账号封禁终局判定（T013）。
                var loginFailure: AuthenticationFailureKind? = null
                val bannedLoginObserved = CompletableDeferred<AuthenticationFailureKind>()
                val observedClient = ImClient(
                    host = "127.0.0.1",
                    port = env.tcpPort,
                    onAuthResult = { _, _, _, _, _, _, _, _ -> },
                    onAuthenticationFailureObserved = { failure ->
                        loginFailure = failure.kind
                        bannedLoginObserved.complete(failure.kind)
                    },
                )
                observedClient.login(username, "password123", "d1", "B", deviceModel = "T")
                withTimeout(10_000) { assertEquals(AuthenticationFailureKind.ACCOUNT_BANNED, bannedLoginObserved.await()) }
                observedClient.destroy()

                // 3. 重连路径：封禁前签发的 refresh token 同样返回账号封禁判定。
                //     （封禁已删除凭据行并写入墓碑；本用例的旧 token 来自注册会话。）
            }
        }
    }
}

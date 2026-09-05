package com.virjar.tk.server.api

import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuard
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuardConfig
import com.virjar.tk.server.domain.auth.AuthenticationAttemptKeys
import com.virjar.tk.server.domain.auth.AuthenticationOperation
import com.virjar.tk.server.domain.auth.AuthenticationOperationLimits
import com.virjar.tk.server.domain.telemetry.TelemetryNumericRange
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueQuery
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 管理后台鉴权、分页值边界和轻量 HTTP 路由行为。
 */
class AdminApiTest {

    @Test
    fun `带 query 的规范登录 path 仍豁免鉴权且相似 suffix 不豁免`() = testApplication {
        val protectedHandlerEntered = AtomicBoolean(false)
        application {
            install(ContentNegotiation) { json() }
            routing {
                route("/api/admin") {
                    post("/login") { call.respondText("ok") }
                    get("/lookalike/api/admin/login") {
                        protectedHandlerEntered.set(true)
                        call.respondText("protected")
                    }
                    installAdminAuthorization(this@AdminApiTest.auth())
                }
            }
        }

        assertEquals(HttpStatusCode.OK, client.post("/api/admin/login?source=desktop").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/admin/lookalike/api/admin/login").status,
        )
        assertEquals(false, protectedHandlerEntered.get(), "unauthorized calls must not enter handlers")
    }

    @Test
    fun `负分页参数在 route 边界返回 400`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/page") {
                    val pagination = call.adminPageRequestOrRespond() ?: return@get
                    call.respondText(pagination.offset.toString())
                }
            }
        }

        assertEquals(HttpStatusCode.BadRequest, client.get("/page?page=-1&size=20").status)
    }

    private fun auth(
        user: String = "admin",
        pass: String = "test-only-password",
        clock: () -> Long = { 1_000L },
        maxActiveTokens: Int = AdminAuthConfig.DEFAULT_MAX_ACTIVE_TOKENS,
    ) = AdminAuthConfig(user, pass, clock, maxActiveTokens)

    @Test
    fun `正确凭据换 token，错误凭据 null`() {
        val a = auth()
        val token = a.login("admin", "test-only-password")
        assertTrue(!token.isNullOrBlank(), "正确凭据应返回 token")
        assertNull(a.login("admin", "wrong"), "错误密码拒绝")
        assertNull(a.login("nobody", "test-only-password"), "未知用户拒绝")
        assertNull(AdminAuthConfig(username = null, password = null).login("admin", "admin"))
    }

    @Test
    fun `token 校验与拒绝`() {
        val a = auth()
        val token = a.login("admin", "test-only-password")!!
        assertEquals("admin", a.principal(token), "receipt actor must come from the verified admin session")
        assertNull(a.principal(null))
        assertNull(a.principal(""))
        assertNull(a.principal("Bearer $token"), "带前缀的原始值不是 token")
    }

    @Test
    fun `route authorization publishes verified admin principal for audit receipts`() = testApplication {
        val auth = auth(user = "custody-operator")
        val token = auth.login("custody-operator", "test-only-password")!!
        application {
            install(ContentNegotiation) { json() }
            routing {
                route("/api/admin") {
                    get("/principal") { call.respondText(call.requireAdminPrincipal()) }
                    installAdminAuthorization(auth)
                }
            }
        }

        val response = client.get("/api/admin/principal") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("custody-operator", response.bodyAsText())
    }

    @Test
    fun `token 随机不重复`() {
        val a = auth()
        val t1 = a.login("admin", "test-only-password")!!
        val t2 = a.login("admin", "test-only-password")!!
        assertNotEquals(t1, t2)
        assertEquals(AdminAuthConfig.TOKEN_TTL_MS, 12 * 3600 * 1000L, "12h 过期")
    }

    @Test
    fun `token 有上限且过期后回收`() {
        var now = 10_000L
        val a = auth(clock = { now }, maxActiveTokens = 2)
        val first = a.login("admin", "test-only-password")!!
        val second = a.login("admin", "test-only-password")!!
        val third = a.login("admin", "test-only-password")!!

        assertEquals(2, a.activeTokenCount())
        assertNull(a.principal(first), "超过上限时最早 token 被撤销")
        assertEquals("admin", a.principal(second))
        assertEquals("admin", a.principal(third))

        now += AdminAuthConfig.TOKEN_TTL_MS
        assertNull(a.principal(second))
        a.login("admin", "test-only-password")
        assertEquals(1, a.activeTokenCount(), "下一次登录清理其他过期 token")
    }

    @Test
    fun `管理登录复用统一认证门禁且冷却拒绝不泄露原因`() {
        var now = 0L
        val limits = AuthenticationOperation.entries.associateWith {
            AuthenticationOperationLimits(10, 10, 10)
        }.toMutableMap().apply {
            this[AuthenticationOperation.ADMIN] = AuthenticationOperationLimits(10, 10, 1)
        }
        val attempts = AuthenticationAttemptGuard(
            AuthenticationAttemptGuardConfig(
                windowNanos = 100,
                cooldownNanos = 200,
                globalAttempts = 100,
                maxConcurrentAttempts = 1,
                maxTrackedSources = 10,
                maxTrackedAccounts = 10,
                limits = limits,
            ),
            monotonicNanos = { now },
        )
        val auth = AdminAuthConfig(
            username = "admin",
            password = "test-only-password",
            authenticationAttempts = attempts,
        )
        val source = AuthenticationAttemptKeys.directSource("192.0.2.10")

        assertNull(auth.login("admin", "wrong", source))
        assertNull(auth.login("admin", "test-only-password", source))
        assertEquals(0, attempts.concurrentAttemptCount())

        now = 200
        assertTrue(auth.login("admin", "test-only-password", source)?.isNotBlank() == true)
        assertEquals(0, attempts.concurrentAttemptCount())
    }

    @Test
    fun `管理分页拒绝负页且深分页不会发生 Int 溢出`() {
        assertFailsWith<IllegalArgumentException> {
            parseAdminPageRequest(pageValue = "-1", sizeValue = "20")
        }

        val deepestDatabasePage = parseAdminPageRequest(
            pageValue = Int.MAX_VALUE.toString(),
            sizeValue = "100",
        )
        assertEquals(214_748_364_600L, deepestDatabasePage.offset)
        assertEquals(
            9_900,
            parseAdminPageRequest(
                pageValue = "100",
                sizeValue = "100",
                requireSearchOffset = true,
            ).searchOffset(),
        )
        assertFailsWith<IllegalArgumentException> {
            parseAdminPageRequest(
                pageValue = "101",
                sizeValue = "100",
                requireSearchOffset = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseAdminPageRequest(
                pageValue = Int.MAX_VALUE.toString(),
                sizeValue = "100",
                requireSearchOffset = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseAdminPageRequest(
                pageValue = "21474837",
                sizeValue = "100",
                requireSearchOffset = true,
            )
        }
    }

    @Test
    fun `telemetry 管理校验异常只映射固定公开错误`() {
        val internal = IllegalArgumentException("synthetic-internal-telemetry-detail")

        val response = publicTelemetryAdminBadRequest(internal)

        assertEquals(mapOf("error" to "invalid telemetry request"), response)
        assertTrue(response.values.none { it.contains("synthetic-internal-telemetry-detail") })
    }

    @Test
    fun `telemetry queue route parameters map every numeric range without text fallback`() {
        val query = Parameters.build {
            append("pendingCountMin", "1")
            append("pendingCountMax", "2")
            append("retryWaitCountMin", "3")
            append("terminalFailedCountMax", "4")
            append("oldestActiveAgeMillisMin", "5000")
            append("maxAttemptCountMax", "6")
        }.outgoingQueueQueryOrNull()

        assertEquals(
            TelemetryOutgoingQueueQuery(
                pendingCount = TelemetryNumericRange(1L, 2L),
                retryWaitCount = TelemetryNumericRange(minInclusive = 3L),
                terminalFailedCount = TelemetryNumericRange(maxInclusive = 4L),
                oldestActiveAgeMillis = TelemetryNumericRange(minInclusive = 5_000L),
                maxAttemptCount = TelemetryNumericRange(maxInclusive = 6L),
            ),
            query,
        )
        assertNull(Parameters.build { }.outgoingQueueQueryOrNull())
        assertFailsWith<IllegalArgumentException> {
            Parameters.build { append("pendingCountMin", "message-body") }
                .outgoingQueueQueryOrNull()
        }
    }
}

package com.virjar.tk.server.integration

import com.virjar.tk.server.api.AdminLoginRequest
import com.virjar.tk.server.api.AdminTokenResponse
import com.virjar.tk.server.api.adminRoutes
import com.virjar.tk.server.application.admin.AdminAuditFailureReason
import com.virjar.tk.server.application.admin.AdminBootstrap
import com.virjar.tk.server.application.admin.AdminPasswordRotationResult
import com.virjar.tk.server.application.admin.AdminSecurityService
import com.virjar.tk.server.application.admin.AdminSecurityStatus
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuard
import com.virjar.tk.server.infra.db.AdminSecurityAudits
import com.virjar.tk.server.infra.db.AdminSecurityCredentials
import com.virjar.tk.server.infra.db.repository.ExposedAdminSecurityStore
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real PostgreSQL, BCrypt and the published Ktor management routes share these credential/audit checks. */
class AdminSecurityIntegrationTest {
    companion object { @JvmField @RegisterExtension val ext = IntegrationTestExtension() }
    private val ctx get() = ext.env
    private val store get() = ExposedAdminSecurityStore(ctx.database)

    @BeforeEach
    fun clearOwnedAdminState() = transaction(ctx.database) {
        AdminSecurityAudits.deleteAll()
        AdminSecurityCredentials.deleteAll()
        Unit
    }

    private fun security(password: String = "bootstrap-password", recoveryId: String? = null) = AdminSecurityService(
        store, ctx.passwordHasher, AuthenticationAttemptGuard(), AdminBootstrap("test-admin", password, recoveryId),
    )

    private fun Application.management(auth: AdminSecurityService) {
        install(ContentNegotiation) { json() }
        install(StatusPages) { exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal error"))
        } }
        routing { adminRoutes(ctx.adminService, auth) }
    }

    private suspend fun ApplicationTestBuilder.login(password: String = "bootstrap-password"): String {
        val response = client.post("/api/admin/login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(AdminLoginRequest("test-admin", password)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return Json.decodeFromString<AdminTokenResponse>(response.bodyAsText()).token
    }

    @Test
    fun `sessions revoke separately or together and audit never contains submitted secrets`() = testApplication {
        val auth = security()
        application { management(auth) }
        val secret = "submitted-secret-must-not-be-audited"
        val rejected = client.post("/api/admin/login") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(AdminLoginRequest(secret, secret)))
        }
        assertEquals(HttpStatusCode.Unauthorized, rejected.status)
        assertEquals(AdminAuditFailureReason.INVALID_CREDENTIALS, store.audits(null, 1).single().failureReason)
        val first = login()
        val second = login()
        val status = client.get("/api/admin/security") { header(HttpHeaders.Authorization, "Bearer $first") }
        val sessions = Json.decodeFromString<AdminSecurityStatus>(status.bodyAsText()).sessions
        assertEquals(2, sessions.size)
        val other = sessions.single { !it.current }
        assertEquals(HttpStatusCode.OK, client.delete("/api/admin/security/sessions/${other.id}") {
            header(HttpHeaders.Authorization, "Bearer $first")
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/security") {
            header(HttpHeaders.Authorization, "Bearer $second")
        }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/admin/logout") { header(HttpHeaders.Authorization, "Bearer $first") }.status)
        assertNull(auth.principal(first))
        val third = login()
        val fourth = login()
        assertEquals(HttpStatusCode.OK, client.delete("/api/admin/security/sessions") {
            header(HttpHeaders.Authorization, "Bearer $third")
        }.status)
        assertNull(auth.principal(third))
        assertNull(auth.principal(fourth))
        val latest = store.audits(null, 3)
        val older = store.audits(latest.last().id, 3)
        assertTrue(older.all { it.id < latest.last().id })
        val records = store.audits(null, 100)
        assertTrue(records.any { it.action == "admin.sessions.revoke-all" && it.result == "SUCCESS" })
        val encoded = Json.encodeToString(records)
        for (sensitive in listOf(secret, first, second, third, fourth, "bootstrap-password")) assertFalse(encoded.contains(sensitive))
    }

    @Test
    fun `login throttling keeps the public rejection generic and the durable reason explicit`() = testApplication {
        val auth = security()
        application { management(auth) }
        val responses = List(9) {
            client.post("/api/admin/login") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(AdminLoginRequest("test-admin", "wrong-password")))
            }
        }
        assertTrue(responses.all { it.status == HttpStatusCode.Unauthorized })
        assertEquals(responses.first().bodyAsText(), responses.last().bodyAsText())
        val latest = store.audits(null, 1).single()
        assertEquals("unauthenticated", latest.actor)
        assertEquals(AdminAuditFailureReason.RATE_LIMITED, latest.failureReason)
        assertEquals(401, latest.httpStatus)
    }

    @Test
    fun `rotation survives service recreation and recovery id cannot later overwrite the new password`() = testApplication {
        val auth = security()
        application { management(auth) }
        val first = login()
        val second = login()
        suspend fun rotate(current: String, replacement: String) = client.post("/api/admin/security/password") {
            header(HttpHeaders.Authorization, "Bearer $first")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("currentPassword", current); put("newPassword", replacement) }.toString())
        }
        assertEquals(HttpStatusCode.Forbidden, rotate("incorrect", "replacement-password").status)
        assertEquals(AdminAuditFailureReason.INVALID_CREDENTIALS, store.audits(null, 1).single().failureReason)
        assertEquals(HttpStatusCode.BadRequest, rotate("bootstrap-password", "short").status)
        assertEquals(AdminAuditFailureReason.INVALID_REQUEST, store.audits(null, 1).single().failureReason)
        assertEquals(HttpStatusCode.OK, rotate("bootstrap-password", "replacement-password").status)
        assertNull(auth.principal(first))
        assertNull(auth.principal(second))
        val restarted = security("ignored-environment-password")
        assertNotNull(restarted.login("test-admin", "replacement-password"))
        assertNull(restarted.login("test-admin", "bootstrap-password"))
        val recoveryId = UUID.randomUUID().toString()
        val recovered = security("recovery-password", recoveryId)
        val recoveredToken = assertNotNull(recovered.login("test-admin", "recovery-password"))
        assertEquals(AdminPasswordRotationResult.ROTATED,
            recovered.rotatePassword(recoveredToken, "recovery-password", "after-recovery-password"))
        val sameRecovery = security("recovery-password", recoveryId)
        assertNotNull(sameRecovery.login("test-admin", "after-recovery-password"))
        assertNull(sameRecovery.login("test-admin", "recovery-password"))
        assertEquals(1, store.audits(null, 100).count { it.action == "admin.credentials.recover" })
        assertFalse(store.credential()!!.passwordHash.contains("after-recovery-password"))
    }

    @Test
    fun `legacy short and long bootstrap passwords remain usable without bcrypt truncation`() = runTest {
        val short = security("pw")
        assertNotNull(short.login("test-admin", "pw"))
        assertNotNull(security("ignored-password").login("test-admin", "pw"))
        val legacyLong = "legacy-" + "x".repeat(90)
        val recovered = security(legacyLong, UUID.randomUUID().toString())
        assertNotNull(recovered.login("test-admin", legacyLong))
        assertNull(recovered.login("test-admin", legacyLong + "different-tail"))
        assertTrue(store.credential()!!.passwordHash.startsWith("sha256:"))
        assertNotNull(security().login("test-admin", legacyLong))
    }

    @Test
    fun `audit persistence fences mutations and credential audit failure rolls back the password`() = testApplication {
        val auth = security()
        application { management(auth) }
        val token = login()
        val originalHash = store.credential()!!.passwordHash
        rejectAuditWrites("admin.credentials.rotate", updates = false)
        try {
            val failed = client.post("/api/admin/security/password") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"currentPassword":"bootstrap-password","newPassword":"replacement-password"}""")
            }
            assertEquals(HttpStatusCode.InternalServerError, failed.status)
            assertFalse(failed.bodyAsText().contains("TEST_SECRET_SENTINEL"))
            assertEquals(originalHash, store.credential()!!.passwordHash)
            assertEquals("test-admin", auth.principal(token))
            assertEquals(AdminAuditFailureReason.INTERNAL_ERROR, store.audits(null, 1).single().failureReason)
        } finally { removeAuditFailure() }

        val user = ctx.registerUser(uniqueUsername("admin-audit"))
        val before = ctx.userRepo.findByUid(user)!!.revision
        rejectAuditWrites("user.ban", updates = false)
        try {
            assertEquals(HttpStatusCode.InternalServerError, client.post("/api/admin/users/$user/ban") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.status)
            assertEquals(before, ctx.userRepo.findByUid(user)!!.revision, "Admission audit failure must precede the business mutation")
        } finally { removeAuditFailure() }
        rejectAuditWrites("user.ban", updates = true)
        try {
            assertEquals(HttpStatusCode.InternalServerError, client.post("/api/admin/users/$user/ban") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.status)
            assertEquals(before + 1, ctx.userRepo.findByUid(user)!!.revision)
            assertEquals("STARTED", store.audits(null, 1).single().result, "Failed completion leaves an honest unknown outcome")
        } finally { removeAuditFailure() }
        val invalid = client.post("/api/admin/users/$user/reset-password") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"password":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(AdminAuditFailureReason.INVALID_REQUEST, store.audits(null, 1).single().failureReason)
        assertFalse(Json.encodeToString(store.audits(null, 100)).contains("TEST_SECRET_SENTINEL"))
    }

    private fun rejectAuditWrites(action: String, updates: Boolean) = transaction(ctx.database) {
        exec("CREATE FUNCTION reject_admin_audit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''TEST_SECRET_SENTINEL''; END'")
        exec("CREATE TRIGGER reject_admin_audit BEFORE ${if (updates) "UPDATE" else "INSERT"} ON admin_security_audits " +
            "FOR EACH ROW WHEN (NEW.action = '$action') EXECUTE FUNCTION reject_admin_audit()")
    }

    private fun removeAuditFailure() = transaction(ctx.database) {
        exec("DROP TRIGGER reject_admin_audit ON admin_security_audits")
        exec("DROP FUNCTION reject_admin_audit()")
    }
}

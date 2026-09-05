package com.virjar.tk.server.integration

import com.virjar.tk.server.api.AdminAuthConfig
import com.virjar.tk.server.api.AdminTokenResponse
import com.virjar.tk.server.api.adminRoutes
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuard
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrganizationAdminHttpIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `removing the current leader is an actionable conflict instead of an internal error`() = testApplication {
        val leader = ctx.registerUser(uniqueUsername("org-admin-leader"))
        val root = ctx.organizationService.createUnit(null, "HTTP 组织根", null)
        val unit = ctx.organizationService.createUnit(root.unitId, "HTTP 研发部", leader)
        val adminAuth = AdminAuthConfig("test-admin", "test-admin-password")

        application {
            install(ContentNegotiation) { json() }
            routing {
                adminRoutes(
                    ctx.adminService,
                    AuthenticationAttemptGuard(),
                    auth = adminAuth,
                )
            }
        }

        val login = client.post("/api/admin/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"test-admin","password":"test-admin-password"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = Json.decodeFromString<AdminTokenResponse>(login.bodyAsText()).token
        val memberPath = "/api/admin/organization/units/${unit.unitId}/members/$leader"

        val conflict = client.delete(memberPath) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(
            "请先在编辑组织节点时变更部门负责人",
            Json.parseToJsonElement(conflict.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content,
        )
        assertEquals(leader, ctx.organizationService.listUnits().single { it.unitId == unit.unitId }.leaderUid)
        assertTrue(ctx.organizationService.listMembers(unit.unitId, recursive = false).any { it.uid == leader })

        ctx.organizationService.updateUnit(unit.unitId, root.unitId, unit.name, leaderUid = null, sortOrder = 0)
        val removed = client.delete(memberPath) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, removed.status)
        assertFalse(ctx.organizationService.listMembers(unit.unitId, recursive = false).any { it.uid == leader })
    }
}

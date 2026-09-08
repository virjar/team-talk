package com.virjar.tk.server.api

import com.virjar.tk.server.application.admin.AdminSecurityService
import com.virjar.tk.server.application.admin.AdminAuditFailureReason
import com.virjar.tk.server.application.admin.AdminPasswordRotationResult
import io.ktor.util.AttributeKey
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
internal data class AdminPasswordRotationRequest(val currentPassword: String, val newPassword: String)

internal fun Route.adminSecurityRoutes(auth: AdminSecurityService) {
    post("/logout") {
        auth.logout(call.adminBearerToken())
        call.respond(mapOf("ok" to true))
    }
    get("/security") {
        val status = auth.status(call.adminBearerToken())
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
        call.respond(status)
    }
    post("/security/password") {
        val request = call.receiveBoundedJsonOrRespond<AdminPasswordRotationRequest>() ?: return@post
        try {
            when (auth.rotatePassword(call.adminBearerToken(), request.currentPassword, request.newPassword)) {
                AdminPasswordRotationResult.ROTATED -> call.respond(mapOf("ok" to true))
                AdminPasswordRotationResult.UNAUTHENTICATED -> call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                AdminPasswordRotationResult.INVALID_CREDENTIALS -> {
                    call.attributes.put(ADMIN_AUDIT_FAILURE_KEY, AdminAuditFailureReason.INVALID_CREDENTIALS)
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "当前密码不正确"))
                }
                AdminPasswordRotationResult.RATE_LIMITED -> call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "请求过于频繁，请稍后重试"))
            }
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "新密码须与当前密码不同，至少 6 个字符且不超过 72 个 UTF-8 字节"))
        }
    }
    delete("/security/sessions/{sessionId}") {
        if (auth.revoke(call.adminBearerToken(), call.parameters["sessionId"]!!)) call.respond(mapOf("ok" to true))
        else call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
    }
    delete("/security/sessions") {
        if (auth.revokeAll(call.adminBearerToken())) call.respond(mapOf("ok" to true))
        else call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
    }
    get("/security/audits") {
        val query = call.request.queryParameters
        val before = query["beforeId"]?.toLongOrNull()
        val limit = query["limit"]?.toIntOrNull() ?: 50
        if ((query["beforeId"] != null && (before == null || before <= 0)) ||
            (query["limit"] != null && query["limit"]?.toIntOrNull() == null) || limit !in 1..100) {
            return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid audit pagination"))
        }
        call.respond(auth.audits(before, limit))
    }
}

internal val ADMIN_AUDIT_FAILURE_KEY = AttributeKey<AdminAuditFailureReason>("admin-audit-failure")

/** Only known mutation routes: no bodies, headers, query strings or arbitrary unmatched URLs enter audit. */
internal fun adminAuditAction(method: HttpMethod, path: String): String? = auditRoutes.firstOrNull {
    it.first == method && it.second.matches(path)
}?.third

private val auditRoutes = listOf(
    Triple(HttpMethod.Post, Regex("/api/admin/logout"), "admin.logout"),
    Triple(HttpMethod.Post, Regex("/api/admin/security/password"), "admin.credentials.rotate-request"),
    Triple(HttpMethod.Delete, Regex("/api/admin/security/sessions/[^/]+"), "admin.session.revoke"),
    Triple(HttpMethod.Delete, Regex("/api/admin/security/sessions"), "admin.sessions.revoke-all"),
    *listOf("ban", "unban", "kick-all", "reset-password", "document-custody-transfer").map {
        Triple(HttpMethod.Post, Regex("/api/admin/users/[^/]+/$it"), "user.$it")
    }.toTypedArray(),
    Triple(HttpMethod.Post, Regex("/api/admin/organization/units"), "organization.create"),
    Triple(HttpMethod.Put, Regex("/api/admin/organization/units/[^/]+"), "organization.update"),
    Triple(HttpMethod.Delete, Regex("/api/admin/organization/units/[^/]+"), "organization.archive"),
    Triple(HttpMethod.Post, Regex("/api/admin/organization/units/[^/]+/members"), "organization.member.assign"),
    Triple(HttpMethod.Delete, Regex("/api/admin/organization/units/[^/]+/members/[^/]+"), "organization.member.remove"),
    Triple(HttpMethod.Post, Regex("/api/admin/organization/units/[^/]+/group/enable"), "organization.group.enable"),
    Triple(HttpMethod.Post, Regex("/api/admin/organization/units/[^/]+/group/disable"), "organization.group.disable"),
    Triple(HttpMethod.Post, Regex("/api/admin/organization/reconcile"), "organization.reconcile"),
    Triple(HttpMethod.Post, Regex("/api/admin/bots"), "bot.create"),
    *listOf("rotate-token", "disable", "grants").map {
        Triple(HttpMethod.Post, Regex("/api/admin/bots/[^/]+/$it"), "bot.$it")
    }.toTypedArray(),
    Triple(HttpMethod.Delete, Regex("/api/admin/bots/[^/]+/grants/[^/]+"), "bot.grant.revoke"),
    Triple(HttpMethod.Post, Regex("/api/admin/messages/[^/]+/[^/]+/revoke"), "message.revoke"),
    *listOf("dissolve", "mute-all", "unmute-all").map {
        Triple(HttpMethod.Post, Regex("/api/admin/groups/[^/]+/$it"), "group.$it")
    }.toTypedArray(),
)

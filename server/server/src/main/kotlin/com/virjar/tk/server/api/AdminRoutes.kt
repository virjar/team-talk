package com.virjar.tk.server.api

import com.virjar.tk.server.application.admin.AdminSecurityService
import com.virjar.tk.server.application.admin.AdminService
import com.virjar.tk.server.application.admin.AdminPageRequest
import com.virjar.tk.server.application.admin.ClientTelemetryAdminService
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.document.DocumentCustodyPlanConflictException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException

/**
 * 管理后台 REST API（/api/admin 前缀）。
 *
 * 单实例管理员凭据由 PostgreSQL 保存；环境变量只作初始化或显式恢复输入。
 * POST /login 换随机 token（有界内存，12h 过期）→ 后续请求 Authorization: Bearer。
 * 部署仍应使用强密码，并通过网关/防火墙限制 /api/admin 来源。
 */
@Serializable
data class AdminLoginRequest(val username: String, val password: String)

@Serializable
data class AdminTokenResponse(val token: String, val expiresInSeconds: Long)

@Serializable
data class AdminMessageRequest(val password: String? = null)

@Serializable
data class DocumentCustodyTransferRequest(
    val operationId: String,
    val expectedPlanFingerprint: String,
    val targetOwnerPrincipalType: Int? = null,
    val targetOwnerPrincipalId: String? = null,
    val targetStewardUid: String? = null,
)

@Serializable
data class ExportSettingRequest(val enabled: Boolean)

@Serializable
data class CreateBotRequest(val name: String)

@Serializable
data class BotGrantRequest(val chatId: String)

internal fun Route.adminRoutes(
    adminService: AdminService,
    auth: AdminSecurityService,
    clientTelemetry: ClientTelemetryAdminService? = null,
    documentExport: com.virjar.tk.server.domain.document.DocumentSpaceExportService? = null,
    documentExportPolicy: com.virjar.tk.server.infra.db.AdminFeatureSettingsStore? = null,
) {
    route("/api/admin") {
        post("/login") {
            val req = call.receiveBoundedJsonOrRespond<AdminLoginRequest>() ?: return@post
            // request.local 是连接器的直接 socket 对端。TeamTalk 未安装任何转发头插件，
            // 并且有意在此边界不信任 X-Forwarded-For。
            val token = auth.login(req.username, req.password, call.request.local.remoteAddress)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid credentials"))
            call.respond(AdminTokenResponse(token, 12 * 3600))
        }

        installAdminAuthorization(auth)
        adminSecurityRoutes(auth)
        adminOrganizationRoutes(adminService)
        clientTelemetry?.let(::adminTelemetryRoutes)

        get("/overview") {
            call.respond(adminService.overview())
        }

        // ── 文档空间导出（超级管理员）与后台开关 ──
        get("/settings/document-export") {
            call.respond(mapOf("enabled" to requireNotNull(documentExportPolicy).isEnabled()))
        }
        put("/settings/document-export") {
            val req = call.receiveBoundedJsonOrRespond<ExportSettingRequest>() ?: return@put
            requireNotNull(documentExportPolicy).setEnabled(req.enabled, call.requireAdminPrincipal())
            call.respond(mapOf("enabled" to req.enabled))
        }
        get("/documents/spaces/{spaceId}/export") {
            val export = requireNotNull(documentExport) {
                "Document space export service is not wired into this deployment"
            }
            val spaceId = call.parameters["spaceId"] ?: return@get call.respond(
                HttpStatusCode.NotFound, mapOf("error" to "document space not found"),
            )
            val plan = export.buildAdminPlan(spaceId) ?: return@get call.respond(
                HttpStatusCode.NotFound, mapOf("error" to "document space not found"),
            )
            call.respondSpaceExportZip(plan) { p, out -> export.writeZip(p, out) }
        }

        // ── 用户 ──
        get("/users") {
            val query = call.request.queryParameters["query"]
            val pagination = call.adminPageRequestOrRespond() ?: return@get
            call.respond(adminService.listUsers(query, pagination))
        }
        get("/users/{uid}") {
            val uid = call.parameters["uid"]!!
            call.respond(adminService.userDetail(uid))
        }
        post("/users/{uid}/ban") {
            adminService.banUser(call.parameters["uid"]!!)
            call.respond(mapOf("ok" to true))
        }
        post("/users/{uid}/unban") {
            adminService.unbanUser(call.parameters["uid"]!!)
            call.respond(mapOf("ok" to true))
        }
        post("/users/{uid}/kick-all") {
            adminService.kickAll(call.parameters["uid"]!!)
            call.respond(mapOf("ok" to true))
        }
        post("/users/{uid}/reset-password") {
            val req = call.receiveBoundedJsonOrRespond<AdminMessageRequest>() ?: return@post
            try {
                adminService.resetPassword(call.parameters["uid"]!!, req.password ?: throw IllegalArgumentException("password required"))
                call.respond(mapOf("ok" to true))
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid password reset request"))
            }
        }
        get("/users/{uid}/document-custody-plan") {
            call.respondDocumentCustody {
                val query = call.request.queryParameters
                adminService.planDocumentCustody(
                    sourceUid = call.parameters["uid"] ?: throw IllegalArgumentException("uid required"),
                    targetOwnerPrincipalType = query["targetOwnerPrincipalType"]?.toIntOrNull()
                        ?: throw IllegalArgumentException("targetOwnerPrincipalType required"),
                    targetOwnerPrincipalId = query["targetOwnerPrincipalId"]
                        ?: throw IllegalArgumentException("targetOwnerPrincipalId required"),
                    targetStewardUid = query["targetStewardUid"]
                        ?: throw IllegalArgumentException("targetStewardUid required"),
                )
            }
        }
        post("/users/{uid}/document-custody-transfer") {
            val request = call.receiveBoundedJsonOrRespond<DocumentCustodyTransferRequest>() ?: return@post
            call.respondDocumentCustody {
                adminService.transferDocumentCustody(
                    adminPrincipal = call.requireAdminPrincipal(),
                    sourceUid = call.parameters["uid"] ?: throw IllegalArgumentException("uid required"),
                    operationId = request.operationId,
                    expectedPlanFingerprint = request.expectedPlanFingerprint,
                    targetOwnerPrincipalType = request.targetOwnerPrincipalType
                        ?: throw IllegalArgumentException("targetOwnerPrincipalType required"),
                    targetOwnerPrincipalId = request.targetOwnerPrincipalId
                        ?: throw IllegalArgumentException("targetOwnerPrincipalId required"),
                    targetStewardUid = request.targetStewardUid
                        ?: throw IllegalArgumentException("targetStewardUid required"),
                )
            }
        }

        // ── 通知机器人 ──
        get("/bots") {
            call.respond(adminService.listBots())
        }
        post("/bots") {
            val req = call.receiveBoundedJsonOrRespond<CreateBotRequest>() ?: return@post
            call.respond(adminService.createBot(req.name))
        }
        post("/bots/{botId}/rotate-token") {
            call.respond(adminService.rotateBotToken(call.parameters["botId"]!!))
        }
        post("/bots/{botId}/disable") {
            adminService.disableBot(call.parameters["botId"]!!)
            call.respond(mapOf("ok" to true))
        }
        post("/bots/{botId}/grants") {
            val req = call.receiveBoundedJsonOrRespond<BotGrantRequest>() ?: return@post
            call.respond(adminService.grantBot(call.parameters["botId"]!!, req.chatId))
        }
        delete("/bots/{botId}/grants/{chatId}") {
            call.respond(adminService.revokeBotGrant(call.parameters["botId"]!!, call.parameters["chatId"]!!))
        }

        // ── 消息 ──
        get("/messages") {
            val q = call.request.queryParameters
            val pagination = call.adminPageRequestOrRespond(requireSearchOffset = true) ?: return@get
            call.respond(adminService.searchMessages(
                keyword = q["keyword"]?.takeIf { it.isNotBlank() },
                chatId = q["chatId"],
                senderUid = q["senderUid"],
                start = q["start"]?.toLongOrNull(),
                end = q["end"]?.toLongOrNull(),
                pagination = pagination,
            ))
        }
        get("/messages/{chatId}/{seq}/context") {
            val chatId = call.parameters["chatId"]!!
            val seq = call.parameters["seq"]!!.toLongOrNull() ?: throw IllegalArgumentException("bad seq")
            val size = (call.request.queryParameters["size"] ?: "20").toIntOrNull()?.coerceIn(2, 60) ?: 20
            call.respond(adminService.messageContext(chatId, seq, size))
        }
        post("/messages/{chatId}/{seq}/revoke") {
            adminService.revokeMessage(call.parameters["chatId"]!!, call.parameters["seq"]!!.toLong())
            call.respond(mapOf("ok" to true))
        }

        // ── 日志 ──
        get("/logs/server") {
            call.respond(adminService.listServerLogs())
        }
        get("/logs/server/{name}") {
            val name = call.parameters["name"]!!
            val lines = (call.request.queryParameters["lines"] ?: "300").toIntOrNull() ?: 300
            try {
                call.respond(mapOf("lines" to adminService.readServerLog(name, lines)))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "bad request")))
            }
        }
        // ── 群 ──
        get("/groups") {
            val query = call.request.queryParameters["query"]
            val pagination = call.adminPageRequestOrRespond() ?: return@get
            call.respond(adminService.listGroups(query, pagination))
        }
        get("/groups/{chatId}") {
            call.respond(adminService.groupDetail(call.parameters["chatId"]!!))
        }
        post("/groups/{chatId}/dissolve") {
            adminService.dissolveGroup(call.parameters["chatId"]!!)
            call.respond(mapOf("ok" to true))
        }
        post("/groups/{chatId}/mute-all") {
            adminService.muteAllGroup(call.parameters["chatId"]!!)
            call.respond(mapOf("ok" to true))
        }
        post("/groups/{chatId}/unmute-all") {
            adminService.unmuteAllGroup(call.parameters["chatId"]!!)
            call.respond(mapOf("ok" to true))
        }
    }
}

/** 精确的规范化路径匹配使登录路由不受查询字符串和形似后缀的影响。 */
internal fun Route.installAdminAuthorization(auth: AdminSecurityService) {
    install(AdminAuthorizationPlugin) {
        this.auth = auth
    }
}

private class AdminAuthorizationConfig {
    lateinit var auth: AdminSecurityService
}

private val AdminAuthorizationPlugin = createRouteScopedPlugin(
    name = "TeamTalkAdminAuthorization",
    createConfiguration = ::AdminAuthorizationConfig,
) {
    val auth = pluginConfig.auth
    onCall { call ->
        if (call.request.path() == ADMIN_LOGIN_PATH) return@onCall
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
        val principal = auth.principal(token)
        if (principal == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
        } else {
            call.attributes.put(ADMIN_PRINCIPAL_KEY, principal)
            adminAuditAction(call.request.httpMethod, call.request.path())?.let { action ->
                // Persist admission before a sensitive handler can mutate business state.
                val target = call.request.path().removePrefix("/api/admin/").filterNot(Char::isISOControl).take(400)
                call.attributes.put(ADMIN_AUDIT_KEY, auth.beginAudit(principal, action, target))
            }
        }
    }
    onCallRespond { call, _ ->
        call.attributes.getOrNull(ADMIN_AUDIT_KEY)?.let { auditId ->
            call.attributes.remove(ADMIN_AUDIT_KEY)
            val status = call.response.status()?.value ?: 200
            val reason = call.attributes.getOrNull(ADMIN_AUDIT_FAILURE_KEY)
                ?: com.virjar.tk.server.application.admin.AdminAuditFailureReason.forStatus(status)
            auth.completeAudit(auditId, status, reason)
        }
    }
    on(CallFailed) { call, cause ->
        // Global StatusPages sends its response outside this route's response hooks. Record only
        // known handler failures here; cancellation and failed audit completion remain indeterminate.
        if (cause is CancellationException || call.response.isCommitted) return@on
        call.attributes.getOrNull(ADMIN_AUDIT_KEY)?.let { auditId ->
            call.attributes.remove(ADMIN_AUDIT_KEY)
            auth.completeAudit(auditId, 500)
        }
    }
}

private val ADMIN_AUDIT_KEY = AttributeKey<Long>("admin-audit")

internal fun ApplicationCall.adminBearerToken(): String =
    request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ") ?: error("Missing admin session")

internal fun ApplicationCall.requireAdminPrincipal(): String =
    attributes.getOrNull(ADMIN_PRINCIPAL_KEY)
        ?: error("Authenticated admin principal is missing")

private suspend inline fun <reified T : Any> ApplicationCall.respondDocumentCustody(
    block: suspend () -> T,
) {
    val result = try {
        block()
    } catch (_: DocumentCustodyPlanConflictException) {
        respond(HttpStatusCode.Conflict, mapOf("error" to "document custody plan changed"))
        return
    } catch (_: ReliableCommandConflictException) {
        respond(HttpStatusCode.Conflict, mapOf("error" to "document custody operation conflict"))
        return
    } catch (_: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid document custody request"))
        return
    }
    respond(result)
}

internal fun parseAdminPageRequest(
    pageValue: String?,
    sizeValue: String?,
    requireSearchOffset: Boolean = false,
): AdminPageRequest {
    val page = pageValue?.toIntOrNull()
        ?: if (pageValue == null) 1 else throw IllegalArgumentException("page must be an integer")
    val size = sizeValue?.toIntOrNull()
        ?: if (sizeValue == null) DEFAULT_ADMIN_PAGE_SIZE
        else throw IllegalArgumentException("size must be an integer")
    return AdminPageRequest(page, size).also { request ->
        if (requireSearchOffset) request.searchOffset()
    }
}

internal suspend fun ApplicationCall.adminPageRequestOrRespond(
    requireSearchOffset: Boolean = false,
): AdminPageRequest? = try {
    parseAdminPageRequest(
        pageValue = request.queryParameters["page"],
        sizeValue = request.queryParameters["size"],
        requireSearchOffset = requireSearchOffset,
    )
} catch (error: IllegalArgumentException) {
    respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid pagination")))
    null
}

private const val DEFAULT_ADMIN_PAGE_SIZE = 20
private const val ADMIN_LOGIN_PATH = "/api/admin/login"
private val ADMIN_PRINCIPAL_KEY = AttributeKey<String>("team-talk-admin-principal")

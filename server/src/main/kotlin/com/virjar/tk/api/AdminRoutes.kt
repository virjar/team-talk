package com.virjar.tk.api

import com.virjar.tk.application.admin.AdminService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 管理后台 REST API（/api/admin 前缀）。
 *
 * 鉴权模型（刻意极简）：固定账号密码（env ADMIN_USER/ADMIN_PASSWORD，默认 admin/admin-change-me），
 * POST /login 换随机 token（内存，12h 过期）→ 后续请求 Authorization: Bearer。
 * 生产建议：强密码 + nginx/防火墙将 /api/admin 限内网。
 */
@Serializable
data class AdminLoginRequest(val username: String, val password: String)

@Serializable
data class AdminTokenResponse(val token: String, val expiresInSeconds: Long)

@Serializable
data class AdminMessageRequest(val password: String? = null)

@Serializable
data class OrganizationUnitRequest(
    val parentId: String? = null,
    val name: String,
    val leaderUid: String? = null,
    val sortOrder: Int = 0,
    val enableGroup: Boolean = false,
)

@Serializable
data class OrganizationMemberRequest(
    val uid: String,
    val title: String? = null,
    val primary: Boolean = false,
)

@Serializable
data class CreateBotRequest(val name: String)

@Serializable
data class BotGrantRequest(val chatId: String)

/** 鉴权器（独立可单元测试）：固定凭据 + 内存 token（12h）。 */
internal class AdminAuthConfig {
    val username: String = System.getenv("ADMIN_USER") ?: "admin"
    val password: String = System.getenv("ADMIN_PASSWORD") ?: "admin-change-me"
    private val tokens = ConcurrentHashMap<String, Long>() // token -> expireAt
    private val random = SecureRandom()

    fun login(user: String, pass: String): String? {
        if (user != username || pass != password) return null
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        tokens[token] = System.currentTimeMillis() + TOKEN_TTL_MS
        return token
    }

    fun validate(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val expire = tokens[token] ?: return false
        if (System.currentTimeMillis() > expire) {
            tokens.remove(token); return false
        }
        return true
    }

    companion object {
        internal const val TOKEN_TTL_MS = 12 * 3600 * 1000L
    }
}

fun Route.adminRoutes(adminService: AdminService) {
    val auth = AdminAuthConfig()

    route("/api/admin") {
        post("/login") {
            val req = call.receive<AdminLoginRequest>()
            val token = auth.login(req.username, req.password)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid credentials"))
            call.respond(AdminTokenResponse(token, 12 * 3600))
        }

        // 统一 Bearer 拦截
        intercept(ApplicationCallPipeline.Call) {
            if (call.request.local.uri.endsWith("/api/admin/login")) return@intercept
            val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            if (!auth.validate(token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                finish()
            }
        }

        get("/overview") {
            call.respond(adminService.overview())
        }

        // ── 用户 ──
        get("/users") {
            val query = call.request.queryParameters["query"]
            val page = (call.request.queryParameters["page"] ?: "1").toIntOrNull() ?: 1
            val size = (call.request.queryParameters["size"] ?: "20").toIntOrNull()?.coerceIn(1, 100) ?: 20
            call.respond(adminService.listUsers(query, page, size))
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
            val req = call.receive<AdminMessageRequest>()
            adminService.resetPassword(call.parameters["uid"]!!, req.password ?: throw IllegalArgumentException("password required"))
            call.respond(mapOf("ok" to true))
        }

        // ── 组织架构 ──
        get("/organization/units") {
            call.respond(adminService.listOrganizationUnits())
        }
        post("/organization/units") {
            val req = call.receive<OrganizationUnitRequest>()
            call.respond(adminService.createOrganizationUnit(
                req.parentId, req.name, req.leaderUid, req.sortOrder, req.enableGroup,
            ))
        }
        put("/organization/units/{unitId}") {
            val req = call.receive<OrganizationUnitRequest>()
            call.respond(adminService.updateOrganizationUnit(
                call.parameters["unitId"]!!, req.parentId, req.name, req.leaderUid, req.sortOrder,
            ))
        }
        delete("/organization/units/{unitId}") {
            adminService.archiveOrganizationUnit(call.parameters["unitId"]!!)
            call.respond(mapOf("ok" to true))
        }
        get("/organization/units/{unitId}/members") {
            val recursive = call.request.queryParameters["recursive"]?.toBooleanStrictOrNull() ?: false
            call.respond(adminService.listOrganizationMembers(call.parameters["unitId"]!!, recursive))
        }
        post("/organization/units/{unitId}/members") {
            val req = call.receive<OrganizationMemberRequest>()
            call.respond(adminService.assignOrganizationMember(
                call.parameters["unitId"]!!, req.uid, req.title, req.primary,
            ))
        }
        delete("/organization/units/{unitId}/members/{uid}") {
            adminService.removeOrganizationMember(call.parameters["unitId"]!!, call.parameters["uid"]!!)
            call.respond(mapOf("ok" to true))
        }
        post("/organization/units/{unitId}/group/enable") {
            call.respond(adminService.enableDepartmentGroup(call.parameters["unitId"]!!))
        }
        post("/organization/units/{unitId}/group/disable") {
            call.respond(adminService.disableDepartmentGroup(call.parameters["unitId"]!!))
        }
        post("/organization/reconcile") {
            val failures = adminService.reconcileDepartmentGroups()
            call.respond(mapOf("ok" to failures.isEmpty(), "failedUnitIds" to failures))
        }

        // ── 通知机器人 ──
        get("/bots") {
            call.respond(adminService.listBots())
        }
        post("/bots") {
            call.respond(adminService.createBot(call.receive<CreateBotRequest>().name))
        }
        post("/bots/{botId}/rotate-token") {
            call.respond(adminService.rotateBotToken(call.parameters["botId"]!!))
        }
        post("/bots/{botId}/disable") {
            adminService.disableBot(call.parameters["botId"]!!)
            call.respond(mapOf("ok" to true))
        }
        post("/bots/{botId}/grants") {
            val req = call.receive<BotGrantRequest>()
            call.respond(adminService.grantBot(call.parameters["botId"]!!, req.chatId))
        }
        delete("/bots/{botId}/grants/{chatId}") {
            call.respond(adminService.revokeBotGrant(call.parameters["botId"]!!, call.parameters["chatId"]!!))
        }

        // ── 消息 ──
        get("/messages") {
            val q = call.request.queryParameters
            call.respond(adminService.searchMessages(
                keyword = q["keyword"]?.takeIf { it.isNotBlank() },
                chatId = q["chatId"],
                senderUid = q["senderUid"],
                start = q["start"]?.toLongOrNull(),
                end = q["end"]?.toLongOrNull(),
                page = (q["page"] ?: "1").toIntOrNull() ?: 1,
                size = (q["size"] ?: "20").toIntOrNull()?.coerceIn(1, 100) ?: 20,
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
        get("/logs/client") {
            call.respond(adminService.listClientLogDirs())
        }
        get("/logs/client/content") {
            val q = call.request.queryParameters
            try {
                call.respond(mapOf("lines" to adminService.readClientLog(
                    q["uid"] ?: throw IllegalArgumentException("uid required"),
                    q["deviceId"] ?: throw IllegalArgumentException("deviceId required"),
                    q["date"] ?: throw IllegalArgumentException("date required"),
                )))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "bad request")))
            }
        }

        // ── 群 ──
        get("/groups") {
            val query = call.request.queryParameters["query"]
            val page = (call.request.queryParameters["page"] ?: "1").toIntOrNull() ?: 1
            val size = (call.request.queryParameters["size"] ?: "20").toIntOrNull()?.coerceIn(1, 100) ?: 20
            call.respond(adminService.listGroups(query, page, size))
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

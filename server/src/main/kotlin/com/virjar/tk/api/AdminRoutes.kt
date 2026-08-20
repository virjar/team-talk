package com.virjar.tk.api

import com.virjar.tk.application.admin.AdminService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 管理后台 REST API（/api/admin 前缀）。
 *
 * 鉴权模型（刻意极简）：只有同时显式配置 ADMIN_USER/ADMIN_PASSWORD 才开放登录，
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

/** 鉴权器（独立可单元测试）：显式凭据 + 有界内存 token（12h）。 */
internal class AdminAuthConfig(
    username: String? = System.getenv("ADMIN_USER"),
    password: String? = System.getenv("ADMIN_PASSWORD"),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxActiveTokens: Int = DEFAULT_MAX_ACTIVE_TOKENS,
) {
    private val configuredUsername = username?.takeIf(String::isNotBlank)
    private val configuredPassword = password?.takeIf(String::isNotBlank)
    /** Insertion order is the revocation order when a valid administrator exceeds the cap. */
    private val tokens = LinkedHashMap<String, Long>() // token -> expireAt
    private val tokenLock = Any()
    private val random = SecureRandom()

    init {
        require(maxActiveTokens > 0) { "maxActiveTokens must be positive" }
    }

    fun login(user: String, pass: String): String? {
        val expectedUser = configuredUsername ?: return null
        val expectedPassword = configuredPassword ?: return null
        if (!constantTimeEquals(user, expectedUser) || !constantTimeEquals(pass, expectedPassword)) return null
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        synchronized(tokenLock) {
            val now = clock()
            removeExpired(now)
            while (tokens.size >= maxActiveTokens) {
                val oldest = tokens.entries.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
            tokens[token] = saturatedAdd(now, TOKEN_TTL_MS)
        }
        return token
    }

    fun validate(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return synchronized(tokenLock) {
            val expire = tokens[token] ?: return@synchronized false
            if (clock() >= expire) {
                tokens.remove(token)
                false
            } else {
                true
            }
        }
    }

    internal fun activeTokenCount(): Int = synchronized(tokenLock) { tokens.size }

    private fun removeExpired(now: Long) {
        val iterator = tokens.entries.iterator()
        while (iterator.hasNext()) {
            if (now >= iterator.next().value) iterator.remove()
        }
    }

    private fun constantTimeEquals(actual: String, expected: String): Boolean = MessageDigest.isEqual(
        actual.toByteArray(StandardCharsets.UTF_8),
        expected.toByteArray(StandardCharsets.UTF_8),
    )

    companion object {
        internal const val TOKEN_TTL_MS = 12 * 3600 * 1000L
        internal const val DEFAULT_MAX_ACTIVE_TOKENS = 256

        private fun saturatedAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
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

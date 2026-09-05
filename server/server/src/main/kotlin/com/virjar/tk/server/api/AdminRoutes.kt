package com.virjar.tk.server.api

import com.virjar.tk.server.application.admin.AdminService
import com.virjar.tk.server.application.admin.AdminPageRequest
import com.virjar.tk.server.application.admin.ClientTelemetryAdminService
import com.virjar.tk.server.domain.auth.AuthenticationAttempt
import com.virjar.tk.server.domain.auth.AuthenticationAttemptGuard
import com.virjar.tk.server.domain.auth.AuthenticationAttemptKeys
import com.virjar.tk.server.domain.auth.AuthenticationOperation
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.document.DocumentCustodyPlanConflictException
import com.virjar.tk.server.domain.organization.OrganizationMemberRemovalConflictException
import com.virjar.tk.server.domain.organization.OrganizationUnitArchiveConflictException
import com.virjar.tk.server.domain.telemetry.TelemetrySearchUnavailableException
import com.virjar.tk.server.domain.telemetry.TelemetryNumericRange
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueQuery
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
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
data class DocumentCustodyTransferRequest(
    val operationId: String,
    val expectedPlanFingerprint: String,
    val targetOwnerPrincipalType: Int? = null,
    val targetOwnerPrincipalId: String? = null,
    val targetStewardUid: String? = null,
)

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
internal data class OrganizationReconcileResponse(
    val ok: Boolean,
    val failedUnitIds: List<String>,
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
    private val authenticationAttempts: AuthenticationAttemptGuard = AuthenticationAttemptGuard(),
) {
    private val configuredUsername = username?.takeIf(String::isNotBlank)
    private val configuredPassword = password?.takeIf(String::isNotBlank)
    /** 插入顺序即有效管理员超过上限时的吊销顺序。 */
    private val tokens = LinkedHashMap<String, AdminTokenSession>()
    private val tokenLock = Any()
    private val random = SecureRandom()

    init {
        require(maxActiveTokens > 0) { "maxActiveTokens must be positive" }
    }

    fun login(
        user: String,
        pass: String,
        sourceKey: String = AuthenticationAttemptKeys.directSource("unattributed-admin-peer"),
    ): String? {
        val admission = authenticationAttempts.tryAcquire(
            AuthenticationAttempt(
                operation = AuthenticationOperation.ADMIN,
                sourceKey = sourceKey,
                accountKey = AuthenticationAttemptKeys.username("admin", user),
            ),
        ) ?: return null
        try {
            val expectedUser = configuredUsername ?: return null
            val expectedPassword = configuredPassword ?: return null
            if (!constantTimeEquals(user, expectedUser) || !constantTimeEquals(pass, expectedPassword)) return null
            val token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32).also { random.nextBytes(it) })
            synchronized(tokenLock) {
                val now = clock()
                removeExpired(now)
                while (tokens.size >= maxActiveTokens) {
                    val oldest = tokens.entries.iterator()
                    if (!oldest.hasNext()) break
                    oldest.next()
                    oldest.remove()
                }
                tokens[token] = AdminTokenSession(
                    principal = expectedUser,
                    expiresAt = saturatedAdd(now, TOKEN_TTL_MS),
                )
            }
            return token
        } finally {
            admission.close()
        }
    }

    /** 原子地返回通过校验的已配置管理员身份，同时校验 token 是否过期。 */
    fun principal(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return synchronized(tokenLock) {
            val session = tokens[token] ?: return@synchronized null
            if (clock() >= session.expiresAt) {
                tokens.remove(token)
                null
            } else {
                session.principal
            }
        }
    }

    internal fun activeTokenCount(): Int = synchronized(tokenLock) { tokens.size }

    private fun removeExpired(now: Long) {
        val iterator = tokens.entries.iterator()
        while (iterator.hasNext()) {
            if (now >= iterator.next().value.expiresAt) iterator.remove()
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

    private data class AdminTokenSession(
        val principal: String,
        val expiresAt: Long,
    )
}

internal fun Route.adminRoutes(
    adminService: AdminService,
    authenticationAttempts: AuthenticationAttemptGuard,
    clientTelemetry: ClientTelemetryAdminService? = null,
    auth: AdminAuthConfig = AdminAuthConfig(authenticationAttempts = authenticationAttempts),
) {
    route("/api/admin") {
        post("/login") {
            val req = call.receiveBoundedJsonOrRespond<AdminLoginRequest>() ?: return@post
            // request.local 是连接器的直接 socket 对端。TeamTalk 未安装任何转发头插件，
            // 并且有意在此边界不信任 X-Forwarded-For。
            val sourceKey = AuthenticationAttemptKeys.directSource(call.request.local.remoteAddress)
            val token = auth.login(req.username, req.password, sourceKey)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid credentials"))
            call.respond(AdminTokenResponse(token, 12 * 3600))
        }

        installAdminAuthorization(auth)

        get("/overview") {
            call.respond(adminService.overview())
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
            adminService.resetPassword(call.parameters["uid"]!!, req.password ?: throw IllegalArgumentException("password required"))
            call.respond(mapOf("ok" to true))
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

        // ── 组织架构 ──
        get("/organization/units") {
            call.respond(adminService.listOrganizationUnits())
        }
        post("/organization/units") {
            val req = call.receiveBoundedJsonOrRespond<OrganizationUnitRequest>() ?: return@post
            call.respond(adminService.createOrganizationUnit(
                req.parentId, req.name, req.leaderUid, req.sortOrder, req.enableGroup,
            ))
        }
        put("/organization/units/{unitId}") {
            val req = call.receiveBoundedJsonOrRespond<OrganizationUnitRequest>() ?: return@put
            call.respond(adminService.updateOrganizationUnit(
                call.parameters["unitId"]!!, req.parentId, req.name, req.leaderUid, req.sortOrder,
            ))
        }
        delete("/organization/units/{unitId}") {
            try {
                adminService.archiveOrganizationUnit(call.parameters["unitId"]!!)
                call.respond(mapOf("ok" to true))
            } catch (_: OrganizationUnitArchiveConflictException) {
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "organization unit still owns active document spaces"),
                )
            }
        }
        get("/organization/units/{unitId}/members") {
            val recursive = call.request.queryParameters["recursive"]?.toBooleanStrictOrNull() ?: false
            call.respond(adminService.listOrganizationMembers(call.parameters["unitId"]!!, recursive))
        }
        post("/organization/units/{unitId}/members") {
            val req = call.receiveBoundedJsonOrRespond<OrganizationMemberRequest>() ?: return@post
            call.respond(adminService.assignOrganizationMember(
                call.parameters["unitId"]!!, req.uid, req.title, req.primary,
            ))
        }
        delete("/organization/units/{unitId}/members/{uid}") {
            try {
                adminService.removeOrganizationMember(call.parameters["unitId"]!!, call.parameters["uid"]!!)
                call.respond(mapOf("ok" to true))
            } catch (_: OrganizationMemberRemovalConflictException) {
                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "请先在编辑组织节点时变更部门负责人"),
                )
            }
        }
        post("/organization/units/{unitId}/group/enable") {
            call.respond(adminService.enableDepartmentGroup(call.parameters["unitId"]!!))
        }
        post("/organization/units/{unitId}/group/disable") {
            call.respond(adminService.disableDepartmentGroup(call.parameters["unitId"]!!))
        }
        post("/organization/reconcile") {
            val failures = adminService.reconcileDepartmentGroups()
            call.respond(OrganizationReconcileResponse(failures.isEmpty(), failures))
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
        // ── 客户端遥测（PostgreSQL 控制面 + Lucene 七天可丢事实存储）──
        clientTelemetry?.let { telemetry ->
            get("/telemetry/events") {
                val q = call.request.queryParameters
                val pagination = call.adminPageRequestOrRespond(requireSearchOffset = true) ?: return@get
                call.respondTelemetry(
                    block = {
                        telemetry.searchEvents(
                            actor = call.requireAdminPrincipal(),
                            keyword = q["keyword"],
                            uid = q["uid"],
                            deviceId = q["deviceId"],
                            phone = q["phone"],
                            platform = q["platform"],
                            osName = q["osName"],
                            osVersion = q["osVersion"],
                            appVersion = q["appVersion"],
                            gitCommit = q["gitCommit"],
                            category = q["category"],
                            eventName = q["eventName"],
                            start = q.optionalLong("start"),
                            end = q.optionalLong("end"),
                            pagination = pagination,
                            outgoingQueue = q.outgoingQueueQueryOrNull(),
                        )
                    },
                )
            }
            get("/telemetry/events/{eventRecordId}/connection-traces") {
                val actor = call.requireAdminPrincipal()
                val eventRecordId = call.parameters["eventRecordId"]?.toLongOrNull()
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "invalid telemetry request"),
                    )
                val response = try {
                    telemetry.connectionTraces(eventRecordId, actor)
                } catch (_: TelemetrySearchUnavailableException) {
                    return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("error" to "telemetry search unavailable"),
                    )
                } catch (error: IllegalArgumentException) {
                    return@get call.respond(HttpStatusCode.BadRequest, publicTelemetryAdminBadRequest(error))
                }
                if (response == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "telemetry event not found"))
                } else {
                    call.respond(response)
                }
            }
            get("/telemetry/devices") {
                call.requireAdminPrincipal()
                val pagination = call.adminPageRequestOrRespond() ?: return@get
                val q = call.request.queryParameters
                call.respondTelemetry {
                    telemetry.pageDevices(q["query"], q["phone"], pagination)
                }
            }
            get("/telemetry/policies") {
                call.requireAdminPrincipal()
                val pagination = call.adminPageRequestOrRespond() ?: return@get
                call.respondTelemetry { telemetry.pagePolicies(pagination) }
            }
            post("/telemetry/policies") {
                val request = call.receiveBoundedJsonOrRespond<ClientTelemetryAdminService.EnablePolicyRequest>()
                    ?: return@post
                call.respondTelemetry { telemetry.enablePolicy(request, call.requireAdminPrincipal()) }
            }
            delete("/telemetry/policies/{policyId}") {
                try {
                    val policy = telemetry.disablePolicy(
                        call.parameters["policyId"] ?: throw IllegalArgumentException("policyId required"),
                        call.requireAdminPrincipal(),
                    ) ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "policy not found"))
                    call.respond(policy)
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, publicTelemetryAdminBadRequest(error))
                }
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

private fun Parameters.optionalLong(name: String): Long? {
    val raw = this[name] ?: return null
    return raw.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer timestamp")
}

internal fun Parameters.outgoingQueueQueryOrNull(): TelemetryOutgoingQueueQuery? {
    fun range(name: String): TelemetryNumericRange? {
        val minimum = optionalLong("${name}Min")
        val maximum = optionalLong("${name}Max")
        return if (minimum == null && maximum == null) null else TelemetryNumericRange(minimum, maximum)
    }
    val query = TelemetryOutgoingQueueQuery(
        pendingCount = range("pendingCount"),
        retryWaitCount = range("retryWaitCount"),
        terminalFailedCount = range("terminalFailedCount"),
        oldestActiveAgeMillis = range("oldestActiveAgeMillis"),
        maxAttemptCount = range("maxAttemptCount"),
    )
    return query.takeIf {
        it.pendingCount != null || it.retryWaitCount != null || it.terminalFailedCount != null ||
            it.oldestActiveAgeMillis != null || it.maxAttemptCount != null
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.respondTelemetry(block: suspend () -> T) {
    val response = try {
        block()
    } catch (error: TelemetrySearchUnavailableException) {
        respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "telemetry search unavailable"))
        return
    } catch (error: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, publicTelemetryAdminBadRequest(error))
        return
    }
    respond(response)
}

/** 遥测适配器可能把请求或存储细节附加到校验失败信息上；绝不能把这些细节回显给客户端。 */
internal fun publicTelemetryAdminBadRequest(
    @Suppress("UNUSED_PARAMETER") error: IllegalArgumentException,
): Map<String, String> = mapOf("error" to "invalid telemetry request")

/** 精确的规范化路径匹配使登录路由不受查询字符串和形似后缀的影响。 */
internal fun Route.installAdminAuthorization(auth: AdminAuthConfig) {
    install(AdminAuthorizationPlugin) {
        this.auth = auth
    }
}

private class AdminAuthorizationConfig {
    lateinit var auth: AdminAuthConfig
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
        }
    }
}

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

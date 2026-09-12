package com.virjar.tk.headless.agent

import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class AgentMcpOwner(val deployment: String, val datasetId: String, val uid: String)

internal data class AgentMcpGrant(
    val id: String,
    val tokenDigest: String,
    val owner: AgentMcpOwner,
    val tools: Set<String>,
    val chatIds: Set<String>,
    val allChats: Boolean,
    val createdAt: Long,
    val revokedAt: Long? = null,
) {
    override fun toString() = "AgentMcpGrant(id=$id, tokenDigest=<redacted>)"
    fun metadata() = buildJsonObject {
        put("id", id); put("tools", strings(tools)); put("chatIds", strings(chatIds)); put("allChats", allChats)
        put("createdAt", createdAt); put("revokedAt", revokedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("deployment", owner.deployment); put("datasetId", owner.datasetId); put("uid", owner.uid)
    }
}

internal sealed interface AgentApiPrincipal {
    data object Administrator : AgentApiPrincipal
    data class Scoped(val grant: AgentMcpGrant) : AgentApiPrincipal
}

internal data class AgentMcpCall(val requestId: String, val grantId: String, val tool: String, val chatId: String?, val waiting: Boolean)
internal class AgentMcpAccessException(val status: Int, val safeMessage: String) : IllegalArgumentException(safeMessage)

/** One local API boundary. Grants restrict an authenticated agent; they never grant server membership. */
internal class AgentMcpAccess(
    dataDir: File,
    private val owner: AgentMcpOwner,
    private val masterToken: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val files = AgentMcpFiles(AgentDataDirectoryPolicy.openRuntime(dataDir))
    private val audit = AgentMcpAudit(files)
    private val grants = load()
    private val limits = mutableMapOf<String, Window>()
    private var closed = false
    private var receiveWaiters = 0

    @Synchronized fun authenticate(header: String?): AgentApiPrincipal {
        requireOpen()
        if (isValidAgentAuthorization(header, masterToken)) return AgentApiPrincipal.Administrator
        val token = header?.takeIf { it.startsWith("Bearer ") && it.length in 39..135 }?.removePrefix("Bearer ")
            ?: denied(401, "invalid token")
        val digest = digest(token)
        val grant = grants.values.firstOrNull {
            MessageDigest.isEqual(it.tokenDigest.toByteArray(), digest.toByteArray()) && it.revokedAt == null && it.owner == owner
        } ?: denied(401, "invalid token")
        return AgentApiPrincipal.Scoped(grant)
    }

    @Synchronized fun describe(principal: AgentApiPrincipal): JsonObject {
        val grant = (principal as? AgentApiPrincipal.Scoped)?.grant ?: denied(403, "MCP requires a scoped token")
        requireCurrent(grant.id)
        return grant.metadata()
    }

    @Synchronized fun list(principal: AgentApiPrincipal): JsonObject {
        administrator(principal)
        return buildJsonObject { put("grants", JsonArray(grants.values.map(AgentMcpGrant::metadata))) }
    }

    @Synchronized fun create(principal: AgentApiPrincipal, fields: Map<String, String>): JsonObject {
        administrator(principal)
        val id = fields["id"]?.takeIf { ID.matches(it) } ?: denied(400, "invalid grant id")
        val token = fields["token"]?.takeIf { TOKEN.matches(it) } ?: denied(400, "invalid grant token")
        if (MessageDigest.isEqual(token.toByteArray(), masterToken.toByteArray())) denied(400, "grant token must be independent")
        val tools = csv(fields["tools"], 14)
        if (tools.isEmpty() || !TOOLS.containsAll(tools)) denied(400, "invalid grant tools")
        val chats = csv(fields["chatIds"], 128)
        chats.forEach(::requireAgentChatId)
        val all = when (fields["allChats"]) { "true" -> true; "false", null -> false; else -> denied(400, "invalid allChats") }
        if (all && chats.isNotEmpty()) denied(400, "allChats cannot include chatIds")
        if (!all && "chat_with" in tools) denied(400, "chat_with requires allChats")
        val candidate = AgentMcpGrant(id, digest(token), owner, tools, chats, all, clock())
        grants[id]?.let { existing ->
            if (existing.revokedAt != null || existing.copy(createdAt = candidate.createdAt) != candidate) denied(409, "grant id is already used")
            return buildJsonObject { put("grant", existing.metadata()) }
        }
        if (grants.size >= MAX_GRANTS) denied(409, "grant registry is full")
        if (grants.values.any { it.tokenDigest == candidate.tokenDigest }) denied(409, "grant token is already used")
        auditAdmission("administrator", "grant", null, id)
        persist(grants + (id to candidate))
        grants[id] = candidate
        auditResult("administrator", "grant", null, id, 200)
        return buildJsonObject { put("grant", candidate.metadata()) }
    }

    @Synchronized fun revoke(principal: AgentApiPrincipal, id: String): JsonObject {
        administrator(principal)
        val previous = grants[id] ?: denied(404, "grant not found")
        if (previous.revokedAt != null) return buildJsonObject { put("grant", previous.metadata()) }
        auditAdmission("administrator", "revoke_grant", null, id)
        val next = previous.copy(revokedAt = clock())
        persist(grants + (id to next)); grants[id] = next
        auditResult("administrator", "revoke_grant", null, id, 200)
        return buildJsonObject { put("grant", next.metadata()) }
    }

    @Synchronized fun auditTail(principal: AgentApiPrincipal, limit: Int): JsonObject {
        administrator(principal)
        if (limit !in 1..500) denied(400, "audit limit must be between 1 and 500")
        return buildJsonObject { put("records", JsonArray(audit.tail(limit))) }
    }

    /** Never hold this monitor while executing SDK work or waiting for messages. */
    @Synchronized fun begin(principal: AgentApiPrincipal, path: String, fields: Map<String, String>): AgentMcpCall? {
        requireOpen()
        if (principal == AgentApiPrincipal.Administrator) return null
        val grant = requireCurrent((principal as AgentApiPrincipal.Scoped).grant.id)
        val tool = ROUTES[path] ?: "unavailable"
        val requestId = UUID.randomUUID().toString()
        val chatId = fields["chatId"]?.let(::requireAgentChatId)
        val window = limits.getOrPut(grant.id) { Window(clock()) }
        val now = clock()
        if (now - window.start >= 60_000L || now < window.start) { window.start = now; window.requests = 0 }
        fun reject(code: Int, message: String): Nothing {
            auditResult(grant.id, tool, chatId, requestId, code)
            denied(code, message)
        }
        if (window.requests++ >= 120) reject(429, "MCP request rate exceeded")
        if (tool !in grant.tools) reject(403, "MCP tool is not allowed")
        if (tool in CHAT_TOOLS) {
            if (chatId == null && (tool !in OPTIONAL_CHAT_TOOLS || !grant.allChats)) reject(403, "MCP requires an allowed chatId")
            if (chatId != null && !grant.allChats && chatId !in grant.chatIds) reject(403, "MCP chat is not allowed")
        }
        if (tool == "chat_with" && !grant.allChats) reject(403, "MCP chat creation is not allowed")
        val waiting = tool == "recv"
        if (waiting && (window.waiters >= 2 || receiveWaiters >= 8)) reject(429, "MCP receive concurrency exceeded")
        auditAdmission(grant.id, tool, chatId, requestId)
        if (waiting) { window.waiters++; receiveWaiters++ }
        return AgentMcpCall(requestId, grant.id, tool, chatId, waiting)
    }

    @Synchronized fun recheck(call: AgentMcpCall?) {
        if (call != null) requireCurrent(call.grantId)
    }

    @Synchronized fun finish(call: AgentMcpCall?, status: Int, outgoingState: String? = null, responseStatus: Int = status) {
        if (call == null) return
        if (call.waiting) {
            limits[call.grantId]?.let { it.waiters = (it.waiters - 1).coerceAtLeast(0) }
            receiveWaiters = (receiveWaiters - 1).coerceAtLeast(0)
        }
        auditResult(call.grantId, call.tool, call.chatId, call.requestId, status, outgoingState, responseStatus)
    }

    fun allowsChat(principal: AgentApiPrincipal, chatId: String): Boolean = when (principal) {
        AgentApiPrincipal.Administrator -> true
        is AgentApiPrincipal.Scoped -> principal.grant.allChats || chatId in principal.grant.chatIds
    }

    @Synchronized override fun close() { closed = true }
    private fun requireOpen() { if (closed) denied(503, "agent is closing") }
    private fun administrator(principal: AgentApiPrincipal) {
        requireOpen(); if (principal != AgentApiPrincipal.Administrator) denied(403, "administrator token required")
    }
    private fun requireCurrent(id: String): AgentMcpGrant {
        requireOpen()
        return grants[id]?.takeIf { it.revokedAt == null && it.owner == owner } ?: denied(401, "invalid token")
    }
    private fun auditAdmission(actor: String, tool: String, chat: String?, requestId: String) {
        try { audit.append(record(actor, tool, chat, requestId, "admitted", null)) }
        catch (_: Exception) { denied(503, "MCP audit storage unavailable") }
    }
    private fun auditResult(actor: String, tool: String, chat: String?, requestId: String, code: Int, state: String? = null, responseStatus: Int = code) {
        // Admission is durable before effects. A late disk failure cannot turn a known successful enqueue into a retryable failure.
        runCatching { audit.append(record(actor, tool, chat, requestId, "result", code, state, responseStatus)) }
            .onFailure { com.virjar.tk.shared.log.PlatformOnlyTkLogger("AgentMcpAudit").fault("MCP audit result could not be persisted") }
    }
    private fun record(actor: String, tool: String, chat: String?, requestId: String, phase: String, code: Int?, state: String? = null, responseStatus: Int? = null) = buildJsonObject {
        put("timestamp", clock()); put("grantId", actor); put("tool", tool); put("requestId", requestId); put("phase", phase)
        put("deployment", owner.deployment); put("datasetId", owner.datasetId); put("uid", owner.uid)
        chat?.let { put("chatId", it) }; code?.let { put("status", it) }
        responseStatus?.takeIf { it != code }?.let { put("responseStatus", it) }
        state?.takeIf { it in setOf("queued", "sending", "sent", "failed") }?.let { put("outgoingState", it) }
    }

    private fun persist(values: Map<String, AgentMcpGrant>) {
        val bytes = buildJsonObject {
            put("format", 1)
            put("grants", JsonArray(values.values.map { grant -> JsonObject(grant.metadata() + ("tokenDigest" to JsonPrimitive(grant.tokenDigest))) }))
        }.toString().toByteArray()
        if (bytes.size > MAX_GRANT_FILE_BYTES) denied(409, "grant registry exceeds its storage budget")
        files.replace("mcp-grants.json", bytes)
    }

    private fun load(): LinkedHashMap<String, AgentMcpGrant> {
        val bytes = files.read("mcp-grants.json", MAX_GRANT_FILE_BYTES) ?: return linkedMapOf()
        val data = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(data["format"]?.jsonPrimitive?.int == 1) { "Unsupported MCP grants format" }
        val rows = data.getValue("grants").jsonArray
        require(rows.size <= MAX_GRANTS) { "MCP grant registry is too large" }
        val result = linkedMapOf<String, AgentMcpGrant>()
        for (row in rows) {
            val value = row.jsonObject
            fun text(key: String) = value.getValue(key).jsonPrimitive.content
            fun set(key: String) = value.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()
            val grant = AgentMcpGrant(text("id"), text("tokenDigest"), AgentMcpOwner(text("deployment"), text("datasetId"), text("uid")),
                set("tools"), set("chatIds"), value.getValue("allChats").jsonPrimitive.boolean,
                value.getValue("createdAt").jsonPrimitive.long, value["revokedAt"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long)
            require(ID.matches(grant.id) && grant.tokenDigest.matches(Regex("[0-9a-f]{64}")))
            require(grant.tools.isNotEmpty() && TOOLS.containsAll(grant.tools) && grant.chatIds.size <= 128)
            grant.chatIds.forEach(::requireAgentChatId)
            require(!grant.allChats || grant.chatIds.isEmpty())
            require(grant.allChats || "chat_with" !in grant.tools)
            require(result.put(grant.id, grant) == null) { "Duplicate MCP grant" }
        }
        return result
    }

    private class Window(var start: Long, var requests: Int = 0, var waiters: Int = 0)
    companion object {
        private const val MAX_GRANTS = 128
        private const val MAX_GRANT_FILE_BYTES = 1024 * 1024
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val TOKEN = Regex("[A-Za-z0-9_-]{32,128}")
        val ROUTES = mapOf("/v1/status" to "status", "/v1/conversations" to "conversations", "/v1/friends" to "friends",
            "/v1/send-text" to "send_text", "/v1/send-rich" to "send_markdown", "/v1/send-file" to "send_file",
            "/v1/outgoing" to "outgoing_status", "/v1/recv-wait" to "recv", "/v1/messages" to "messages",
            "/v1/history" to "history", "/v1/users-search" to "search_users", "/v1/chat-personal" to "chat_with",
            "/v1/mark-read" to "mark_read", "/v1/revoke" to "revoke")
        val TOOLS = ROUTES.values.toSet()
        private val OPTIONAL_CHAT_TOOLS = setOf("recv", "messages")
        private val CHAT_TOOLS = OPTIONAL_CHAT_TOOLS + setOf("send_text", "send_markdown", "send_file", "outgoing_status", "history", "mark_read", "revoke")
        private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun csv(value: String?, maximum: Int): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            val parts = value.split(',').map(String::trim)
            if (parts.size > maximum || parts.any(String::isBlank)) denied(400, "invalid grant list")
            return parts.toSortedSet()
        }
        private fun denied(code: Int, message: String): Nothing = throw AgentMcpAccessException(code, message)
    }
}

private fun strings(values: Set<String>) = JsonArray(values.sorted().map(::JsonPrimitive))

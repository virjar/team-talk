package com.virjar.tk.shared.agent

import com.sun.net.httpserver.HttpExchange
import com.virjar.tk.shared.client.BotDeliveryHistoryCursorExpiredException
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.OutgoingMessage
import com.virjar.tk.shared.client.OutgoingMessageConflictException
import com.virjar.tk.shared.client.OutgoingMessageState
import com.virjar.tk.shared.client.PendingBotMessage
import com.virjar.tk.protocol.body.markdownContentOrNull
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.net.URLDecoder

private val agentApiLogger = PlatformOnlyTkLogger("AgentApi")

/**
 * agent REST API（doc/05-clients/headless.md）。仅 loopback + Bearer apiToken。
 * 统一 `{ok, data|error}`；JSON 手工构建（body 多态结构不适合序列化器推断）。
 */
class AgentApi(private val agent: AgentRuntime) {

    private val json = Json { ignoreUnknownKeys = true }

    fun handle(ex: HttpExchange, path: String) {
        try {
            val auth = ex.requestHeaders.getFirst("Authorization")
            if (!isValidAgentAuthorization(auth, agent.apiToken)) {
                ex.resp(401, err("invalid token"))
                return
            }
            val body = readAgentRequestBody(
                input = ex.requestBody,
                declaredLength = ex.requestHeaders.getFirst("Content-Length"),
            )
            // 只读端点 GET/POST 双支持（读操作 GET 语义为主，POST 便于 curl 无 -G）
            val readonly = path == "/v1/conversations" || path == "/v1/friends" || path == "/v1/friend-pending"
            val resp: Pair<Int, JsonObject> = when {
                ex.requestMethod == "GET" && path == "/v1/status" -> status()
                ex.requestMethod == "GET" && path == "/v1/messages" -> messages(ex)
                ex.requestMethod == "GET" && path == "/v1/recv-wait" -> recvWait(ex)
                ex.requestMethod == "GET" && path == "/v1/outgoing" -> outgoing(ex)
                ex.requestMethod == "POST" -> post(path, body)
                readonly -> post(path, body)
                else -> 404 to err("unknown $path")
            }
            ex.resp(resp.first, resp.second)
        } catch (e: AgentRequestBodyException) {
            runCatching { ex.resp(e.status, err(e.safeMessage)) }
        } catch (_: AgentFileRequestException) {
            val response = agentFileRequestErrorResponse()
            runCatching { ex.resp(response.first, response.second) }
        } catch (e: OutgoingMessageConflictException) {
            runCatching { ex.resp(409, err(e.message ?: "clientMsgId conflict")) }
        } catch (_: BotDeliveryHistoryCursorExpiredException) {
            val response = agentDeliveryHistoryCursorExpiredResponse()
            runCatching { ex.resp(response.first, response.second) }
        } catch (_: Exception) {
            // 绝不回显任意的异常消息：它们可能包含路径、payload 或
            // transport 细节。bearer 调用方收到固定的边界错误；诊断保留为
            // 不含秘密异常文本的固定本地 fault。
            runCatching { agentApiLogger.fault("Agent API request failed at the internal boundary") }
            val response = agentInternalErrorResponse()
            runCatching { ex.resp(response.first, response.second) }
        }
    }

    private fun status(): Pair<Int, JsonObject> {
        val state = agent.connectionState
        return 200 to ok(buildJsonObject {
            put("connected", state == ConnectionState.AUTHENTICATED)
            put("state", state.name)
            put("uid", agent.bot.uid)
            put("username", agent.bot.username ?: "")
            put("bufferedMessages", agent.bufferedCount)
        })
    }

    private fun messages(ex: HttpExchange): Pair<Int, JsonObject> {
        val q = ex.query()
        if ("afterSeq" in q) return 400 to err("afterSeq is not supported; use afterEventId")
        val afterEventId = parseAgentCursor(q["afterEventId"], default = 0L)!!
        val limit = parseAgentBoundedInt(q["limit"], 50, 1..MAX_AGENT_MESSAGE_PAGE, "limit")
        val chatId = q["chatId"]?.takeIf { it.isNotBlank() }
        if (q["chatId"] != null && chatId == null) return 400 to err("chatId must not be blank")
        val list = agent.bufferedMessages(chatId, limit, afterEventId)
        return 200 to ok(buildJsonObject {
            put("messages", buildJsonArray { list.forEach { add(it.toJson()) } })
            put("nextEventId", list.lastOrNull()?.eventId ?: afterEventId)
        })
    }

    private fun recvWait(ex: HttpExchange): Pair<Int, JsonObject> {
        val q = ex.query()
        if ("afterSeq" in q) return 400 to err("afterSeq is not supported; use afterEventId")
        val afterEventId = parseAgentCursor(q["afterEventId"], default = null)
        val timeout = parseAgentBoundedInt(q["timeout"], 10, 1..MAX_AGENT_WAIT_SECONDS, "timeout")
        val chatId = q["chatId"]?.takeIf { it.isNotBlank() }
        if (q["chatId"] != null && chatId == null) return 400 to err("chatId must not be blank")
        val result = agent.waitMessage(afterEventId, chatId, timeout)
        val msg = result.delivery
        return 200 to ok(buildJsonObject {
            put("message", msg?.toJson() ?: kotlinx.serialization.json.JsonNull)
            put("nextEventId", result.nextEventId)
        })
    }

    private fun outgoing(ex: HttpExchange): Pair<Int, JsonObject> {
        val q = ex.query()
        val chatId = q["chatId"]?.let(::requireAgentChatId)
            ?: return 400 to err("chatId is required")
        val clientMsgId = q["clientMsgId"]?.let(::requireAgentClientMsgId)
            ?: return 400 to err("clientMsgId is required")
        val receipt = agent.outgoingReceipt(chatId, clientMsgId)
            ?: return 404 to err("outgoing receipt not found")
        return outgoingReceiptResponse(receipt)
    }

    private fun post(path: String, body: String): Pair<Int, JsonObject> = runBlocking {
        val r = parse(body)
        when (path) {
            "/v1/send-text" -> {
                val receipt = agent.bot.enqueueText(
                    requireAgentChatId(r.req("chatId")),
                    requireAgentClientMsgId(r.req("clientMsgId")),
                    r.req("text"),
                )
                outgoingReceiptResponse(receipt)
            }
            "/v1/send-rich" -> {
                val receipt = agent.bot.enqueueRichText(
                    requireAgentChatId(r.req("chatId")),
                    requireAgentClientMsgId(r.req("clientMsgId")),
                    r.req("markdown"),
                )
                outgoingReceiptResponse(receipt)
            }
            "/v1/send-file" -> {
                val receipt = agent.enqueueFile(
                    requireAgentChatId(r.req("chatId")),
                    requireAgentClientMsgId(r.req("clientMsgId")),
                    r.req("path"),
                )
                outgoingReceiptResponse(receipt)
            }
            "/v1/upload" -> withStagedUpload(r) { staged ->
                val attachment = agent.bot.uploadFile(
                    staged.source,
                    staged.originalFileName,
                    "application/octet-stream",
                )
                200 to ok(buildJsonObject {
                    put("path", attachment.path)
                    put("url", com.virjar.tk.shared.repository.FileOps.resolveUrl(agent.serverUrl, attachment))
                    put("name", attachment.name)
                    put("contentType", attachment.contentType)
                    put("size", attachment.size)
                })
            }
            "/v1/history" -> {
                val limit = parseAgentBoundedInt(
                    raw = r["limit"],
                    default = Message.MAX_QUERY_PAGE_SIZE,
                    range = 1..Message.MAX_QUERY_PAGE_SIZE,
                    label = "limit",
                )
                val list = agent.bot.getHistory(r.req("chatId"), r["fromSeq"]?.toLongOrNull() ?: 0L, limit)
                200 to ok(buildJsonObject {
                    put("messages", buildJsonArray {
                        list.forEach { m -> add(buildJsonObject {
                            put("seq", m.serverSeq); put("sender", m.senderUid)
                            put("text", m.body.markdownContentOrNull() ?: "")
                            put("ts", m.timestamp)
                        }) }
                    })
                })
            }
            "/v1/revoke" -> {
                agent.bot.revoke(r.req("chatId"), r.req("serverSeq").toLong())
                200 to ok(buildJsonObject { put("revoked", true) })
            }
            "/v1/forward" -> {
                agent.bot.forward(r.req("srcChatId"), r.req("srcSeq").toLong(), r.req("targetChatId"))
                200 to ok(buildJsonObject { put("forwarded", true) })
            }
            "/v1/mark-read" -> {
                agent.bot.markRead(r.req("chatId"), r.req("readSeq").toLong())
                200 to ok(buildJsonObject { put("read", true) })
            }
            "/v1/conversations" -> {
                val list = agent.bot.listConversations()
                200 to ok(buildJsonObject {
                    put("conversations", buildJsonArray {
                        list.forEach { c -> add(buildJsonObject {
                            put("chatId", c.chatId); put("name", c.chatName ?: "")
                            put("chatType", c.chatType); put("unread", c.unreadCount)
                            put("lastMsg", c.lastMessage ?: "")
                        }) }
                    })
                })
            }
            "/v1/friends" -> {
                val list = agent.bot.listFriends()
                200 to ok(buildJsonObject {
                    put("friends", buildJsonArray {
                        list.forEach { c -> add(buildJsonObject {
                            put("uid", c.friendUid)
                            put("name", c.user?.name ?: c.friendUid)
                            put("remark", c.remark ?: "")
                        }) }
                    })
                })
            }
            "/v1/friend-apply" -> {
                agent.bot.applyFriend(r.req("targetUid"), r["remark"])
                200 to ok(buildJsonObject { put("applied", true) })
            }
            "/v1/friend-accept" -> {
                agent.bot.acceptFriendApply(r.req("token"))
                200 to ok(buildJsonObject { put("accepted", true) })
            }
            "/v1/friend-pending" -> {
                val list = agent.bot.pendingApplies()
                200 to ok(buildJsonObject {
                    put("applies", buildJsonArray {
                        list.forEach { a -> add(buildJsonObject {
                            put("fromUid", a.fromUid)
                            put("name", a.fromUser?.name ?: a.fromUid)
                            put("token", a.token ?: "")
                        }) }
                    })
                })
            }
            "/v1/users-search" -> {
                val list = agent.bot.searchUsers(r.req("keyword"))
                200 to ok(buildJsonObject {
                    put("users", buildJsonArray {
                        list.forEach { u -> add(buildJsonObject {
                            put("uid", u.uid); put("username", u.username); put("name", u.name)
                        }) }
                    })
                })
            }
            "/v1/group-create" -> {
                val chat = agent.bot.createGroup(
                    name = r.req("name"),
                    memberUids = r.req("memberUids").split(",").filter { it.isNotBlank() },
                    operationId = r.req("operationId"),
                )
                200 to ok(buildJsonObject { put("chatId", chat.chatId) })
            }
            "/v1/group-members" -> {
                val list = agent.bot.groupMembers(r.req("chatId"))
                200 to ok(buildJsonObject {
                    put("members", buildJsonArray {
                        list.forEach { m -> add(buildJsonObject {
                            put("uid", m.uid); put("name", m.user?.name ?: m.uid); put("role", m.role)
                        }) }
                    })
                })
            }
            "/v1/group-invite" -> {
                agent.bot.inviteMembers(r.req("chatId"), r.req("uids").split(",").filter { it.isNotBlank() })
                200 to ok(buildJsonObject { put("invited", true) })
            }
            "/v1/chat-personal" -> {
                200 to ok(buildJsonObject { put("chatId", agent.bot.createPersonalChat(r.req("targetUid"))) })
            }
            else -> 404 to err("unknown $path")
        }
    }

    // ── 工具 ──
    private suspend fun withStagedUpload(
        request: Map<String, String>,
        block: suspend (AgentStagedUpload) -> Pair<Int, JsonObject>,
    ): Pair<Int, JsonObject> {
        val staged = runCatching {
            agent.fileAccessPolicy.stageUpload(request.req("path"))
        }.getOrElse {
            return 400 to err("file path is not allowed")
        }
        return preserveRemoteResultDuringCleanup(staged::close) { block(staged) }
    }

    private fun Map<String, String>.req(key: String): String =
        this[key] ?: throw AgentRequestBodyException(400, "missing field: $key")

    private fun PendingBotMessage.toJson() = pendingBotMessageJson(this)

    private fun parse(body: String): Map<String, String> =
        if (body.isBlank()) emptyMap()
        else runCatching {
            json.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrElse {
            throw AgentRequestBodyException(400, "invalid JSON request body")
        }

    private fun ok(data: JsonObject) = buildJsonObject { put("ok", true); put("data", data) }
    private fun err(msg: String) = buildJsonObject { put("ok", false); put("error", msg) }

    private fun HttpExchange.query(): Map<String, String> = parseAgentQuery(requestURI.rawQuery)

    private fun HttpExchange.resp(code: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray()
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

internal fun pendingBotMessageJson(delivery: PendingBotMessage): JsonObject = buildJsonObject {
    put("eventId", delivery.eventId)
    val message = delivery.message
    put("clientMsgId", message.clientMsgId)
    put("chatId", message.chatId)
    put("seq", message.serverSeq)
    put("sender", message.senderUid)
    put("messageType", message.messageType)
    put("flags", message.flags)
    put("edited", message.flags and Message.FLAG_EDITED != 0)
    put("revoked", message.flags and Message.FLAG_REVOKED != 0)
    put("forwarded", message.flags and Message.FLAG_FORWARDED != 0)
    put("text", message.body.markdownContentOrNull() ?: "")
    put("ts", message.timestamp)
}

private const val MAX_AGENT_MESSAGE_PAGE = 1000
internal const val MAX_AGENT_WAIT_SECONDS = 60

internal fun parseAgentCursor(raw: String?, default: Long?): Long? {
    if (raw == null) return default
    return raw.toLongOrNull()?.takeIf { it >= 0L }
        ?: throw AgentRequestBodyException(400, "invalid afterEventId")
}

internal fun parseAgentBoundedInt(raw: String?, default: Int, range: IntRange, label: String): Int {
    val value = raw?.toIntOrNull()
        ?: if (raw == null) default else throw AgentRequestBodyException(400, "invalid $label")
    if (value !in range) {
        throw AgentRequestBodyException(400, "$label must be between ${range.first} and ${range.last}")
    }
    return value
}

/** 只解码一次 URI 原始 query；解码后的 `URI.query` 会破坏字面上的 `%xx` ID。 */
internal fun parseAgentQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery == null) return emptyMap()
    if (rawQuery.length > MAX_AGENT_QUERY_CHARS) {
        throw AgentRequestBodyException(414, "query is too large")
    }
    val fields = rawQuery.split("&")
    if (fields.size > MAX_AGENT_QUERY_FIELDS) {
        throw AgentRequestBodyException(414, "query has too many fields")
    }
    return try {
        fields.mapNotNull { field ->
            val separator = field.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            URLDecoder.decode(field.substring(0, separator), "UTF-8") to
                URLDecoder.decode(field.substring(separator + 1), "UTF-8")
        }.toMap()
    } catch (_: IllegalArgumentException) {
        throw AgentRequestBodyException(400, "invalid query encoding")
    }
}

internal fun isValidAgentAuthorization(header: String?, apiToken: String): Boolean {
    if (
        apiToken.isBlank() || header == null ||
        header.length != BEARER_PREFIX.length + apiToken.length ||
        !header.startsWith(BEARER_PREFIX)
    ) return false
    val candidate = header.substring(BEARER_PREFIX.length)
    return MessageDigest.isEqual(candidate.toByteArray(), apiToken.toByteArray())
}

private const val BEARER_PREFIX = "Bearer "
internal const val MAX_AGENT_REQUEST_BODY_BYTES = 64 * 1024
internal const val MAX_AGENT_QUERY_CHARS = 8 * 1024
internal const val MAX_AGENT_QUERY_FIELDS = 32

internal class AgentRequestBodyException(
    val status: Int,
    val safeMessage: String,
) : IllegalArgumentException(safeMessage)

/** Content-Length 只是预检；流式计数仍然是权威的上限。 */
internal fun readAgentRequestBody(input: InputStream, declaredLength: String?): String {
    val expected = declaredLength?.let { raw ->
        raw.toLongOrNull()?.takeIf { it >= 0L }
            ?: throw AgentRequestBodyException(400, "invalid request body length")
    }
    if (expected != null && expected > MAX_AGENT_REQUEST_BODY_BYTES) {
        throw AgentRequestBodyException(413, "request body is too large")
    }
    val initialCapacity = minOf(expected ?: 0L, MAX_AGENT_REQUEST_BODY_BYTES.toLong()).toInt()
    return input.use { stream ->
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (total > MAX_AGENT_REQUEST_BODY_BYTES - read) {
                throw AgentRequestBodyException(413, "request body is too large")
            }
            output.write(buffer, 0, read)
            total += read
        }
        output.toByteArray().toString(Charsets.UTF_8)
    }
}

/**
 * 一旦 [block] 返回，远程上传/发送的结果就是权威的。本地暂存清理属于
 * 尽力而为，既不能把成功变成可重试的 500，也不能掩盖远程失败。
 */
internal suspend fun <T> preserveRemoteResultDuringCleanup(
    cleanup: () -> Unit,
    block: suspend () -> T,
): T = try {
    block()
} finally {
    runCatching(cleanup).onFailure {
        // 绝不记录暂存路径或异常消息：两者都可能包含调用方输入。
        runCatching {
            agentApiLogger.fault(
                "Private upload staging cleanup failed; operator cleanup may be required",
            )
        }
    }
}

internal fun outgoingReceiptResponse(receipt: OutgoingMessage): Pair<Int, JsonObject> =
    200 to buildJsonObject {
        put("ok", true)
        put("data", outgoingReceiptJson(receipt))
    }

internal fun agentFileRequestErrorResponse(): Pair<Int, JsonObject> =
    400 to buildJsonObject {
        put("ok", false)
        put("error", "file path is not allowed")
    }

/** 游标是一条已退场的续接，而不是空页面或内部服务器故障。 */
internal fun agentDeliveryHistoryCursorExpiredResponse(): Pair<Int, JsonObject> =
    410 to buildJsonObject {
        put("ok", false)
        put("error", "delivery history cursor expired; restart with afterEventId=0")
    }

internal fun agentInternalErrorResponse(): Pair<Int, JsonObject> =
    500 to buildJsonObject {
        put("ok", false)
        put("error", "internal error")
    }

internal fun outgoingReceiptJson(receipt: OutgoingMessage): JsonObject = buildJsonObject {
    put("clientMsgId", receipt.message.clientMsgId)
    put("chatId", receipt.message.chatId)
    put("state", when (receipt.state) {
        OutgoingMessageState.PENDING,
        OutgoingMessageState.RETRY_WAIT -> "queued"
        OutgoingMessageState.IN_FLIGHT -> "sending"
        OutgoingMessageState.TERMINAL_FAILED -> "failed"
        OutgoingMessageState.SUCCESS -> "sent"
    })
    put("attemptCount", receipt.attemptCount)
    put("nextAttemptAt", receipt.nextAttemptAt)
    put("createdAt", receipt.createdAt)
    put("updatedAt", receipt.updatedAt)
    put("serverSeq", receipt.serverSeq ?: 0L)
    put("terminalCode", receipt.terminalCode ?: 0)
    put("failureCode", receipt.failureCode?.apiCode ?: "")
    put("failureMessage", receipt.failureCode?.publicMessage ?: "")
    put("completedAt", receipt.completedAt ?: 0L)
}

internal fun requireAgentClientMsgId(value: String): String {
    if (
        value.isBlank() || value.any(Char::isISOControl) ||
        value.encodeToByteArray().size > MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH
    ) {
        throw AgentRequestBodyException(400, "invalid clientMsgId")
    }
    return value
}

internal fun requireAgentChatId(value: String): String {
    if (
        value.isBlank() || value.any(Char::isISOControl) ||
        value.encodeToByteArray().size > MessageBodyPolicy.MAX_CHAT_ID_LENGTH
    ) {
        throw AgentRequestBodyException(400, "invalid chatId")
    }
    return value
}

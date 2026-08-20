package com.virjar.tk.agent

import com.sun.net.httpserver.HttpExchange
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.body.markdownContentOrNull
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.util.PlatformOnlyTkLogger
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
                ex.requestMethod == "POST" -> post(path, body)
                readonly -> post(path, body)
                else -> 404 to err("unknown $path")
            }
            ex.resp(resp.first, resp.second)
        } catch (e: AgentRequestBodyException) {
            runCatching { ex.resp(e.status, err(e.safeMessage)) }
        } catch (e: Exception) {
            runCatching { ex.resp(500, err(e.message ?: e::class.simpleName ?: "error")) }
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
        val list = agent.bufferedMessages(q["chatId"], q["limit"]?.toIntOrNull() ?: 50, q["afterSeq"]?.toLongOrNull() ?: 0L)
        return 200 to ok(buildJsonObject {
            put("messages", buildJsonArray { list.forEach { add(it.toJson()) } })
        })
    }

    private fun recvWait(ex: HttpExchange): Pair<Int, JsonObject> {
        val q = ex.query()
        val msg = agent.waitMessage(q["chatId"], q["timeout"]?.toIntOrNull() ?: 10)
        return 200 to ok(buildJsonObject {
            put("message", msg?.toJson() ?: kotlinx.serialization.json.JsonNull)
        })
    }

    private fun post(path: String, body: String): Pair<Int, JsonObject> = runBlocking {
        val r = parse(body)
        when (path) {
            "/v1/send-text" -> {
                val ack = agent.bot.sendText(r.req("chatId"), r.req("text"))
                agentAckResponse(ack)
            }
            "/v1/send-rich" -> {
                val ack = agent.bot.sendRichText(r.req("chatId"), r.req("markdown"))
                agentAckResponse(ack)
            }
            "/v1/send-file" -> {
                withStagedUpload(r) { staged ->
                    val ack = agent.bot.uploadAndSendFile(
                        r.req("chatId"),
                        staged.source,
                        staged.originalFileName,
                        "application/octet-stream",
                    )
                    agentAckResponse(ack)
                }
            }
            "/v1/upload" -> withStagedUpload(r) { staged ->
                val attachment = agent.bot.uploadFile(
                    staged.source,
                    staged.originalFileName,
                    "application/octet-stream",
                )
                200 to ok(buildJsonObject {
                    put("path", attachment.path)
                    put("url", com.virjar.tk.repository.FileOps.resolveUrl(agent.serverUrl, attachment))
                    put("name", attachment.name)
                    put("contentType", attachment.contentType)
                    put("size", attachment.size)
                })
            }
            "/v1/history" -> {
                val list = agent.bot.getHistory(r.req("chatId"), r["fromSeq"]?.toLongOrNull() ?: 0L, r["limit"]?.toIntOrNull() ?: 10)
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
                val chat = agent.bot.createGroup(r.req("name"), r.req("memberUids").split(",").filter { it.isNotBlank() })
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
        this[key] ?: throw IllegalArgumentException("missing field: $key")

    private fun com.virjar.tk.model.Message.toJson() = buildJsonObject {
        put("chatId", chatId); put("seq", serverSeq); put("sender", senderUid)
        put("text", body.markdownContentOrNull() ?: ""); put("ts", timestamp)
    }

    private fun parse(body: String): Map<String, String> =
        if (body.isBlank()) emptyMap()
        else runCatching {
            json.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())

    private fun ok(data: JsonObject) = buildJsonObject { put("ok", true); put("data", data) }
    private fun err(msg: String) = buildJsonObject { put("ok", false); put("error", msg) }

    private fun HttpExchange.query(): Map<String, String> {
        val q = requestURI.query ?: return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('='); if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
    }

    private fun HttpExchange.resp(code: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray()
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

internal fun isValidAgentAuthorization(header: String?, apiToken: String): Boolean {
    if (apiToken.isBlank() || header == null || !header.startsWith(BEARER_PREFIX)) return false
    val candidate = header.removePrefix(BEARER_PREFIX)
    return MessageDigest.isEqual(candidate.toByteArray(), apiToken.toByteArray())
}

private const val BEARER_PREFIX = "Bearer "
internal const val MAX_AGENT_REQUEST_BODY_BYTES = 64 * 1024

internal class AgentRequestBodyException(
    val status: Int,
    val safeMessage: String,
) : IllegalArgumentException(safeMessage)

/** Content-Length is only a preflight; streaming accounting remains the authoritative limit. */
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
 * A remote upload/send result is authoritative once [block] returns. Local staging cleanup is
 * best-effort and must neither turn success into a retryable 500 nor mask a remote failure.
 */
internal suspend fun <T> preserveRemoteResultDuringCleanup(
    cleanup: () -> Unit,
    block: suspend () -> T,
): T = try {
    block()
} finally {
    runCatching(cleanup).onFailure {
        // Never log the staging path or the exception message: either may contain caller input.
        runCatching {
            agentApiLogger.fault(
                "Private upload staging cleanup failed; operator cleanup may be required",
            )
        }
    }
}

/** 服务端拒绝、内部错误和 ACK 超时都不能被本地 Agent 伪装成成功。 */
internal fun agentAckResponse(ack: MessageAckPayload): Pair<Int, JsonObject> {
    if (ack.code == 0) {
        return 200 to buildJsonObject {
            put("ok", true)
            put("data", buildJsonObject {
                put("code", ack.code)
                put("serverSeq", ack.serverSeq)
                put("reason", ack.reason ?: "")
            })
        }
    }

    val status = if (ack.code in 400..499) ack.code else 502
    return status to buildJsonObject {
        put("ok", false)
        put("error", ack.reason?.takeIf { it.isNotBlank() } ?: "消息发送失败（ACK ${ack.code}）")
    }
}

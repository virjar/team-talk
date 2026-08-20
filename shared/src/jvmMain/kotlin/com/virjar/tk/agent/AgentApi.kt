package com.virjar.tk.agent

import com.sun.net.httpserver.HttpExchange
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.body.markdownContentOrNull
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.repository.asUploadSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.io.File

/**
 * agent REST API（doc/05-clients/headless.md）。仅 127.0.0.1 + Bearer apiToken。
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
            val body = ex.bodyString()
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
            put("username", agent.bot.userSession.username ?: "")
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
                val file = File(r.req("path"))
                if (!file.exists()) {
                    404 to err("file not found: ${file.path}")
                } else {
                    val ack = agent.bot.uploadAndSendFile(
                        r.req("chatId"),
                        file.asUploadSource(),
                        file.name,
                        "application/octet-stream",
                    )
                    agentAckResponse(ack)
                }
            }
            "/v1/upload" -> withFile(r) { f ->
                val attachment = agent.bot.uploadFile(
                    f.asUploadSource(),
                    f.name,
                    "application/octet-stream",
                )
                buildJsonObject {
                    put("path", attachment.path)
                    put("url", com.virjar.tk.repository.FileOps.resolveUrl(agent.serverUrl, attachment))
                    put("name", attachment.name)
                    put("contentType", attachment.contentType)
                    put("size", attachment.size)
                }
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
    private inline fun withFile(r: Map<String, String>, block: (File) -> JsonObject): Pair<Int, JsonObject> {
        val f = File(r.req("path"))
        return if (!f.exists()) 404 to err("file not found: ${f.path}")
        else 200 to ok(block(f))
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

    private fun HttpExchange.bodyString() = requestBody.bufferedReader().use { it.readText() }
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

internal fun isValidAgentAuthorization(header: String?, apiToken: String): Boolean =
    apiToken.isNotBlank() && header == "Bearer $apiToken"

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

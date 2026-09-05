package com.virjar.tk.server.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * CLI 对等账号操作器（doc/05-clients/headless.md）。
 *
 * 与 [TestPeer] 的区别：TestPeer 每用例内嵌 ImClient 建连；CliPeer 经
 * 常驻 tt-agent 的 REST 操作——**测试路径=产品路径**（CLI/agent 的 bug 在测试期暴露），
 * 无每用例 TCP 连接开销。用法同 TestPeer（-Dpeer.* / -Dtk.e2e.*），
 * 额外 -Dcli.api=127.0.0.1:8600 -Dcli.token=xxx。
 */
class CliPeer(
    private val api: String = System.getProperty("cli.api") ?: "127.0.0.1:8600",
    private val token: String = System.getProperty("cli.token")
        ?: File(System.getProperty("cli.tokenFile") ?: "${System.getProperty("user.home")}/.tt-cli")
            .takeIf { it.exists() }?.readText()?.trim()
        ?: error("需要 -Dcli.token 或 ~/.tt-cli"),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var recvCursor = 0L

    fun status(): Map<String, String> {
        val d = get("/v1/status").jsonObject
        return mapOf(
            "connected" to d["connected"]!!.jsonPrimitive.content,
            "uid" to d["uid"]!!.jsonPrimitive.content,
            "username" to d["username"]!!.jsonPrimitive.content,
        )
    }

    fun sendText(
        chatId: String,
        text: String,
        clientMsgId: String = UUID.randomUUID().toString(),
    ): Pair<Int, Long> {
        var receipt = post(
            "/v1/send-text",
            mapOf("chatId" to chatId, "clientMsgId" to clientMsgId, "text" to text),
        )
        val deadline = System.currentTimeMillis() + 30_000L
        while (true) {
            when (receipt["state"]?.jsonPrimitive?.content) {
                "sent" -> return 0 to receipt["serverSeq"]!!.jsonPrimitive.content.toLong()
                "failed" -> {
                    val code = receipt["terminalCode"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it != 0 } ?: -1
                    return code to (receipt["serverSeq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
                }
            }
            check(System.currentTimeMillis() < deadline) {
                "agent outgoing receipt did not reach a terminal state: chatId=$chatId clientMsgId=$clientMsgId"
            }
            Thread.sleep(50L)
            receipt = get(
                "/v1/outgoing?chatId=${queryValue(chatId)}&clientMsgId=${queryValue(clientMsgId)}",
            )
        }
    }

    fun searchUsers(keyword: String): List<Map<String, String>> =
        post("/v1/users-search", mapOf("keyword" to keyword))["users"]!!.jsonArray.map {
            val o = it.jsonObject
            mapOf("uid" to o["uid"]!!.jsonPrimitive.content, "name" to o["name"]!!.jsonPrimitive.content)
        }

    fun chatWith(targetUid: String): String =
        post("/v1/chat-personal", mapOf("targetUid" to targetUid))["chatId"]!!.jsonPrimitive.content

    /** 等待（长轮询）一条指定发送者的消息。 */
    fun recvFrom(senderUid: String, timeoutSec: Int = 10): Map<String, String>? {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val d = runCatching {
                get("/v1/recv-wait?timeout=3&afterEventId=$recvCursor")
            }.getOrNull() ?: continue
            recvCursor = maxOf(
                recvCursor,
                d["nextEventId"]?.jsonPrimitive?.content?.toLongOrNull() ?: recvCursor,
            )
            val m = d["message"] as? kotlinx.serialization.json.JsonObject ?: continue
            if (m["sender"]?.jsonPrimitive?.content == senderUid) {
                return mapOf(
                    "chatId" to m["chatId"]!!.jsonPrimitive.content,
                    "seq" to m["seq"]!!.jsonPrimitive.content,
                    "text" to (m["text"]?.jsonPrimitive?.content ?: ""),
                )
            }
        }
        return null
    }

    fun friendPendingTokens(): List<String> =
        runCatching {
            get("/v1/friend-pending")["applies"]?.jsonArray?.mapNotNull {
                it.jsonObject["token"]?.jsonPrimitive?.content?.takeIf { t -> t.isNotBlank() }
            } ?: emptyList()
        }.getOrDefault(emptyList())

    fun friendAccept(token: String) { post("/v1/friend-accept", mapOf("token" to token)) }

    // ── HTTP 基元 ──
    private fun get(path: String) = raw("GET", path, null)
    private fun post(path: String, fields: Map<String, String>) = raw("POST", path, fields)

    private fun raw(method: String, path: String, fields: Map<String, String>?): kotlinx.serialization.json.JsonObject {
        val conn = (URL("http://$api$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            fields?.let {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { o -> o.write(jsonBody(it).toByteArray()) }
            }
        }
        val text = try {
            (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        } finally {
            conn.disconnect()
        }
        val obj = json.parseToJsonElement(text).jsonObject
        check(obj["ok"]?.jsonPrimitive?.content == "true") { "agent API error: ${obj["error"]}" }
        return obj["data"]?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
    }

    private fun jsonBody(fields: Map<String, String>): String = buildJsonObject {
        fields.forEach { (key, value) -> put(key, value) }
    }.toString()

    private fun queryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

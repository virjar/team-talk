package com.virjar.tk.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * tt-cli：无状态薄客户端（doc/11-cli-agent 二期）。
 * 所有命令经本地 REST 转发给常驻 tt-agent；`--json` 输出机器可读（e2e 断言用）。
 *
 * 配置：~/.tt-cli（内容为 agent api token）；或 --token / TT_TOKEN env；--api 默认 127.0.0.1:8601
 *
 * 用法示例：
 * ```
 * tt status
 * tt send <chatId> <text...>
 * tt send-file <chatId> <path>
 * tt history <chatId> [--limit n] [--after seq]
 * tt recv [--chatId id] [--wait 10]
 * tt conversations / friends / friend-pending
 * tt user-search <keyword>        tt chat-with <uid>
 * tt friend-add <uid> [remark]    tt friend-accept <token>
 * tt group-create <name> <uid,...>  tt group-members <chatId>
 * tt group-invite <chatId> <uid,...>
 * tt revoke <chatId> <seq>        tt forward <srcChatId> <seq> <targetChatId>
 * tt mark-read <chatId> [seq]     tt upload <path>
 * ```
 */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "help" || args[0] == "--help") {
        println(USAGE)
        return
    }
    val jsonOut = args.contains("--json")
    val rest = args.filter { it != "--json" }
    val cmd = rest.first()
    val positional = rest.drop(1).filter { !it.startsWith("--") }
    val flags = rest.drop(1).filter { it.startsWith("--") }.map { it.removePrefix("--") }
    val flagValues = mutableMapOf<String, String>()
    var i = 1
    while (i < rest.size) {
        if (rest[i].startsWith("--") && i + 1 < rest.size && !rest[i + 1].startsWith("--")) {
            flagValues[rest[i].removePrefix("--")] = rest[i + 1]; i += 2
        } else i++
    }

    val api = flagValues["api"] ?: System.getenv("TT_API") ?: "127.0.0.1:8601"
    val token = flagValues["token"] ?: System.getenv("TT_TOKEN")
        ?: File(System.getenv("TT_CLI_CONFIG") ?: "${System.getProperty("user.home")}/.tt-cli")
            .takeIf { it.exists() }?.readText()?.trim()

    if (token == null) {
        System.err.println("缺少 token：--token / TT_TOKEN / ~/.tt-cli（写入 agent 打印的 api-token）")
        kotlin.system.exitProcess(2)
    }
    val cli = Cli(api, token)

    try {
        val out: String = when (cmd) {
            "status" -> cli.get("/v1/status").pretty(jsonOut)
            "conversations" -> cli.get("/v1/conversations").pretty(jsonOut)
            "friends" -> cli.get("/v1/friends").pretty(jsonOut)
            "friend-pending" -> cli.get("/v1/friend-pending").pretty(jsonOut)
            "messages" -> cli.get("/v1/messages?limit=${flagValues["limit"] ?: 20}").pretty(jsonOut)
            "recv" -> {
                val q = buildString {
                    append("/v1/recv-wait?timeout=${flagValues["wait"] ?: 10}")
                    flagValues["chatId"]?.let { append("&chatId=").append(enc(it)) }
                }
                cli.get(q).pretty(jsonOut)
            }
            "send" -> cli.post("/v1/send-text", mapOf("chatId" to pos(positional, 0), "text" to positional.drop(1).joinToString(" ")))
            "send-rich" -> cli.post("/v1/send-rich", mapOf("chatId" to pos(positional, 0), "markdown" to positional.drop(1).joinToString(" ")))
            "send-file" -> cli.post("/v1/send-file", mapOf("chatId" to pos(positional, 0), "path" to abs(pos(positional, 1))))
            "upload" -> cli.post("/v1/upload", mapOf("path" to abs(pos(positional, 0))))
            "history" -> cli.post("/v1/history", mapOf(
                "chatId" to pos(positional, 0),
                "fromSeq" to (flagValues["after"] ?: "0"),
                "limit" to (flagValues["limit"] ?: "20"),
            ))
            "revoke" -> cli.post("/v1/revoke", mapOf("chatId" to pos(positional, 0), "serverSeq" to pos(positional, 1)))
            "forward" -> cli.post("/v1/forward", mapOf("srcChatId" to pos(positional, 0), "srcSeq" to pos(positional, 1), "targetChatId" to pos(positional, 2)))
            "mark-read" -> cli.post("/v1/mark-read", mapOf("chatId" to pos(positional, 0), "readSeq" to (positional.getOrNull(1) ?: "99999999")))
            "user-search" -> cli.post("/v1/users-search", mapOf("keyword" to pos(positional, 0)))
            "chat-with" -> cli.post("/v1/chat-personal", mapOf("targetUid" to pos(positional, 0)))
            "friend-add" -> cli.post("/v1/friend-apply", mapOf("targetUid" to pos(positional, 0), "remark" to positional.getOrNull(1)))
            "friend-accept" -> cli.post("/v1/friend-accept", mapOf("token" to pos(positional, 0)))
            "group-create" -> cli.post("/v1/group-create", mapOf("name" to pos(positional, 0), "memberUids" to positional.drop(1).joinToString(",")))
            "group-members" -> cli.post("/v1/group-members", mapOf("chatId" to pos(positional, 0)))
            "group-invite" -> cli.post("/v1/group-invite", mapOf("chatId" to pos(positional, 0), "uids" to positional.drop(1).joinToString(",")))
            else -> {
                System.err.println("未知命令: $cmd\n$USAGE")
                kotlin.system.exitProcess(2)
            }
        }
        println(out)
    } catch (e: CliException) {
        System.err.println("错误: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}

class CliException(msg: String) : Exception(msg)

class Cli(private val api: String, private val token: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(path: String): String = raw("GET", path, null)
    fun post(path: String, fields: Map<String, String?>): String = raw("POST", path, buildJsonObject(fields))

    fun raw(method: String, path: String, body: String?): String {
        val conn = (URL("http://$api$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            body?.let {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { o -> o.write(it.toByteArray()) }
            }
        }
        val text = try {
            (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        } finally {
            conn.disconnect()
        }
        val obj = json.parseToJsonElement(text).jsonObject
        if (obj["ok"]?.jsonPrimitive?.content != "true") {
            throw CliException(obj["error"]?.jsonPrimitive?.content ?: "HTTP ${conn.responseCode}")
        }
        return obj["data"]?.toString() ?: "{}"
    }

    /** 构造 JSON body（null 值字段跳过；字符串手工转义足够——值不含控制字符）。 */
    private fun buildJsonObject(fields: Map<String, String?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in fields) {
            if (v == null) continue
            if (!first) sb.append(",")
            first = false
            sb.append("\"$k\":\"").append(v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append("\"")
        }
        sb.append("}")
        return sb.toString()
    }
}

/** --json 直出；默认人类可读（常见结构表格化）。 */
private fun String.pretty(jsonOut: Boolean): String {
    if (jsonOut) return this
    return runCatching { humanizeJson(Json.parseToJsonElement(this).jsonObject) }.getOrDefault(this)
}

private fun humanizeJson(obj: JsonObject): String = buildString {
    obj.forEach { (key, value) ->
        when {
            value is kotlinx.serialization.json.JsonArray -> {
                appendLine("$key:")
                value.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    appendLine("  " + o.entries.joinToString("  ") { "${it.key}=${it.value.jsonPrimitive.content.take(40)}" })
                }
            }
            else -> appendLine("$key=${value.jsonPrimitive.content}")
        }
    }
}

private fun pos(args: List<String>, i: Int): String = args.getOrNull(i) ?: run {
    System.err.println("缺少参数（用法见 tt help）"); kotlin.system.exitProcess(2); ""
}

private fun abs(p: String): String = File(p).absolutePath
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

private val USAGE = """
tt — TeamTalk CLI（经本地 tt-agent，doc/11-cli-agent 二期）

命令：
  status                              连接状态/uid
  send <chatId> <text...>             发文本
  send-rich <chatId> <markdown...>    发富文本
  send-file <chatId> <path>           发文件
  upload <path>                       仅上传（返回 url）
  history <chatId> [--after n] [--limit n]
  recv [--chatId id] [--wait s]       等新消息（长轮询）
  messages [--limit n]                环形缓冲最近消息
  revoke <chatId> <seq>               撤回
  forward <srcChatId> <seq> <target>  转发
  mark-read <chatId> [seq]            已读
  conversations | friends | friend-pending
  user-search <keyword>
  chat-with <uid>                     建私聊（返回 chatId）
  friend-add <uid> [remark] | friend-accept <token>
  group-create <name> <uid...> | group-members <chatId> | group-invite <chatId> <uid...>

选项：--json 机器可读 | --token <t> | --api <host:port> | --wait/--limit/--after
配置：TT_TOKEN / TT_API 环境变量，或 ~/.tt-cli（内容=agent token）
""".trim()

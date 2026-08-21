package com.virjar.tk.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * tt-cli：无状态薄客户端（doc/05-clients/headless.md）。
 * 所有命令经本地 REST 转发给常驻 tt-agent；`--json` 输出机器可读（e2e 断言用）。
 *
 * 配置：~/.tt-cli（内容为 agent api token）；或 --token / TT_TOKEN env；--api 默认 127.0.0.1:8600
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
    val parsed = try {
        parseCliArguments(args)
    } catch (error: IllegalArgumentException) {
        System.err.println("参数错误: ${error.message}")
        kotlin.system.exitProcess(2)
    }
    val jsonOut = parsed.jsonOutput
    val cmd = parsed.command
    val positional = parsed.positional
    val flagValues = parsed.flagValues

    val durableClientMsgId = try {
        resolveDurableClientMsgId(cmd, flagValues["clientMsgId"])
    } catch (error: IllegalArgumentException) {
        System.err.println("参数错误: ${error.message}")
        kotlin.system.exitProcess(2)
    }
    // Emit before reading credentials or opening the HTTP connection. Even a local I/O failure,
    // transport failure, truncated response or invalid JSON leaves a stable id for safe retry.
    durableClientMsgId?.let { System.err.println(durableSendRecoveryNotice(it)) }

    val api = flagValues["api"] ?: System.getenv("TT_API") ?: "127.0.0.1:8600"
    val token = (flagValues["token"] ?: System.getenv("TT_TOKEN")?.takeIf { it.isNotBlank() })
        ?: File(System.getenv("TT_CLI_CONFIG")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.tt-cli")
            .takeIf { it.exists() }?.readText()?.trim()

    if (token == null) {
        System.err.println("缺少 token：--token / TT_TOKEN / ~/.tt-cli（从 agent 私有凭据显式配置）")
        kotlin.system.exitProcess(2)
    }
    val cli = Cli(api, token)

    try {
        val out: String = when (cmd) {
            "status" -> cli.get("/v1/status").pretty(jsonOut)
            "conversations" -> cli.get("/v1/conversations").pretty(jsonOut)
            "friends" -> cli.get("/v1/friends").pretty(jsonOut)
            "friend-pending" -> cli.get("/v1/friend-pending").pretty(jsonOut)
            "messages" -> {
                val q = buildString {
                    append("/v1/messages?limit=${flagValues["limit"] ?: 20}")
                    flagValues["chatId"]?.let { append("&chatId=").append(enc(it)) }
                    flagValues["afterEventId"]?.let { append("&afterEventId=").append(it) }
                }
                cli.get(q).pretty(jsonOut)
            }
            "recv" -> {
                val q = buildString {
                    append("/v1/recv-wait?timeout=${flagValues["wait"] ?: 10}")
                    flagValues["chatId"]?.let { append("&chatId=").append(enc(it)) }
                    flagValues["afterEventId"]?.let { append("&afterEventId=").append(it) }
                }
                cli.get(q).pretty(jsonOut)
            }
            "send" -> cli.post("/v1/send-text", mapOf(
                "chatId" to pos(positional, 0),
                "clientMsgId" to durableClientMsgId,
                "text" to positional.drop(1).joinToString(" "),
            ))
            "send-rich" -> cli.post("/v1/send-rich", mapOf(
                "chatId" to pos(positional, 0),
                "clientMsgId" to durableClientMsgId,
                "markdown" to positional.drop(1).joinToString(" "),
            ))
            "send-file" -> cli.post("/v1/send-file", mapOf(
                "chatId" to pos(positional, 0),
                "clientMsgId" to durableClientMsgId,
                "path" to abs(pos(positional, 1)),
            ))
            "outgoing-status" -> cli.get(
                "/v1/outgoing?chatId=${enc(pos(positional, 0))}" +
                    "&clientMsgId=${enc(pos(positional, 1))}",
            ).pretty(jsonOut)
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
        val retryHint = durableClientMsgId?.let { "; clientMsgId=$it（重试时复用）" }.orEmpty()
        System.err.println("错误: ${e.message}$retryHint")
        kotlin.system.exitProcess(1)
    }
}

class CliException(msg: String) : Exception(msg)

internal data class ParsedCliArguments(
    val command: String,
    val positional: List<String>,
    val flagValues: Map<String, String>,
    val jsonOutput: Boolean,
)

/**
 * Consumes each option together with its value so credentials and API addresses can never leak
 * into a command's positional payload. `--` explicitly ends option parsing for text beginning
 * with two dashes.
 */
internal fun parseCliArguments(args: Array<String>): ParsedCliArguments {
    require(args.isNotEmpty()) { "缺少命令" }
    val positional = mutableListOf<String>()
    val flagValues = linkedMapOf<String, String>()
    var jsonOutput = false
    var optionsEnded = false
    var index = 1
    while (index < args.size) {
        val argument = args[index]
        when {
            optionsEnded -> {
                positional += argument
                index += 1
            }
            argument == "--" -> {
                optionsEnded = true
                index += 1
            }
            argument == "--json" -> {
                jsonOutput = true
                index += 1
            }
            argument.startsWith("--") -> {
                val name = argument.removePrefix("--")
                require(name in CLI_VALUE_FLAGS) { "未知选项: --$name" }
                require(index + 1 < args.size && !args[index + 1].startsWith("--")) {
                    "选项 --$name 缺少值"
                }
                flagValues[name] = args[index + 1]
                index += 2
            }
            else -> {
                positional += argument
                index += 1
            }
        }
    }
    return ParsedCliArguments(args.first(), positional, flagValues, jsonOutput)
}

class Cli(private val api: String, private val token: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(path: String): String = raw("GET", path, null)
    fun post(path: String, fields: Map<String, String?>): String = raw("POST", path, buildCliJsonObject(fields))

    fun raw(method: String, path: String, body: String?): String {
        val conn = (URL("http://$api$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = CLI_HTTP_READ_TIMEOUT_MILLIS
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

}

/** Standards-compliant JSON encoding covers every control character and deliberately skips null. */
internal fun buildCliJsonObject(fields: Map<String, String?>): String = buildJsonObject {
    fields.forEach { (key, value) -> if (value != null) put(key, value) }
}.toString()

internal const val CLI_HTTP_READ_TIMEOUT_MILLIS = 75_000

private val CLI_VALUE_FLAGS = setOf(
    "api",
    "token",
    "limit",
    "chatId",
    "afterEventId",
    "wait",
    "after",
    "clientMsgId",
)

private val DURABLE_SEND_COMMANDS = setOf("send", "send-rich", "send-file")

internal fun resolveDurableClientMsgId(
    command: String,
    explicit: String?,
    generate: () -> String = { UUID.randomUUID().toString() },
): String? = if (command in DURABLE_SEND_COMMANDS) {
    requireAgentClientMsgId(explicit ?: generate())
} else {
    null
}

internal fun durableSendRecoveryNotice(clientMsgId: String): String =
    "clientMsgId=$clientMsgId（响应丢失或重试时请复用）"

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
tt — TeamTalk CLI（经本地 tt-agent，见 doc/05-clients/headless.md）

命令：
  status                              连接状态/uid
  send <chatId> <text...>             发文本
  send-rich <chatId> <markdown...>    发富文本
  send-file <chatId> <path>           发文件
  outgoing-status <chatId> <clientMsgId>
                                      查询持久发送回执
  upload <path>                       仅上传（返回 url）
  history <chatId> [--after n] [--limit n]
  recv [--chatId id] [--wait s] [--afterEventId n]
                                      按全局事件游标等新消息
  messages [--chatId id] [--limit n] [--afterEventId n]
                                      按全局事件游标读取持久消息
  revoke <chatId> <seq>               撤回
  forward <srcChatId> <seq> <target>  转发
  mark-read <chatId> [seq]            已读
  conversations | friends | friend-pending
  user-search <keyword>
  chat-with <uid>                     建私聊（返回 chatId）
  friend-add <uid> [remark] | friend-accept <token>
  group-create <name> <uid...> | group-members <chatId> | group-invite <chatId> <uid...>

发送选项：--clientMsgId <id>（省略时生成；失败重试必须复用输出的 ID）
通用选项：--json | --token <t> | --api <host:port> | --wait/--limit/--after/--afterEventId
配置：TT_TOKEN / TT_API 环境变量，或 ~/.tt-cli（内容=agent token）
""".trim()

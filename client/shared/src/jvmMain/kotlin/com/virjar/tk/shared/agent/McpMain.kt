package com.virjar.tk.shared.agent

import com.virjar.tk.protocol.model.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.URLEncoder

/**
 * tt-mcp：MCP server（doc/05-clients/headless.md）。
 * stdio JSON-RPC 2.0（MCP 传输层），工具面 = agent REST 的收发核心子集。
 * 大模型（Claude 等）配置 stdio command 后即可作为 IM 用户协作。
 *
 * 用法：tt-mcp [--api host:port] [--token t]（同 tt-cli 配置链）
 * MCP 客户端（如 Claude Desktop）配置：
 * ```
 * {"mcpServers":{"teamtalk":{"command":"/opt/tt-agent/bin/tt-mcp"}}}
 * ```
 */
fun main(args: Array<String>) {
    val opts = AgentCli.parse(args)
    val api = try {
        AgentClientIoPolicy.endpoint(
            opts["api"] ?: System.getenv("TT_API")?.takeIf { it.isNotBlank() }
                ?: "127.0.0.1:8600",
        ).display
    } catch (error: CliException) {
        System.err.println("配置错误: ${error.message}")
        kotlin.system.exitProcess(2)
    }
    val token = try {
        opts["token"] ?: System.getenv("TT_TOKEN")?.takeIf { it.isNotBlank() }
            ?: AgentClientIoPolicy.readTokenFile(
                File(System.getenv("TT_CLI_CONFIG") ?: "${System.getProperty("user.home")}/.tt-cli"),
            )
            ?: run { System.err.println("缺少 token"); kotlin.system.exitProcess(2) }
    } catch (error: CliException) {
        System.err.println("配置错误: ${error.message}")
        kotlin.system.exitProcess(2)
    }

    val server = McpServer(api, token)
    val input = McpStdioLineReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val output = PrintWriter(System.out, true)
    val json = Json { ignoreUnknownKeys = true }

    while (true) {
        val line = when (val frame = input.readLine()) {
            McpStdioLine.EndOfInput -> break
            McpStdioLine.Oversized -> {
                output.println(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", JsonNull)
                    put("error", buildJsonObject {
                        put("code", -32700); put("message", "request is too large")
                    })
                })
                continue
            }
            is McpStdioLine.Value -> frame.text
        }
        if (line.isBlank()) continue
        val req = try {
            json.parseToJsonElement(line).jsonObject
        } catch (_: Exception) {
            output.println(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", JsonNull)
                put("error", buildJsonObject { put("code", -32700); put("message", "parse error") })
            })
            continue
        }
        val resp = server.handle(req)
        if (resp != null) output.println(resp.toString())
    }
}

/** MCP 协议处理（initialize/tools/list/tools/call 三核心方法）。 */
class McpServer(api: String, token: String) {
    private val cli = Cli(api, token)

    fun handle(req: JsonObject): JsonObject? {
        val id = req["id"]
        val result: JsonObject = try {
            val methodElement = req["method"]
                ?: throw McpRequestException(-32600, "invalid request")
            val method = runCatching { methodElement.jsonPrimitive.content }.getOrElse {
                throw McpRequestException(-32600, "invalid request")
            }
            when (method) {
                "initialize" -> initialize()
                "notifications/initialized" -> buildJsonObject {}
                "ping" -> buildJsonObject {}
                "tools/list" -> toolsList()
                "tools/call" -> toolsCall(req["params"]?.jsonObject)
                else -> throw McpRequestException(-32601, "method not found: $method")
            }
        } catch (failure: McpRequestException) {
            return if (id == null) null else error(id, failure.code, failure.message)
        } catch (_: Exception) {
            return if (id == null) null else error(id, -32603, "internal error")
        }
        if (id == null) return null
        return buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            put("result", result)
        }
    }

    private fun initialize() = buildJsonObject {
        put("protocolVersion", "2024-11-05")
        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
        put("serverInfo", buildJsonObject {
            put("name", "teamtalk")
            put("version", com.virjar.tk.shared.TeamTalkBuild.RELEASE_VERSION)
        })
    }

    private fun toolsList() = buildJsonObject {
        put("tools", buildJsonArray {
            TOOLS.forEach { add(it.def) }
        })
    }

    private fun toolsCall(params: JsonObject?): JsonObject {
        val name = params?.get("name")?.jsonPrimitive?.content
            ?: throw McpRequestException(-32602, "missing tool name")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}
        val str = { k: String -> args[k]?.jsonPrimitive?.content }
        return try {
            val raw: String = when (name) {
                "status" -> cli.get("/v1/status")
                "conversations" -> cli.get("/v1/conversations")
                "friends" -> cli.get("/v1/friends")
                "send_text" -> cli.post("/v1/send-text", mapOf(
                    "chatId" to str("chatId"),
                    "clientMsgId" to str("clientMsgId"),
                    "text" to str("text"),
                ))
                "send_markdown" -> cli.post("/v1/send-rich", mapOf(
                    "chatId" to str("chatId"),
                    "clientMsgId" to str("clientMsgId"),
                    "markdown" to str("markdown"),
                ))
                "send_file" -> cli.post("/v1/send-file", mapOf(
                    "chatId" to str("chatId"),
                    "clientMsgId" to str("clientMsgId"),
                    "path" to str("path"),
                ))
                "outgoing_status" -> cli.get(
                    "/v1/outgoing?chatId=${urlEncode(str("chatId"))}" +
                        "&clientMsgId=${urlEncode(str("clientMsgId"))}",
                )
                "recv" -> cli.get(
                    "/v1/recv-wait?timeout=${str("timeout") ?: 10}" +
                        (str("chatId")?.let { "&chatId=${urlEncode(it)}" } ?: "") +
                        (str("afterEventId")?.let { "&afterEventId=$it" } ?: ""),
                )
                "messages" -> cli.get(
                    "/v1/messages?limit=${str("limit") ?: 20}" +
                        (str("chatId")?.let { "&chatId=${urlEncode(it)}" } ?: "") +
                        (str("afterEventId")?.let { "&afterEventId=$it" } ?: ""),
                )
                "history" -> cli.post("/v1/history", mapOf(
                    "chatId" to str("chatId"),
                    "fromSeq" to (str("fromSeq") ?: "0"),
                    "limit" to (str("limit") ?: Message.MAX_QUERY_PAGE_SIZE.toString()),
                ))
                "search_users" -> cli.post("/v1/users-search", mapOf("keyword" to str("keyword")))
                "chat_with" -> cli.post("/v1/chat-personal", mapOf("targetUid" to str("targetUid")))
                "mark_read" -> cli.post("/v1/mark-read", mapOf("chatId" to str("chatId"), "readSeq" to str("readSeq")))
                "revoke" -> cli.post("/v1/revoke", mapOf("chatId" to str("chatId"), "serverSeq" to str("serverSeq")))
                else -> throw McpRequestException(-32602, "unknown tool: $name")
            }
            buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", raw) })
                })
            }
        } catch (e: CliException) {
            // 工具业务失败：MCP isError 标记（不崩协议层）
            buildJsonObject {
                put("isError", true)
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", e.message ?: "error") })
                })
            }
        }
    }

    private fun error(id: kotlinx.serialization.json.JsonElement?, code: Int, msg: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id ?: JsonNull)
        put("error", buildJsonObject { put("code", code); put("message", msg) })
    }

    companion object {
        private fun tool(
            name: String,
            desc: String,
            vararg props: Pair<String, String>,
            required: List<String>? = null,
        ) = ToolDef(
            buildJsonObject {
                put("name", name)
                put("description", desc)
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        props.forEach { (k, d) -> put(k, buildJsonObject { put("type", "string"); put("description", d) }) }
                    })
                    val requiredProperties = required ?: props.take(1).map { it.first }
                    if (requiredProperties.isNotEmpty()) {
                        put("required", buildJsonArray {
                            requiredProperties.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        })
                    }
                })
            },
        )

        private val TOOLS = listOf(
            tool("status", "获取 IM 连接状态与当前账号", ),
            tool("conversations", "列出所有会话（含未读数/最后一条消息）"),
            tool("friends", "列出好友"),
            tool(
                "send_text",
                "发送纯文本消息",
                "chatId" to "目标会话 ID（可从 conversations 获取）",
                "text" to "消息文本",
                "clientMsgId" to "调用方生成并在重试时复用的稳定消息 ID",
                required = listOf("chatId", "text", "clientMsgId"),
            ),
            tool(
                "send_markdown",
                "发送 markdown 富文本消息",
                "chatId" to "目标会话 ID",
                "markdown" to "markdown 内容",
                "clientMsgId" to "调用方生成并在重试时复用的稳定消息 ID",
                required = listOf("chatId", "markdown", "clientMsgId"),
            ),
            tool(
                "send_file",
                "上传并持久排队发送文件",
                "chatId" to "目标会话 ID",
                "path" to "agent outgoing 目录内的文件路径",
                "clientMsgId" to "调用方生成并在重试时复用的稳定消息 ID",
                required = listOf("chatId", "path", "clientMsgId"),
            ),
            tool(
                "outgoing_status",
                "查询持久发送回执",
                "chatId" to "目标会话 ID",
                "clientMsgId" to "发送时使用的稳定消息 ID",
                required = listOf("chatId", "clientMsgId"),
            ),
            tool(
                "recv",
                "按全局事件游标等待新消息（长轮询）",
                "chatId" to "可选，只等该会话",
                "timeout" to "等待秒数默认 10",
                "afterEventId" to "可选，全局事件游标",
                required = emptyList(),
            ),
            tool(
                "messages",
                "按全局事件游标读取持久消息",
                "limit" to "条数默认 20",
                "chatId" to "可选，会话过滤",
                "afterEventId" to "可选，全局事件游标",
                required = emptyList(),
            ),
            tool("history", "拉取服务端历史消息", "chatId" to "会话 ID", "fromSeq" to "起始 seq（0 为最新）", "limit" to "条数"),
            tool("search_users", "按关键词搜索用户", "keyword" to "用户名/昵称关键词"),
            tool("chat_with", "与用户建立私聊会话，返回 chatId", "targetUid" to "目标用户 uid"),
            tool(
                "mark_read",
                "标记会话已读",
                "chatId" to "会话 ID",
                "readSeq" to "已读水位",
                required = listOf("chatId", "readSeq"),
            ),
            tool(
                "revoke",
                "撤回自己发的消息",
                "chatId" to "会话 ID",
                "serverSeq" to "消息 seq",
                required = listOf("chatId", "serverSeq"),
            ),
        )

        private data class ToolDef(val def: JsonObject)
    }

    private class McpRequestException(
        val code: Int,
        override val message: String,
    ) : IllegalArgumentException(message)
}

private fun urlEncode(value: String?): String = URLEncoder.encode(value ?: "", "UTF-8")

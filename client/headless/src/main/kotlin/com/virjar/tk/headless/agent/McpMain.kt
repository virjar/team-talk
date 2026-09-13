package com.virjar.tk.headless.agent

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
 * 用法：tt-mcp [--api host:port] --token-file <private scoped token file>
 * MCP 客户端（如 Claude Desktop）配置：
 * ```
 * {"mcpServers":{"teamtalk":{"command":"/opt/tt-agent/bin/tt-mcp","args":["--token-file","/private/token"]}}}
 * ```
 */
fun main(args: Array<String>) {
    try {
        val options = parseMcpOptions(args)
        val api = AgentClientIoPolicy.endpoint(options["api"] ?: System.getenv("TT_API")?.takeIf(String::isNotBlank)
            ?: "127.0.0.1:8600").display
        val tokenFile = options["token-file"] ?: System.getenv("TT_MCP_TOKEN_FILE")?.takeIf(String::isNotBlank)
            ?: throw CliException("MCP requires --token-file or TT_MCP_TOKEN_FILE")
        val token = readMcpTokenFile(File(tokenFile))
        HeadlessBundleInstaller.acquireRuntimeLease(HeadlessRuntime.currentBundle()).use {
            val server = McpServer(api, token)
            server.verifyAccess()
            serveMcpStdio(server, InputStreamReader(System.`in`, Charsets.UTF_8), PrintWriter(System.out, true))
        }
    } catch (failure: CliException) {
        System.err.println("MCP configuration error: ${failure.message}")
        kotlin.system.exitProcess(2)
    } catch (_: Exception) {
        System.err.println("MCP could not start; check the local agent and token configuration")
        kotlin.system.exitProcess(2)
    }
}

internal fun parseMcpOptions(args: Array<String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val name = args[index]
        if (name !in setOf("--api", "--token-file") || index + 1 >= args.size || args[index + 1].startsWith("--"))
            throw CliException("MCP accepts only --api and --token-file")
        if (result.put(name.removePrefix("--"), args[index + 1]) != null) throw CliException("duplicate MCP option")
        index += 2
    }
    return result
}

internal fun readMcpTokenFile(file: File): String {
    try {
        val path = file.toPath()
        val attrs = java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink)
        require(java.nio.file.Files.getPosixFilePermissions(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ==
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        require(java.nio.file.Files.getOwner(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ==
            path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name")))
        require((java.nio.file.Files.getAttribute(path, "unix:nlink", java.nio.file.LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1)
        val bytes = java.nio.ByteBuffer.allocate(AgentClientIoPolicy.MAX_TOKEN_FILE_BYTES + 1)
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { /* bounded private token read */ }
        }
        require(bytes.position() <= AgentClientIoPolicy.MAX_TOKEN_FILE_BYTES)
        val token = bytes.array().copyOf(bytes.position()).toString(Charsets.UTF_8).trim()
        return token.takeIf { it.matches(Regex("[A-Za-z0-9_-]{32,128}")) }
            ?: throw CliException("invalid MCP token file")
    } catch (failure: CliException) { throw failure }
    catch (_: Exception) { throw CliException("MCP token file must be an owned regular 0600 file") }
}

internal fun serveMcpStdio(server: McpServer, reader: java.io.Reader, output: PrintWriter) {
    val input = McpStdioLineReader(reader)
    val json = Json { ignoreUnknownKeys = true }
    while (true) {
        val line = when (val frame = input.readLine()) {
            McpStdioLine.EndOfInput -> break
            McpStdioLine.Oversized -> {
                output.println(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", JsonNull)
                    put("error", buildJsonObject { put("code", -32700); put("message", "request is too large") })
                })
                continue
            }
            is McpStdioLine.Value -> frame.text
        }
        if (line.isBlank()) continue
        val req = try { json.parseToJsonElement(line).jsonObject } catch (_: Exception) {
            output.println(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", JsonNull)
                put("error", buildJsonObject { put("code", -32700); put("message", "parse error") })
            })
            continue
        }
        server.handle(req)?.let { output.println(it.toString()) }
    }
}

/** MCP 协议处理（initialize/tools/list/tools/call 三核心方法）。 */
class McpServer(api: String, token: String) {
    private val cli = Cli(api, token)

    internal fun verifyAccess(): Set<String> = allowedTools(accessDescription())

    private fun accessDescription(): JsonObject = Json.parseToJsonElement(cli.get("/v1/mcp/access")).jsonObject

    private fun allowedTools(data: JsonObject): Set<String> =
        data.getValue("tools").jsonArray.map { it.jsonPrimitive.content }.toSet().also {
            if (it.isEmpty() || !AgentMcpTool.names.containsAll(it)) throw CliException("invalid MCP access description")
        }

    fun handle(req: JsonObject): JsonObject? {
        val id = req["id"]
        val result: JsonObject = try {
            val methodElement = req["method"]
                ?: throw McpRequestException(-32600, "invalid request")
            val method = runCatching { methodElement.jsonPrimitive.content }.getOrElse {
                throw McpRequestException(-32600, "invalid request")
            }
            when (method) {
                "initialize" -> { verifyAccess(); initialize() }
                "notifications/initialized" -> buildJsonObject {}
                "ping" -> buildJsonObject {}
                "tools/list" -> toolsList()
                "tools/call" -> toolsCall(req["params"]?.jsonObject)
                else -> throw McpRequestException(-32601, "method not found: $method")
            }
        } catch (failure: McpRequestException) {
            return if (id == null) null else error(id, failure.code, failure.message)
        } catch (failure: CliException) {
            return if (id == null) null else error(id, -32001, failure.message ?: "MCP access denied")
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

    private fun toolsList(): JsonObject {
        val access = accessDescription()
        val allowed = allowedTools(access)
        val allChats = access["allChats"]?.jsonPrimitive?.content == "true"
        val chatIds = access["chatIds"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        return buildJsonObject {
            put("tools", buildJsonArray {
                AgentMcpTool.entries.filter { it.wireName in allowed }.forEach { tool ->
                    val schema = tool.inputSchema
                    val properties = schema.getValue("properties").jsonObject
                    if (allChats || "chatId" !in properties) {
                        add(tool.definition)
                    } else {
                        val chat = JsonObject(properties.getValue("chatId").jsonObject + mapOf(
                            "enum" to chatIds,
                            "description" to kotlinx.serialization.json.JsonPrimitive("必须选择管理员授权的会话 ID"),
                        ))
                        val required = schema["required"]?.jsonArray?.toMutableList() ?: mutableListOf()
                        val chatKey = kotlinx.serialization.json.JsonPrimitive("chatId")
                        if (chatKey !in required) required.add(chatKey)
                        val scoped = JsonObject(schema + mapOf(
                            "properties" to JsonObject(properties + ("chatId" to chat)),
                            "required" to kotlinx.serialization.json.JsonArray(required),
                        ))
                        add(JsonObject(tool.definition + ("inputSchema" to scoped)))
                    }
                }
            })
        }
    }

    private fun toolsCall(params: JsonObject?): JsonObject {
        val name = params?.get("name")?.jsonPrimitive?.content
            ?: throw McpRequestException(-32602, "missing tool name")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}
        val str = { k: String -> args[k]?.jsonPrimitive?.content }
        return try {
            verifyAccess()
            val tool = AgentMcpTool.byName[name] ?: throw McpRequestException(-32602, "unknown tool: $name")
            // Argument defaults and omission are transport behavior, not inferred from JSON schema.
            val fields = when (tool) {
                AgentMcpTool.STATUS, AgentMcpTool.CONVERSATIONS, AgentMcpTool.FRIENDS -> emptyMap()
                AgentMcpTool.SEND_TEXT -> mapOf("chatId" to str("chatId"), "clientMsgId" to str("clientMsgId"), "text" to str("text"))
                AgentMcpTool.SEND_MARKDOWN -> mapOf("chatId" to str("chatId"), "clientMsgId" to str("clientMsgId"), "markdown" to str("markdown"))
                AgentMcpTool.SEND_FILE -> mapOf("chatId" to str("chatId"), "clientMsgId" to str("clientMsgId"), "path" to str("path"))
                AgentMcpTool.OUTGOING_STATUS -> mapOf("chatId" to str("chatId").orEmpty(), "clientMsgId" to str("clientMsgId").orEmpty())
                AgentMcpTool.RECV -> mapOf("timeout" to (str("timeout") ?: "10"), "chatId" to str("chatId"), "afterEventId" to str("afterEventId"))
                AgentMcpTool.MESSAGES -> mapOf("limit" to (str("limit") ?: "20"), "chatId" to str("chatId"), "afterEventId" to str("afterEventId"))
                AgentMcpTool.HISTORY -> mapOf("chatId" to str("chatId"), "fromSeq" to (str("fromSeq") ?: "0"),
                    "limit" to (str("limit") ?: Message.MAX_QUERY_PAGE_SIZE.toString()))
                AgentMcpTool.SEARCH_USERS -> mapOf("keyword" to str("keyword"))
                AgentMcpTool.CHAT_WITH -> mapOf("targetUid" to str("targetUid"))
                AgentMcpTool.MARK_READ -> mapOf("chatId" to str("chatId"), "readSeq" to str("readSeq"))
                AgentMcpTool.REVOKE -> mapOf("chatId" to str("chatId"), "serverSeq" to str("serverSeq"))
            }
            val raw = when (tool.method) {
                McpToolMethod.GET -> {
                    val query = fields.entries.filter { it.value != null }
                        .joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
                    cli.get(tool.path + if (query.isEmpty()) "" else "?$query")
                }
                McpToolMethod.POST -> cli.post(tool.path, fields)
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

    private class McpRequestException(
        val code: Int,
        override val message: String,
    ) : IllegalArgumentException(message)
}

private fun urlEncode(value: String?): String = URLEncoder.encode(value ?: "", "UTF-8")

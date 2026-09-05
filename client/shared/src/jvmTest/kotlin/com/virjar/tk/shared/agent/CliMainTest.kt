package com.virjar.tk.shared.agent

import com.sun.net.httpserver.HttpServer
import com.virjar.tk.protocol.model.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliMainTest {
    @Test
    fun `CLI and MCP default history requests stay within the protocol page limit`() {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/history") { exchange ->
            try {
                requests += Json.parseToJsonElement(
                    exchange.requestBody.bufferedReader().use { it.readText() },
                ).jsonObject
                // 接受请求以检查客户端真正发出的参数，避免 CLI 错误出口结束测试 JVM。
                val response = """{"ok":true,"data":{"messages":[]}}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val api = "127.0.0.1:${server.address.port}"
            // CLI、agent 和 MCP 有同签名顶层 main；按 JVM 入口选择真正的 CLI。
            Class.forName("com.virjar.tk.shared.agent.CliMainKt")
                .getMethod("main", Array<String>::class.java)
                .invoke(null, arrayOf("history", "chat-1", "--api", api, "--token", "test-token"))
            val mcp = McpServer(api, "test-token")
            mcp.handle(Json.parseToJsonElement("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"history","arguments":{"chatId":"chat-1"}}}
            """).jsonObject)
            mcp.handle(Json.parseToJsonElement("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                 "params":{"name":"history","arguments":{"chatId":"chat-1","fromSeq":7,"limit":1}}}
            """).jsonObject)

            assertEquals(3, requests.size)
            assertEquals(
                listOf(Message.MAX_QUERY_PAGE_SIZE, Message.MAX_QUERY_PAGE_SIZE, 1).map(Int::toString),
                requests.map { it["limit"]?.jsonPrimitive?.content },
            )
            assertEquals(listOf("0", "0", "7"), requests.map { it["fromSeq"]?.jsonPrimitive?.content })
            assertTrue(requests.all { it["chatId"]?.jsonPrimitive?.content == "chat-1" })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `global option values never enter a send payload`() {
        val parsed = parseCliArguments(
            arrayOf(
                "send",
                "chat-1",
                "hello",
                "--token",
                "api-secret",
                "--api",
                "127.0.0.1:8600",
                "--json",
            ),
        )

        assertEquals(listOf("chat-1", "hello"), parsed.positional)
        assertEquals("api-secret", parsed.flagValues["token"])
        assertEquals("127.0.0.1:8600", parsed.flagValues["api"])
        assertTrue(parsed.jsonOutput)
    }

    @Test
    fun `option terminator preserves text beginning with dashes`() {
        val parsed = parseCliArguments(arrayOf("send", "chat-1", "--", "--literal", "text"))
        assertEquals(listOf("chat-1", "--literal", "text"), parsed.positional)
    }

    @Test
    fun `stable client message id is parsed as an option not message text`() {
        val parsed = parseCliArguments(
            arrayOf("send", "chat-1", "hello", "--clientMsgId", "job:42"),
        )

        assertEquals(listOf("chat-1", "hello"), parsed.positional)
        assertEquals("job:42", parsed.flagValues["clientMsgId"])
    }

    @Test
    fun `missing or unknown option values fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            parseCliArguments(arrayOf("send", "chat-1", "--token"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseCliArguments(arrayOf("send", "chat-1", "--unknown", "value"))
        }
    }

    @Test
    fun `client endpoint is loopback only so bearer credentials cannot leave the host`() {
        assertEquals("127.0.0.1:8600", AgentClientIoPolicy.endpoint("localhost:8600").display)
        listOf("example.com:8600", "192.168.1.5:8600", "0.0.0.0:8600").forEach { endpoint ->
            assertFailsWith<CliException> { Cli(endpoint, "credential") }
        }
    }

    @Test
    fun `token files and HTTP responses have authoritative streaming bounds`() {
        val tokenFile = File.createTempFile("tt-cli-token-", ".txt")
        try {
            tokenFile.writeText("bounded-token\n")
            assertEquals("bounded-token", AgentClientIoPolicy.readTokenFile(tokenFile))
            tokenFile.writeBytes(ByteArray(AgentClientIoPolicy.MAX_TOKEN_FILE_BYTES + 1))
            assertFailsWith<CliException> { AgentClientIoPolicy.readTokenFile(tokenFile) }
        } finally {
            tokenFile.delete()
        }

        val maximum = ByteArray(4)
        assertEquals(
            maximum.size,
            AgentClientIoPolicy.readHttpResponse(
                ByteArrayInputStream(maximum),
                maximumBytes = maximum.size,
            ).length,
        )
        assertFailsWith<CliException> {
            AgentClientIoPolicy.readHttpResponse(
                ByteArrayInputStream(maximum + byteArrayOf(1)),
                maximumBytes = maximum.size,
            )
        }
    }

    @Test
    fun `request JSON preserves every control character and skips null fields`() {
        val value = "tab\tcarriage\rbackspace\bnewline\nquote\"slash\\"
        val encoded = buildCliJsonObject(mapOf("text" to value, "absent" to null))
        val decoded = Json.parseToJsonElement(encoded).jsonObject

        assertEquals(value, decoded["text"]?.jsonPrimitive?.content)
        assertTrue("absent" !in decoded)
    }
}

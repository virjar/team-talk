package com.virjar.tk.headless.agent

import com.sun.net.httpserver.HttpServer
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.headless.bot.ImBotMessageInbox
import com.virjar.tk.shared.testkit.FakeLocalCache
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AgentMcpHttpTest {
    @Test fun `direct rest cannot bypass tool or chat restrictions and replies omit private data`() = fixture { f ->
        f.publish(1, "chat-a", "allowed body")
        f.publish(2, "chat-b", "other body")
        assertEquals(403, f.request("GET", "/v1/messages").first)
        assertEquals(403, f.request("GET", "/v1/messages?chatId=chat-b").first)
        assertEquals(403, f.request("POST", "/v1/send-text", """{"chatId":"chat-a","clientMsgId":"stable","text":"secret payload"}""").first)
        assertEquals(403, f.request("POST", "/v1/upload", """{"path":"secret path"}""").first)
        assertEquals(403, f.request("GET", "/v1/mcp/grants").first)
        val (status, response) = f.request("GET", "/v1/messages?chatId=chat-a")
        assertEquals(200, status)
        assertTrue(response.contains("allowed body")); assertFalse(response.contains("other body"))
        assertEquals(403, f.request("GET", "/v1/mcp/access", token = AgentMcpAccessTest.MASTER).first)
        assertEquals(401, f.request("GET", "/v1/messages?chatId=chat-a", token = "invalid").first)
        val audit = f.request("GET", "/v1/mcp/audit", token = AgentMcpAccessTest.MASTER).second
        listOf("secret payload", "secret path", "allowed body", "other body", AgentMcpAccessTest.TOKEN).forEach { assertFalse(audit.contains(it)) }
    }

    @Test fun `revoked grant cannot receive an already waiting response`() = fixture { f ->
        val client = Executors.newSingleThreadExecutor()
        try {
            val waiting = client.submit<Pair<Int, String>> { f.request("GET", "/v1/recv-wait?chatId=chat-a&afterEventId=1&timeout=3") }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (true) {
                val log = f.access.auditTail(AgentMcpAccessTest.ADMIN, 20).getValue("records").jsonArray
                if (log.any { it.jsonObject["tool"]?.jsonPrimitive?.content == "recv" && it.jsonObject["phase"]?.jsonPrimitive?.content == "admitted" }) break
                check(System.nanoTime() < deadline) { "Receive request was not admitted" }
                Thread.sleep(10)
            }
            val revoked = f.request("POST", "/v1/mcp/revoke", """{"id":"assistant"}""", AgentMcpAccessTest.MASTER)
            assertEquals(200, revoked.first)
            f.publish(2, "chat-a", "revoked access body")
            val result = waiting.get(5, TimeUnit.SECONDS)
            assertEquals(401, result.first)
            assertFalse(result.second.contains("revoked access body"))
            val record = f.access.auditTail(AgentMcpAccessTest.ADMIN, 1).getValue("records").jsonArray.single().jsonObject
            assertEquals(200, record.getValue("status").jsonPrimitive.int)
            assertEquals(401, record.getValue("responseStatus").jsonPrimitive.int)
        } finally { client.shutdownNow() }
    }

    @Test fun `MCP lists the scoped tools and direct forbidden calls are audited by agent`() = fixture { f ->
        val mcp = McpServer(f.endpoint, AgentMcpAccessTest.TOKEN)
        val listed = mcp.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").jsonObject)!!
        val names = listed.getValue("result").jsonObject.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet()
        assertEquals(setOf("status", "messages", "recv"), names)
        val definitions = listed.getValue("result").jsonObject.getValue("tools").jsonArray.map { it.jsonObject }
        for (name in listOf("messages", "recv")) {
            val schema = definitions.single { it.getValue("name").jsonPrimitive.content == name }.getValue("inputSchema").jsonObject
            assertTrue(schema.getValue("required").jsonArray.any { it.jsonPrimitive.content == "chatId" })
            assertEquals(listOf("chat-a"), schema.getValue("properties").jsonObject.getValue("chatId").jsonObject
                .getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        }
        val response = mcp.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"send_text","arguments":{"chatId":"chat-a","text":"not sent","clientMsgId":"stable"}}}""").jsonObject)!!
        assertEquals(true, response.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
        val records = f.access.auditTail(AgentMcpAccessTest.ADMIN, 20).getValue("records").jsonArray
        assertTrue(records.any { it.jsonObject["tool"]?.jsonPrimitive?.content == "send_text" && it.jsonObject["status"]?.jsonPrimitive?.int == 403 })
        f.access.revoke(AgentMcpAccessTest.ADMIN, "assistant")
        assertTrue(mcp.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""").jsonObject)!!.containsKey("error"))
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = createAgentSecurityTestRoot("mcp-http-")
        try { Fixture(File(root, "data")).use(block) } finally { root.deleteRecursively() }
    }

    private class Fixture(dataDir: File) : AutoCloseable {
        val access = AgentMcpAccess(dataDir, AgentMcpAccessTest.OWNER, AgentMcpAccessTest.MASTER)
        val cache = FakeLocalCache()
        val inbox = ImBotMessageInbox().also { it.bind(cache) }
        val runtime = AgentRuntime("127.0.0.1", 5100, dataDir, "http://127.0.0.1", inbox)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newFixedThreadPool(4)
        val endpoint get() = "127.0.0.1:${server.address.port}"
        init {
            access.create(AgentMcpAccessTest.ADMIN, AgentMcpAccessTest.fields())
            val api = AgentApi(runtime, access)
            for (path in listOf("/v1/messages", "/v1/recv-wait", "/v1/send-text", "/v1/upload", "/v1/mcp/access", "/v1/mcp/grants", "/v1/mcp/revoke", "/v1/mcp/audit")) {
                server.createContext(path) { api.handle(it, path) }
            }
            server.executor = executor
            server.start()
        }
        fun publish(eventId: Long, chatId: String, text: String) {
            val message = Message(chatId, "message-$eventId", eventId, "sender", MessageType.RICH_TEXT.code, 1,
                body = buildRichTextBody(text))
            cache.insertMessage(message)
            inbox.publish(eventId, message)
        }
        fun request(method: String, path: String, body: String? = null, token: String = AgentMcpAccessTest.TOKEN): Pair<Int, String> {
            val connection = URL("http://$endpoint$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method; connection.connectTimeout = 2_000; connection.readTimeout = 5_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
                val status = connection.responseCode
                return status to (if (status >= 400) connection.errorStream else connection.inputStream).bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
        }
        override fun close() { server.stop(0); executor.shutdownNow(); runtime.close(); access.close() }
    }
}

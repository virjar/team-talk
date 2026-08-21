package com.virjar.tk.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class McpServerTest {
    private val server = McpServer("127.0.0.1:1", "test-token")

    @Test
    fun `initialized notification has no json rpc response`() {
        assertNull(server.handle(request(method = "notifications/initialized", id = null)))
    }

    @Test
    fun `every request without an id produces no json rpc response`() {
        listOf(
            request(method = "ping", id = null),
            request(method = "unknown-method", id = null),
            request(method = "tools/call", id = null, params = buildJsonObject {}),
        ).forEach { notification ->
            assertNull(server.handle(notification))
        }
    }

    @Test
    fun `internal failures preserve the request id and use internal error code`() {
        val response = requireNotNull(server.handle(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 91)
            put("method", "tools/call")
            put("params", JsonPrimitive("not-an-object"))
        }))

        assertFalse("result" in response)
        assertEquals("91", response["id"]?.jsonPrimitive?.content)
        assertEquals("-32603", response["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        assertEquals("internal error", response["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
    }

    @Test
    fun `malformed method is contained and the next request still succeeds`() {
        val malformed = requireNotNull(server.handle(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 92)
            put("method", buildJsonObject {})
        }))
        assertEquals("-32600", malformed["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)

        val next = requireNotNull(server.handle(request(method = "ping", id = JsonPrimitive(93))))
        assertEquals("93", next["id"]?.jsonPrimitive?.content)
        assertFalse("error" in next)
    }

    @Test
    fun `missing and unknown tool names are top level protocol errors`() {
        listOf(
            buildJsonObject {},
            buildJsonObject { put("name", "unknown") },
        ).forEachIndexed { index, params ->
            val response = requireNotNull(
                server.handle(request("tools/call", JsonPrimitive(index + 1), params)),
            )
            assertFalse("result" in response)
            assertEquals("-32602", response["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
            assertEquals((index + 1).toString(), response["id"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `tool schema declares every required send and mutation argument`() {
        val response = requireNotNull(server.handle(request("tools/list", JsonPrimitive(7))))
        val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray ?: JsonArray(emptyList())
        val requiredByName = tools.associate { toolElement ->
            val tool = toolElement.jsonObject
            val name = tool.getValue("name").jsonPrimitive.content
            val required = tool.getValue("inputSchema").jsonObject["required"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                .orEmpty()
            name to required
        }

        assertEquals(listOf("chatId", "text", "clientMsgId"), requiredByName["send_text"])
        assertEquals(listOf("chatId", "markdown", "clientMsgId"), requiredByName["send_markdown"])
        assertEquals(listOf("chatId", "path", "clientMsgId"), requiredByName["send_file"])
        assertEquals(listOf("chatId", "clientMsgId"), requiredByName["outgoing_status"])
        assertEquals(listOf("chatId", "readSeq"), requiredByName["mark_read"])
        assertEquals(listOf("chatId", "serverSeq"), requiredByName["revoke"])
    }

    private fun request(
        method: String,
        id: JsonPrimitive?,
        params: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        id?.let { put("id", it) }
        put("method", method)
        params?.let { put("params", it) }
    }
}

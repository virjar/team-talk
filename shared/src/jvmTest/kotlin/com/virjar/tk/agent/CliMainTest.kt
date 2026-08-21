package com.virjar.tk.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliMainTest {
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
    fun `durable send id is resolved and recoverable before any transport result`() {
        assertEquals(
            "job:42",
            resolveDurableClientMsgId("send", "job:42") { error("must not generate") },
        )
        assertEquals(
            "generated-id",
            resolveDurableClientMsgId("send-file", null) { "generated-id" },
        )
        assertEquals(null, resolveDurableClientMsgId("status", null) { error("must not generate") })
        assertTrue("generated-id" in durableSendRecoveryNotice("generated-id"))
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
    fun `client read timeout exceeds maximum server long poll`() {
        assertTrue(CLI_HTTP_READ_TIMEOUT_MILLIS > MAX_AGENT_WAIT_SECONDS * 1_000)
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

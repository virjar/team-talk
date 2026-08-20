package com.virjar.tk.http

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupBotApiTest {
    @Test
    fun `target webhook body contains only markdown and ignores legacy routing fields`() {
        val decoded = Json.decodeFromString<GroupBotMessageRequest>(
            """{"chatId":"must-not-route","markdown":"hello","idempotencyKey":"must-not-win"}""",
        )

        assertEquals("hello", decoded.markdown)
        assertEquals("""{"markdown":"hello"}""", Json.encodeToString(decoded))
    }

    @Test
    fun `target webhook acknowledgement is minimal`() {
        assertEquals("""{"ok":true}""", Json.encodeToString(GroupBotMessageResponse(ok = true)))
    }
}

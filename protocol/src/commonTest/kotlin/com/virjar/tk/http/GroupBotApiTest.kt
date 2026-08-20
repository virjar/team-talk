package com.virjar.tk.http

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GroupBotApiTest {
    @Test
    fun `target webhook body contains only markdown and rejects routing fields`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<GroupBotMessageRequest>(
                """{"chatId":"must-not-route","markdown":"hello","idempotencyKey":"must-not-win"}""",
            )
        }

        val decoded = Json.decodeFromString<GroupBotMessageRequest>("""{"markdown":"hello"}""")
        assertEquals("""{"markdown":"hello"}""", Json.encodeToString(decoded))
    }

    @Test
    fun `target webhook acknowledgement is minimal`() {
        assertEquals("""{"ok":true}""", Json.encodeToString(GroupBotMessageResponse(ok = true)))
    }
}

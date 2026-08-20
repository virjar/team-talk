package com.virjar.tk.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupBotsScreenTest {
    @Test
    fun `absolute endpoint normalizes one slash`() {
        assertEquals(
            "https://im.example.com/api/v1/groups/chat-1/bots/bot-1/messages",
            absoluteBotEndpoint("https://im.example.com/", "/api/v1/groups/chat-1/bots/bot-1/messages"),
        )
    }

    @Test
    fun `curl example carries credential and markdown only`() {
        val example = botCurlExample(
            endpoint = "https://im.example.com/api/v1/groups/chat-1/bots/bot-1/messages",
            token = "ttb_secret",
        )

        assertTrue("Authorization: Bearer ttb_secret" in example)
        assertTrue("\"markdown\"" in example)
        assertTrue("chatId" !in example)
        assertTrue("idempotencyKey" !in example)
        assertTrue("Idempotency-Key" !in example)
        assertTrue("POST 'https://im.example.com/api/v1/groups/chat-1/bots/bot-1/messages' \\\n" in example)
        assertTrue("构建完成\\n\\n版本已发布" in example)
        assertTrue("构建完成\\\\n" !in example, "JSON should encode a newline rather than a literal backslash-n")
    }
}

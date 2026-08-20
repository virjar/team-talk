package com.virjar.tk.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GroupBotManagementRepositoryTest {
    @Test
    fun `contract builds only bounded identifier paths`() {
        assertEquals("/api/v1/groups/chat-1/bots", GroupBotHttpContract.listPath("chat-1"))
        assertEquals("/api/v1/groups/chat-1/bots/bot_2", GroupBotHttpContract.botPath("chat-1", "bot_2"))
        assertEquals(
            "/api/v1/groups/chat-1/bots/bot_2/rotate-token",
            GroupBotHttpContract.rotatePath("chat-1", "bot_2"),
        )

        assertFailsWith<IllegalArgumentException> { GroupBotHttpContract.listPath("../admin") }
        assertFailsWith<IllegalArgumentException> { GroupBotHttpContract.botPath("chat-1", "bot/other") }
    }

    @Test
    fun `contract decodes safe metadata without requiring credentials`() {
        val bots = GroupBotHttpContract.decodeList(
            """[{"botId":"bot-1","name":"构建通知","status":1,"lastUsedAt":null,"createdAt":7,"apiPath":"/api/v1/groups/chat-1/bots/bot-1/messages","groupManaged":true,"createdByMe":false,"canRotateToken":false,"canRemove":true,"futureField":"ignored"}]""",
        )

        assertEquals(1, bots.size)
        assertEquals("构建通知", bots.single().name)
        assertEquals("/api/v1/groups/chat-1/bots/bot-1/messages", bots.single().apiPath)
        assertTrue(bots.single().canRemove)
    }

    @Test
    fun `contract carries server business error text`() {
        assertEquals("不是当前群成员", GroupBotHttpContract.errorMessage("""{"error":"不是当前群成员"}""", "fallback"))
        assertEquals("fallback", GroupBotHttpContract.errorMessage("not-json", "fallback"))
    }
}

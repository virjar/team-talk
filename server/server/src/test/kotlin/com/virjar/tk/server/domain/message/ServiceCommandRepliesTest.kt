package com.virjar.tk.server.domain.message

import com.virjar.tk.protocol.body.buildRichTextBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 服务号指令纯函数契约：回复身份派生与指令解析（内测反馈 T058 / CODE-01）。 */
class ServiceCommandRepliesTest {
    @Test
    fun `short command ids keep the legacy reply identity`() {
        assertEquals("svc-abc", ServiceCommandReplies.replyId("abc"))
    }

    @Test
    fun `overlong command ids derive a hash identity that never collides with legacy ones`() {
        val longId = "c".repeat(300)
        val derived = ServiceCommandReplies.replyId(longId)
        assertTrue(derived.startsWith("svc.sha256:"))
        assertTrue(derived.length <= com.virjar.tk.protocol.body.MessageBodyPolicy.MAX_CLIENT_MESSAGE_ID_LENGTH)
        assertEquals(derived, ServiceCommandReplies.replyId(longId))
    }

    @Test
    fun `help unknown command and plain text route to help text`() {
        assertEquals(ServiceCommandReplies.HELP_TEXT, ServiceCommandReplies.replyFor("/HELP"))
        assertEquals(ServiceCommandReplies.HELP_TEXT, ServiceCommandReplies.replyFor("你好"))
        val unknown = ServiceCommandReplies.replyFor("/不存在")
        assertTrue(unknown.contains("未识别指令：/不存在"))
        assertTrue(unknown.contains(ServiceCommandReplies.HELP_TEXT))
    }

    @Test
    fun `command text extracts from rich text and reply bodies only`() {
        assertEquals("hello", ServiceCommandReplies.commandText(buildRichTextBody("hello")))
        assertNull(ServiceCommandReplies.commandText(null))
    }
}

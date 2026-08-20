package com.virjar.tk.ui.component

import com.virjar.tk.body.ReplyBody
import com.virjar.tk.model.User
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplySenderNameTest {

    @Test
    fun `authoritative snapshot is preferred`() {
        val body = reply(senderUid = "member-1", senderName = "发送时姓名")

        assertEquals(
            "发送时姓名",
            resolveReplySenderName(body) { User("member-1", "current", "当前姓名") },
        )
    }

    @Test
    fun `legacy uid snapshot resolves only the referenced sender`() {
        val requestedUids = mutableListOf<String>()
        val body = reply(senderUid = "member-1", senderName = "member-1")

        val resolved = resolveReplySenderName(body) { requestedUid ->
            requestedUids += requestedUid
            if (requestedUid == "member-1") User(requestedUid, "alice", "Alice") else null
        }

        assertEquals("Alice", resolved)
        assertEquals(listOf("member-1"), requestedUids)
    }

    @Test
    fun `username and readable placeholder are safe fallbacks`() {
        val body = reply(senderUid = "member-123456", senderName = null)

        assertEquals(
            "alice",
            resolveReplySenderName(body) { User("member-123456", "alice", "") },
        )
        assertEquals("未知成员", resolveReplySenderName(body, resolveSender = null))
        assertEquals("未知成员", resolveReplySenderName(reply("", null), resolveSender = null))
    }

    private fun reply(senderUid: String, senderName: String?) = ReplyBody(
        replyToMsgId = "42",
        replyToSenderUid = senderUid,
        replyToSenderName = senderName,
        replySnippet = "引用内容",
        content = "回复内容",
    )
}

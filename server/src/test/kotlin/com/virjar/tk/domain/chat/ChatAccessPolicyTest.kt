package com.virjar.tk.domain.chat

import com.virjar.tk.model.Chat
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Member
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatAccessPolicyTest {
    private val source = FakeChatAccessSource(
        chats = mapOf(
            "personal" to Chat(chatId = "personal", chatType = ChatType.PERSONAL.code),
            "group" to Chat(chatId = "group", chatType = ChatType.GROUP.code),
        ),
        members = mapOf(
            ("personal" to "person") to Member(uid = "person", chatId = "personal", role = 0),
            ("group" to "member") to Member(uid = "member", chatId = "group", role = 0),
            ("group" to "admin") to Member(uid = "admin", chatId = "group", role = 1),
            ("group" to "owner") to Member(uid = "owner", chatId = "group", role = 2),
        ),
    )
    private val access = ChatAccessPolicy(source)

    @Test
    fun `group boundary rejects a missing or personal chat before membership`() {
        assertDenied("群聊不存在") { access.requireGroupMember("person", "missing") }
        assertDenied("群聊不存在") { access.requireGroupMember("person", "personal") }
        assertDenied("不是群成员") { access.requireGroupMember("outsider", "group") }
    }

    @Test
    fun `admin and owner thresholds are defined once`() {
        assertEquals(1, access.requireAdmin("admin", "group").role)
        assertEquals(2, access.requireAdmin("owner", "group").role)
        assertEquals(2, access.requireOwner("owner", "group").role)
        assertDenied("需要管理员权限") { access.requireAdmin("member", "group") }
        assertDenied("需要群主权限") { access.requireOwner("admin", "group") }
    }

    @Test
    fun `member management requires a strictly higher role`() {
        assertEquals(
            "admin" to "member",
            access.requireCanManageMember("admin", "group", "member").let { it.first.uid to it.second.uid },
        )
        assertEquals(
            "owner" to "admin",
            access.requireCanManageMember("owner", "group", "admin").let { it.first.uid to it.second.uid },
        )
        assertDenied("不能管理同级或更高角色") {
            access.requireCanManageMember("admin", "group", "owner")
        }
        assertDenied("不能管理自己") {
            access.requireCanManageMember("admin", "group", "admin")
        }
    }

    private fun assertDenied(message: String, block: () -> Unit) {
        assertEquals(message, assertFailsWith<ChatAccessDeniedException>(block = block).message)
    }
}

private class FakeChatAccessSource(
    private val chats: Map<String, Chat>,
    private val members: Map<Pair<String, String>, Member>,
) : ChatAccessSource {
    override fun getChat(chatId: String): Chat? = chats[chatId]
    override fun getMember(chatId: String, uid: String): Member? = members[chatId to uid]
}

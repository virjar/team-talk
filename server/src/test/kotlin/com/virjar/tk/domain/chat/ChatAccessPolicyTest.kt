package com.virjar.tk.domain.chat

import com.virjar.tk.model.Chat
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Member
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `group boundary rejects a missing or personal chat before membership`() = runTest {
        assertDenied("群聊不存在") { access.requireGroupMember("person", "missing") }
        assertDenied("群聊不存在") { access.requireGroupMember("person", "personal") }
        assertDenied("不是群成员") { access.requireGroupMember("outsider", "group") }
    }

    @Test
    fun `admin and owner thresholds are defined once`() = runTest {
        assertEquals(1, access.requireAdmin("admin", "group").role)
        assertEquals(2, access.requireAdmin("owner", "group").role)
        assertEquals(2, access.requireOwner("owner", "group").role)
        assertDenied("需要管理员权限") { access.requireAdmin("member", "group") }
        assertDenied("需要群主权限") { access.requireOwner("admin", "group") }
    }

    @Test
    fun `member management requires a strictly higher role`() = runTest {
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

    private suspend fun assertDenied(message: String, block: suspend () -> Unit) {
        val error = try {
            block()
            throw AssertionError("expected ChatAccessDeniedException")
        } catch (error: ChatAccessDeniedException) {
            error
        }
        assertEquals(message, error.message)
    }
}

private class FakeChatAccessSource(
    private val chats: Map<String, Chat>,
    private val members: Map<Pair<String, String>, Member>,
) : ChatAccessSource {
    override suspend fun load(chatId: String, memberUids: Set<String>): ChatAccessSnapshot = ChatAccessSnapshot(
        chat = chats[chatId],
        members = memberUids.mapNotNull { uid -> members[chatId to uid] },
    )

    override suspend fun loadAllMembers(chatId: String): ChatAccessSnapshot = ChatAccessSnapshot(
        chat = chats[chatId],
        members = members.filterKeys { it.first == chatId }.values.toList(),
    )

    override suspend fun listAccessibleChatIds(uid: String): Set<String> = members.keys
        .filterTo(linkedSetOf()) { (_, memberUid) -> memberUid == uid }
        .mapTo(linkedSetOf()) { it.first }

    override suspend fun <T> read(
        chatId: String,
        memberUids: Set<String>,
        includeAllMembers: Boolean,
        block: (ChatAccessSnapshot) -> T,
    ): T = block(if (includeAllMembers) loadAllMembers(chatId) else load(chatId, memberUids))

    override suspend fun <T> readAccessibleChatIds(uid: String, block: (Set<String>) -> T): T =
        block(listAccessibleChatIds(uid))
}

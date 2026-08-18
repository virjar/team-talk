package com.virjar.tk.ui.screen

import com.virjar.tk.model.Contact
import com.virjar.tk.model.Conversation
import com.virjar.tk.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GlobalSearchTest {

    @Test
    fun `conversation search covers name preview and id but ignores blank query`() {
        val conversations = listOf(
            Conversation(chatId = "group-architecture", chatType = 2, chatName = "产品设计"),
            Conversation(chatId = "personal-1", chatType = 1, chatName = "Alice", lastMessage = "release checklist"),
        )

        assertEquals(emptyList(), filterConversationsForSearch(conversations, "   "))
        assertEquals("group-architecture", filterConversationsForSearch(conversations, "产品").single().chatId)
        assertEquals("personal-1", filterConversationsForSearch(conversations, "CHECKLIST").single().chatId)
        assertEquals("group-architecture", filterConversationsForSearch(conversations, "architecture").single().chatId)
    }

    @Test
    fun `contact search covers display name username and phone`() {
        val user = User(uid = "u1", username = "alice.dev", name = "艾丽丝", phone = "13800138000")
        val contacts = listOf(Contact(uid = "me", friendUid = user.uid, user = user))

        assertSame(user, filterContactsForSearch(contacts, "alice").single())
        assertSame(user, filterContactsForSearch(contacts, "艾丽").single())
        assertSame(user, filterContactsForSearch(contacts, "1380013").single())
    }

    @Test
    fun `local friend wins when remote search returns same user`() {
        val local = User(uid = "u1", username = "alice", name = "好友备注来源")
        val remoteDuplicate = local.copy(name = "远端名称")
        val remoteOnly = User(uid = "u2", username = "bob", name = "Bob")

        val merged = mergeUsersForSearch(listOf(local), listOf(remoteDuplicate, remoteOnly))

        assertEquals(listOf("u1", "u2"), merged.map { it.uid })
        assertSame(local, merged.first())
    }
}

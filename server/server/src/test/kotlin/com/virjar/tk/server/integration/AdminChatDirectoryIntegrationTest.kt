package com.virjar.tk.server.integration

import com.virjar.tk.server.application.admin.AdminPageRequest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminChatDirectoryIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `admin group reads are stable paged and batch active member counts`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("admin-chat-owner"))
        val firstMember = ctx.registerUser(uniqueUsername("admin-chat-member-a"))
        val secondMember = ctx.registerUser(uniqueUsername("admin-chat-member-b"))
        val prefix = uniqueUsername("admin-directory")
        val groups = listOf(
            ctx.chatService.createGroup("$prefix alpha", null, owner, listOf(firstMember, secondMember)),
            ctx.chatService.createGroup("$prefix beta", null, owner, emptyList()),
            ctx.chatService.createGroup("$prefix gamma", null, owner, listOf(firstMember)),
        )
        val personal = ctx.chatService.createPersonalChat(owner, firstMember)

        val firstPage = ctx.adminService.listGroups(prefix, AdminPageRequest(page = 1, size = 2))
        val secondPage = ctx.adminService.listGroups(prefix, AdminPageRequest(page = 2, size = 2))
        val listed = firstPage.items + secondPage.items

        assertEquals(3L, firstPage.total)
        assertEquals(3L, secondPage.total)
        assertEquals(groups.map { it.chatId }.toSet(), listed.map { it.chatId }.toSet())
        assertEquals(3, listed.single { it.chatId == groups[0].chatId }.memberCount)
        assertEquals(1, listed.single { it.chatId == groups[1].chatId }.memberCount)
        assertEquals(2, listed.single { it.chatId == groups[2].chatId }.memberCount)

        val detail = ctx.adminService.groupDetail(groups[0].chatId)
        assertEquals(3, detail.chat.memberCount)
        assertEquals(3, detail.members.size)
        assertFailsWith<IllegalArgumentException> { ctx.adminService.groupDetail(personal.chatId) }
    }
}

package com.virjar.tk.integration

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrganizationIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `department group follows subtree and rejects manual membership changes`() = runTest {
        val leader = ctx.registerUser(uniqueUsername("org-leader"))
        val engineer = ctx.registerUser(uniqueUsername("org-engineer"))
        val root = ctx.organizationService.createUnit(null, "Example Inc", null)
        val engineering = ctx.organizationService.createUnit(root.unitId, "研发", leader, enableGroup = true)
        val mobile = ctx.organizationService.createUnit(engineering.unitId, "移动端", null)

        assertFailsWith<IllegalArgumentException> {
            ctx.organizationService.createUnit(null, "Another Root", null)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.organizationService.removeMember(engineering.unitId, leader)
        }

        ctx.organizationService.assignMember(mobile.unitId, engineer, "客户端工程师", primary = true)

        val managed = ctx.organizationService.listUnits().first { it.unitId == engineering.unitId }
        assertEquals(engineering.unitId, managed.groupChatId, "部门节点 ID 同时作为稳定受管群 ID")
        assertEquals("研发部门群", ctx.chatService.getChat(managed.groupChatId!!)?.name)
        assertTrue(ctx.chatService.getMembers(managed.groupChatId!!).map { it.uid }.containsAll(listOf(leader, engineer)))

        val error = assertFailsWith<IllegalArgumentException> {
            ctx.chatService.leaveGroup(engineer, managed.groupChatId!!)
        }
        assertTrue(error.message.orEmpty().contains("维护"))

        // 子部门移出研发树后，成员和会话自动收敛；再移回时原 membership 无需重写即可恢复。
        ctx.organizationService.updateUnit(mobile.unitId, root.unitId, "移动端", null, 0)
        assertFalse(ctx.chatService.getMembers(managed.groupChatId!!).any { it.uid == engineer })
        assertEquals(null, ctx.conversationRepo.getConversation(engineer, managed.groupChatId!!))

        ctx.organizationService.updateUnit(mobile.unitId, engineering.unitId, "移动端", null, 0)
        assertTrue(ctx.chatService.getMembers(managed.groupChatId!!).any { it.uid == engineer })
        assertNotNull(ctx.conversationRepo.getConversation(engineer, managed.groupChatId!!))
    }
}

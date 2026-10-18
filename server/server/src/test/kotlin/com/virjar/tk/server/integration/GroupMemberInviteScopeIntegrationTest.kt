package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 普通群成员的邀请范围（办公优先，默认开放）：
 * 可以邀请好友与组织成员；管理员/群主保持无限制；受管部门群围栏不变。
 *
 * 服务端组织是单根树（"单组织只能有一个根节点"），本类（PER_CLASS 单实例）所有用例
 * 共享同一个惰性根节点，每个用例在根下自建子部门。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupMemberInviteScopeIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env
    private var sharedRootUnitId: String? = null

    @Test
    fun `ordinary member can invite friend`() = runTest {
        val owner = ctx.registerUser()
        val member = ctx.registerUser()
        val friend = ctx.registerUser()
        val group = ctx.chatService.createGroup("InviteFriend", null, owner, listOf(member))
        makeFriends(member, friend)

        ctx.chatService.addMembers(member, group.chatId, listOf(friend))

        assertTrue(ctx.chatService.getMembers(group.chatId).any { it.uid == friend })
    }

    @Test
    fun `ordinary member can invite organization peer without friendship`() = runTest {
        val owner = ctx.registerUser()
        val member = ctx.registerUser()
        val peer = ctx.registerUser()
        val group = ctx.chatService.createGroup("InviteOrgPeer", null, owner, listOf(member))
        val unit = createChildUnit("邀请范围测试部门")
        ctx.organizationService.assignMember(unit.unitId, member, null, primary = true)
        ctx.organizationService.assignMember(unit.unitId, peer, null, primary = true)

        ctx.chatService.addMembers(member, group.chatId, listOf(peer))

        assertTrue(ctx.chatService.getMembers(group.chatId).any { it.uid == peer })
    }

    @Test
    fun `ordinary member cannot invite stranger`() = runTest {
        val owner = ctx.registerUser()
        val member = ctx.registerUser()
        val stranger = ctx.registerUser()
        val group = ctx.chatService.createGroup("InviteStranger", null, owner, listOf(member))

        val error = assertFailsWith<ChatAccessDeniedException> {
            ctx.chatService.addMembers(member, group.chatId, listOf(stranger))
        }
        assertEquals("普通成员只能邀请好友或组织成员", error.message)
        assertTrue(ctx.chatService.getMembers(group.chatId).none { it.uid == stranger })
    }

    @Test
    fun `member without organization cannot invite organization user`() = runTest {
        val owner = ctx.registerUser()
        val member = ctx.registerUser()
        val orgUser = ctx.registerUser()
        val group = ctx.chatService.createGroup("InviteFailClosed", null, owner, listOf(member))
        val unit = createChildUnit("FailClosed 部门")
        ctx.organizationService.assignMember(unit.unitId, orgUser, null, primary = true)

        // 操作者自己没有组织归属时，即使目标是组织成员也必须拒绝（与目录访问的 fail-closed 一致）。
        assertFailsWith<ChatAccessDeniedException> {
            ctx.chatService.addMembers(member, group.chatId, listOf(orgUser))
        }
    }

    @Test
    fun `admin keeps unrestricted invite`() = runTest {
        val owner = ctx.registerUser()
        val stranger = ctx.registerUser()
        val group = ctx.chatService.createGroup("AdminInvite", null, owner, listOf(owner))

        ctx.chatService.addMembers(owner, group.chatId, listOf(stranger))

        assertTrue(ctx.chatService.getMembers(group.chatId).any { it.uid == stranger })
    }

    @Test
    fun `non group member cannot add members`() = runTest {
        val owner = ctx.registerUser()
        val outsider = ctx.registerUser()
        val target = ctx.registerUser()
        val group = ctx.chatService.createGroup("OutsiderAdd", null, owner, listOf(owner))

        assertFailsWith<ChatAccessDeniedException> {
            ctx.chatService.addMembers(outsider, group.chatId, listOf(target))
        }
    }

    @Test
    fun `user managed group still rejects manual addition`() = runTest {
        val leader = ctx.registerUser()
        val stranger = ctx.registerUser()
        // 在共享根下建子部门并启用部门群；受管群成员事实源是组织投影，手工加成员必须被拒。
        val unit = ctx.organizationService.createUnit(
            parentId = ensureSharedRoot(),
            name = "受管邀请围栏部门",
            leaderUid = leader,
            enableGroup = true,
        )
        val managedChatId = requireNotNull(unit.groupChatId)

        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.addMembers(leader, managedChatId, listOf(stranger))
        }
    }

    private suspend fun createChildUnit(name: String) =
        ctx.organizationService.createUnit(parentId = ensureSharedRoot(), name = name, leaderUid = null)

    /** 根节点 leader 只是占位（对断言无影响），保证整类用例共享单根。 */
    private suspend fun ensureSharedRoot(): String {
        sharedRootUnitId?.let { return it }
        val placeholderLeader = ctx.registerUser()
        val root = ctx.organizationService.createUnit(
            parentId = null, name = "邀请范围共享根", leaderUid = placeholderLeader,
        )
        sharedRootUnitId = root.unitId
        return root.unitId
    }

    private suspend fun makeFriends(uidA: String, uidB: String) {
        ctx.contactService(uidA).apply(uidB, null)
        val token = ctx.contactService(uidB).listPendingApplies()
            .single { it.fromUid == uidA }
            .token!!
        ctx.contactService(uidB).accept(token)
    }
}

package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.organization.OrganizationAccessDeniedException
import com.virjar.tk.server.domain.organization.OrganizationMemberRemovalConflictException
import com.virjar.tk.server.domain.organization.OrganizationChangePublisher
import com.virjar.tk.server.domain.organization.OrganizationRepository
import com.virjar.tk.server.domain.organization.OrganizationService
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationMemberPageRequest
import com.virjar.tk.protocol.model.OrganizationUnitPageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun `department group follows subtree and directory projects direct member counts`() = runTest {
        val leader = ctx.registerUser(uniqueUsername("org-leader"))
        val engineer = ctx.registerUser(uniqueUsername("org-engineer"))
        val directoryBeforeInvalidCreate = ctx.organizationService.listUnits()
        assertFailsWith<IllegalArgumentException> {
            ctx.organizationService.createUnit(null, "invalid sort", null, sortOrder = -1)
        }
        assertEquals(directoryBeforeInvalidCreate, ctx.organizationService.listUnits(), "拒绝无效创建不能留下组织节点")
        // 同类用例共享数据库；人数与移树断言只针对本用例拥有的子树，不依赖测试执行顺序。
        val root = directoryBeforeInvalidCreate.singleOrNull { it.parentId == null }
            ?: ctx.organizationService.createUnit(null, "Example Inc", null)
        val division = ctx.organizationService.createUnit(root.unitId, uniqueUsername("部门群测试"), null)
        val engineering = ctx.organizationService.createUnit(division.unitId, "研发", leader, enableGroup = true)
        val mobile = ctx.organizationService.createUnit(engineering.unitId, "移动端", null)
        val emptyDepartment = ctx.organizationService.createUnit(division.unitId, "空部门", null)
        assertFailsWith<IllegalArgumentException> {
            ctx.organizationService.updateUnit(mobile.unitId, engineering.unitId, mobile.name, null, sortOrder = -1)
        }
        assertEquals(0, ctx.organizationService.listUnits().single { it.unitId == mobile.unitId }.sortOrder)

        assertFailsWith<IllegalArgumentException> {
            ctx.organizationService.createUnit(null, "Another Root", null)
        }
        assertFailsWith<OrganizationMemberRemovalConflictException> {
            ctx.organizationService.removeMember(engineering.unitId, leader)
        }

        ctx.organizationService.assignMember(mobile.unitId, engineer, "客户端工程师", primary = true)

        val units = ctx.organizationService.listUnits().associateBy { it.unitId }
        assertEquals(0, units.getValue(division.unitId).directMemberCount)
        assertEquals(1, units.getValue(engineering.unitId).directMemberCount)
        assertEquals(1, units.getValue(mobile.unitId).directMemberCount)
        assertEquals(0, units.getValue(emptyDepartment.unitId).directMemberCount)
        assertEquals(
            2,
            ctx.organizationService.listMembers(division.unitId, recursive = true).size,
            "递归人数仍由显式 recursive 查询提供，目录行只展示直属人数",
        )

        val managed = units.getValue(engineering.unitId)
        assertEquals(engineering.unitId, managed.groupChatId, "部门节点 ID 同时作为稳定受管群 ID")
        assertEquals("研发部门群", ctx.chatService.getChat(managed.groupChatId!!)?.name)
        assertTrue(ctx.chatService.getMembers(managed.groupChatId!!).map { it.uid }.containsAll(listOf(leader, engineer)))

        val error = assertFailsWith<IllegalArgumentException> {
            ctx.chatService.leaveGroup(engineer, managed.groupChatId!!)
        }
        assertTrue(error.message.orEmpty().contains("维护"))

        // 子部门移出研发树后，成员和会话自动收敛；再移回时原 membership 无需重写即可恢复。
        ctx.organizationService.updateUnit(mobile.unitId, division.unitId, "移动端", null, 0)
        assertFalse(ctx.chatService.getMembers(managed.groupChatId!!).any { it.uid == engineer })
        assertEquals(null, ctx.conversationRepo.getConversation(engineer, managed.groupChatId!!))

        ctx.organizationService.updateUnit(mobile.unitId, engineering.unitId, "移动端", null, 0)
        assertTrue(ctx.chatService.getMembers(managed.groupChatId!!).any { it.uid == engineer })
        assertNotNull(ctx.conversationRepo.getConversation(engineer, managed.groupChatId!!))
    }

    @Test
    fun `organization directory requires valid membership and converges when membership is lost`() = runTest {
        val guest = ctx.registerUser(uniqueUsername("org-guest"))
        val member = ctx.registerUser(uniqueUsername("org-member"))
        val otherMember = ctx.registerUser(uniqueUsername("org-other"))
        val root = ctx.organizationService.listUnits().singleOrNull { it.parentId == null }
            ?: ctx.organizationService.createUnit(null, "权限公司", null)
        val team = ctx.organizationService.createUnit(root.unitId, uniqueUsername("团队"), null)

        // 未加入组织的访客不能读取组织目录与成员名册。
        assertFailsWith<OrganizationAccessDeniedException> {
            ctx.organizationService.listUnitPage(guest, OrganizationUnitPageRequest())
        }
        assertFailsWith<OrganizationAccessDeniedException> {
            ctx.organizationService.listMemberPage(guest, OrganizationMemberPageRequest(root.unitId, recursive = false))
        }

        ctx.organizationService.assignMember(team.unitId, member, null, primary = true)
        ctx.organizationService.assignMember(root.unitId, member, null, primary = false)
        ctx.organizationService.assignMember(team.unitId, otherMember, null, primary = true)

        // 存在有效组织成员关系后可以读取；两个部门归属移除其一仍保有资格。
        val visibleUnits = ctx.organizationService.listUnitPage(member, OrganizationUnitPageRequest()).items.size
        assertTrue(visibleUnits >= 2, "成员至少应看到根节点与其团队")
        assertTrue(
            ctx.organizationService.listMemberPage(member, OrganizationMemberPageRequest(team.unitId, recursive = false))
                .items.any { it.uid == otherMember },
        )
        ctx.organizationService.removeMember(team.unitId, member)
        assertTrue(
            ctx.organizationService.listUnitPage(member, OrganizationUnitPageRequest()).items.isNotEmpty(),
            "仅移除部分归属时仍保有组织成员资格",
        )

        // 失去最后一项归属立即恢复访客边界。
        ctx.organizationService.removeMember(root.unitId, member)
        assertFailsWith<OrganizationAccessDeniedException> {
            ctx.organizationService.listUnitPage(member, OrganizationUnitPageRequest())
        }
        assertFailsWith<OrganizationAccessDeniedException> {
            ctx.organizationService.listMemberPage(member, OrganizationMemberPageRequest(root.unitId, recursive = false))
        }

        // 其他成员不受影响；普通群成员关系不授予组织目录资格。
        assertTrue(
            ctx.organizationService.listUnitPage(otherMember, OrganizationUnitPageRequest()).items.isNotEmpty(),
        )
        ctx.organizationService.removeMember(team.unitId, otherMember)
        assertFailsWith<OrganizationAccessDeniedException> {
            ctx.organizationService.listUnitPage(otherMember, OrganizationUnitPageRequest())
        }
        val guestGroup = ctx.chatService.createGroup(
            operationId = UUID.randomUUID().toString(),
            name = "访客群",
            avatar = null,
            creatorUid = guest,
            memberUids = emptyList(),
        )
        ctx.chatService.addMembers(guest, guestGroup.chatId, listOf(otherMember))
        assertTrue(ctx.chatService.getMembers(guestGroup.chatId).any { it.uid == otherMember })
        // 加入普通聊天群不等于加入组织，目录读取资格不因此恢复。
        assertFailsWith<OrganizationAccessDeniedException> {
            ctx.organizationService.listUnitPage(otherMember, OrganizationUnitPageRequest())
        }
    }

    @Test
    fun `user organization paths respect viewer boundary and project department spine`() = runTest {
        val viewer = ctx.registerUser(uniqueUsername("t008-viewer"))
        val subject = ctx.registerUser(uniqueUsername("t008-subject"))
        val guest = ctx.registerUser(uniqueUsername("t008-guest"))
        val root = ctx.organizationService.listUnits().singleOrNull { it.parentId == null }
            ?: ctx.organizationService.createUnit(null, uniqueUsername("路径公司"), null)
        val dept = ctx.organizationService.createUnit(root.unitId, uniqueUsername("研发"), null)
        val team = ctx.organizationService.createUnit(dept.unitId, uniqueUsername("移动端"), null)

        // 查看者与被查看者都需要有效组织成员关系；访客不能经资料获取组织信息（T001 边界）。
        assertFailsWith<OrganizationAccessDeniedException> {
            ctx.organizationService.getUserOrganization(guest, subject)
        }
        ctx.organizationService.assignMember(team.unitId, viewer, null, primary = true)

        // 无归属账号返回空 paths，不构造虚构部门。
        assertTrue(ctx.organizationService.getUserOrganization(viewer, subject).paths.isEmpty())

        ctx.organizationService.assignMember(dept.unitId, subject, "顾问", primary = false)
        ctx.organizationService.assignMember(team.unitId, subject, null, primary = true)

        val summary = ctx.organizationService.getUserOrganization(viewer, subject)
        assertEquals(subject, summary.uid)
        assertEquals(2, summary.paths.size)
        // 主部门优先；路径从组织根到直属节点。
        val primaryPath = summary.paths.first()
        assertTrue(primaryPath.primary)
        assertEquals(team.unitId, primaryPath.unitId)
        assertEquals(listOf(root.name, dept.name, team.name), primaryPath.pathNames)
        val secondary = summary.paths.last()
        assertFalse(secondary.primary)
        assertEquals("顾问", secondary.title)
        assertEquals(listOf(root.name, dept.name), secondary.pathNames)

        // 失去最后一项归属后资料组织信息收敛为空。
        ctx.organizationService.removeMember(team.unitId, subject)
        ctx.organizationService.removeMember(dept.unitId, subject)
        assertTrue(ctx.organizationService.getUserOrganization(viewer, subject).paths.isEmpty())

        // 访客没有资格查看任何人的组织信息。
        assertFailsWith<OrganizationAccessDeniedException> {
            ctx.organizationService.getUserOrganization(guest, viewer)
        }
    }

    @Test
    fun `directory authorization and returned facts share a snapshot during membership revocation`() = runTest {
        for (query in listOf("units", "members", "profile")) {
            val viewer = ctx.registerUser(uniqueUsername("org-snapshot-viewer"))
            val subject = ctx.registerUser(uniqueUsername("org-snapshot-subject"))
            val root = ctx.organizationService.listUnits().singleOrNull { it.parentId == null }
                ?: ctx.organizationService.createUnit(null, uniqueUsername("快照公司"), null)
            val team = ctx.organizationService.createUnit(root.unitId, uniqueUsername("原部门"), null)
            ctx.organizationService.assignMember(team.unitId, viewer, null, primary = true)
            ctx.organizationService.assignMember(team.unitId, subject, null, primary = true)

            val membershipRead = CountDownLatch(1)
            val continueRead = CountDownLatch(1)
            val repository = object : OrganizationRepository by ctx.organizationRepo {
                override fun listMemberships(uid: String, transaction: PgReadTransactionContext?): List<OrganizationMember> {
                    val memberships = ctx.organizationRepo.listMemberships(uid, transaction)
                    if (uid == viewer) {
                        membershipRead.countDown()
                        check(continueRead.await(10, TimeUnit.SECONDS))
                    }
                    return memberships
                }
            }
            val service = OrganizationService(
                repository, ctx.userRepo, ctx.pgUnitOfWork, ctx.organizationProjector,
                OrganizationChangePublisher { },
            )
            val observed = async(Dispatchers.Default) {
                when (query) {
                    "units" -> service.listUnitPage(viewer, OrganizationUnitPageRequest())
                        .items.single { it.unitId == team.unitId }.name
                    "members" -> service.listMemberPage(viewer, OrganizationMemberPageRequest(team.unitId, false))
                        .items.single { it.uid == viewer }.uid
                    else -> service.getUserOrganization(viewer, subject).paths.single().pathNames.last()
                }
            }
            try {
                check(membershipRead.await(10, TimeUnit.SECONDS))
                // 权限已读、正文尚未读时提交撤权与改名；正在执行的读取必须完整保留原快照。
                ctx.organizationService.removeMember(team.unitId, viewer)
                ctx.organizationService.updateUnit(team.unitId, root.unitId, "${team.name}-updated", null, 0)
            } finally {
                continueRead.countDown()
            }
            assertEquals(if (query == "members") viewer else team.name, observed.await())
            // 后来的请求使用新快照，不能沿用上一请求的资格。
            assertFailsWith<OrganizationAccessDeniedException> {
                when (query) {
                    "units" -> ctx.organizationService.listUnitPage(viewer, OrganizationUnitPageRequest())
                    "members" -> ctx.organizationService.listMemberPage(viewer, OrganizationMemberPageRequest(team.unitId, false))
                    else -> ctx.organizationService.getUserOrganization(viewer, subject)
                }
            }
        }
    }
}

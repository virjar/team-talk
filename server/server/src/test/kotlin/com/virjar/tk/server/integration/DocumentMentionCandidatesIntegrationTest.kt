package com.virjar.tk.server.integration

import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 内测反馈 T047 第二阶段：文档 @ 候选 = 空间 USER 授权人 + 组织成员搜索，访客 fail-closed。 */
class DocumentMentionCandidatesIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `granted users are candidates and outsiders cannot call`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("mc-owner"))
        val bob = ctx.registerUser(uniqueUsername("mc-bob"))
        val outsider = ctx.registerUser(uniqueUsername("mc-out"))
        val space = ctx.documentService.createSpace(owner, "候选空间", null)

        ctx.documentService.upsertGrant(
            owner,
            space.spaceId,
            DocumentSpaceGrant.PRINCIPAL_USER,
            bob,
            DocumentSpace.ROLE_VIEWER,
            false,
            space.policyRevision,
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
        )

        // 群外人员（无空间访问权）调用直接拒绝
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.mentionCandidates(outsider, space.spaceId, "")
        }

        // owner 的候选包含授权人 bob；query 过滤命中
        val all = ctx.documentService.mentionCandidates(owner, space.spaceId, "")
        assertTrue(all.any { it.uid == bob }, "空 query 应返回授权用户窗口")
        val bobUser = ctx.userService.getProfile(bob)
        val filtered = ctx.documentService.mentionCandidates(owner, space.spaceId, bobUser.username.take(6))
        assertTrue(filtered.any { it.uid == bob })
        // owner 自己不会出现在候选里
        assertFalse(all.any { it.uid == owner })
        // outsider 不是授权人也不在组织，绝不会成为候选
        assertFalse(filtered.any { it.uid == outsider })
    }

    @Test
    fun `org members are searchable only for org members`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("mc2-owner"))
        val orgMate = ctx.registerUser(uniqueUsername("mc2-mate"))
        val outsider = ctx.registerUser(uniqueUsername("mc2-out"))
        val unit = ctx.organizationService.createUnit(parentId = null, name = "候选部门-" + UUID.randomUUID(), leaderUid = null, sortOrder = 0)
        ctx.organizationService.assignMember(unit.unitId, orgMate, null, primary = false)

        val space = ctx.documentService.createSpace(owner, "组织候选空间", null)
        // outsider 无空间访问权
        assertFailsWith<DocumentAccessDeniedException> {
            ctx.documentService.mentionCandidates(outsider, space.spaceId, "")
        }

        val mateName = ctx.userService.getProfile(orgMate).username.take(8)

        // owner 不是组织成员 → 组织搜索 fail-closed：即使按 orgMate 名字搜也搜不到
        val ownerResults = ctx.documentService.mentionCandidates(owner, space.spaceId, mateName)
        assertFalse(ownerResults.any { it.uid == orgMate })

        // 把 owner 拉进组织后，组织成员可被搜到
        ctx.organizationService.assignMember(unit.unitId, owner, null, primary = false)
        val results = ctx.documentService.mentionCandidates(owner, space.spaceId, mateName)
        assertTrue(results.any { it.uid == orgMate }, "组织成员应可被按名搜索")
    }

    private fun uniqueUsername(base: String): String = "$base-${UUID.randomUUID().toString().take(8)}"
}

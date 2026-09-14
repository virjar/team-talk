package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.chat.InviteLinkRecord
import com.virjar.tk.server.domain.chat.InviteLinkPolicy
import com.virjar.tk.protocol.model.InvitePreview
import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.infra.db.Chats
import com.virjar.tk.server.infra.db.GroupInviteLinks
import com.virjar.tk.server.infra.db.GroupMembers
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.protocol.rpc.ChatRpcImpl
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteLinkIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `non member previews only group summary without consuming invite or granting content access`() = runTest {
        val (creator, chatId) = setupGroup()
        val guest = ctx.registerUser()
        val token = ctx.chatService.createInviteLink(creator, chatId, "工作群邀请", 1, 0)
        val eventsBefore = transaction(ctx.database) { SyncEvents.selectAll().count() }
        val rpc = ChatRpcImpl(guest, ctx.chatService)
        repeat(2) {
            assertEquals(InvitePreview(InvitePreview.VALID, chatId, "TestGroup", 2), rpc.previewInvite(token))
        }
        assertEquals(0, ctx.chatService.getInviteInfo(token).useCount)
        assertEquals(eventsBefore, transaction(ctx.database) { SyncEvents.selectAll().count() })
        assertFailsWith<ChatAccessDeniedException> { ctx.chatService.getChatFor(guest, chatId) }
        assertFailsWith<ChatAccessDeniedException> { ctx.chatService.getMembersFor(guest, chatId) }
        assertTrue(ctx.chatService.getGroupAvatars(guest, listOf(chatId)).isEmpty())
        assertEquals(InvitePreview(InvitePreview.NOT_FOUND), rpc.previewInvite("invalid"))
        assertEquals(InvitePreview(InvitePreview.NOT_FOUND), rpc.previewInvite(java.util.UUID.randomUUID().toString()))

        // 预览不是加入授权：确认前撤销后，重读和实际加入都立即看到当前事实。
        ctx.chatService.revokeInviteLink(creator, token)
        assertEquals(InvitePreview(InvitePreview.REVOKED), rpc.previewInvite(token))
        assertFailsWith<IllegalArgumentException> { rpc.joinByInvite(token) }
    }

    @Test
    fun `joined member can open and retry after invite is exhausted revoked or expired`() = runTest {
        val (creator, chatId) = setupGroup()
        val guest = ctx.registerUser()
        val outsider = ctx.registerUser()
        val token = ctx.chatService.createInviteLink(creator, chatId, "一次邀请", 1, 0)
        assertEquals(chatId, ctx.chatService.joinByInvite(guest, token).chatId)
        assertEquals(1, ctx.chatService.getInviteInfo(token).useCount)
        val joined = ctx.chatService.previewInvite(guest, token)
        assertEquals(InvitePreview.EXHAUSTED, joined.status)
        assertTrue(joined.alreadyJoined)
        assertEquals(chatId, joined.chatId)
        assertEquals(3, joined.memberCount)
        assertEquals(InvitePreview(InvitePreview.EXHAUSTED), ctx.chatService.previewInvite(outsider, token))
        val eventsAfterJoin = transaction(ctx.database) { SyncEvents.selectAll().count() }
        assertEquals(chatId, ctx.chatService.joinByInvite(guest, token).chatId)
        transaction(ctx.database) {
            GroupInviteLinks.update({ GroupInviteLinks.token eq token }) { it[expiresAt] = 1L }
        }
        assertEquals(InvitePreview.EXPIRED, ctx.chatService.previewInvite(guest, token).status)
        assertEquals(InvitePreview(InvitePreview.EXPIRED), ctx.chatService.previewInvite(outsider, token))
        assertEquals(chatId, ctx.chatService.joinByInvite(guest, token).chatId)
        ctx.chatService.revokeInviteLink(creator, token)
        assertEquals(InvitePreview.REVOKED, ctx.chatService.previewInvite(guest, token).status)
        assertEquals(chatId, ctx.chatService.joinByInvite(guest, token).chatId)
        assertEquals(1, ctx.chatService.getInviteInfo(token).useCount)
        assertEquals(eventsAfterJoin, transaction(ctx.database) { SyncEvents.selectAll().count() })

        val expired = ctx.chatService.createInviteLink(creator, chatId, "过期邀请", 0, 1L)
        assertEquals(InvitePreview(InvitePreview.EXPIRED), ctx.chatService.previewInvite(outsider, expired))
        assertEquals(InvitePreview.EXPIRED, ctx.chatService.previewInvite(guest, expired).status)
        assertEquals(chatId, ctx.chatService.joinByInvite(guest, expired).chatId)
    }

    @Test
    fun `preview reads membership from current database and hides unavailable group`() = runTest {
        val (creator, chatId) = setupGroup()
        val guest = ctx.registerUser()
        val token = ctx.chatService.createInviteLink(creator, chatId, "最新状态", 0, 0)
        ctx.chatService.joinByInvite(guest, token)
        assertTrue(ctx.chatStore.getMembers(chatId).any { it.uid == guest })
        // 模拟另一个进程的已提交成员撤销，不发布本进程缓存失效。
        transaction(ctx.database) {
            GroupMembers.update({ (GroupMembers.chatId eq chatId) and (GroupMembers.uid eq guest) }) {
                it[status] = 0
            }
        }
        val preview = ctx.chatService.previewInvite(guest, token)
        assertFalse(preview.alreadyJoined)
        assertEquals(2, preview.memberCount)
        transaction(ctx.database) {
            Chats.update({ Chats.chatId eq chatId }) { it[status] = 0 }
        }
        val unavailable = ctx.chatService.previewInvite(guest, token)
        assertEquals(InvitePreview.GROUP_UNAVAILABLE, unavailable.status)
        assertNull(unavailable.chatId)
        assertFailsWith<IllegalArgumentException> { ctx.chatService.joinByInvite(guest, token) }
    }

    @Test
    fun `invite aggregate is bounded and a revoked slot is retired before reuse`() = runTest {
        val creator = ctx.registerUser(uniqueUsername("invite-cap-owner"), "password123")
        val chatId = ctx.chatService.createGroup("invite-cap", null, creator, emptyList()).chatId
        val tokens = List(InviteLinkPolicy.MAX_LINKS_PER_CHAT) { index ->
            ctx.chatService.createInviteLink(creator, chatId, "link-$index", 0, 0)
        }

        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.createInviteLink(creator, chatId, "overflow", 0, 0)
        }
        ctx.chatService.revokeInviteLink(creator, tokens.first())
        val replacement = ctx.chatService.createInviteLink(creator, chatId, "replacement", 0, 0)

        val retained = ctx.chatService.listInviteLinks(creator, chatId)
        assertEquals(InviteLinkPolicy.MAX_LINKS_PER_CHAT, retained.size)
        assertTrue(retained.none { it.token == tokens.first() })
        assertTrue(retained.any { it.token == replacement })
    }

    private suspend fun setupGroup(): Pair<String, String> {
        val creator = ctx.registerUser()
        val member = ctx.registerUser()
        val group = ctx.chatService.createGroup("TestGroup", null, creator, listOf(member))
        return creator to group.chatId
    }

    @Test
    fun `create invite link`() = runTest {
        val (creator, chatId) = setupGroup()
        val token = ctx.chatService.createInviteLink(creator, chatId, "test-link", 0, 0)
        assertNotNull(token)
        assertTrue(token.isNotEmpty())
    }

    @Test
    fun `list invite links`() = runTest {
        val (creator, chatId) = setupGroup()
        ctx.chatService.createInviteLink(creator, chatId, "link1", 0, 0)
        ctx.chatService.createInviteLink(creator, chatId, "link2", 10, 0)

        val links = ctx.chatService.listInviteLinks(creator, chatId)
        assertEquals(2, links.size)
    }

    @Test
    fun `revoke invite link`() = runTest {
        val (creator, chatId) = setupGroup()
        val token = ctx.chatService.createInviteLink(creator, chatId, "revoke-me", 0, 0)
        ctx.chatService.revokeInviteLink(creator, token)

        val link = ctx.chatService.getInviteInfo(token)
        assertNotNull(link.revokedAt)
    }

    @Test
    fun `get invite info`() = runTest {
        val (creator, chatId) = setupGroup()
        val token = ctx.chatService.createInviteLink(creator, chatId, "info-link", 5, 0)
        val info = ctx.chatService.getInviteInfo(token)

        assertEquals(chatId, info.chatId)
        assertEquals(creator, info.creatorUid)
        assertEquals("info-link", info.name)
        assertEquals(5, info.maxUses)
    }

    @Test
    fun `revoke non-existent link throws`() = runTest {
        val (creator, _) = setupGroup()
        var caught = false
        try {
            ctx.chatService.revokeInviteLink(creator, "non-existent-token")
        } catch (_: IllegalArgumentException) {
            caught = true
        }
        assertTrue(caught)
    }
}

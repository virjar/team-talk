package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.chat.InviteLinkRecord
import com.virjar.tk.server.domain.chat.InviteLinkPolicy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InviteLinkIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

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

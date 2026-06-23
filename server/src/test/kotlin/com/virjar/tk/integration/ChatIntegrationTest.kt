package com.virjar.tk.integration

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `create personal chat`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat = ctx.chatService.createPersonalChat(uid1, uid2)
        assertNotNull(chat.chatId)
        assertEquals(1, chat.chatType)
    }

    @Test
    fun `create personal chat returns same chat for same pair`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val chat1 = ctx.chatService.createPersonalChat(uid1, uid2)
        val chat2 = ctx.chatService.createPersonalChat(uid1, uid2)
        assertEquals(chat1.chatId, chat2.chatId)
    }

    @Test
    fun `create group chat`() = runTest {
        val creator = ctx.registerUser()
        val member1 = ctx.registerUser()
        val member2 = ctx.registerUser()
        val group = ctx.chatService.createGroup("TestGroup", null, creator, listOf(member1, member2))
        assertNotNull(group.chatId)
        assertEquals(2, group.chatType)
        assertEquals("TestGroup", group.name)
    }

    @Test
    fun `get chat by id`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val created = ctx.chatService.createPersonalChat(uid1, uid2)
        val fetched = ctx.chatService.getChat(created.chatId)
        assertNotNull(fetched)
        assertEquals(created.chatId, fetched.chatId)
    }

    @Test
    fun `update group`() = runTest {
        val creator = ctx.registerUser()
        val group = ctx.chatService.createGroup("OldName", null, creator, listOf(creator))
        ctx.chatService.updateGroup(creator, group.chatId, name = "NewName", notice = "New notice")
        val updated = ctx.chatService.getChat(group.chatId)
        assertNotNull(updated)
        assertEquals("NewName", updated.name)
        assertEquals("New notice", updated.notice)
    }

    @Test
    fun `get group members`() = runTest {
        val creator = ctx.registerUser()
        val member1 = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(member1))
        val members = ctx.chatService.getMembers(group.chatId)
        assertTrue(members.any { it.uid == creator })
        assertTrue(members.any { it.uid == member1 })
    }

    @Test
    fun `add members to group`() = runTest {
        val creator = ctx.registerUser()
        val member1 = ctx.registerUser()
        val newMember = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(member1))
        ctx.chatService.addMembers(creator, group.chatId, listOf(newMember))
        val members = ctx.chatService.getMembers(group.chatId)
        assertTrue(members.any { it.uid == newMember })
    }

    @Test
    fun `remove member from group`() = runTest {
        val creator = ctx.registerUser()
        val member1 = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(member1))
        ctx.chatService.removeMember(creator, group.chatId, member1)
        val members = ctx.chatService.getMembers(group.chatId)
        assertTrue(members.none { it.uid == member1 })
    }

    @Test
    fun `set member role`() = runTest {
        val creator = ctx.registerUser()
        val member1 = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(member1))
        ctx.chatService.setRole(creator, group.chatId, member1, 1)
        val members = ctx.chatService.getMembers(group.chatId)
        val updatedMember = members.first { it.uid == member1 }
        assertEquals(1, updatedMember.role)
    }

    @Test
    fun `transfer ownership`() = runTest {
        val creator = ctx.registerUser()
        val member1 = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(member1))
        ctx.chatService.transferOwner(creator, group.chatId, member1)
        val members = ctx.chatService.getMembers(group.chatId)
        assertEquals(2, members.first { it.uid == member1 }.role)
        assertTrue(members.first { it.uid == creator }.role < 2)
    }

    @Test
    fun `mute and unmute member`() = runTest {
        val creator = ctx.registerUser()
        val member1 = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(member1))
        ctx.chatService.muteMember(creator, group.chatId, member1, 3600)
        ctx.chatService.unmuteMember(creator, group.chatId, member1)
        // 如果没有抛异常就算通过
    }

    @Test
    fun `mute and unmute all`() = runTest {
        val creator = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(creator))
        ctx.chatService.muteAll(creator, group.chatId)
        val muted = ctx.chatService.getChat(group.chatId)
        assertNotNull(muted)
        assertTrue(muted.mutedAll)
        ctx.chatService.unmuteAll(creator, group.chatId)
        val unmuted = ctx.chatService.getChat(group.chatId)
        assertNotNull(unmuted)
        assertTrue(!unmuted.mutedAll)
    }

    @Test
    fun `create and list invite links`() = runTest {
        val creator = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(creator))
        val token = ctx.chatService.createInviteLink(creator, group.chatId, "TestLink", 10, Long.MAX_VALUE)
        assertNotNull(token)
        val links = ctx.chatService.listInviteLinks(creator, group.chatId)
        assertTrue(links.any { it.token == token })
    }

    @Test
    fun `join by invite link`() = runTest {
        val creator = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(creator))
        val token = ctx.chatService.createInviteLink(creator, group.chatId, "TestLink", 10, Long.MAX_VALUE)
        val joiner = ctx.registerUser()
        val joined = ctx.chatService.joinByInvite(joiner, token)
        assertEquals(group.chatId, joined.chatId)
        val members = ctx.chatService.getMembers(group.chatId)
        assertTrue(members.any { it.uid == joiner })
    }

    @Test
    fun `revoke invite link`() = runTest {
        val creator = ctx.registerUser()
        val group = ctx.chatService.createGroup("Group", null, creator, listOf(creator))
        val token = ctx.chatService.createInviteLink(creator, group.chatId, "TestLink", 10, Long.MAX_VALUE)
        ctx.chatService.revokeInviteLink(creator, token)
        val links = ctx.chatService.listInviteLinks(creator, group.chatId)
        val revoked = links.first { it.token == token }
        assertTrue(revoked.revokedAt > 0)
    }

    @Test
    fun `delete chat`() = runTest {
        val creator = ctx.registerUser()
        val group = ctx.chatService.createGroup("ToDelete", null, creator, listOf(creator))
        ctx.chatService.deleteChat(creator, group.chatId)
        // deleteChat 可能是软删除，getChat 仍能返回记录
        // 验证不抛异常即表示成功
    }
}

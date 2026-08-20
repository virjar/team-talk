package com.virjar.tk.integration

import com.virjar.tk.body.RichTextBody
import com.virjar.tk.domain.bot.BotAuthenticationException
import com.virjar.tk.domain.bot.BotAuthorizationException
import com.virjar.tk.domain.bot.BotRequestException
import com.virjar.tk.model.UserRole
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `bot delivery requires credentials and grant and is idempotent`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-owner"))
        val member = ctx.registerUser(uniqueUsername("bot-member"))
        val group = ctx.chatService.createGroup("发布通知", null, owner, listOf(member))
        val created = ctx.botService.create("发布机器人")

        assertEquals(UserRole.BOT, ctx.userRepo.findByUid(created.bot.userUid)?.role)
        assertFailsWith<BotAuthenticationException> {
            ctx.botService.deliver(created.bot.botId, "wrong-token", group.chatId, "hello", "wrong-token")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.botService.deliver(created.bot.botId, created.webhookToken, group.chatId, "hello", "before-grant")
        }

        ctx.botService.grant(created.bot.botId, group.chatId)
        assertTrue(ctx.chatService.getMembers(group.chatId).any { it.uid == created.bot.userUid })

        val first = ctx.botService.deliver(
            created.bot.botId,
            created.webhookToken,
            group.chatId,
            "## 构建完成\n\n版本 `1.2.3` 已发布。",
            "deploy-1.2.3",
        )
        val retried = ctx.botService.deliver(
            created.bot.botId,
            created.webhookToken,
            group.chatId,
            "## 构建完成\n\n版本 `1.2.3` 已发布。",
            "deploy-1.2.3",
        )
        assertEquals(first.serverSeq, retried.serverSeq)
        assertEquals(first.clientMsgId, retried.clientMsgId)
        assertFailsWith<BotRequestException> {
            ctx.botService.deliver(created.bot.botId, created.webhookToken, group.chatId, "invalid key", "")
        }
        assertFailsWith<BotRequestException> {
            ctx.botService.deliver(
                created.bot.botId,
                created.webhookToken,
                group.chatId,
                "invalid key",
                "x".repeat(com.virjar.tk.domain.bot.BotService.MAX_IDEMPOTENCY_KEY_LENGTH + 1),
            )
        }

        val unread = ctx.conversationService.listConversations(member).single { it.chatId == group.chatId }
        assertTrue(unread.unreadCount > 0, "机器人消息应形成目标人类成员的未读")
        ctx.conversationService.markRead(member, group.chatId, first.serverSeq)
        val read = ctx.conversationService.listConversations(member).single { it.chatId == group.chatId }
        assertEquals(first.serverSeq, read.readSeq)
        assertEquals(0, read.unreadCount, "markRead 到机器人消息 seq 后应清零未读")

        val history = ctx.messageService.getHistory(owner, group.chatId, Long.MAX_VALUE, 10)
        val delivered = history.single { it.clientMsgId == first.clientMsgId }
        assertEquals(created.bot.userUid, delivered.senderUid)
        assertEquals("## 构建完成\n\n版本 `1.2.3` 已发布。", (delivered.body as RichTextBody).markdown)

        ctx.botService.revokeGrant(created.bot.botId, group.chatId)
        assertFalse(ctx.chatService.getMembers(group.chatId).any { it.uid == created.bot.userUid })
        assertFailsWith<IllegalArgumentException> {
            ctx.botService.deliver(created.bot.botId, created.webhookToken, group.chatId, "hello", "after-revoke")
        }
    }

    @Test
    fun `department reconciliation preserves explicitly granted bot`() = runTest {
        val leader = ctx.registerUser(uniqueUsername("bot-org-leader"))
        val root = ctx.organizationService.createUnit(null, "Example Inc", null)
        val unit = ctx.organizationService.createUnit(root.unitId, "运维", leader, enableGroup = true)
        val created = ctx.botService.create("监控机器人")

        ctx.botService.grant(created.bot.botId, unit.groupChatId!!)
        assertTrue(ctx.organizationService.reconcileAllManagedGroups().isEmpty())

        assertTrue(ctx.chatService.getMembers(unit.groupChatId!!).any { it.uid == created.bot.userUid })
        assertTrue(ctx.botService.recoverGrantMemberships().isEmpty())

        ctx.organizationService.disableDepartmentGroup(unit.unitId)
        val reenabled = ctx.organizationService.enableDepartmentGroup(unit.unitId)
        assertEquals(unit.unitId, reenabled.groupChatId)
        assertFalse(ctx.chatService.getMembers(unit.unitId).any { it.uid == created.bot.userUid })
        assertFailsWith<BotAuthorizationException> {
            ctx.botService.deliver(
                created.bot.botId,
                created.webhookToken,
                unit.unitId,
                "停用后不应恢复",
                "department-reenabled",
            )
        }
    }

    @Test
    fun `dissolving a group disables owned bot and revokes system bot grant`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-dissolve-owner"))
        val member = ctx.registerUser(uniqueUsername("bot-dissolve-member"))
        val group = ctx.chatService.createGroup("待解散群", null, owner, listOf(member))
        val owned = ctx.botService.createForGroup(member, group.chatId, "群内机器人")
        val system = ctx.botService.create("系统机器人")
        ctx.botService.grant(system.bot.botId, group.chatId)

        ctx.chatService.dissolveGroup(owner, group.chatId)

        assertFailsWith<BotAuthenticationException> {
            ctx.botService.deliver(owned.bot.botId, owned.webhookToken, group.chatId, "不应发送", "owned-after-delete")
        }
        assertFailsWith<BotAuthorizationException> {
            ctx.botService.deliver(system.bot.botId, system.webhookToken, group.chatId, "不应发送", "system-after-delete")
        }
    }

    @Test
    fun `every group member can create while credentials remain creator scoped`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-group-owner"))
        val creator = ctx.registerUser(uniqueUsername("bot-group-creator"))
        val member = ctx.registerUser(uniqueUsername("bot-group-member"))
        val admin = ctx.registerUser(uniqueUsername("bot-group-admin"))
        val outsider = ctx.registerUser(uniqueUsername("bot-group-outsider"))
        val group = ctx.chatService.createGroup("工程协作", null, owner, listOf(creator, member, admin))
        ctx.chatService.setRole(owner, group.chatId, admin, 1)

        val personalChat = ctx.chatService.createPersonalChat(creator, member)
        val botCountBeforePersonalAttempt = ctx.botService.list().size
        assertFailsWith<BotAuthorizationException> {
            ctx.botService.createForGroup(creator, personalChat.chatId, "错误的私聊机器人")
        }
        assertEquals(botCountBeforePersonalAttempt, ctx.botService.list().size, "私聊请求不应留下服务账号机器人")

        val created = ctx.botService.createForGroup(creator, group.chatId, "流水线通知")
        assertTrue(created.webhookToken.startsWith("ttb_"))
        assertEquals(
            "/api/v1/groups/${group.chatId}/bots/${created.bot.botId}/messages",
            created.bot.apiPath,
        )
        assertTrue(created.bot.createdByMe)
        assertTrue(created.bot.canRotateToken)
        assertTrue(created.bot.canRemove)
        assertTrue(ctx.chatService.getMembers(group.chatId).any { it.uid == ctx.botService.list()
            .single { bot -> bot.botId == created.bot.botId }.userUid })
        val botUid = ctx.botService.list().single { bot -> bot.botId == created.bot.botId }.userUid
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.removeMember(owner, group.chatId, botUid)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.setRole(owner, group.chatId, botUid, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.transferOwner(owner, group.chatId, botUid)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.muteMember(owner, group.chatId, botUid, 60)
        }

        val ordinaryView = ctx.botService.listForGroup(member, group.chatId).single()
        assertFalse(ordinaryView.createdByMe)
        assertFalse(ordinaryView.canRotateToken)
        assertFalse(ordinaryView.canRemove)

        val ownerView = ctx.botService.listForGroup(owner, group.chatId).single()
        assertFalse(ownerView.canRotateToken, "群主不能接管别人机器人的外部凭据")
        assertTrue(ownerView.canRemove)
        val adminView = ctx.botService.listForGroup(admin, group.chatId).single()
        assertTrue(adminView.canRemove)

        assertFailsWith<BotAuthorizationException> {
            ctx.botService.listForGroup(outsider, group.chatId)
        }
        assertFailsWith<BotAuthorizationException> {
            ctx.botService.rotateTokenForGroup(member, group.chatId, created.bot.botId)
        }
        assertFailsWith<BotAuthorizationException> {
            ctx.botService.removeFromGroup(member, group.chatId, created.bot.botId)
        }

        val otherGroup = ctx.chatService.createGroup("另一项目", null, owner, listOf(creator))
        assertFailsWith<BotAuthorizationException> {
            ctx.botService.grant(created.bot.botId, otherGroup.chatId)
        }

        val rotated = ctx.botService.rotateTokenForGroup(creator, group.chatId, created.bot.botId)
        assertFailsWith<BotAuthenticationException> {
            ctx.botService.deliver(
                created.bot.botId,
                created.webhookToken,
                group.chatId,
                "old token",
                "old-token",
            )
        }
        val delivered = ctx.botService.deliver(
            created.bot.botId,
            rotated.webhookToken,
            group.chatId,
            "## 自动构建完成",
            "build-1",
        )
        assertTrue(delivered.serverSeq > 0)

        ctx.botService.removeFromGroup(admin, group.chatId, created.bot.botId)
        assertTrue(ctx.botService.listForGroup(owner, group.chatId).isEmpty())
        assertFailsWith<BotAuthenticationException> {
            ctx.botService.deliver(
                created.bot.botId,
                rotated.webhookToken,
                group.chatId,
                "after remove",
                "after-remove",
            )
        }

        val systemBot = ctx.botService.create("系统下发通知")
        ctx.botService.grant(systemBot.bot.botId, group.chatId)
        val systemView = ctx.botService.listForGroup(member, group.chatId).single()
        assertEquals(
            "/api/v1/groups/${group.chatId}/bots/${systemView.botId}/messages",
            systemView.apiPath,
        )
        assertFalse(systemView.groupManaged)
        assertFalse(systemView.canRotateToken)
        assertFalse(systemView.canRemove)
        assertFailsWith<BotAuthorizationException> {
            ctx.botService.removeFromGroup(owner, group.chatId, systemBot.bot.botId)
        }
    }

    @Test
    fun `group bot quota limits one creator without reserving the group for admins`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("bot-quota-owner"))
        val creator = ctx.registerUser(uniqueUsername("bot-quota-creator"))
        val teammate = ctx.registerUser(uniqueUsername("bot-quota-teammate"))
        val group = ctx.chatService.createGroup("机器人配额", null, owner, listOf(creator, teammate))

        val owned = (1..com.virjar.tk.domain.bot.BotService.MAX_MANAGED_BOTS_PER_CREATOR_IN_GROUP).map { index ->
            ctx.botService.createForGroup(creator, group.chatId, "创建者机器人-$index")
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.botService.createForGroup(creator, group.chatId, "超过个人配额")
        }

        val teammateBot = ctx.botService.createForGroup(teammate, group.chatId, "团队成员机器人")
        assertTrue(teammateBot.bot.createdByMe)

        ctx.botService.removeFromGroup(creator, group.chatId, owned.first().bot.botId)
        val replacement = ctx.botService.createForGroup(creator, group.chatId, "释放配额后创建")
        assertTrue(replacement.bot.createdByMe)
    }
}

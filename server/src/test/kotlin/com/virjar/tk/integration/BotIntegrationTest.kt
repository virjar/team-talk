package com.virjar.tk.integration

import com.virjar.tk.body.RichTextBody
import com.virjar.tk.domain.bot.BotAuthenticationException
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
            "重复请求不会生成第二条消息",
            "deploy-1.2.3",
        )
        assertEquals(first.serverSeq, retried.serverSeq)
        assertEquals(first.clientMsgId, retried.clientMsgId)

        val history = ctx.messageService.getHistory(owner, group.chatId, Long.MAX_VALUE, 20)
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
        assertTrue(ctx.botService.reconcileGrants().isEmpty())
    }
}

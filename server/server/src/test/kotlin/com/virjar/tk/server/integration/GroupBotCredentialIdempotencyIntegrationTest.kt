package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.bot.BotCredentialCommandConflictException
import com.virjar.tk.server.domain.bot.BotCredentialCommandTerminalException
import com.virjar.tk.server.infra.db.AutomationBots
import com.virjar.tk.server.infra.db.BotCredentialCommands
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class GroupBotCredentialIdempotencyIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `create replay returns one bot and one secret-free receipt`() = runTest {
        val actor = ctx.registerUser(uniqueUsername("bot-command-create"))
        val group = ctx.chatService.createGroup("Credential create", null, actor, emptyList())
        val operationId = UUID.randomUUID().toString()
        val token = newGroupBotTestToken()
        val before = ctx.botService.list().size

        val first = ctx.botService.createForGroup(actor, group.chatId, operationId, "Build bot", token)
        val replay = ctx.botService.createForGroup(actor, group.chatId, operationId, "Build bot", token)

        assertEquals(first, replay)
        assertEquals(before + 1, ctx.botService.list().size)
        transaction(ctx.database) {
            val receipt = BotCredentialCommands.selectAll().where {
                (BotCredentialCommands.actorUid eq actor) and
                    (BotCredentialCommands.operationId eq operationId)
            }.single()
            assertEquals(first.bot.botId, receipt[BotCredentialCommands.botId])
            assertEquals(64, receipt[BotCredentialCommands.tokenHash].length)
            assertFalse(token == receipt[BotCredentialCommands.tokenHash])
            assertEquals(
                receipt[BotCredentialCommands.tokenHash],
                AutomationBots.selectAll().where { AutomationBots.botId eq first.bot.botId }
                    .single()[AutomationBots.tokenHash],
            )
        }

        assertFailsWith<BotCredentialCommandConflictException> {
            ctx.botService.createForGroup(actor, group.chatId, operationId, "Different bot", token)
        }
        assertFailsWith<BotCredentialCommandConflictException> {
            ctx.botService.createForGroup(actor, group.chatId, operationId, "Build bot", newGroupBotTestToken())
        }
        assertFailsWith<BotCredentialCommandConflictException> {
            ctx.botService.createForGroup(
                actor,
                "missing-chat",
                operationId,
                "Build bot",
                token,
            )
        }
        assertFailsWith<BotCredentialCommandConflictException> {
            ctx.botService.rotateTokenForGroup(actor, group.chatId, "missing-bot", operationId, token)
        }
        ctx.botService.rotateTokenForGroup(
            actor,
            group.chatId,
            first.bot.botId,
            UUID.randomUUID().toString(),
            newGroupBotTestToken(),
        )
        assertFailsWith<BotCredentialCommandTerminalException> {
            ctx.botService.createForGroup(actor, group.chatId, operationId, "Build bot", token)
        }
        assertEquals(before + 1, ctx.botService.list().size)
    }

    @Test
    fun `rotate replay preserves one valid token and rejects a superseded receipt`() = runTest {
        val actor = ctx.registerUser(uniqueUsername("bot-command-rotate"))
        val group = ctx.chatService.createGroup("Credential rotate", null, actor, emptyList())
        val created = ctx.botService.createGroupBotForTest(actor, group.chatId, "Deploy bot")
        val firstOperationId = UUID.randomUUID().toString()
        val firstToken = newGroupBotTestToken()

        val first = ctx.botService.rotateTokenForGroup(
            actor,
            group.chatId,
            created.bot.botId,
            firstOperationId,
            firstToken,
        )
        val replay = ctx.botService.rotateTokenForGroup(
            actor,
            group.chatId,
            created.bot.botId,
            firstOperationId,
            firstToken,
        )
        assertEquals(first, replay)
        ctx.botService.deliver(created.bot.botId, firstToken, group.chatId, "first", "rotate-first")

        assertFailsWith<BotCredentialCommandConflictException> {
            ctx.botService.rotateTokenForGroup(
                actor,
                group.chatId,
                created.bot.botId,
                firstOperationId,
                newGroupBotTestToken(),
            )
        }

        val secondOperationId = UUID.randomUUID().toString()
        val secondToken = newGroupBotTestToken()
        ctx.botService.rotateTokenForGroup(
            actor,
            group.chatId,
            created.bot.botId,
            secondOperationId,
            secondToken,
        )
        assertFailsWith<BotCredentialCommandTerminalException> {
            ctx.botService.rotateTokenForGroup(
                actor,
                group.chatId,
                created.bot.botId,
                firstOperationId,
                firstToken,
            )
        }
        ctx.botService.deliver(created.bot.botId, secondToken, group.chatId, "second", "rotate-second")

        ctx.botService.removeFromGroup(actor, group.chatId, created.bot.botId)
        assertFailsWith<BotCredentialCommandTerminalException> {
            ctx.botService.rotateTokenForGroup(
                actor,
                group.chatId,
                created.bot.botId,
                secondOperationId,
                secondToken,
            )
        }
        assertFailsWith<BotCredentialCommandTerminalException> {
            ctx.botService.rotateTokenForGroup(
                actor,
                group.chatId,
                created.bot.botId,
                UUID.randomUUID().toString(),
                newGroupBotTestToken(),
            )
        }
    }
}

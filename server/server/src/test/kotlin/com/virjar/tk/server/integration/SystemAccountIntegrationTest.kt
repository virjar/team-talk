package com.virjar.tk.server.integration

import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.protocol.model.ConversationCapacityPolicy
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.UserRole
import com.virjar.tk.server.domain.message.MessageProjectionHooks
import com.virjar.tk.server.domain.message.MessageProjectionStage
import com.virjar.tk.server.domain.user.SystemAccountUids
import com.virjar.tk.server.infra.db.Conversations
import com.virjar.tk.server.infra.db.ConversationUsages
import com.virjar.tk.server.infra.db.Users
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 内测反馈 T058：固定系统账号引导与系统私聊的幂等创建。 */
class SystemAccountIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `legacy human usernames do not block fixed system account bootstrap`() = runTest {
        TestEnvironment().use { legacy ->
            val humans = listOf("sys-assistant", "sys-service").associateWith { username ->
                legacy.registerUser(username, "password123")
            }
            val before = humans.mapValues { (_, uid) -> legacy.userService.getProfile(uid) }

            legacy.userService.ensureSystemAccounts()
            legacy.userService.ensureSystemAccounts()

            humans.forEach { (username, uid) ->
                assertEquals(before.getValue(username), legacy.userService.getProfile(uid))
                assertEquals(uid, legacy.userService.login(username, "password123").uid)
            }
            SystemAccountUids.ALL.forEach { uid ->
                val account = legacy.userService.getProfile(uid)
                assertEquals(UserRole.SYSTEM, account.role)
                assertEquals(SystemAccountUids.USERNAMES.getValue(uid), account.username)
                assertNotNull(AuthRules.validateUsername(account.username), "系统保留用户名不能成为合法人类注册输入")
            }
            transaction(legacy.database) {
                assertEquals(2L, Users.selectAll().where { Users.uid inList SystemAccountUids.ALL }.count())
            }
        }
    }

    @Test
    fun `existing fixed system identities preserve their old usernames and account state`() = runTest {
        TestEnvironment().use { legacy ->
            transaction(legacy.database) {
                Users.batchInsert(SystemAccountUids.DISPLAY_NAMES.entries) { (uid, name) ->
                    this[Users.uid] = uid
                    this[Users.username] = uid.replace('_', '-')
                    this[Users.name] = name
                    this[Users.role] = UserRole.SYSTEM
                    this[Users.status] = 2
                    this[Users.passwordHash] = "!service-account:v1:fixture-only"
                    this[Users.createdAt] = 11L
                    this[Users.updatedAt] = 12L
                }
            }
            val before = SystemAccountUids.ALL.associateWith(legacy.userService::getProfile)
            legacy.userService.ensureSystemAccounts()
            legacy.userService.ensureSystemAccounts()
            assertEquals(before, SystemAccountUids.ALL.associateWith(legacy.userService::getProfile))
        }
    }

    @Test
    fun `boot bootstraps fixed system accounts idempotently`() = runTest {
        ctx.userService.ensureSystemAccounts()
        SystemAccountUids.DISPLAY_NAMES.forEach { (uid, name) ->
            val user = ctx.userService.getProfile(uid)
            assertEquals(name, user.name)
            assertEquals(com.virjar.tk.protocol.model.UserRole.SYSTEM, user.role)
        }
        // 二次引导幂等：不抛唯一约束冲突
        ctx.userService.ensureSystemAccounts()
    }

    @Test
    fun `system chat keeps both members but only the human owns its projection`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("sys-alice"))

        val first = ctx.chatService.getOrCreateSystemChat(alice, SystemAccountUids.ASSISTANT)
        val second = ctx.chatService.getOrCreateSystemChat(alice, SystemAccountUids.ASSISTANT)
        assertEquals(first.chatId, second.chatId, "同用户同系统账号必须复用同一条私聊")

        // CHAT_CREATED 只在首次创建时发出一次（幂等重入不重复）
        val createdEvents = ctx.syncEventReader.getEventsAfter(alice, 0L, 1_000)
            .filter { it.notifyType == NotifyType.CHAT_CREATED.code }
            .map { com.virjar.tk.protocol.ProtoCodec.decode(com.virjar.tk.protocol.model.Chat, requireNotNull(it.payload)) }
            .filter { it.chatId == first.chatId }
        assertEquals(1, createdEvents.size)
        assertEquals(setOf(alice, SystemAccountUids.ASSISTANT), ctx.chatService.getMembers(first.chatId).map { it.uid }.toSet())
        assertEquals(first.chatId, ctx.conversationService.listConversations(alice).single().chatId)
        assertNoSystemProjections()
    }

    @Test
    fun `more than one thousand humans can each own both system chats`() = runTest {
        ctx.userService.ensureSystemAccounts()
        // 这里只需真实 User 身份；批量准备省去与会话容量无关的 1,001 次 BCrypt 注册。
        val prefix = UUID.randomUUID().toString().take(8)
        val humans = List(ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER + 1) { "system-capacity-$prefix-$it" }
        transaction(ctx.database) {
            Users.batchInsert(humans) { uid ->
                this[Users.uid] = uid
                this[Users.username] = uid
                this[Users.name] = uid
                this[Users.passwordHash] = "fixture-only"
                this[Users.createdAt] = 1L
                this[Users.updatedAt] = 1L
            }
        }
        val chatIds = mutableSetOf<String>()
        humans.forEach { uid ->
            SystemAccountUids.ALL.forEach { systemUid ->
                val chat = ctx.chatService.getOrCreateSystemChat(uid, systemUid)
                assertTrue(chatIds.add(chat.chatId))
                assertEquals(chat.chatId, ctx.chatService.getOrCreateSystemChat(uid, systemUid).chatId)
            }
        }
        assertEquals(humans.size * 2, chatIds.size)
        transaction(ctx.database) {
            assertEquals(humans.size.toLong() * 2, Conversations.selectAll().where { Conversations.uid inList humans }.count())
            val usages = ConversationUsages.selectAll().where { ConversationUsages.uid inList humans }.toList()
            assertEquals(humans.size, usages.size)
            assertTrue(usages.all { it[ConversationUsages.conversationCount] == 2 })
        }
        val lastHuman = humans.last()
        val serviceChat = ctx.chatService.getOrCreateSystemChat(lastHuman, SystemAccountUids.SERVICE)
        val service = ctx.freshMessageService()
        assertEquals(1L, service.sendMessage(lastHuman, Message(
            chatId = serviceChat.chatId,
            clientMsgId = UUID.randomUUID().toString(),
            senderUid = lastHuman,
            messageType = MessageType.RICH_TEXT.code,
            timestamp = 1L,
            body = buildRichTextBody("超过一千人的系统私聊仍可收发"),
        )))
        assertEquals(2L, service.sendServiceReply(serviceChat.chatId, "capacity-reply", "收到"))
        assertEquals(2, service.getHistory(lastHuman, serviceChat.chatId, 0L, 10).size)
        assertNoSystemProjections()
    }

    @Test
    fun `system chats still respect the human capacity and preserve creation retries`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("sys-capacity"))
        val assistant = ctx.chatService.getOrCreateSystemChat(alice, SystemAccountUids.ASSISTANT)
        transaction(ctx.database) {
            ConversationUsages.update({ ConversationUsages.uid eq alice }) {
                it[ConversationUsages.conversationCount] = ConversationCapacityPolicy.MAX_CONVERSATIONS_PER_USER
            }
        }
        assertEquals(assistant.chatId, ctx.chatService.getOrCreateSystemChat(alice, SystemAccountUids.ASSISTANT).chatId)
        val failure = assertFailsWith<IllegalArgumentException> {
            ctx.chatService.getOrCreateSystemChat(alice, SystemAccountUids.SERVICE)
        }
        assertEquals(ConversationCapacityPolicy.CONVERSATION_LIMIT_REASON, failure.message)
        assertEquals(listOf(assistant.chatId), ctx.conversationService.listConversations(alice).map { it.chatId })
        assertNoSystemProjections()
    }

    @Test
    fun `old recipient snapshots recover file messages without restoring system projections`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("sys-recover"))
        for (systemUid in SystemAccountUids.ALL) {
            val chat = ctx.chatService.getOrCreateSystemChat(alice, systemUid)
            val source = File.createTempFile("system-message-", ".txt")
            val attachment = try {
                source.writeText("kept system chat attachment")
                val path = ctx.fileStore.store(alice, "kept.txt", "text/plain", source)
                assertNotNull(ctx.fileStore.getAttachment(path))
            } finally {
                source.delete()
            }
            val failOnce = AtomicBoolean(true)
            val service = ctx.freshMessageService(projectionHooks = MessageProjectionHooks { stage, _ ->
                if (stage == MessageProjectionStage.AFTER_LUCENE_BEFORE_POSTGRES && failOnce.compareAndSet(true, false)) {
                    throw IOException("injected system chat projection failure")
                }
            })
            val message = Message(
                chatId = chat.chatId,
                clientMsgId = UUID.randomUUID().toString(),
                senderUid = alice,
                messageType = MessageType.FILE.code,
                timestamp = 1L,
                body = FileBody(attachment),
            )
            assertFailsWith<IOException> { service.sendMessage(alice, message) }
            val pending = ctx.messageStore.getPendingProjectionOperations(chat.chatId, 1L).single()
            assertEquals(setOf(alice, systemUid), pending.target.recipientUids.toSet(), "持久快照仍保留旧版双方成员身份")
            assertEquals(1, ctx.messageProjector.recoverPendingProjections())
            assertEquals(0, ctx.messageProjector.recoverPendingProjections())
            assertEquals(1L, service.sendMessage(alice, message))
            assertEquals(1L, service.sendMessage(alice, message))
            val received = service.getHistory(alice, chat.chatId, 0L, 10).single()
            assertEquals(attachment, (received.body as FileBody).attachment)
            assertEquals(attachment, ctx.fileStore.getAttachment(attachment.path))
            assertEquals(1L, ctx.conversationService.listConversations(alice).single { it.chatId == chat.chatId }.lastSeq)
            if (systemUid == SystemAccountUids.SERVICE) {
                assertEquals(2L, service.sendServiceReply(chat.chatId, "explicit-service-reply", "系统仍可发送回复"))
                assertEquals(SystemAccountUids.SERVICE, service.getHistory(alice, chat.chatId, 0L, 10)
                    .single { it.clientMsgId == "explicit-service-reply" }.senderUid)
            }
            service.revokeMessage(alice, chat.chatId, 1L)
            assertNoSystemProjections()
        }
    }

    @Test
    fun `unknown system uid is rejected`() = runTest {
        val alice = ctx.registerUser(uniqueUsername("sys-bad"))
        assertFailsWith<IllegalArgumentException> {
            ctx.chatService.getOrCreateSystemChat(alice, "sys_nope")
        }
    }

    private fun uniqueUsername(base: String): String = "$base-${UUID.randomUUID().toString().take(8)}"

    private fun assertNoSystemProjections() {
        transaction(ctx.database) {
            assertEquals(0L, Conversations.selectAll().where { Conversations.uid inList SystemAccountUids.ALL }.count())
            assertEquals(0L, ConversationUsages.selectAll().where { ConversationUsages.uid inList SystemAccountUids.ALL }.count())
        }
        SystemAccountUids.ALL.forEach { uid ->
            assertTrue(ctx.syncEventReader.getEventsAfter(uid, 0L, 100).isEmpty(), "固定系统账号不持有客户端同步流")
        }
    }
}

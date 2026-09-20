package com.virjar.tk.server.integration

import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.server.domain.message.ServiceAccountMessages
import com.virjar.tk.server.domain.user.SystemAccountUids
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 服务号官方触达（原始需求）：新用户欢迎语自愈补发 + 管理员全员广播的幂等与续跑。
 */
class ServiceAccountMessagesIntegrationTest {
    companion object {
        @JvmField @RegisterExtension val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    private suspend fun serviceChatFor(uid: String) =
        ctx.chatService.getOrCreateSystemChat(uid, SystemAccountUids.SERVICE)

    private suspend fun welcomeMessages(uid: String, chatId: String): List<Message> =
        ctx.messageService.getHistory(uid, chatId, 0L, 10)
            .filter { it.senderUid == SystemAccountUids.SERVICE && it.clientMsgId == ServiceAccountMessages.WELCOME_MESSAGE_ID }

    private suspend fun broadcastMessages(uid: String, chatId: String, broadcastId: String): List<Message> =
        ctx.messageService.getHistory(uid, chatId, 0L, 10)
            .filter {
                it.senderUid == SystemAccountUids.SERVICE &&
                    it.clientMsgId == "${ServiceAccountMessages.BROADCAST_REPLY_PREFIX}$broadcastId"
            }

    @Test
    fun `welcome is delivered once per user and self-heals on re-entry`() = runTest {
        ctx.userService.ensureSystemAccounts()
        // 共享环境里模板可能被其他用例覆写；显式恢复默认值使断言确定。
        ctx.serviceAccountMessages.setWelcomeTemplate(ServiceAccountMessages.DEFAULT_WELCOME_TEMPLATE, "test")
        val alice = ctx.registerUser(uniqueUsername("svc-welcome"))
        val chat = serviceChatFor(alice)

        ctx.serviceAccountMessages.ensureWelcome(alice, chat.chatId)
        ctx.serviceAccountMessages.ensureWelcome(alice, chat.chatId)

        val messages = welcomeMessages(alice, chat.chatId)
        assertEquals(1, messages.size, "欢迎语必须恰好一条，重入不重复")
        assertTrue(
            (messages.single().body as RichTextBody).markdown.contains("TeamTalk"),
            "默认模板应包含欢迎内容",
        )
    }

    @Test
    fun `template override applies to users welcomed afterwards`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-tpl-a"))
        val aliceChat = serviceChatFor(alice)
        ctx.serviceAccountMessages.ensureWelcome(alice, aliceChat.chatId)

        ctx.serviceAccountMessages.setWelcomeTemplate("欢迎使用私有部署示例。", "admin-operator")
        assertEquals("欢迎使用私有部署示例。", ctx.serviceAccountMessages.getWelcomeTemplate())

        val bob = ctx.registerUser(uniqueUsername("svc-tpl-b"))
        val bobChat = serviceChatFor(bob)
        ctx.serviceAccountMessages.ensureWelcome(bob, bobChat.chatId)

        val bobWelcome = welcomeMessages(bob, bobChat.chatId).single()
        assertTrue((bobWelcome.body as RichTextBody).markdown.contains("私有部署"), "新用户使用更新后的模板")
        // 模板更新不重发：此前已欢迎的用户保持一条默认模板欢迎语。
        assertEquals(1, welcomeMessages(alice, aliceChat.chatId).size)
    }

    @Test
    fun `broadcast reaches every human exactly once and reissue stays idempotent`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val users = List(3) { ctx.registerUser(uniqueUsername("svc-bcast-$it")) }

        val record = ctx.serviceAccountMessages.createBroadcast(
            markdown = "维护公告：今晚 22:00 例行升级。",
            operator = "admin-operator",
            launch = { },
        )
        assertTrue(ServiceAccountMessages.isValidBroadcastId(record.broadcastId))
        // 内联执行（等价于运行时 launch 的执行体）。
        ctx.serviceAccountMessages.runBroadcast(record.broadcastId)

        val finished = checkNotNull(ctx.serviceAccountMessages.getBroadcast(record.broadcastId))
        assertTrue(finished.totalUsers >= users.size, "全员基数应覆盖本次注册的用户")
        assertEquals(finished.totalUsers, finished.coveredUsers, "内联执行应无失败地覆盖全员")
        assertEquals(0, finished.failedUsers)
        checkNotNull(finished.finishedAt)

        for (uid in users) {
            val chat = serviceChatFor(uid)
            assertEquals(1, broadcastMessages(uid, chat.chatId, record.broadcastId).size, "每用户恰好一条广播")
            // 广播同时确保了欢迎语就位。
            assertEquals(1, welcomeMessages(uid, chat.chatId).size)
        }

        // 重新执行（reissue 语义）：已送达用户幂等跳过，不产生第二条。
        ctx.serviceAccountMessages.runBroadcast(record.broadcastId)
        for (uid in users) {
            val chat = serviceChatFor(uid)
            assertEquals(1, broadcastMessages(uid, chat.chatId, record.broadcastId).size)
            val history = ctx.messageService.getHistory(uid, chat.chatId, 0L, 10)
            assertEquals(2, history.size, "欢迎语 + 广播各一条")
        }
    }

    @Test
    fun `unfinished broadcasts resume on startup`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-resume"))
        val record = ctx.serviceAccountMessages.createBroadcast(
            markdown = "续跑公告",
            operator = "admin-operator",
            launch = { },
        )
        checkNotNull(ctx.serviceAccountMessages.getBroadcast(record.broadcastId)).let {
            check(it.finishedAt == null) { "未执行的广播必须处于未完成状态" }
        }

        val resumed = ctx.serviceAccountMessages.resumeUnfinishedBroadcasts { }
        assertTrue(record.broadcastId in resumed, "未完成广播必须进入启动续跑清单")
        ctx.serviceAccountMessages.runBroadcast(record.broadcastId)

        val chat = serviceChatFor(alice)
        assertEquals(1, broadcastMessages(alice, chat.chatId, record.broadcastId).size)
        checkNotNull(ctx.serviceAccountMessages.getBroadcast(record.broadcastId)).let {
            check(it.finishedAt != null)
        }
    }

    @Test
    fun `command messages still route to help replies alongside official outreach`() = runTest {
        ctx.userService.ensureSystemAccounts()
        val alice = ctx.registerUser(uniqueUsername("svc-mixed"))
        val chat = serviceChatFor(alice)
        ctx.serviceAccountMessages.ensureWelcome(alice, chat.chatId)

        ctx.messageService.sendMessage(
            alice,
            Message(
                chatId = chat.chatId,
                clientMsgId = "cmd-${System.nanoTime()}",
                senderUid = alice,
                messageType = MessageType.RICH_TEXT.code,
                timestamp = System.currentTimeMillis(),
                body = buildRichTextBody("/help"),
            ),
        )
        // 指令回复走 SystemCommandRouter（真实接线），官方触达与指令回复互不干扰。
        var commandReply: Message? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            commandReply = ctx.messageService.getHistory(alice, chat.chatId, 0L, 10)
                .firstOrNull { it.senderUid == SystemAccountUids.SERVICE && it.clientMsgId.startsWith("svc-") }
            if (commandReply != null) break
            kotlinx.coroutines.delay(100)
        }
        checkNotNull(commandReply) { "指令回复应经真实路由送达" }
        assertTrue((commandReply.body as RichTextBody).plainText.contains("/help"))
    }
}

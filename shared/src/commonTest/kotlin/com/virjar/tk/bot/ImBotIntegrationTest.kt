package com.virjar.tk.bot

import com.virjar.tk.client.ConnectionState
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ImBot 无头客户端全链路集成测试（bot 对 bot，经真实服务器）。
 *
 * 这是 SDK 闭环的验收测试：连接→注册→认证→建会话→发消息→对端收消息→回执，
 * 全程零 UI 依赖。SDK 层任何回归（协议/时序/编解码）在此暴露，
 * 不会漏到 UI 客户端集成时。
 *
 * 默认跳过（无服务器依赖的 CI 环境）。手动/CI with 服务器时开启：
 * ```
 * ./gradlew :shared:jvmTest -Dtk.botTest.host=im.virjar.com -Dtk.botTest.port=5100
 * ```
 */
class ImBotIntegrationTest {

    private val enabled = System.getProperty("tk.botTest.host") != null
    private val host = System.getProperty("tk.botTest.host") ?: "127.0.0.1"
    private val port = System.getProperty("tk.botTest.port")?.toInt() ?: 5100

    private val bots = mutableListOf<ImBot>()

    @AfterTest
    fun tearDown() {
        bots.forEach { runCatching { it.shutdown() } }
    }

    private fun bot(prefix: String): ImBot = runBlocking {
        ImBot.register(host, port, prefix).also { bots += it }
    }

    @Test
    fun `注册即认证 - 三级状态就位`() {
        if (!enabled) return
        runBlocking {
            val b = bot("reg")
            assertTrue(b.uid.isNotBlank(), "认证成功后 uid 应写入 UserSession")
            assertEquals(ConnectionState.AUTHENTICATED, b.imClient.state.value)
        }
    }

    @Test
    fun `bot对bot消息全链路 - 注册建会话发送接收`() {
        if (!enabled) return
        runBlocking {
            val alice = bot("alice")
            val bob = bot("bob")

            // Alice → Bob 建私聊（服务端 ensureConversations 预创建会话行）
            val chatId = alice.createPersonalChat(bob.uid)

            // Alice 发消息（走 sendAndWaitAck）
            val text = "hello-${UUID.randomUUID()}"
            val ack = alice.sendText(chatId, text)
            assertEquals(0, ack.code, "发送 ACK 应成功: ${ack.reason}")

            // Bob 收到（EventProcessor 契约解码 → messageEvents 流）
            val received = bob.nextMessage(timeoutMs = 15_000)
            assertEquals(chatId, received.chatId)
            assertEquals(alice.uid, received.senderUid)
            val body = received.body
            assertTrue(body is com.virjar.tk.body.TextBody && body.text == text,
                "收到 body 应为文本[$text]，实际 ${body?.let { it::class.simpleName }}")

            // Bob 回消息，Alice 也能收到（双向）
            val reply = "ack-${UUID.randomUUID()}"
            assertEquals(0, bob.sendText(chatId, reply).code)
            // 过滤自己（服务端 MESSAGE_RECV 含发送者回环）
            val received2 = alice.nextMessage(timeoutMs = 15_000) { it.senderUid == bob.uid }
            assertTrue(received2.body is com.virjar.tk.body.TextBody &&
                (received2.body as com.virjar.tk.body.TextBody).text == reply)

            // 会话列表同步（bot 内存 LocalCache 经 EventProcessor 维护）
            val convs = bob.listConversations()
            assertTrue(convs.any { it.chatId == chatId }, "Bob 应看到与 Alice 的会话")
        }
    }

    @Test
    fun `未读回执链路 - 对端已读 peerReadSeq 推进`() {
        if (!enabled) return
        runBlocking {
            val sender = bot("snd")
            val reader = bot("rdr")
            val chatId = sender.createPersonalChat(reader.uid)

            sender.sendText(chatId, "need-read")
            val msg = reader.nextMessage(timeoutMs = 15_000)
            assertTrue(msg.serverSeq > 0, "serverSeq 应已分配")

            // reader 标记已读 → 服务端持久化 + READ_SYNC 推给 sender
            reader.session.messageRepo.markRead(chatId, msg.serverSeq).getOrThrow()
            // sender 等待 READ_SYNC 事件更新 peerReadSeq（EventProcessor → LocalCache）
            val deadline = System.currentTimeMillis() + 10_000
            var peerReadSeq = 0L
            while (System.currentTimeMillis() < deadline) {
                peerReadSeq = sender.session.localCache.getConversations()
                    .firstOrNull { it.chatId == chatId }?.peerReadSeq ?: 0L
                if (peerReadSeq >= msg.serverSeq) break
                kotlinx.coroutines.delay(300)
            }
            assertTrue(peerReadSeq >= msg.serverSeq,
                "sender 的 peerReadSeq($peerReadSeq) 应推进到 >= ${msg.serverSeq}")
        }
    }
}

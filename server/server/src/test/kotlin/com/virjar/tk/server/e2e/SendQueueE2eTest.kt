package com.virjar.tk.server.e2e

import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.shared.client.ConnectionState
import com.virjar.tk.shared.client.ImClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 发送队列 e2e（真实 PG + embedded TcpServer）。
 * 场景：断线期间发送 → QUEUED（不失败）→ 自动重连 → 队列补发 → 送达。
 */
class SendQueueE2eTest {

    @Test
    fun `断线发送排队重连后送达`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                // B 在线（队列发送方）
                var bUid: String? = null
                val b = ImClient(onAuthResult = { ok, uid, _, _, _, _, _, _ -> if (ok) bUid = uid })
                val bEvents = b.installE2eEventProjection(env.syncDatasetId)
                b.register("sq-b-${System.nanoTime()}", "password123", "B", "dev-b", "Test", "127.0.0.1", env.tcpPort)
                withTimeout(10_000) { b.state.first { it == ConnectionState.AUTHENTICATED } }

                val a = com.virjar.tk.shared.bot.ImBot.register(
                    "127.0.0.1", env.tcpPort, "sq-a", TEST_IM_BOT_PASSWORD, testImBotCacheOwner,
                )
                try {
                    val chatId = a.createPersonalChat(bUid!!)
                    val queueScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                    val states = mutableListOf<Int>()
                    lateinit var queue: com.virjar.tk.shared.client.SendQueue
                    queue = com.virjar.tk.shared.client.SendQueue(
                        ownerUid = bUid!!,
                        localCache = com.virjar.tk.shared.testkit.FakeLocalCache(),
                        connectionState = b.state,
                        sender = com.virjar.tk.shared.client.MessageSender { msg -> b.sendAndWaitAck(msg) },
                        scope = queueScope,
                        onQueued = { states.add(com.virjar.tk.protocol.model.Message.SEND_STATUS_QUEUED) },
                        onSent = { _, _ -> states.add(com.virjar.tk.protocol.model.Message.SEND_STATUS_SENT) },
                        onFailed = { _, reason -> states.add(-1); println("FAILED: $reason") },
                    )

                    // 断线 → 发送（应排队不失败）→ 重连（自动）→ 送达
                    b.simulateNetworkDrop()
                    withTimeout(5_000) { b.state.first { it == ConnectionState.DISCONNECTED } }

                    val msg = com.virjar.tk.protocol.model.Message(
                        chatId = chatId,
                        clientMsgId = java.util.UUID.randomUUID().toString(),
                        senderUid = bUid!!,
                        messageType = com.virjar.tk.protocol.MessageType.RICH_TEXT.code,
                        timestamp = System.currentTimeMillis(),
                        body = buildRichTextBody("断线排队的消息"),
                    )
                    queue.enqueue(msg)
                    Thread.sleep(2_000) // 断线期间排队
                    assertTrue(-1 !in states, "断线期间不应失败: $states")
                    assertTrue(com.virjar.tk.protocol.model.Message.SEND_STATUS_QUEUED in states, "应进入 QUEUED: $states")

                    // 重连（退避 1s 起）→ AUTHENTICATED → 队列唤醒补发
                    withTimeout(20_000) { b.state.first { it == ConnectionState.AUTHENTICATED } }
                    withTimeout(15_000) {
                        while (com.virjar.tk.protocol.model.Message.SEND_STATUS_SENT !in states) kotlinx.coroutines.delay(200)
                    }
                    assertTrue(-1 !in states, "补发不应失败: $states")

                    // A 侧收到
                    val received = withTimeout(10_000) { a.nextMessage { it.senderUid == bUid } }
                    assertEquals("断线排队的消息", (received.body as RichTextBody).markdown)

                    queue.close(); queueScope.cancel()
                    a.shutdown(); bEvents.close(); b.destroy()
                } finally {
                    runCatching { a.shutdown() }
                    runCatching { bEvents.close() }
                    runCatching { b.destroy() }
                }
            }
        }
    }
}

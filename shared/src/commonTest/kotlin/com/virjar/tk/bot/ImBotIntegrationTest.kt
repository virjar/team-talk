package com.virjar.tk.bot

import com.virjar.tk.client.ConnectionState
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ImBot 无头客户端全链路集成测试（bot 对 bot，经真实服务器）。
 *
 * SDK 闭环的验收防线：连接→认证→消息→事件→断线重连→离线补发→社交→群组→typing→撤回→presence。
 * 任何 SDK 层回归（协议/时序/编解码/重连生命周期）在此暴露。
 *
 * 默认跳过；开启：`./gradlew :shared:jvmTest -Dtk.botTest.host=im.virjar.com -Dtk.botTest.port=5100`
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

    /** 建立好友关系并返回 B 的私聊 chatId（A 视角）。 */
    private suspend fun befriend(a: ImBot, b: ImBot): String {
        a.applyFriend(b.uid)
        val apply = withTimeout(10_000) { b.pendingApplies().first { it.fromUid == a.uid } }
        b.acceptFriendApply(apply.token!!)
        // 双方确认收到 CONTACT_ACCEPTED
        withTimeout(10_000) { a.nextContactEvent() }
        withTimeout(10_000) { b.nextContactEvent() }
        return a.createPersonalChat(b.uid)
    }

    @Test
    fun `注册即认证 - 三级状态就位`() {
        if (!enabled) return
        runBlocking {
            val b = bot("reg")
            assertTrue(b.uid.isNotBlank())
            assertEquals(ConnectionState.AUTHENTICATED, b.imClient.state.value)
        }
    }

    @Test
    fun `bot对bot消息全链路 - 注册建会话发送接收`() {
        if (!enabled) return
        runBlocking {
            val alice = bot("alice")
            val bob = bot("bob")
            val chatId = alice.createPersonalChat(bob.uid)

            val text = "hello-${UUID.randomUUID()}"
            val ack = alice.sendText(chatId, text)
            assertEquals(0, ack.code, "发送 ACK 应成功: ${ack.reason}")

            val received = bob.nextMessage { it.senderUid == alice.uid }
            assertEquals(chatId, received.chatId)
            val body = received.body
            assertTrue(body is com.virjar.tk.body.TextBody && body.text == text,
                "收到 body 应为文本[$text]，实际 ${body?.let { it::class.simpleName }}")

            val reply = "ack-${UUID.randomUUID()}"
            assertEquals(0, bob.sendText(chatId, reply).code)
            val received2 = alice.nextMessage { it.senderUid == bob.uid }
            assertTrue(received2.body is com.virjar.tk.body.TextBody &&
                (received2.body as com.virjar.tk.body.TextBody).text == reply)

            assertTrue(bob.listConversations().any { it.chatId == chatId })
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
            val msg = reader.nextMessage { it.senderUid == sender.uid }
            assertTrue(msg.serverSeq > 0)

            reader.markRead(chatId, msg.serverSeq)
            val deadline = System.currentTimeMillis() + 10_000
            var peerReadSeq = 0L
            while (System.currentTimeMillis() < deadline) {
                peerReadSeq = sender.session.localCache.getConversations()
                    .firstOrNull { it.chatId == chatId }?.peerReadSeq ?: 0L
                if (peerReadSeq >= msg.serverSeq) break
                delay(300)
            }
            assertTrue(peerReadSeq >= msg.serverSeq,
                "sender 的 peerReadSeq($peerReadSeq) 应推进到 >= ${msg.serverSeq}")
        }
    }

    // ══════════ SDK 完整性验收（重连/补发/社交/群/typing/撤回/presence） ══════════

    @Test
    fun `断线重连恢复 - 离线期间消息经补发全达`() {
        if (!enabled) return
        runBlocking {
            val a = bot("reca")
            val b = bot("recb")
            val chatId = befriend(a, b)
            // 清空双方缓冲里建交期间的杂音，记录当前水位
            a.sendText(chatId, "warmup")
            b.nextMessage { it.senderUid == a.uid && it.body is com.virjar.tk.body.TextBody }

            // A 模拟网络断（不置 destroyed → 自动重连路径）
            a.imClient.simulateNetworkDrop()
            withTimeout(10_000) { a.imClient.state.first { it == ConnectionState.DISCONNECTED } }

            // 断线期间 B 发 3 条（A 不在线，事件入 sync_events）
            val texts = (1..3).map { "offline-$it-${UUID.randomUUID()}" }
            texts.forEach { assertEquals(0, b.sendText(chatId, it).code) }
            delay(500)

            // A 自动重连（退避 1s 起）+ 认证携带 lastEventId → 服务端补发
            withTimeout(30_000) { a.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
            val received = mutableListOf<String>()
            repeat(texts.size) {
                val m = withTimeout(15_000) {
                    var msg: Message
                    while (true) {
                        msg = a.nextMessage(15_000) { it.senderUid == b.uid }
                        val body = msg.body
                        if (body is com.virjar.tk.body.TextBody && body.text.startsWith("offline-")) break
                    }
                    msg
                }
                val body = m.body as com.virjar.tk.body.TextBody
                received += body.text
            }
            assertEquals(texts.toSet(), received.toSet(),
                "断线期间的 3 条消息必须经离线补发全部送达（A1 重连重启 + A2 游标接线）")
        }
    }

    @Test
    fun `好友全流程 - 申请事件接受双向同步`() {
        if (!enabled) return
        runBlocking {
            val a = bot("fra")
            val b = bot("frb")
            a.applyFriend(b.uid)
            // B 收到申请（contactEvents + pendingApplies）
            withTimeout(10_000) { b.nextContactEvent() }
            val apply = b.pendingApplies().first { it.fromUid == a.uid }
            b.acceptFriendApply(apply.token!!)
            // 双方都收到 CONTACT_ACCEPTED 事件
            withTimeout(10_000) { a.nextContactEvent() }
            withTimeout(10_000) { b.nextContactEvent() }
            assertTrue(a.listFriends().any { it.friendUid == b.uid }, "A 好友列表应含 B")
            assertTrue(b.listFriends().any { it.friendUid == a.uid }, "B 好友列表应含 A")
        }
    }

    @Test
    fun `群组全流程 - 建群事件群消息成员列表`() {
        if (!enabled) return
        runBlocking {
            val a = bot("gra")
            val b = bot("grb")
            befriend(a, b)

            // B 等待建群广播（过滤私聊建交也触发的 CHAT_CREATED，只认群类型）
            val created = launch {
                withTimeout(15_000) {
                    while (true) {
                        val (type, chat) = b.nextChatEvent(15_000)
                        if (type == NotifyType.CHAT_CREATED && chat.chatType == 2) break
                    }
                }
            }
            delay(300)
            val group = a.createGroup("e2e-群-${UUID.randomUUID().toString().take(6)}", listOf(b.uid))
            created.join()  // 收到即通过（超时抛异常）

            // 群消息
            val got = launch {
                withTimeout(10_000) {
                    while (true) {
                        val m = b.nextMessage(10_000) { it.senderUid == a.uid && it.chatId == group.chatId }
                        val body = m.body
                        if (body is com.virjar.tk.body.TextBody && body.text.startsWith("group-msg")) break
                    }
                }
            }
            delay(200)
            a.sendText(group.chatId, "group-msg-${UUID.randomUUID()}")
            got.join()

            assertEquals(2, a.groupMembers(group.chatId).size, "群成员 = A + B")
        }
    }

    @Test
    fun `typing - 双向指示`() {
        if (!enabled) return
        runBlocking {
            val a = bot("tya")
            val b = bot("tyb")
            val chatId = befriend(a, b)
            a.sendText(chatId, "pre")  // 确保 B 侧 chatEvents/typing 订阅链路热
            b.nextMessage { it.senderUid == a.uid }

            val typing = launch {
                withTimeout(10_000) {
                    while (true) {
                        val (cid, sender) = withTimeout(10_000) { b.typingEvents.first() }
                        if (cid == chatId && sender == a.uid) break
                    }
                }
            }
            delay(300)
            a.sendTyping(chatId)
            typing.join()
        }
    }

    @Test
    fun `撤回 - 对端收到 revoked 标记`() {
        if (!enabled) return
        runBlocking {
            val a = bot("rva")
            val b = bot("rvb")
            val chatId = befriend(a, b)
            val ack = a.sendText(chatId, "to-be-revoked")
            assertEquals(0, ack.code)
            b.nextMessage { it.senderUid == a.uid && it.serverSeq == ack.serverSeq }

            a.revoke(chatId, ack.serverSeq)
            // B 收到 flags 含 REVOKED 的同 seq 消息
            val revoked = withTimeout(10_000) {
                while (true) {
                    val m = b.nextMessage(10_000) { it.serverSeq == ack.serverSeq }
                    if (m.flags and Message.FLAG_REVOKED != 0) return@withTimeout m
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
            assertTrue(revoked.flags and Message.FLAG_REVOKED != 0, "撤回后对端应见 REVOKED 标记")
        }
    }

    @Test
    fun `presence - 好友上线广播`() {
        if (!enabled) return
        runBlocking {
            val a = bot("pra")
            val b = bot("prb")
            befriend(a, b)
            // A 下线再上线 → B 观察 presenceEvents（offline→online 序列）
            a.imClient.simulateNetworkDrop()
            withTimeout(10_000) { a.imClient.state.first { it == ConnectionState.DISCONNECTED } }
            val presence = launch {
                withTimeout(30_000) {
                    while (true) {
                        val ev = b.nextPresenceEvent(30_000)
                        if (ev.uid == a.uid && ev.status == com.virjar.tk.protocol.PresencePayload.STATUS_ONLINE) break
                    }
                }
            }
            withTimeout(30_000) { a.imClient.state.first { it == ConnectionState.AUTHENTICATED } }
            presence.join()
        }
    }
}

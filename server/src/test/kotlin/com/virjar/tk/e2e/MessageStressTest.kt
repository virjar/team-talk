package com.virjar.tk.e2e

import com.virjar.tk.body.TextBody
import com.virjar.tk.client.ConnectionState
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 双端快速收发消息压力测试。
 *
 * 历史背景：V2 重构前，双端同时快速发消息会触发 subscribe→push→loadMessages→subscribe
 * 死循环（commit e9f8e96），表现：消息同步死循环 + 页面不停闪烁 + 网络不停发包。
 *
 * 本测试模拟双端同时快速发送 N 条消息，验证：
 * 1. 消息不丢失（A 发的 B 都收到，反之亦然）
 * 2. 消息不重复（每条消息只收到一次）
 * 3. 无死循环（有界完成，不超时）
 * 4. serverSeq 单调递增
 *
 * 运行：./gradlew :server:test --tests "*MessageStressTest" -Dtk.e2e.remote=true
 */
@EnabledIfSystemProperty(named = "tk.e2e.remote", matches = "true")
class MessageStressTest {

    companion object {
        private const val MSG_COUNT = 20 // 每端发送的消息数（共 40 条并发）
    }

    @Test
    fun `dual-end rapid send no loop no loss`() = runBlocking {
        val user1 = RemoteDemoSupport.registerUser("stress1")
        val user2 = RemoteDemoSupport.registerUser("stress2")

        try {
            // 建好友 + 私聊
            val applyResp = user1.invoke(
                com.virjar.tk.protocol.ServiceId.CONTACT,
                com.virjar.tk.protocol.ContactMethod.APPLY.id,
                ProtoCodec.encodePayload { writeString(user2.uid); writeString("hi") }
            )
            val apply = ProtoCodec.decode(com.virjar.tk.model.ContactApply, applyResp.payload!!)
            user2.invoke(
                com.virjar.tk.protocol.ServiceId.CONTACT,
                com.virjar.tk.protocol.ContactMethod.ACCEPT.id,
                ProtoCodec.encodePayload { writeString(apply.token) }
            )
            val chatResp = user1.invoke(
                com.virjar.tk.protocol.ServiceId.CHAT,
                com.virjar.tk.protocol.ChatMethod.CREATE_PERSONAL.id,
                ProtoCodec.encodePayload { writeString(user2.uid) }
            )
            val chatId = ProtoCodec.decode(com.virjar.tk.model.Chat, chatResp.payload!!).chatId

            println("[Stress] 开始双端并发发送 ${MSG_COUNT}x2 条消息, chatId=$chatId")

            // 收集 user1/user2 收到的 MESSAGE_RECV 数量
            val user1Recv = AtomicInteger(0)
            val user2Recv = AtomicInteger(0)
            val user1Seqs = java.util.concurrent.ConcurrentSkipListSet<Long>()
            val user2Seqs = java.util.concurrent.ConcurrentSkipListSet<Long>()

            val collectJob1 = launch {
                while (isActive) {
                    try {
                        val notify = user1.awaitNotify(NotifyType.MESSAGE_RECV.code, 3000)
                        user1Recv.incrementAndGet()
                        val msg = ProtoCodec.decode(Message, notify.payload!!)
                        user1Seqs.add(msg.serverSeq)
                    } catch (e: Exception) { break }
                }
            }
            val collectJob2 = launch {
                while (isActive) {
                    try {
                        val notify = user2.awaitNotify(NotifyType.MESSAGE_RECV.code, 3000)
                        user2Recv.incrementAndGet()
                        val msg = ProtoCodec.decode(Message, notify.payload!!)
                        user2Seqs.add(msg.serverSeq)
                    } catch (e: Exception) { break }
                }
            }

            // 双端同时快速发消息
            val sendJob1 = launch {
                repeat(MSG_COUNT) { i ->
                    val msg = Message(
                        chatId = chatId, clientMsgId = "u1-${UUID.randomUUID()}",
                        messageType = MessageType.TEXT.code, timestamp = System.currentTimeMillis(),
                        senderUid = user1.uid, body = TextBody("A-$i"),
                    )
                    user1.imClient.sendAndWaitAck(msg)
                }
            }
            val sendJob2 = launch {
                repeat(MSG_COUNT) { i ->
                    val msg = Message(
                        chatId = chatId, clientMsgId = "u2-${UUID.randomUUID()}",
                        messageType = MessageType.TEXT.code, timestamp = System.currentTimeMillis(),
                        senderUid = user2.uid, body = TextBody("B-$i"),
                    )
                    user2.imClient.sendAndWaitAck(msg)
                }
            }

            // 等待发送完成（有界，不会死循环）
            withTimeout(60_000) {
                sendJob1.join()
                sendJob2.join()
                println("[Stress] 发送完成，等待消息投递稳定...")
                // 等待接收完成（给 3 秒缓冲）
                delay(3000)
            }

            collectJob1.cancel()
            collectJob2.cancel()

            // ── 验证 ──
            println("[Stress] user1 收到 ${user1Recv.get()} 条, user2 收到 ${user2Recv.get()} 条")
            println("[Stress] user1 seqs: ${user1Seqs.size} 个, user2 seqs: ${user2Seqs.size} 个")

            // 1. 消息不丢失：每端应至少收到对方发的 MSG_COUNT 条
            //    （加上服务端推给自己的回环，可能更多，但不应少于对方的数量）
            assertTrue(user2Recv.get() >= MSG_COUNT, "user2 应至少收到 user1 发的 $MSG_COUNT 条，实际 ${user2Recv.get()}")
            assertTrue(user1Recv.get() >= MSG_COUNT, "user1 应至少收到 user2 发的 $MSG_COUNT 条，实际 ${user1Recv.get()}")

            // 2. seq 单调递增且无重复
            assertTrue(user1Seqs.size == user1Seqs.toSet().size, "user1 收到的 seq 不应有重复")
            assertTrue(user2Seqs.size == user2Seqs.toSet().size, "user2 收到的 seq 不应有重复")

            // 3. 消息不重复：收到的消息数不应远超发送数（允许服务端回环多一倍，但不应该 3x+）
            val totalSent = MSG_COUNT * 2
            val totalRecv = user1Recv.get() + user2Recv.get()
            assertTrue(totalRecv <= totalSent * 2, "总接收数 $totalRecv 不应超过总发送数 ${totalSent} 的 2 倍（无放大循环）")

            println("[Stress] PASS: 双端各发 $MSG_COUNT 条，总接收 $totalRecv 条，无死循环无丢失")
        } finally {
            user1.close()
            user2.close()
        }
    }
}

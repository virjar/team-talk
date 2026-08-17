package com.virjar.tk.e2e

import com.virjar.tk.bot.ImBot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 超长消息回归：Conversations.lastMessage varchar(500)，预览超限曾致整条消息
 * 入库失败（code=400 "exceeds length"）——extractor 出口截断后应正常发送。
 */
class LongMessageE2eTest {

    @Test
    fun `超长文本消息可发送可接收`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val b = ImBot.register("127.0.0.1", env.tcpPort, "longmsg-b")
                try {
                    val a = ImBot.register("127.0.0.1", env.tcpPort, "longmsg-a")
                    try {
                        val chatId = a.createPersonalChat(b.uid)
                        val longText = buildString { repeat(400) { append("长文本测试段落。") } } // ~2400 字符
                        val ack = a.sendText(chatId, longText)
                        assertEquals(0, ack.code, "长消息发送应成功: ${ack.reason}")
                        val received = withTimeout(10_000) { b.nextMessage { it.senderUid == a.uid } }
                        assertEquals(longText.length, received.body?.let { (it as? com.virjar.tk.body.TextBody)?.text?.length })
                    } finally { a.shutdown() }
                } finally { b.shutdown() }
            }
        }
    }
}

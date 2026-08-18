package com.virjar.tk.e2e

import com.virjar.tk.body.ImageBody
import com.virjar.tk.bot.ImBot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ImageBody 尾部 thumbnailUrl 字段的消息发送/接收回归（新字段曾致发送挂起）。 */
class ImageThumbE2eTest {

    @Test
    fun `带缩略图的图片消息端到端`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val b = ImBot.register("127.0.0.1", env.tcpPort, "imgthumb-b")
                try {
                    val a = ImBot.register("127.0.0.1", env.tcpPort, "imgthumb-a")
                    try {
                        val chatId = a.createPersonalChat(b.uid)
                        // 主 url 必须指向文件存储中的真实文件（附件存在性校验）
                        val attachment = env.storeFile(ByteArray(64) { it.toByte() }, "im.png", "image/png")
                        val thumbnail = env.storeFile(ByteArray(32) { (it + 1).toByte() }, "im-thumb.jpg", "image/jpeg")
                        val ack = a.send(
                            chatId,
                            ImageBody(
                                attachment,
                                width = 800, height = 600,
                                thumbnail = thumbnail,
                            ),
                            com.virjar.tk.protocol.MessageType.IMAGE,
                        )
                        assertEquals(0, ack.code, "发送应成功: ${ack.reason}")
                        assertTrue(ack.serverSeq > 0)
                        val received = withTimeout(10_000) { b.nextMessage { it.senderUid == a.uid } }
                        val body = received.body as ImageBody
                        assertEquals(thumbnail, body.thumbnail, "缩略图描述符应完整传输")
                        assertEquals(attachment, body.attachment, "主附件描述符应完整传输")
                    } finally { a.shutdown() }
                } finally { b.shutdown() }
            }
        }
    }
}

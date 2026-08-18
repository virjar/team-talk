package com.virjar.tk.e2e

import com.virjar.tk.body.FileBody
import com.virjar.tk.body.ImageBody
import com.virjar.tk.bot.ImBot
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.model.Attachment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 媒体消息附件存在性拦截：发送成功 = 引用的附件在文件存储中真实存在。
 *
 * 架构约束（消息契约）：文件附件只走服务端文件存储；wire 存相对 path，
 * 完整 http URL 仅为对接形态（服务端剥 /api/v1/files/ 前缀后校验）。
 */
class FileValidationE2eTest {

    @Test
    fun `相对 path 引用真实文件 - 发送成功`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val b = ImBot.register("127.0.0.1", env.tcpPort, "fileval-b")
                val a = ImBot.register("127.0.0.1", env.tcpPort, "fileval-a")
                try {
                    val chatId = a.createPersonalChat(b.uid)
                    val attachment = env.storeFile("hello".toByteArray(), "a.txt")
                    val ack = a.send(chatId, FileBody(attachment), MessageType.FILE)
                    assertEquals(0, ack.code, "真实附件应发送成功: ${ack.reason}")
                } finally { a.shutdown(); b.shutdown() }
            }
        }
    }

    @Test
    fun `完整 URL 形态 - 剥前缀后校验成功`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val b = ImBot.register("127.0.0.1", env.tcpPort, "fileval2-b")
                val a = ImBot.register("127.0.0.1", env.tcpPort, "fileval2-a")
                try {
                    val chatId = a.createPersonalChat(b.uid)
                    val attachment = env.storeFile("hello".toByteArray(), "b.txt")
                    // 对接形态：完整 URL（base 随意，校验只认 /api/v1/files/ 前缀后的 path）
                    val fullUrl = "https://some-host.example/api/v1/files/${attachment.path}"
                    val ack = a.send(chatId, FileBody(attachment.copy(path = fullUrl)), MessageType.FILE)
                    assertEquals(0, ack.code, "完整 URL 指向真实文件应成功: ${ack.reason}")
                    val received = withTimeout(10_000) { b.nextMessage { it.senderUid == a.uid } }
                    assertEquals(attachment, (received.body as FileBody).attachment, "服务端必须下发权威相对路径描述符")
                } finally { a.shutdown(); b.shutdown() }
            }
        }
    }

    @Test
    fun `不存在的附件 path - 服务端拒绝`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val b = ImBot.register("127.0.0.1", env.tcpPort, "fileval3-b")
                val a = ImBot.register("127.0.0.1", env.tcpPort, "fileval3-a")
                try {
                    val chatId = a.createPersonalChat(b.uid)
                    val missing = Attachment("no-such-uid/ghost.txt", "ghost.txt", "text/plain", 5)
                    val ack = a.send(chatId, FileBody(missing), MessageType.FILE)
                    assertTrue(ack.code != 0, "断链附件必须被服务端拒绝")
                    assertTrue(ack.reason.orEmpty().contains("附件"), "拒绝理由应指向附件: ${ack.reason}")
                } finally { a.shutdown(); b.shutdown() }
            }
        }
    }

    @Test
    fun `伪造小文件大小 - 服务端拒绝`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val b = ImBot.register("127.0.0.1", env.tcpPort, "fileval-size-b")
                val a = ImBot.register("127.0.0.1", env.tcpPort, "fileval-size-a")
                try {
                    val chatId = a.createPersonalChat(b.uid)
                    val attachment = env.storeFile(ByteArray(2048), "large.bin")
                    val ack = a.send(chatId, FileBody(attachment.copy(size = 1)), MessageType.FILE)
                    assertTrue(ack.code != 0, "不能用虚假的小文件大小绕过静默下载阈值")
                    assertTrue(ack.reason.orEmpty().contains("元数据不匹配"), "拒绝理由应指出元数据不匹配: ${ack.reason}")
                } finally { a.shutdown(); b.shutdown() }
            }
        }
    }

    @Test
    fun `图片消息假 URL - 同样拦截`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val b = ImBot.register("127.0.0.1", env.tcpPort, "fileval4-b")
                val a = ImBot.register("127.0.0.1", env.tcpPort, "fileval4-a")
                try {
                    val chatId = a.createPersonalChat(b.uid)
                    // 带 TeamTalk 端点的 SDK 兼容 URL 会先归一化；不存在性由服务端权威拒绝。
                    val ack = a.send(chatId, ImageBody(
                        Attachment("https://third-party.example/api/v1/files/no-such/image.png", "image.png", "image/png", 1),
                        width = 1, height = 1,
                    ), MessageType.IMAGE)
                    assertTrue(ack.code != 0, "三方/断链图片 URL 必须被拒绝（文件只走服务端本身）")
                } finally { a.shutdown(); b.shutdown() }
            }
        }
    }

    @Test
    fun `缩略图不存在 - 服务端拒绝整条消息`() {
        TcpE2eEnvironment().use { env ->
            runBlocking {
                val b = ImBot.register("127.0.0.1", env.tcpPort, "fileval5-b")
                val a = ImBot.register("127.0.0.1", env.tcpPort, "fileval5-a")
                try {
                    val chatId = a.createPersonalChat(b.uid)
                    val attachment = env.storeFile(byteArrayOf(1, 2, 3), "real.png", "image/png")
                    val ack = a.send(chatId, ImageBody(
                        attachment, width = 1, height = 1,
                        thumbnail = Attachment("no-such/thumb.jpg", "thumb.jpg", "image/jpeg", 1),
                    ), MessageType.IMAGE)
                    assertTrue(ack.code != 0, "任一引用附件不存在都不能发送成功")
                } finally { a.shutdown(); b.shutdown() }
            }
        }
    }
}

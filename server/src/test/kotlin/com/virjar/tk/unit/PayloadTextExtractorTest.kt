package com.virjar.tk.unit

import com.virjar.tk.protocol.ChannelType
import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.service.PayloadTextExtractor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PayloadTextExtractor 测试：使用 Message 构造，验证提取结果。
 */
class PayloadTextExtractorTest {

    @Test
    fun `extract text from TEXT message`() {
        val message = createMessage(PacketType.TEXT) { TextBody("hello world", emptyList()) }
        assertEquals("hello world", PayloadTextExtractor.extract(message))
    }

    @Test
    fun `extract text from IMAGE message with caption`() {
        val message = createMessage(PacketType.IMAGE) {
            ImageBody("https://img.example.com/1.jpg", 800, 600, 102400, null, "a photo")
        }
        assertEquals("a photo", PayloadTextExtractor.extract(message))
    }

    @Test
    fun `extract null from IMAGE message without caption`() {
        val message = createMessage(PacketType.IMAGE) {
            ImageBody("https://img.example.com/1.jpg", 800, 600, 102400, null, null)
        }
        assertNull(PayloadTextExtractor.extract(message))
    }

    @Test
    fun `extract text from FILE message`() {
        val message = createMessage(PacketType.FILE) {
            FileBody("https://file.example.com/doc.pdf", "report.pdf", 5000, "application/pdf", null)
        }
        assertEquals("report.pdf", PayloadTextExtractor.extract(message))
    }

    @Test
    fun `extract text from REPLY message`() {
        val message = createMessage(PacketType.REPLY) {
            ReplyBody("orig-msg-1", "u1", "Alice", 20, "my reply", emptyList())
        }
        assertEquals("my reply", PayloadTextExtractor.extract(message))
    }

    @Test
    fun `extract text from CARD message`() {
        val message = createMessage(PacketType.CARD) {
            CardBody("user-1", "Alice", null, null)
        }
        assertEquals("Alice", PayloadTextExtractor.extract(message))
    }

    @Test
    fun `extract text from MERGE_FORWARD message`() {
        val message = createMessage(PacketType.MERGE_FORWARD) {
            MergeForwardBody("Chat History", emptyList(), emptyList())
        }
        assertEquals("Chat History", PayloadTextExtractor.extract(message))
    }

    @Test
    fun `extract null from unknown message type`() {
        // TYPING doesn't have searchable text
        val message = createMessage(PacketType.TYPING) {
            TypingBody(1)
        }
        assertNull(PayloadTextExtractor.extract(message))
    }

    private fun createMessage(packetType: PacketType, bodyFn: () -> MessageBody): Message {
        return Message(
            header = MessageHeader(
                channelId = "test-channel",
                messageId = "test-msg-id",
                senderUid = "test-user",
                channelType = ChannelType.PERSONAL,
            ),
            body = bodyFn(),
        )
    }
}

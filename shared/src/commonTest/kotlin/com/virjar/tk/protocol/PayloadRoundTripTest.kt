package com.virjar.tk.protocol

import com.virjar.tk.protocol.payload.*
import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Message round-trip 测试：验证 Message(header + body) 的 writeTo → Message.readFrom round-trip。
 */
class PayloadRoundTripTest {

    // ================================================================
    // Message writeTo → readFrom round-trip 验证
    // ================================================================

    @Test
    fun `TextBody round-trip`() {
        val header = MessageHeader(
            channelId = "ch-1", clientMsgNo = "msg-1", clientSeq = 1L,
            messageId = "mid-1", senderUid = "u1", channelType = 1,
            serverSeq = 100L, timestamp = 1700000000L, flags = 5,
        )
        val body = TextBody(text = "hello world", mentionUids = listOf("u2", "u3"))
        val message = Message(header, body)

        val buf = Unpooled.buffer()
        message.writeTo(buf)

        val decoded = Message.readFrom(buf, PacketType.TEXT)
        assertEquals("ch-1", decoded.channelId)
        assertEquals("msg-1", decoded.clientMsgNo)
        assertEquals(1L, decoded.clientSeq)
        assertEquals("mid-1", decoded.messageId)
        assertEquals("u1", decoded.senderUid)
        assertEquals(1, decoded.channelType)
        assertEquals(100L, decoded.serverSeq)
        assertEquals(1700000000L, decoded.timestamp)
        assertEquals(5, decoded.flags)

        val textBody = decoded.body as TextBody
        assertEquals("hello world", textBody.text)
        assertEquals(listOf("u2", "u3"), textBody.mentionUids)
        buf.release()
    }

    @Test
    fun `ImageBody round-trip`() {
        val header = MessageHeader(channelId = "ch-2", channelType = 2, flags = 3)
        val body = ImageBody(url = "https://img.example.com/1.jpg", width = 800, height = 600,
            size = 102400L, thumbnailUrl = "https://thumb.example.com/1.jpg", caption = "a photo")
        val message = Message(header, body)

        val buf = Unpooled.buffer()
        message.writeTo(buf)

        val decoded = Message.readFrom(buf, PacketType.IMAGE)
        assertEquals("ch-2", decoded.channelId)
        assertEquals(3, decoded.flags)

        val imageBody = decoded.body as ImageBody
        assertEquals("https://img.example.com/1.jpg", imageBody.url)
        assertEquals(800, imageBody.width)
        assertEquals(600, imageBody.height)
        assertEquals(102400L, imageBody.size)
        assertEquals("https://thumb.example.com/1.jpg", imageBody.thumbnailUrl)
        assertEquals("a photo", imageBody.caption)
        buf.release()
    }

    @Test
    fun `VoiceBody round-trip with waveform`() {
        val waveform = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val header = MessageHeader(channelId = "ch-3", flags = 7)
        val body = VoiceBody(url = "https://voice.example.com/1.amr", duration = 30, size = 5000L, waveform = waveform)
        val message = Message(header, body)

        val buf = Unpooled.buffer()
        message.writeTo(buf)

        val decoded = Message.readFrom(buf, PacketType.VOICE)
        val voiceBody = decoded.body as VoiceBody
        assertEquals("https://voice.example.com/1.amr", voiceBody.url)
        assertEquals(30, voiceBody.duration)
        assertEquals(5000L, voiceBody.size)
        assertTrue(waveform.contentEquals(voiceBody.waveform!!))
        assertEquals(7, decoded.flags)
        buf.release()
    }

    @Test
    fun `FileBody round-trip`() {
        val header = MessageHeader(channelId = "ch-5", flags = 2)
        val body = FileBody(url = "https://file.example.com/doc.pdf", fileName = "report.pdf",
            fileSize = 5_000_000L, mimeType = "application/pdf", thumbnailUrl = null)
        val message = Message(header, body)

        val buf = Unpooled.buffer()
        message.writeTo(buf)

        val decoded = Message.readFrom(buf, PacketType.FILE)
        val fileBody = decoded.body as FileBody
        assertEquals("https://file.example.com/doc.pdf", fileBody.url)
        assertEquals("report.pdf", fileBody.fileName)
        assertEquals(5_000_000L, fileBody.fileSize)
        assertEquals("application/pdf", fileBody.mimeType)
        assertEquals(null, fileBody.thumbnailUrl)
        assertEquals(2, decoded.flags)
        buf.release()
    }

    @Test
    fun `ReplyBody round-trip`() {
        val header = MessageHeader(
            channelId = "ch-r", clientMsgNo = "msg-r", clientSeq = 10L,
            messageId = "mid-r", senderUid = "u-r", channelType = 1,
            serverSeq = 50L, timestamp = 1000L, flags = 1,
        )
        val body = ReplyBody(replyToMessageId = "orig-msg", replyToSenderUid = "orig-u",
            replyToSenderName = "Alice", replyToPacketType = 20,
            text = "my reply", mentionUids = emptyList())
        val message = Message(header, body)

        val buf = Unpooled.buffer()
        message.writeTo(buf)

        val decoded = Message.readFrom(buf, PacketType.REPLY)
        val replyBody = decoded.body as ReplyBody
        assertEquals("orig-msg", replyBody.replyToMessageId)
        assertEquals("orig-u", replyBody.replyToSenderUid)
        assertEquals("Alice", replyBody.replyToSenderName)
        assertEquals(20, replyBody.replyToPacketType.toInt())
        assertEquals("my reply", replyBody.text)
        assertEquals(emptyList<String>(), replyBody.mentionUids)
        assertEquals(1, decoded.flags)
        buf.release()
    }

    @Test
    fun `EditBody round-trip`() {
        val header = MessageHeader(
            channelId = "ch-e", clientMsgNo = "msg-e", clientSeq = 20L,
            messageId = "mid-e", senderUid = "u-e", channelType = 1,
            serverSeq = 200L, timestamp = 2000L,
        )
        val body = EditBody(targetMessageId = "target-msg", newContent = "edited text", editedAt = 3000L)
        val message = Message(header, body)

        val buf = Unpooled.buffer()
        message.writeTo(buf)

        val decoded = Message.readFrom(buf, PacketType.EDIT)
        val editBody = decoded.body as EditBody
        assertEquals("target-msg", editBody.targetMessageId)
        assertEquals("edited text", editBody.newContent)
        assertEquals(3000L, editBody.editedAt)
        assertEquals(0, decoded.flags)
        buf.release()
    }

    // ================================================================
    // JSON round-trip 验证
    // ================================================================

    @Test
    fun `Message JSON round-trip`() {
        val header = MessageHeader(
            channelId = "ch-1", clientMsgNo = "msg-1", clientSeq = 1L,
            messageId = "mid-1", senderUid = "u1", channelType = 1,
            serverSeq = 100L, timestamp = 1700000000L, flags = 1,
        )
        val body = TextBody("hello", listOf("a"))
        val message = Message(header, body)

        val json = message.toJson("TestUser")
        val decoded = Message.fromJson(json)!!

        assertEquals("ch-1", decoded.channelId)
        assertEquals("mid-1", decoded.messageId)
        assertEquals("u1", decoded.senderUid)
        assertEquals(100L, decoded.serverSeq)
        assertEquals(1, decoded.flags)

        val textBody = decoded.body as TextBody
        assertEquals("hello", textBody.text)
        assertEquals(listOf("a"), textBody.mentionUids)
    }
}

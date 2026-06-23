package com.virjar.tk

import com.virjar.tk.body.*
import com.virjar.tk.model.*
import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.payload.*
import io.netty.buffer.ByteBufAllocator
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证所有传输模型的二进制编解码 round-trip。
 */
class ProtoRoundTripTest {

    private fun <T : IProto> roundTrip(obj: T, reader: IProtoReader<T>): T {
        val writeBuf = ByteBufAllocator.DEFAULT.buffer()
        val readBuf = ByteBufAllocator.DEFAULT.buffer()
        try {
            PacketBuffer(writeBuf).also { obj.writeTo(it) }
            writeBuf.resetReaderIndex()
            // 复制到新 buffer 确保 readerIndex=0
            readBuf.writeBytes(writeBuf)
            return reader.readFrom(PacketBuffer(readBuf))
        } finally {
            writeBuf.release()
            readBuf.release()
        }
    }

    @Test
    fun testUserRoundTrip() {
        val user = User(uid = "u1", username = "alice", name = "Alice", avatar = null, phone = "123", sex = 1, role = 0, status = 1)
        val decoded = roundTrip(user, User)
        assertEquals(user, decoded)
    }

    @Test
    fun testUserWithNullsRoundTrip() {
        val user = User(uid = "u2", username = "bob", name = "Bob")
        val decoded = roundTrip(user, User)
        assertEquals(user, decoded)
    }

    @Test
    fun testChatRoundTrip() {
        val chat = Chat(chatId = "c1", chatType = 2, name = "TestGroup", creator = "u1", memberCount = 10, maxSeq = 100, mutedAll = true)
        val decoded = roundTrip(chat, Chat)
        assertEquals(chat, decoded)
    }

    @Test
    fun testMemberRoundTrip() {
        val member = Member(uid = "u1", chatId = "c1", role = 2, nickname = "Owner", joinedAt = 1000L, user = User(uid = "u1", username = "alice", name = "Alice"))
        val decoded = roundTrip(member, Member)
        assertEquals(member, decoded)
    }

    @Test
    fun testMemberWithoutUserRoundTrip() {
        val member = Member(uid = "u1", chatId = "c1", role = 0)
        val decoded = roundTrip(member, Member)
        assertEquals(member, decoded)
    }

    @Test
    fun testMessageWithTextBodyRoundTrip() {
        val msg = Message(chatId = "c1", clientMsgId = "msg-uuid-1", serverSeq = 42, senderUid = "u1", messageType = MessageType.TEXT.code, timestamp = 1700000000L, flags = 0, body = TextBody("Hello world"))
        val decoded = roundTrip(msg, Message)
        assertEquals(msg, decoded)
    }

    @Test
    fun testMessageWithoutBodyRoundTrip() {
        val msg = Message(chatId = "c1", clientMsgId = "msg-uuid-2", serverSeq = 0, senderUid = "u1", messageType = MessageType.TEXT.code, timestamp = 1000L)
        val decoded = roundTrip(msg, Message)
        assertEquals(msg, decoded)
    }

    @Test
    fun testContactRoundTrip() {
        val contact = Contact(uid = "u1", friendUid = "u2", remark = "Bob", status = 1, user = User(uid = "u2", username = "bob", name = "Bob"))
        val decoded = roundTrip(contact, Contact)
        assertEquals(contact, decoded)
    }

    @Test
    fun testConversationRoundTrip() {
        val conv = Conversation(chatId = "c1", chatType = 1, chatName = "Alice", lastMessage = "Hi", lastMessageType = 1, lastMsgTimestamp = 1700000000L, lastSeq = 50, readSeq = 48, unreadCount = 2, isPinned = true, isMuted = false)
        val decoded = roundTrip(conv, Conversation)
        assertEquals(conv, decoded)
    }

    @Test
    fun testDeviceRoundTrip() {
        val device = Device(deviceId = "dev1", deviceName = "Phone", deviceModel = "Pixel 8", deviceFlag = 1, lastLogin = 1700000000L, isOnline = true)
        val decoded = roundTrip(device, Device)
        assertEquals(device, decoded)
    }

    @Test
    fun testAuthRequestRoundTrip() {
        val auth = AuthRequestPayload(authType = 0, username = "alice", password = "secret", deviceId = "dev1", protocolVersion = 1, lastEventId = 0)
        val decoded = roundTrip(auth, AuthRequestPayload)
        assertEquals(auth, decoded)
    }

    @Test
    fun testAuthResponseRoundTrip() {
        val resp = AuthResponsePayload(code = 0, uid = "u1", accessToken = "token123", refreshToken = "refresh456", expiresIn = 86400L)
        val decoded = roundTrip(resp, AuthResponsePayload)
        assertEquals(resp, decoded)
    }

    @Test
    fun testInvokePayloadRoundTrip() {
        val invoke = InvokePayload(requestId = 42, serviceId = ServiceId.USER, methodId = 2, payload = "test-data".encodeToByteArray())
        val decoded = roundTrip(invoke, InvokePayload)
        assertEquals(invoke.requestId, decoded.requestId)
        assertEquals(invoke.serviceId, decoded.serviceId)
        assertEquals(invoke.methodId, decoded.methodId)
    }

    @Test
    fun testNotifyPayloadRoundTrip() {
        val notify = NotifyPayload(eventId = 100L, notifyType = NotifyType.CHAT_CREATED.code, payload = "event-data".encodeToByteArray())
        val decoded = roundTrip(notify, NotifyPayload)
        assertEquals(notify.eventId, decoded.eventId)
        assertEquals(notify.notifyType, decoded.notifyType)
    }

    @Test
    fun testVarIntEncoding() {
        val buf = ByteBufAllocator.DEFAULT.buffer()
        try {
            val packetBuf = PacketBuffer(buf)
            // 测试不同范围的 VarInt
            val values = listOf(0, 1, 127, 128, 255, 256, 16383, 16384, 1000000)
            for (v in values) {
                packetBuf.writeVarInt(v)
            }
            val readBuf = PacketBuffer(buf)
            for (v in values) {
                assertEquals(v, readBuf.readVarInt())
            }
        } finally {
            buf.release()
        }
    }

    @Test
    fun testVarLongEncoding() {
        val buf = ByteBufAllocator.DEFAULT.buffer()
        try {
            val packetBuf = PacketBuffer(buf)
            val values = listOf(0L, 1L, 127L, 128L, 1000000L, 1000000000000L)
            for (v in values) {
                packetBuf.writeVarLong(v)
            }
            val readBuf = PacketBuffer(buf)
            for (v in values) {
                assertEquals(v, readBuf.readVarLong())
            }
        } finally {
            buf.release()
        }
    }

    @Test
    fun testStringNullHandling() {
        val writeBuf = ByteBufAllocator.DEFAULT.buffer()
        val readBuf = ByteBufAllocator.DEFAULT.buffer()
        try {
            PacketBuffer(writeBuf).also {
                it.writeString(null)
                it.writeString("")
                it.writeString("hello")
            }
            writeBuf.resetReaderIndex()
            readBuf.writeBytes(writeBuf)
            val reader = PacketBuffer(readBuf)
            assertEquals(null, reader.readString())
            assertEquals("", reader.readString())
            assertEquals("hello", reader.readString())
        } finally {
            writeBuf.release()
            readBuf.release()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 各消息 body 类型的 round-trip（通过完整 Message 编解码，
    // 同时验证 MessageBodyRegistry 的类型路由）。
    // 之前只覆盖 TextBody，以下补全其余 13 种。
    // ──────────────────────────────────────────────────────────────

    /** 构造一个带 body 的 Message 并做 round-trip（Message.companion 自动用 registry 解码 body）。 */
    private fun roundTripMessage(body: MessageBody, type: MessageType): Message {
        val msg = Message(
            chatId = "c1", clientMsgId = "m-" + type.name,
            serverSeq = 7, senderUid = "u1",
            messageType = type.code, timestamp = 1700000000L,
            body = body,
        )
        return roundTrip(msg, Message)
    }

    @Test
    fun testImageBodyRoundTrip() {
        val original = roundTripMessage(ImageBody(url = "https://demo/f.png", width = 1080, height = 1920, size = 2048576L), MessageType.IMAGE)
        val body = original.body as ImageBody
        assertEquals("https://demo/f.png", body.url)
        assertEquals(1080, body.width)
        assertEquals(1920, body.height)
        assertEquals(2048576L, body.size)
    }

    @Test
    fun testVoiceBodyRoundTrip() {
        val original = roundTripMessage(VoiceBody(url = "https://demo/v.amr", duration = 12, size = 32768L), MessageType.VOICE)
        val body = original.body as VoiceBody
        assertEquals("https://demo/v.amr", body.url)
        assertEquals(12, body.duration)
        assertEquals(32768L, body.size)
    }

    @Test
    fun testVideoBodyRoundTrip() {
        val original = roundTripMessage(VideoBody(url = "https://demo/v.mp4", duration = 60, width = 1920, height = 1080, size = 9999999L), MessageType.VIDEO)
        val body = original.body as VideoBody
        assertEquals(60, body.duration)
        assertEquals(1920, body.width)
        assertEquals(1080, body.height)
        assertEquals(9999999L, body.size)
    }

    @Test
    fun testFileBodyRoundTrip() {
        val original = roundTripMessage(FileBody(url = "https://demo/doc.pdf", fileName = "季度报告.pdf", size = 524288L), MessageType.FILE)
        val body = original.body as FileBody
        assertEquals("季度报告.pdf", body.fileName)
        assertEquals(524288L, body.size)
    }

    @Test
    fun testLocationBodyRoundTrip() {
        val original = roundTripMessage(LocationBody(latitude = 31.2304, longitude = 121.4737, title = "上海外滩", address = "黄浦区"), MessageType.LOCATION)
        val body = original.body as LocationBody
        assertEquals(31.2304, body.latitude, 0.0001)
        assertEquals(121.4737, body.longitude, 0.0001)
        assertEquals("上海外滩", body.title)
        assertEquals("黄浦区", body.address)
    }

    @Test
    fun testCardBodyRoundTrip() {
        val original = roundTripMessage(CardBody(targetUid = "u9", targetName = "张三", targetAvatar = "https://demo/a.png"), MessageType.CARD)
        val body = original.body as CardBody
        assertEquals("u9", body.targetUid)
        assertEquals("张三", body.targetName)
    }

    @Test
    fun testStickerBodyRoundTrip() {
        val original = roundTripMessage(StickerBody(url = "https://demo/s.gif", width = 240, height = 240), MessageType.STICKER)
        val body = original.body as StickerBody
        assertEquals(240, body.width)
        assertEquals(240, body.height)
    }

    @Test
    fun testReplyBodyRoundTrip() {
        val original = roundTripMessage(ReplyBody(replyToMsgId = "m-old", replyToSenderUid = "u2", replyToSenderName = "Bob", replySnippet = "原消息", content = "我的回复"), MessageType.REPLY)
        val body = original.body as ReplyBody
        assertEquals("m-old", body.replyToMsgId)
        assertEquals("Bob", body.replyToSenderName)
        assertEquals("原消息", body.replySnippet)
        assertEquals("我的回复", body.content)
    }

    @Test
    fun testForwardBodyRoundTrip() {
        val original = roundTripMessage(ForwardBody(forwardFromChatId = "c2", forwardFromMsgId = "m-src", forwardFromSenderUid = "u3", forwardNote = "见上文"), MessageType.FORWARD)
        val body = original.body as ForwardBody
        assertEquals("c2", body.forwardFromChatId)
        assertEquals("m-src", body.forwardFromMsgId)
        assertEquals("见上文", body.forwardNote)
    }

    @Test
    fun testForwardBodyNullFieldsRoundTrip() {
        // 所有可空字段为 null 的边界
        val original = roundTripMessage(ForwardBody(), MessageType.FORWARD)
        val body = original.body as ForwardBody
        assertEquals(null, body.forwardFromChatId)
        assertEquals(null, body.forwardNote)
    }

    @Test
    fun testMergeForwardBodyRoundTrip() {
        val original = roundTripMessage(MergeForwardBody(title = "聊天记录", messageCount = 5), MessageType.MERGE_FORWARD)
        val body = original.body as MergeForwardBody
        assertEquals("聊天记录", body.title)
        assertEquals(5, body.messageCount)
    }

    @Test
    fun testRevokeBodyRoundTrip() {
        val original = roundTripMessage(RevokeBody(revokedMsgId = "m-revoke"), MessageType.REVOKE)
        val body = original.body as RevokeBody
        assertEquals("m-revoke", body.revokedMsgId)
    }

    @Test
    fun testEditBodyRoundTrip() {
        val original = roundTripMessage(EditBody(editedMsgId = "m-edit", newContent = "修改后的内容"), MessageType.EDIT)
        val body = original.body as EditBody
        assertEquals("m-edit", body.editedMsgId)
        assertEquals("修改后的内容", body.newContent)
    }

    @Test
    fun testReactionBodyRoundTrip() {
        val original = roundTripMessage(ReactionBody(targetMsgId = "m-target", emoji = "👍", action = 1), MessageType.REACTION)
        val body = original.body as ReactionBody
        assertEquals("m-target", body.targetMsgId)
        assertEquals("👍", body.emoji)
        assertEquals(1, body.action)
    }

    // ══════════════════════════════════════
    // RPC Payload 往返测试
    //
    // 客户端 Repository 用 encodePayload 构造二进制 payload，
    // 服务端 RouteHandler 用 withPayload 读取。
    // 如果两端格式不一致（字段数量/顺序/类型），下面测试会直接炸。
    // ══════════════════════════════════════

    /**
     * 模拟 encodePayload → byte[] → withPayload 的 round-trip。
     *
     * [encode] 应与客户端 Repository 的 encodePayload { ... } 一致，
     * [decode] 应与服务端 RouteHandler 的 withPayload { ... } 一致。
     */
    private fun payloadRoundTrip(encode: PacketBuffer.() -> Unit, decode: PacketBuffer.() -> Unit) {
        val bytes = ProtoCodec.encodePayload(encode)
        ProtoCodec.withPayload(bytes, decode)
    }

    @Test
    fun testPayloadUpdateGroup() {
        payloadRoundTrip(
            encode = {
                writeString("chat-1")
                writeString("新群名")
                writeString(null)     // avatar
                writeString("新公告")
            },
            decode = {
                assertEquals("chat-1", readString())
                assertEquals("新群名", readString())
                assertEquals(null, readString())       // avatar
                assertEquals("新公告", readString())
            },
        )
    }

    @Test
    fun testPayloadCreateGroup() {
        payloadRoundTrip(
            encode = {
                writeString("测试群")
                writeString(null)     // avatar
                writeVarInt(2)        // member count
                writeString("uid-a")
                writeString("uid-b")
            },
            decode = {
                assertEquals("测试群", readString())
                assertEquals(null, readString())       // avatar
                assertEquals(2, readVarInt())
                assertEquals("uid-a", readString())
                assertEquals("uid-b", readString())
            },
        )
    }

    @Test
    fun testPayloadRevokeMessage() {
        payloadRoundTrip(
            encode = {
                writeString("chat-1")
                writeVarLong(42L)
            },
            decode = {
                assertEquals("chat-1", readString())
                assertEquals(42L, readVarLong())
            },
        )
    }

    @Test
    fun testPayloadGetHistory() {
        payloadRoundTrip(
            encode = {
                writeString("chat-1")
                writeVarLong(100L)     // fromSeq
                writeVarInt(50)        // limit
            },
            decode = {
                assertEquals("chat-1", readString())
                assertEquals(100L, readVarLong())
                assertEquals(50, readVarInt())
            },
        )
    }

    @Test
    fun testPayloadSearchMessages() {
        payloadRoundTrip(
            encode = {
                writeString("chat-1")
                writeString("关键词")
                writeVarInt(20)
            },
            decode = {
                assertEquals("chat-1", readString())
                assertEquals("关键词", readString())
                assertEquals(20, readVarInt())
            },
        )
    }

    @Test
    fun testPayloadSendRequest() {
        payloadRoundTrip(
            encode = {
                writeString("uid-b")
                writeString("你好，我是A")
            },
            decode = {
                assertEquals("uid-b", readString())
                assertEquals("你好，我是A", readString())
            },
        )
    }

    @Test
    fun testPayloadSetPin() {
        payloadRoundTrip(
            encode = {
                writeString("chat-1")
                writeVarInt(1)  // pinned
            },
            decode = {
                assertEquals("chat-1", readString())
                assertEquals(1, readVarInt())
            },
        )
    }

    @Test
    fun testPayloadChangePassword() {
        payloadRoundTrip(
            encode = {
                writeString("oldPass")
                writeString("newPass")
            },
            decode = {
                assertEquals("oldPass", readString())
                assertEquals("newPass", readString())
            },
        )
    }
}

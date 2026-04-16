package com.virjar.tk.tcp

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.service.*
import com.virjar.tk.store.ChannelStore
import com.virjar.tk.store.ContactStore
import com.virjar.tk.store.ConversationStore
import com.virjar.tk.store.DeviceStore
import com.virjar.tk.store.UserStore
import com.virjar.tk.tcp.agent.MessageDispatcher
import com.virjar.tk.tcp.agent.SubscribeDispatcher
import com.virjar.tk.tcp.agent.TypingDispatcher
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TcpIntegrationTest {

    companion object {
        private const val TCP_PORT = 15100
        private lateinit var eventLoopGroup: NioEventLoopGroup
        private lateinit var tcpServer: TcpServer
        private lateinit var tokenService: TokenService
        private lateinit var messageService: MessageService
        private val logger = LoggerFactory.getLogger(TcpIntegrationTest::class.java)

        @JvmStatic
        @BeforeAll
        fun setUp() {
            eventLoopGroup = NioEventLoopGroup()

            // 单例 Service 初始化
            TokenService.init("TeamTalk2026SecretKeyForJWTTokenGenerationMustBeAtLeast256Bits")
            tokenService = TokenService

            val messageStore = com.virjar.tk.db.MessageStore("/tmp/teamtalk-test-rocksdb")
            messageStore.start()
            val channelStore = ChannelStore()
            val userStore = UserStore()
            val conversationStore = ConversationStore()
            messageService = MessageService(messageStore, null, channelStore, userStore, conversationStore)

            MessageDeliveryService.init(channelStore)
            DeviceService.init(DeviceStore())
            PresenceService.init(ContactStore())

            MessageDispatcher.init(messageService, channelStore)
            TypingDispatcher.init(channelStore)
            SubscribeDispatcher.init(messageService)

            ClientRegistry.onLastDeviceOffline = { uid ->
                PresenceService.postBroadcastOffline(uid)
            }

            tcpServer = TcpServer(TCP_PORT)
            tcpServer.start()
            Thread.sleep(500)
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            tcpServer.stop()
            eventLoopGroup.shutdownGracefully()
        }
    }

    private fun generateToken(uid: String): String {
        return tokenService.generateTokenPair(uid).accessToken
    }

    // ================================================================
    // 测试用例
    // ================================================================

    @Test
    fun `auth with valid JWT token receives AUTH_RESP success`() {
        val uid = "test-user-1"
        val token = generateToken(uid)
        val responseRef = AtomicReference<AuthResponsePayload>(null)
        val latch = CountDownLatch(1)

        val client = connectAndAuth(token, uid, "device-1") { authResp ->
            responseRef.set(authResp)
            latch.countDown()
        }

        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timeout waiting for AUTH_RESP")
            val response = responseRef.get()
            assertNotNull(response)
            assertEquals(AuthResponsePayload.CODE_OK, response.code)
        } finally {
            client.close()
        }
    }

    @Test
    fun `auth with invalid token receives AUTH_RESP failure`() {
        val responseRef = AtomicReference<AuthResponsePayload>(null)
        val latch = CountDownLatch(1)

        val client = connectAndAuth("invalid-token", "test-user", "device-1") { authResp ->
            responseRef.set(authResp)
            latch.countDown()
        }

        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timeout waiting for AUTH_RESP")
            val response = responseRef.get()
            assertNotNull(response)
            assertEquals(AuthResponsePayload.CODE_AUTH_FAILED, response.code)
        } finally {
            client.close()
        }
    }

    @Test
    fun `PING receives PONG after auth`() {
        val uid = "ping-user"
        val token = generateToken(uid)

        val authLatch = CountDownLatch(1)
        val pongLatch = CountDownLatch(1)
        val typeRef = AtomicReference<PacketType>(null)

        val client = connectRawClient { ch ->
            ch.pipeline().addLast(TestPacketHandler(authLatch, pongLatch, typeRef, null, "PING-test"))
        }

        try {
            performAuth(client, token, uid)
            assertTrue(authLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for AUTH_RESP")

            // 发送 PING
            val pingBuf = Unpooled.buffer()
            pingBuf.writeByte(PacketType.PING.code.toInt())
            pingBuf.writeInt(0)
            client.writeAndFlush(pingBuf)

            assertTrue(pongLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for PONG")
            assertNotNull(typeRef.get())
            assertEquals(PacketType.PONG, typeRef.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun `TEXT message after auth receives SENDACK`() {
        val uid = "sender-user"
        val token = generateToken(uid)

        val authLatch = CountDownLatch(1)
        val sendAckLatch = CountDownLatch(1)
        val typeRef = AtomicReference<PacketType>(null)
        val payloadRef = AtomicReference<ByteBuf>(null)

        val client = connectRawClient { ch ->
            ch.pipeline().addLast(TestPacketHandler(authLatch, sendAckLatch, typeRef, payloadRef, "TEXT-test"))
        }

        try {
            performAuth(client, token, uid)
            assertTrue(authLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for auth")

            // 发送 TEXT 消息
            val message = Message(
                MessageHeader(channelId = "channel-1", clientMsgNo = "msg-no-1", clientSeq = 1L, channelType = ChannelType.PERSONAL),
                TextBody("hello", emptyList())
            )
            sendMessagePacket(client, PacketType.TEXT, message)

            assertTrue(sendAckLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for SENDACK")
            assertEquals(PacketType.SENDACK, typeRef.get())

            val ack = SendAckPayload.create(payloadRef.get())
            assertEquals(0, ack.code.toInt())
            assertEquals("msg-no-1", ack.clientMsgNo)
            assertTrue(ack.messageId.isNotEmpty())
        } finally {
            payloadRef.get()?.release()
            client.close()
        }
    }

    @Test
    fun `ClientRegistry tracks online users`() {
        val uid = "online-user"
        val token = generateToken(uid)
        val latch = CountDownLatch(1)

        val client = connectAndAuth(token, uid, "d1") { _ ->
            latch.countDown()
        }

        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timeout waiting for auth")
            Thread.sleep(200)
            kotlinx.coroutines.runBlocking {
                assertTrue(ClientRegistry.isUserOnline(uid))
                assertTrue(ClientRegistry.getOnlineUserCount() > 0)
            }
        } finally {
            client.close()
        }
    }

    // ================================================================
    // 非 TEXT 消息发送测试
    // ================================================================

    @Test
    fun `IMAGE message receives SENDACK`() {
        val uid = "image-sender"
        val token = generateToken(uid)

        val authLatch = CountDownLatch(1)
        val sendAckLatch = CountDownLatch(1)
        val typeRef = AtomicReference<PacketType>(null)
        val payloadRef = AtomicReference<ByteBuf>(null)

        val client = connectRawClient { ch ->
            ch.pipeline().addLast(TestPacketHandler(authLatch, sendAckLatch, typeRef, payloadRef, "IMAGE-test"))
        }

        try {
            performAuth(client, token, uid)
            assertTrue(authLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for auth")

            val message = Message(
                MessageHeader(channelId = "ch-img", clientMsgNo = "msg-img-1", clientSeq = 1L, channelType = ChannelType.PERSONAL),
                ImageBody(url = "https://img.example.com/test.jpg", width = 800, height = 600,
                    size = 50000L, thumbnailUrl = null, caption = "test image")
            )
            sendMessagePacket(client, PacketType.IMAGE, message)

            assertTrue(sendAckLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for SENDACK")
            assertEquals(PacketType.SENDACK, typeRef.get())

            val ack = SendAckPayload.create(payloadRef.get())
            assertEquals(0, ack.code.toInt())
            assertTrue(ack.messageId.isNotEmpty())
        } finally {
            payloadRef.get()?.release()
            client.close()
        }
    }

    @Test
    fun `FILE message receives SENDACK`() {
        val uid = "file-sender"
        val token = generateToken(uid)

        val authLatch = CountDownLatch(1)
        val sendAckLatch = CountDownLatch(1)
        val typeRef = AtomicReference<PacketType>(null)
        val payloadRef = AtomicReference<ByteBuf>(null)

        val client = connectRawClient { ch ->
            ch.pipeline().addLast(TestPacketHandler(authLatch, sendAckLatch, typeRef, payloadRef, "FILE-test"))
        }

        try {
            performAuth(client, token, uid)
            assertTrue(authLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for auth")

            val message = Message(
                MessageHeader(channelId = "ch-file", clientMsgNo = "msg-file-1", clientSeq = 1L, channelType = ChannelType.PERSONAL),
                FileBody(url = "https://file.example.com/doc.pdf", fileName = "report.pdf",
                    fileSize = 5000L, mimeType = "application/pdf", thumbnailUrl = null)
            )
            sendMessagePacket(client, PacketType.FILE, message)

            assertTrue(sendAckLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for SENDACK")
            assertEquals(PacketType.SENDACK, typeRef.get())

            val ack = SendAckPayload.create(payloadRef.get())
            assertEquals(0, ack.code.toInt())
            assertTrue(ack.messageId.isNotEmpty())
        } finally {
            payloadRef.get()?.release()
            client.close()
        }
    }

    @Test
    fun `VOICE message receives SENDACK`() {
        val uid = "voice-sender"
        val token = generateToken(uid)

        val authLatch = CountDownLatch(1)
        val sendAckLatch = CountDownLatch(1)
        val typeRef = AtomicReference<PacketType>(null)
        val payloadRef = AtomicReference<ByteBuf>(null)

        val client = connectRawClient { ch ->
            ch.pipeline().addLast(TestPacketHandler(authLatch, sendAckLatch, typeRef, payloadRef, "VOICE-test"))
        }

        try {
            performAuth(client, token, uid)
            assertTrue(authLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for auth")

            val message = Message(
                MessageHeader(channelId = "ch-voice", clientMsgNo = "msg-voice-1", clientSeq = 1L, channelType = ChannelType.PERSONAL),
                VoiceBody(url = "https://voice.example.com/test.amr", duration = 30, size = 5000L,
                    waveform = byteArrayOf(1, 2, 3, 4, 5))
            )
            sendMessagePacket(client, PacketType.VOICE, message)

            assertTrue(sendAckLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for SENDACK")
            assertEquals(PacketType.SENDACK, typeRef.get())

            val ack = SendAckPayload.create(payloadRef.get())
            assertEquals(0, ack.code.toInt())
            assertTrue(ack.messageId.isNotEmpty())
        } finally {
            payloadRef.get()?.release()
            client.close()
        }
    }

    @Test
    fun `send message without auth receives no response`() {
        val packetLatch = CountDownLatch(1)
        val typeRef = AtomicReference<PacketType>(null)

        val client = connectRawClient { ch ->
            // 只跳过 MAGIC 读取，不做 auth
            ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                private var magicConsumed = false
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg !is ByteBuf) return
                    if (!magicConsumed) {
                        // 跳过 MAGIC_WITH_VERSION (8 bytes)
                        if (msg.readableBytes() >= IProto.MAGIC_WITH_VERSION.size) {
                            msg.skipBytes(IProto.MAGIC_WITH_VERSION.size)
                            magicConsumed = true
                        }
                    }
                    if (msg.isReadable) {
                        tryDecodeFrames(msg)
                    }
                    msg.release()
                }

                private fun tryDecodeFrames(buf: ByteBuf) {
                    while (buf.readableBytes() >= 5) {
                        buf.markReaderIndex()
                        val typeCode = buf.readByte()
                        val length = buf.readInt()
                        val packetType = PacketType.fromCode(typeCode)
                        if (packetType == null || length < 0 || buf.readableBytes() < length) {
                            buf.resetReaderIndex()
                            return
                        }
                        buf.skipBytes(length)
                        typeRef.set(packetType)
                        packetLatch.countDown()
                    }
                }
            })
        }

        try {
            // 不做 auth，直接发消息
            val message = Message(
                MessageHeader(channelId = "ch-1", clientMsgNo = "msg-noauth", clientSeq = 1L, channelType = ChannelType.PERSONAL),
                TextBody("should be ignored", emptyList())
            )
            sendMessagePacket(client, PacketType.TEXT, message)

            val received = packetLatch.await(2, TimeUnit.SECONDS)
            assertTrue(!received || typeRef.get() == null,
                "Should not receive response for unauthenticated message")
        } finally {
            client.close()
        }
    }

    // ================================================================
    // 辅助类和方法
    // ================================================================

    /**
     * 连接并完成 auth，通过回调接收 AuthResponsePayload。
     */
    private fun connectAndAuth(
        token: String, uid: String, deviceId: String,
        onAuthResp: (AuthResponsePayload) -> Unit
    ): Channel {
        val client = connectRawClient { ch ->
            ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                private var magicConsumed = false
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    if (msg !is ByteBuf) return
                    if (!magicConsumed) {
                        if (msg.readableBytes() >= IProto.MAGIC_WITH_VERSION.size) {
                            msg.skipBytes(IProto.MAGIC_WITH_VERSION.size)
                            magicConsumed = true
                        }
                    }
                    if (msg.isReadable) {
                        // 尝试解码 AUTH_RESP 帧
                        while (msg.readableBytes() >= 5) {
                            msg.markReaderIndex()
                            val typeCode = msg.readByte()
                            val length = msg.readInt()
                            val pt = PacketType.fromCode(typeCode)
                            if (pt == null || length < 0 || msg.readableBytes() < length) {
                                msg.resetReaderIndex()
                                break
                            }
                            if (pt == PacketType.AUTH_RESP && length > 0) {
                                val slice = msg.retainedSlice(msg.readerIndex(), length)
                                msg.skipBytes(length)
                                onAuthResp(AuthResponsePayload(slice))
                                slice.release()
                            } else {
                                msg.skipBytes(length)
                            }
                        }
                    }
                    msg.release()
                }
            })
        }

        // 发送 AUTH 包
        performAuth(client, token, uid, deviceId)
        return client
    }

    /**
     * 测试用 Handler：先处理 AUTH_RESP，然后自动解码后续的数据帧。
     */
    private class TestPacketHandler(
        private val authLatch: CountDownLatch,
        private val packetLatch: CountDownLatch,
        private val typeRef: AtomicReference<PacketType>,
        private val payloadRef: AtomicReference<ByteBuf>?,
        private val tag: String,
    ) : ChannelInboundHandlerAdapter() {
        private val logger = LoggerFactory.getLogger(TestPacketHandler::class.java)
        private var magicConsumed = false
        private var authDone = false

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg !is ByteBuf) return

            if (!magicConsumed) {
                // 消费 MAGIC_WITH_VERSION (8 bytes)
                if (msg.readableBytes() >= IProto.MAGIC_WITH_VERSION.size) {
                    msg.skipBytes(IProto.MAGIC_WITH_VERSION.size)
                    magicConsumed = true
                }
            }

            if (!authDone) {
                // 尝试解码 AUTH_RESP
                if (msg.readableBytes() >= 5) {
                    msg.markReaderIndex()
                    val typeCode = msg.readByte()
                    val length = msg.readInt()
                    val pt = PacketType.fromCode(typeCode)
                    if (pt == PacketType.AUTH_RESP && length >= 0 && msg.readableBytes() >= length) {
                        msg.skipBytes(length)
                        authDone = true
                        authLatch.countDown()
                        logger.info("[{}] AUTH_RESP received", tag)
                    } else {
                        msg.resetReaderIndex()
                    }
                }
            }

            if (authDone && msg.isReadable) {
                tryDecodeFrames(msg)
            }
            msg.release()
        }

        private fun tryDecodeFrames(buf: ByteBuf) {
            while (buf.readableBytes() >= 5) {
                buf.markReaderIndex()
                val typeCode = buf.readByte()
                val length = buf.readInt()

                val packetType = PacketType.fromCode(typeCode)
                logger.info("[{}] Frame header: typeCode={} length={} packetType={} remaining={}",
                    tag, typeCode, length, packetType, buf.readableBytes())

                if (packetType == null) {
                    logger.warn("[{}] Unknown packet type: {}", tag, typeCode)
                    buf.resetReaderIndex()
                    return
                }

                if (length < 0 || buf.readableBytes() < length) {
                    logger.warn("[{}] Incomplete frame: need {} but have {}", tag, length, buf.readableBytes())
                    buf.resetReaderIndex()
                    return
                }

                if (length > 0 && payloadRef != null) {
                    payloadRef.set(buf.retainedSlice(buf.readerIndex(), length))
                }
                buf.skipBytes(length)

                logger.info("[{}] Decoded frame: type={}, payloadSize={}", tag, packetType, length)
                typeRef.set(packetType)
                packetLatch.countDown()
            }
        }
    }

    /**
     * 连接原始客户端。Pipeline 中无解码器，直接收发 ByteBuf。
     */
    private fun connectRawClient(handler: (SocketChannel) -> Unit): Channel {
        val bootstrap = Bootstrap()
        bootstrap.group(eventLoopGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    handler(ch)
                }
            })
            .option(ChannelOption.TCP_NODELAY, true)

        return bootstrap.connect("127.0.0.1", TCP_PORT).sync().channel()
    }

    /**
     * 发送 MAGIC + VERSION + AUTH 包。
     */
    private fun performAuth(client: Channel, token: String, uid: String, deviceId: String = "d1") {
        val buf = Unpooled.buffer()
        // 先写 MAGIC + VERSION（服务端 HandshakeHandler 验证）
        buf.writeBytes(IProto.MAGIC_WITH_VERSION)
        // 再写 AUTH 包
        val authPayload = AuthRequestPayload(token, uid, deviceId, "Test Device", "Model", "1.0.0", 0)
        buf.writeByte(PacketType.AUTH.code.toInt())
        val payloadBuf = Unpooled.buffer()
        authPayload.writeTo(payloadBuf)
        buf.writeInt(payloadBuf.readableBytes())
        buf.writeBytes(payloadBuf)
        payloadBuf.release()
        client.writeAndFlush(buf)
    }

    /**
     * 将 Message 编码为数据帧发送。
     */
    private fun sendMessagePacket(client: Channel, packetType: PacketType, message: Message) {
        val payloadBuf = Unpooled.buffer()
        message.writeTo(payloadBuf)
        val packetBuf = Unpooled.buffer(1 + 4 + payloadBuf.readableBytes())
        packetBuf.writeByte(packetType.code.toInt())
        packetBuf.writeInt(payloadBuf.readableBytes())
        packetBuf.writeBytes(payloadBuf)
        payloadBuf.release()
        client.writeAndFlush(packetBuf)
    }
}

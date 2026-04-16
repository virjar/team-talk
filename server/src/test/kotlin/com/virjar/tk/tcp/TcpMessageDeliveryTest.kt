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
import kotlin.test.assertTrue

/**
 * TCP 消息投递测试：验证 A 发消息 → B 收到 RECV。
 */
class TcpMessageDeliveryTest {

    companion object {
        private const val TCP_PORT = 15101
        private lateinit var eventLoopGroup: NioEventLoopGroup
        private lateinit var tcpServer: TcpServer
        private lateinit var tokenService: TokenService
        private lateinit var messageService: MessageService
        private lateinit var channelStore: ChannelStore

        @JvmStatic
        @BeforeAll
        fun setUp() {
            eventLoopGroup = NioEventLoopGroup()

            TokenService.init("TeamTalk2026SecretKeyForJWTTokenGenerationMustBeAtLeast256Bits")
            tokenService = TokenService

            val messageStore = com.virjar.tk.db.MessageStore("/tmp/teamtalk-delivery-test-rocksdb")
            messageStore.start()
            channelStore = ChannelStore()
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

    @Test
    fun `A sends TEXT message, B receives RECV`() {
        val uidA = "delivery-user-a"
        val uidB = "delivery-user-b"
        val channelId = "dm-a-b"

        // 预设频道成员到 ChannelStore（纯内存，不写 DB）
        channelStore.putMember(channelId, ChannelType.PERSONAL, uidA)
        channelStore.putMember(channelId, ChannelType.PERSONAL, uidB)

        val tokenA = generateToken(uidA)
        val tokenB = generateToken(uidB)

        val authLatchB = CountDownLatch(1)
        val recvLatchB = CountDownLatch(1)
        val recvTypeRef = AtomicReference<PacketType>(null)
        val recvPayloadRef = AtomicReference<ByteBuf>(null)

        // B 先连接
        val clientB = connectRawClient { ch ->
            ch.pipeline().addLast(MultiPacketHandler(authLatch = authLatchB, firstLatch = recvLatchB, firstTypeRef = recvTypeRef, firstPayloadRef = recvPayloadRef, tag = "B"))
        }

        val authLatchA = CountDownLatch(1)
        val sendAckLatchA = CountDownLatch(1)
        val sendAckTypeRef = AtomicReference<PacketType>(null)

        // A 连接
        val clientA = connectRawClient { ch ->
            ch.pipeline().addLast(MultiPacketHandler(authLatch = authLatchA, firstLatch = sendAckLatchA, firstTypeRef = sendAckTypeRef, tag = "A"))
        }

        try {
            // 两个都 auth
            performAuth(clientA, tokenA, uidA, "device-a")
            performAuth(clientB, tokenB, uidB, "device-b")
            assertTrue(authLatchA.await(5, TimeUnit.SECONDS), "A auth timeout")
            assertTrue(authLatchB.await(5, TimeUnit.SECONDS), "B auth timeout")

            // A 发送消息
            val message = Message(
                MessageHeader(channelId = channelId, clientMsgNo = "msg-dm-1", clientSeq = 1L, channelType = ChannelType.PERSONAL),
                TextBody("hello B!", emptyList())
            )
            sendMessagePacket(clientA, PacketType.TEXT, message)

            // A 收到 SENDACK
            assertTrue(sendAckLatchA.await(5, TimeUnit.SECONDS), "A SENDACK timeout")
            assertEquals(PacketType.SENDACK, sendAckTypeRef.get())

            // B 收到 RECV
            assertTrue(recvLatchB.await(5, TimeUnit.SECONDS), "B RECV timeout")
            assertEquals(PacketType.TEXT, recvTypeRef.get())

            // 验证 RECV 的 header 字段正确
            val recvBuf = recvPayloadRef.get()
            val headerLen = recvBuf.readUnsignedShort()
            val header = MessageHeader.readFrom(recvBuf.retainedSlice(recvBuf.readerIndex(), headerLen))
            recvBuf.skipBytes(headerLen)
            assertEquals(channelId, header.channelId)
            assertEquals(uidA, header.senderUid)
            assertTrue(header.messageId.orEmpty().isNotEmpty())
            assertTrue(header.serverSeq > 0)
        } finally {
            recvPayloadRef.get()?.release()
            clientA.close()
            clientB.close()
        }
    }

    @Test
    fun `same UID two connections, one sends, other receives RECV`() {
        val uid = "multi-device-user"
        val channelId = "ch-multi"

        // 预设频道成员（纯内存，不写 DB）
        channelStore.putMember(channelId, ChannelType.PERSONAL, uid)

        val token = generateToken(uid)

        // 连接 1（发送者）
        val authLatch1 = CountDownLatch(1)
        val sendAckLatch1 = CountDownLatch(1)
        val sendAckTypeRef1 = AtomicReference<PacketType>(null)
        val extraLatch1 = CountDownLatch(1)
        val extraTypeRef1 = AtomicReference<PacketType>(null)

        val client1 = connectRawClient { ch ->
            ch.pipeline().addLast(MultiPacketHandler(
                authLatch = authLatch1, firstLatch = sendAckLatch1, firstTypeRef = sendAckTypeRef1,
                extraLatch = extraLatch1, extraTypeRef = extraTypeRef1, tag = "device-1"))
        }

        // 连接 2（接收者 — 同 UID 不同设备）
        val authLatch2 = CountDownLatch(1)
        val recvLatch2 = CountDownLatch(1)
        val recvTypeRef2 = AtomicReference<PacketType>(null)
        val recvPayloadRef2 = AtomicReference<ByteBuf>(null)

        val client2 = connectRawClient { ch ->
            ch.pipeline().addLast(MultiPacketHandler(authLatch = authLatch2, firstLatch = recvLatch2, firstTypeRef = recvTypeRef2, firstPayloadRef = recvPayloadRef2, tag = "device-2"))
        }

        try {
            performAuth(client1, token, uid, "device-1")
            performAuth(client2, token, uid, "device-2")
            assertTrue(authLatch1.await(5, TimeUnit.SECONDS), "device-1 auth timeout")
            assertTrue(authLatch2.await(5, TimeUnit.SECONDS), "device-2 auth timeout")

            // device-1 发消息
            val message = Message(
                MessageHeader(channelId = channelId, clientMsgNo = "msg-multi-1", clientSeq = 1L, channelType = ChannelType.PERSONAL),
                TextBody("from device-1", emptyList())
            )
            sendMessagePacket(client1, PacketType.TEXT, message)

            // device-1 收到 SENDACK
            assertTrue(sendAckLatch1.await(5, TimeUnit.SECONDS), "SENDACK timeout")

            // device-2 收到 RECV（多端同步）
            assertTrue(recvLatch2.await(5, TimeUnit.SECONDS), "device-2 RECV timeout")
            assertEquals(PacketType.TEXT, recvTypeRef2.get())

            val recvBuf = recvPayloadRef2.get()
            val headerLen = recvBuf.readUnsignedShort()
            val header = MessageHeader.readFrom(recvBuf.retainedSlice(recvBuf.readerIndex(), headerLen))
            recvBuf.skipBytes(headerLen)
            assertEquals(channelId, header.channelId)

            // device-1 不应收到自己的 RECV（只收到 SENDACK）
            val receivedExtra = extraLatch1.await(2, TimeUnit.SECONDS)
            assertTrue(!receivedExtra || extraTypeRef1.get() == null,
                "Sender's original connection should not receive RECV for own message")
        } finally {
            recvPayloadRef2.get()?.release()
            client1.close()
            client2.close()
        }
    }

    // ================================================================
    // 辅助类和方法
    // ================================================================

    /**
     * 支持多种帧类型的 Handler：处理 AUTH_RESP，然后区分 SENDACK 和 RECV。
     */
    private class MultiPacketHandler(
        private val authLatch: CountDownLatch,
        private val firstLatch: CountDownLatch,
        private val firstTypeRef: AtomicReference<PacketType>,
        private val firstPayloadRef: AtomicReference<ByteBuf>? = null,
        private val extraLatch: CountDownLatch? = null,
        private val extraTypeRef: AtomicReference<PacketType>? = null,
        private val tag: String,
    ) : ChannelInboundHandlerAdapter() {
        private val logger = LoggerFactory.getLogger(MultiPacketHandler::class.java)
        private var magicConsumed = false
        private var authDone = false
        private var firstReceived = false

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg !is ByteBuf) return

            if (!magicConsumed) {
                if (msg.readableBytes() >= IProto.MAGIC_WITH_VERSION.size) {
                    msg.skipBytes(IProto.MAGIC_WITH_VERSION.size)
                    magicConsumed = true
                }
            }

            if (!authDone) {
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

                if (packetType == null || length < 0 || buf.readableBytes() < length) {
                    buf.resetReaderIndex()
                    return
                }

                logger.info("[{}] Received: type={}", tag, packetType)

                if (!firstReceived) {
                    if (length > 0 && firstPayloadRef != null) {
                        firstPayloadRef.set(buf.retainedSlice(buf.readerIndex(), length))
                    }
                    buf.skipBytes(length)
                    firstTypeRef.set(packetType)
                    firstLatch.countDown()
                    firstReceived = true
                } else if (extraLatch != null && extraTypeRef != null) {
                    buf.skipBytes(length)
                    extraTypeRef.set(packetType)
                    extraLatch.countDown()
                }
            }
        }
    }

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

    private fun performAuth(client: Channel, token: String, uid: String, deviceId: String = "d1") {
        val buf = Unpooled.buffer()
        buf.writeBytes(IProto.MAGIC_WITH_VERSION)
        val authPayload = AuthRequestPayload(token, uid, deviceId, "Test Device", "Model", "1.0.0", 0)
        buf.writeByte(PacketType.AUTH.code.toInt())
        val payloadBuf = Unpooled.buffer()
        authPayload.writeTo(payloadBuf)
        buf.writeInt(payloadBuf.readableBytes())
        buf.writeBytes(payloadBuf)
        payloadBuf.release()
        client.writeAndFlush(buf)
    }

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

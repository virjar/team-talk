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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SUBSCRIBE 离线补拉测试：验证 A 发消息后，B 连接并 SUBSCRIBE 能收到历史消息。
 */
class TcpSubscribeTest {

    /** 解码后的帧：持有 PacketType + ByteBuf payload slice（调用者负责 release） */
    private data class DecodedFrame(val type: PacketType, val payloadBuf: ByteBuf)

    companion object {
        private const val TCP_PORT = 15102
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

            val messageStore = com.virjar.tk.db.MessageStore("/tmp/teamtalk-subscribe-test-rocksdb")
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
    fun `SUBSCRIBE with lastSeq=0 retrieves recent messages`() {
        val senderUid = "sub-sender"
        val receiverUid = "sub-receiver"
        val channelId = "sub-ch-1"

        // 预设频道成员（纯内存，不写 DB）
        channelStore.putMember(channelId, ChannelType.PERSONAL, senderUid)
        channelStore.putMember(channelId, ChannelType.PERSONAL, receiverUid)

        // Step 1: 发送者发送几条消息
        val senderToken = generateToken(senderUid)
        val senderAuthLatch = CountDownLatch(1)
        val senderAckLatch = CountDownLatch(3) // 等待3条消息的 ACK
        val senderAckCount = java.util.concurrent.atomic.AtomicInteger(0)

        val senderClient = connectRawClient { ch ->
            ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                private var magicConsumed = false
                private var authDone = false
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
                                senderAuthLatch.countDown()
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
                        if (length < 0 || buf.readableBytes() < length) {
                            buf.resetReaderIndex()
                            return
                        }
                        buf.skipBytes(length)
                        senderAckCount.incrementAndGet()
                        senderAckLatch.countDown()
                    }
                }
            })
        }

        try {
            performAuth(senderClient, senderToken, senderUid)
            assertTrue(senderAuthLatch.await(5, TimeUnit.SECONDS), "Sender auth timeout")

            // 发送 3 条消息
            for (i in 1..3) {
                val message = Message(
                    MessageHeader(channelId = channelId, clientMsgNo = "msg-sub-$i", clientSeq = i.toLong(),
                        channelType = ChannelType.PERSONAL),
                    TextBody("message $i", emptyList())
                )
                sendMessagePacket(senderClient, PacketType.TEXT, message)
            }
            assertTrue(senderAckLatch.await(5, TimeUnit.SECONDS), "Sender ACK timeout")
        } finally {
            senderClient.close()
        }

        // Step 2: 接收者连接并 SUBSCRIBE
        Thread.sleep(200) // 等待发送者断开

        val receiverToken = generateToken(receiverUid)
        val receiverAuthLatch = CountDownLatch(1)
        val recvFrames = ConcurrentLinkedQueue<DecodedFrame>()
        val recvLatch = CountDownLatch(3)

        val receiverClient = connectRawClient { ch ->
            ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                private var magicConsumed = false
                private var authDone = false
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
                                receiverAuthLatch.countDown()
                            } else {
                                msg.resetReaderIndex()
                            }
                        }
                    }
                    if (authDone && msg.isReadable) {
                        decodeFrames(msg)
                    }
                    msg.release()
                }

                private fun decodeFrames(buf: ByteBuf) {
                    while (buf.readableBytes() >= 5) {
                        buf.markReaderIndex()
                        val typeCode = buf.readByte()
                        val length = buf.readInt()
                        val packetType = PacketType.fromCode(typeCode)
                        if (packetType == null || length < 0 || buf.readableBytes() < length) {
                            buf.resetReaderIndex()
                            return
                        }
                        val payloadSlice = if (length > 0) buf.retainedSlice(buf.readerIndex(), length) else null
                        buf.skipBytes(length)

                        recvFrames.add(DecodedFrame(packetType, payloadSlice ?: Unpooled.EMPTY_BUFFER))
                        recvLatch.countDown()
                    }
                }
            })
        }

        try {
            performAuth(receiverClient, receiverToken, receiverUid)
            assertTrue(receiverAuthLatch.await(5, TimeUnit.SECONDS), "Receiver auth timeout")

            // 发送 SUBSCRIBE（lastSeq=0，获取最近消息）
            val subscribeBuf = Unpooled.buffer()
            SubscribePayload(channelId = channelId, lastSeq = 0L).writeTo(subscribeBuf)
            val packetBuf = Unpooled.buffer(1 + 4 + subscribeBuf.readableBytes())
            packetBuf.writeByte(PacketType.SUBSCRIBE.code.toInt())
            packetBuf.writeInt(subscribeBuf.readableBytes())
            packetBuf.writeBytes(subscribeBuf)
            subscribeBuf.release()
            receiverClient.writeAndFlush(packetBuf)

            // 验证收到 RECV（等待足够时间让所有消息到达）
            Thread.sleep(1000)

            val frames = recvFrames.toList()
            assertTrue(frames.size >= 3, "Expected at least 3 RECV frames for SUBSCRIBE, got ${frames.size}")

            // 验证最后 3 条 RECV 的 header 字段正确
            val recentFrames = frames.takeLast(3)
            for (frame in recentFrames) {
                assertEquals(PacketType.TEXT, frame.type)
                val buf = frame.payloadBuf
                val headerLen = buf.readUnsignedShort()
                val headerBuf = buf.retainedSlice(buf.readerIndex(), headerLen)
                buf.skipBytes(headerLen)
                try {
                    assertEquals(channelId, IProto.readString(headerBuf)!!)
                    IProto.readString(headerBuf) // clientMsgNo - skip
                    IProto.readVarInt(headerBuf) // clientSeq - skip
                    IProto.readString(headerBuf) // messageId - skip
                    assertEquals(senderUid, IProto.readString(headerBuf))
                    headerBuf.readByte() // channelType - skip
                    assertTrue(IProto.readVarInt(headerBuf) > 0) // serverSeq
                } finally {
                    headerBuf.release()
                }
            }
        } finally {
            recvFrames.forEach { it.payloadBuf.release() }
            receiverClient.close()
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

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

    private fun performAuth(client: Channel, token: String, uid: String) {
        val buf = Unpooled.buffer()
        buf.writeBytes(IProto.MAGIC_WITH_VERSION)
        val authPayload = AuthRequestPayload(token, uid, "d1", "Test Device", "Model", "1.0.0", 0)
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

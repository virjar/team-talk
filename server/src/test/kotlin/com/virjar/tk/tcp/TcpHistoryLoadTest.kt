package com.virjar.tk.tcp

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.payload.*
import com.virjar.tk.service.*
import com.virjar.tk.store.ChannelStore
import com.virjar.tk.store.ContactStore
import com.virjar.tk.store.ConversationStore
import com.virjar.tk.store.DeviceStore
import com.virjar.tk.store.UserStore
import com.virjar.tk.tcp.agent.HistoryDispatcher
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HISTORY_LOAD 测试：验证客户端请求历史消息后服务端正确推送 + HISTORY_LOAD_END。
 */
class TcpHistoryLoadTest {

    private data class DecodedFrame(val type: PacketType, val payloadBuf: ByteBuf)

    companion object {
        private const val TCP_PORT = 15103
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

            val messageStore = com.virjar.tk.db.MessageStore("/tmp/teamtalk-history-test-rocksdb")
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
            HistoryDispatcher.init(messageService, channelStore)

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
    fun `HISTORY_LOAD returns messages and END with hasMore=true`() {
        val senderUid = "hist-sender"
        val requesterUid = "hist-requester"
        val channelId = "hist-ch-1"

        // 预设频道成员
        channelStore.putMember(channelId, ChannelType.PERSONAL, senderUid)
        channelStore.putMember(channelId, ChannelType.PERSONAL, requesterUid)

        // Step 1: 发送者发送 10 条消息
        val senderToken = generateToken(senderUid)
        val senderAuthLatch = CountDownLatch(1)
        val senderAckLatch = CountDownLatch(10)

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
                        senderAckLatch.countDown()
                    }
                }
            })
        }

        try {
            performAuth(senderClient, senderToken, senderUid)
            assertTrue(senderAuthLatch.await(5, TimeUnit.SECONDS), "Sender auth timeout")

            for (i in 1..10) {
                val message = Message(
                    MessageHeader(channelId = channelId, clientMsgNo = "msg-hist-$i", clientSeq = i.toLong(),
                        channelType = ChannelType.PERSONAL),
                    TextBody("history message $i", emptyList())
                )
                sendMessagePacket(senderClient, PacketType.TEXT, message)
            }
            assertTrue(senderAckLatch.await(5, TimeUnit.SECONDS), "Sender ACK timeout")
        } finally {
            senderClient.close()
        }

        // Step 2: 请求者连接并发送 HISTORY_LOAD(limit=5)
        Thread.sleep(200)

        val requesterToken = generateToken(requesterUid)
        val requesterAuthLatch = CountDownLatch(1)
        val recvFrames = ConcurrentLinkedQueue<DecodedFrame>()
        val allReceivedLatch = CountDownLatch(6) // 5 messages + 1 END

        val requesterClient = connectRawClient { ch ->
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
                                requesterAuthLatch.countDown()
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
                        allReceivedLatch.countDown()
                    }
                }
            })
        }

        try {
            performAuth(requesterClient, requesterToken, requesterUid)
            assertTrue(requesterAuthLatch.await(5, TimeUnit.SECONDS), "Requester auth timeout")

            // 发送 HISTORY_LOAD(beforeSeq=Long.MAX_VALUE, limit=5)
            val loadPayload = HistoryLoadPayload(channelId = channelId, beforeSeq = Long.MAX_VALUE, limit = 5)
            val payloadBuf = Unpooled.buffer()
            loadPayload.writeTo(payloadBuf)
            val packetBuf = Unpooled.buffer(1 + 4 + payloadBuf.readableBytes())
            packetBuf.writeByte(PacketType.HISTORY_LOAD.code.toInt())
            packetBuf.writeInt(payloadBuf.readableBytes())
            packetBuf.writeBytes(payloadBuf)
            payloadBuf.release()
            requesterClient.writeAndFlush(packetBuf)

            assertTrue(allReceivedLatch.await(5, TimeUnit.SECONDS), "Timeout waiting for history messages")

            val frames = recvFrames.toList()
            // 应该有 5 个 TEXT + 1 个 HISTORY_LOAD_END
            val textFrames = frames.filter { it.type == PacketType.TEXT }
            val endFrames = frames.filter { it.type == PacketType.HISTORY_LOAD_END }
            assertEquals(5, textFrames.size, "Expected 5 TEXT messages")
            assertEquals(1, endFrames.size, "Expected 1 HISTORY_LOAD_END")

            // 验证 END 包
            val endPayload = HistoryLoadEndPayload.create(endFrames[0].payloadBuf)
            assertEquals(channelId, endPayload.channelId)
            assertEquals(Long.MAX_VALUE, endPayload.beforeSeq)
            assertEquals(true, endPayload.hasMore, "Expected hasMore=true since 10 messages exist but only 5 returned")

            // 验证 TEXT 消息的 seq 是升序
            val seqs = textFrames.map { frame ->
                val buf = frame.payloadBuf
                val headerLen = buf.readUnsignedShort()
                buf.skipBytes(headerLen) // skip header, don't need to parse fully
                // Parse header to get serverSeq
                val headerBuf = frame.payloadBuf.retainedSlice(2, headerLen)
                try {
                    IProto.readString(headerBuf) // channelId
                    IProto.readString(headerBuf) // clientMsgNo
                    IProto.readVarInt(headerBuf) // clientSeq
                    IProto.readString(headerBuf) // messageId
                    IProto.readString(headerBuf) // senderUid
                    headerBuf.readByte() // channelType
                    IProto.readVarInt(headerBuf) // serverSeq
                } finally {
                    headerBuf.release()
                }
            }
            // Verify ascending order
            for (i in 1 until seqs.size) {
                assertTrue(seqs[i] > seqs[i - 1], "Messages should be in ascending seq order")
            }
        } finally {
            recvFrames.forEach { it.payloadBuf.release() }
            requesterClient.close()
        }
    }

    @Test
    fun `HISTORY_LOAD with more messages than available returns hasMore=false`() {
        val senderUid = "hist-sender2"
        val requesterUid = "hist-requester2"
        val channelId = "hist-ch-2"

        channelStore.putMember(channelId, ChannelType.PERSONAL, senderUid)
        channelStore.putMember(channelId, ChannelType.PERSONAL, requesterUid)

        // 发送 3 条消息
        val senderToken = generateToken(senderUid)
        val senderAuthLatch = CountDownLatch(1)
        val senderAckLatch = CountDownLatch(3)

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
                        while (msg.readableBytes() >= 5) {
                            msg.markReaderIndex()
                            val tc = msg.readByte()
                            val l = msg.readInt()
                            if (l < 0 || msg.readableBytes() < l) { msg.resetReaderIndex(); return }
                            msg.skipBytes(l)
                            senderAckLatch.countDown()
                        }
                    }
                    msg.release()
                }
            })
        }

        try {
            performAuth(senderClient, senderToken, senderUid)
            assertTrue(senderAuthLatch.await(5, TimeUnit.SECONDS))
            for (i in 1..3) {
                val message = Message(
                    MessageHeader(channelId = channelId, clientMsgNo = "msg-h2-$i", clientSeq = i.toLong(),
                        channelType = ChannelType.PERSONAL),
                    TextBody("msg $i", emptyList())
                )
                sendMessagePacket(senderClient, PacketType.TEXT, message)
            }
            assertTrue(senderAckLatch.await(5, TimeUnit.SECONDS))
        } finally {
            senderClient.close()
        }

        Thread.sleep(200)

        // 请求 limit=50（远超 3 条）
        val requesterToken = generateToken(requesterUid)
        val requesterAuthLatch = CountDownLatch(1)
        val recvFrames = ConcurrentLinkedQueue<DecodedFrame>()
        val allReceivedLatch = CountDownLatch(4) // 3 messages + 1 END

        val requesterClient = connectRawClient { ch ->
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
                                requesterAuthLatch.countDown()
                            } else {
                                msg.resetReaderIndex()
                            }
                        }
                    }
                    if (authDone && msg.isReadable) {
                        while (msg.readableBytes() >= 5) {
                            msg.markReaderIndex()
                            val typeCode = msg.readByte()
                            val length = msg.readInt()
                            val packetType = PacketType.fromCode(typeCode)
                            if (packetType == null || length < 0 || msg.readableBytes() < length) {
                                msg.resetReaderIndex()
                                return
                            }
                            val payloadSlice = if (length > 0) msg.retainedSlice(msg.readerIndex(), length) else null
                            msg.skipBytes(length)
                            recvFrames.add(DecodedFrame(packetType, payloadSlice ?: Unpooled.EMPTY_BUFFER))
                            allReceivedLatch.countDown()
                        }
                    }
                    msg.release()
                }
            })
        }

        try {
            performAuth(requesterClient, requesterToken, requesterUid)
            assertTrue(requesterAuthLatch.await(5, TimeUnit.SECONDS))

            val loadPayload = HistoryLoadPayload(channelId = channelId, beforeSeq = Long.MAX_VALUE, limit = 50)
            val payloadBuf = Unpooled.buffer()
            loadPayload.writeTo(payloadBuf)
            val packetBuf = Unpooled.buffer(1 + 4 + payloadBuf.readableBytes())
            packetBuf.writeByte(PacketType.HISTORY_LOAD.code.toInt())
            packetBuf.writeInt(payloadBuf.readableBytes())
            packetBuf.writeBytes(payloadBuf)
            payloadBuf.release()
            requesterClient.writeAndFlush(packetBuf)

            assertTrue(allReceivedLatch.await(5, TimeUnit.SECONDS))

            val endFrames = recvFrames.filter { it.type == PacketType.HISTORY_LOAD_END }
            assertEquals(1, endFrames.size)

            val endPayload = HistoryLoadEndPayload.create(endFrames[0].payloadBuf)
            assertEquals(false, endPayload.hasMore, "Expected hasMore=false when all messages fit in limit")
        } finally {
            recvFrames.forEach { it.payloadBuf.release() }
            requesterClient.close()
        }
    }

    @Test
    fun `HISTORY_LOAD for non-member returns only END`() {
        val senderUid = "hist-sender3"
        val nonMemberUid = "hist-nonmember"
        val channelId = "hist-ch-3"

        channelStore.putMember(channelId, ChannelType.PERSONAL, senderUid)
        // nonMemberUid is NOT a member

        val senderToken = generateToken(senderUid)
        val senderAuthLatch = CountDownLatch(1)
        val senderAckLatch = CountDownLatch(1)

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
                        while (msg.readableBytes() >= 5) {
                            msg.markReaderIndex()
                            val tc = msg.readByte()
                            val l = msg.readInt()
                            if (l < 0 || msg.readableBytes() < l) { msg.resetReaderIndex(); return }
                            msg.skipBytes(l)
                            senderAckLatch.countDown()
                        }
                    }
                    msg.release()
                }
            })
        }

        try {
            performAuth(senderClient, senderToken, senderUid)
            assertTrue(senderAuthLatch.await(5, TimeUnit.SECONDS))
            val message = Message(
                MessageHeader(channelId = channelId, clientMsgNo = "msg-h3-1", clientSeq = 1L,
                    channelType = ChannelType.PERSONAL),
                TextBody("secret message", emptyList())
            )
            sendMessagePacket(senderClient, PacketType.TEXT, message)
            assertTrue(senderAckLatch.await(5, TimeUnit.SECONDS))
        } finally {
            senderClient.close()
        }

        Thread.sleep(200)

        val nonMemberToken = generateToken(nonMemberUid)
        val nonMemberAuthLatch = CountDownLatch(1)
        val recvFrames = ConcurrentLinkedQueue<DecodedFrame>()
        val endLatch = CountDownLatch(1)

        val nonMemberClient = connectRawClient { ch ->
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
                                nonMemberAuthLatch.countDown()
                            } else {
                                msg.resetReaderIndex()
                            }
                        }
                    }
                    if (authDone && msg.isReadable) {
                        while (msg.readableBytes() >= 5) {
                            msg.markReaderIndex()
                            val typeCode = msg.readByte()
                            val length = msg.readInt()
                            val packetType = PacketType.fromCode(typeCode)
                            if (packetType == null || length < 0 || msg.readableBytes() < length) {
                                msg.resetReaderIndex()
                                return
                            }
                            val payloadSlice = if (length > 0) msg.retainedSlice(msg.readerIndex(), length) else null
                            msg.skipBytes(length)
                            recvFrames.add(DecodedFrame(packetType, payloadSlice ?: Unpooled.EMPTY_BUFFER))
                            endLatch.countDown()
                        }
                    }
                    msg.release()
                }
            })
        }

        try {
            performAuth(nonMemberClient, nonMemberToken, nonMemberUid)
            assertTrue(nonMemberAuthLatch.await(5, TimeUnit.SECONDS))

            val loadPayload = HistoryLoadPayload(channelId = channelId, beforeSeq = Long.MAX_VALUE, limit = 10)
            val payloadBuf = Unpooled.buffer()
            loadPayload.writeTo(payloadBuf)
            val packetBuf = Unpooled.buffer(1 + 4 + payloadBuf.readableBytes())
            packetBuf.writeByte(PacketType.HISTORY_LOAD.code.toInt())
            packetBuf.writeInt(payloadBuf.readableBytes())
            packetBuf.writeBytes(payloadBuf)
            payloadBuf.release()
            nonMemberClient.writeAndFlush(packetBuf)

            assertTrue(endLatch.await(5, TimeUnit.SECONDS))

            // Should only have HISTORY_LOAD_END, no TEXT messages
            val textFrames = recvFrames.filter { it.type == PacketType.TEXT }
            val endFrames = recvFrames.filter { it.type == PacketType.HISTORY_LOAD_END }
            assertEquals(0, textFrames.size, "Non-member should not receive any messages")
            assertEquals(1, endFrames.size)
        } finally {
            recvFrames.forEach { it.payloadBuf.release() }
            nonMemberClient.close()
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

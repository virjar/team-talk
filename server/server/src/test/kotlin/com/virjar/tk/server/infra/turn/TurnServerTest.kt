package com.virjar.tk.server.infra.turn

import com.virjar.tk.protocol.model.IceServer
import io.netty.channel.nio.NioEventLoopGroup
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 内嵌 TURN 的端到端行为：以真实 UDP socket 走完 Binding、Allocate（401 挑战→认证）、
 * CreatePermission、Send/Data 指示、ChannelBind/ChannelData 与 Refresh 释放。
 * 与 libwebrtc 的互通在部署验收中另行覆盖。
 */
class TurnServerTest {

    private val group = NioEventLoopGroup(1)
    private lateinit var server: TurnServer
    private lateinit var listenAddress: InetSocketAddress
    private lateinit var iceServer: IceServer
    private val secret = "0123456789abcdef0123456789abcdef".toByteArray()
    private val realm = "test.realm"
    private val random = SecureRandom()

    @BeforeTest
    fun setUp() {
        val listenPort = freePort()
        val relayStart = freePort()
        server = TurnServer(
            TurnServerConfig(
                listenPort = listenPort,
                publicHost = "127.0.0.1",
                relayPortStart = relayStart,
                relayPortEnd = relayStart + 8,
                secret = secret,
                realm = realm,
            ),
            group,
        )
        server.start()
        listenAddress = InetSocketAddress("127.0.0.1", listenPort)
        iceServer = server.issueIceServer(600)
    }

    @AfterTest
    fun tearDown() {
        server.close()
        group.shutdownGracefully()
    }

    @Test
    fun `完整通话中继流程`() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { client ->
            DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { peer ->
                val clientAddr = client.localSocketAddress as InetSocketAddress
                val peerAddr = peer.localSocketAddress as InetSocketAddress
                val key = md5Key(iceServer.username, iceServer.credential)

                // Binding：服务端返回 XOR-MAPPED-ADDRESS
                val bindingTx = newTxId()
                val bindingResponse = roundTrip(client, listenAddress, Stun.encode(
                    Stun.messageType(Stun.METHOD_BINDING, Stun.CLASS_REQUEST), bindingTx, emptyList(),
                ))
                val binding = Stun.parse(bindingResponse)!!
                assertEquals(Stun.CLASS_SUCCESS, binding.clazz)
                assertEquals(
                    clientAddr,
                    Stun.decodeXorAddress(binding.attr(Stun.ATTR_XOR_MAPPED_ADDRESS)!!.value, bindingTx)!!.toInetSocketAddress(),
                )

                // Allocate 无凭据：401 挑战
                val allocateTx = newTxId()
                val challenge = Stun.parse(roundTrip(client, listenAddress, Stun.encode(
                    Stun.messageType(Stun.METHOD_ALLOCATE, Stun.CLASS_REQUEST), allocateTx,
                    listOf(StunAttribute(Stun.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0))),
                )))!!
                assertEquals(Stun.CLASS_ERROR, challenge.clazz)
                assertEquals(Stun.ERROR_UNAUTHORIZED, Stun.decodeErrorCode(challenge.attr(Stun.ATTR_ERROR_CODE)!!.value))
                val nonce = challenge.attr(Stun.ATTR_NONCE)!!.value.toString(Charsets.UTF_8)
                assertEquals(realm, challenge.attr(Stun.ATTR_REALM)!!.value.toString(Charsets.UTF_8))

                // Allocate 错误凭据：441
                val badTx = newTxId()
                val bad = Stun.parse(roundTrip(client, listenAddress, authenticatedRequest(
                    Stun.METHOD_ALLOCATE, badTx, nonce,
                    StunAttribute(Stun.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)),
                    username = "9999999999", key = md5Key("9999999999", "wrong"),
                )))!!
                assertEquals(Stun.ERROR_WRONG_CREDENTIALS, Stun.decodeErrorCode(bad.attr(Stun.ATTR_ERROR_CODE)!!.value))

                // Allocate 正常：relay 端口落在配置范围内
                val allocateOkTx = newTxId()
                val allocated = Stun.parse(roundTrip(client, listenAddress, authenticatedRequest(
                    Stun.METHOD_ALLOCATE, allocateOkTx, nonce,
                    StunAttribute(Stun.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)),
                    username = iceServer.username, key = key,
                )))!!
                assertEquals(Stun.CLASS_SUCCESS, allocated.clazz)
                val (_, relayPort) = Stun.decodeXorAddress(allocated.attr(Stun.ATTR_XOR_RELAYED_ADDRESS)!!.value, allocateOkTx)!!
                assertTrue(relayPort in server.config.relayPortStart..server.config.relayPortEnd, "relay 端口 $relayPort 越界")
                val relayAddress = InetSocketAddress("127.0.0.1", relayPort)
                assertEquals(1, server.allocationCount())

                // CreatePermission：授权 peer 地址
                val permTx = newTxId()
                val perm = Stun.parse(roundTrip(client, listenAddress, authenticatedRequest(
                    Stun.METHOD_CREATE_PERMISSION, permTx, nonce,
                    StunAttribute(Stun.ATTR_XOR_PEER_ADDRESS, Stun.encodeXorAddress(peerAddr.address, peerAddr.port, permTx)),
                    username = iceServer.username, key = key,
                )))!!
                assertEquals(Stun.CLASS_SUCCESS, perm.clazz)

                // Send 指示：数据到达 peer
                val sendTx = newTxId()
                client.send(datagram(Stun.encode(
                    Stun.messageType(Stun.METHOD_SEND, Stun.CLASS_INDICATION), sendTx,
                    listOf(
                        StunAttribute(Stun.ATTR_XOR_PEER_ADDRESS, Stun.encodeXorAddress(peerAddr.address, peerAddr.port, sendTx)),
                        StunAttribute(Stun.ATTR_DATA, "hello-peer".toByteArray()),
                    ),
                ), listenAddress))
                val atPeer = peer.receiveBytes()
                assertEquals("hello-peer".toByteArray().decodeToString(), atPeer.decodeToString())

                // peer → client：Data 指示带回
                peer.send(datagram("hello-client".toByteArray(), relayAddress))
                val indication = Stun.parse(client.receiveBytes())!!
                assertEquals(Stun.METHOD_DATA, indication.method)
                assertEquals("hello-client".toByteArray().decodeToString(), indication.attr(Stun.ATTR_DATA)!!.value.decodeToString())

                // ChannelBind + ChannelData 上行
                val bindTx = newTxId()
                val channelNumber = 0x4000
                val bound = Stun.parse(roundTrip(client, listenAddress, authenticatedRequest(
                    Stun.METHOD_CHANNEL_BIND, bindTx, nonce,
                    StunAttribute(Stun.ATTR_CHANNEL_NUMBER, byteArrayOf(0x40, 0x00, 0, 0)),
                    username = iceServer.username, key = key,
                    extra = listOf(StunAttribute(Stun.ATTR_XOR_PEER_ADDRESS, Stun.encodeXorAddress(peerAddr.address, peerAddr.port, bindTx))),
                )))!!
                assertEquals(Stun.CLASS_SUCCESS, bound.clazz)
                client.send(datagram(encodeChannelData(channelNumber, "via-channel".toByteArray()), listenAddress))
                assertEquals("via-channel".toByteArray().decodeToString(), peer.receiveBytes().decodeToString())

                // peer → client 走 ChannelData 帧
                peer.send(datagram("back-channel".toByteArray(), relayAddress))
                val frame = client.receiveBytes()
                assertEquals(channelNumber, ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF))
                assertEquals("back-channel", frame.copyOfRange(4, frame.size).decodeToString())

                // Refresh lifetime=0：释放 allocation
                val refreshTx = newTxId()
                val refreshed = Stun.parse(roundTrip(client, listenAddress, authenticatedRequest(
                    Stun.METHOD_REFRESH, refreshTx, nonce,
                    StunAttribute(Stun.ATTR_LIFETIME, byteArrayOf(0, 0, 0, 0)),
                    username = iceServer.username, key = key,
                )))!!
                assertEquals(Stun.CLASS_SUCCESS, refreshed.clazz)
                assertEquals(0, server.allocationCount())
            }
        }
    }

    @Test
    fun `过期凭据被拒绝`() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { client ->
            val challengeTx = newTxId()
            val challenge = Stun.parse(roundTrip(client, listenAddress, Stun.encode(
                Stun.messageType(Stun.METHOD_ALLOCATE, Stun.CLASS_REQUEST), challengeTx,
                listOf(StunAttribute(Stun.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0))),
            )))!!
            val nonce = challenge.attr(Stun.ATTR_NONCE)!!.value.toString(Charsets.UTF_8)
            val expiredUsername = "${System.currentTimeMillis() / 1000 - 10}"
            val response = Stun.parse(roundTrip(client, listenAddress, authenticatedRequest(
                Stun.METHOD_ALLOCATE, newTxId(), nonce,
                StunAttribute(Stun.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)),
                username = expiredUsername, key = md5Key(expiredUsername, "whatever"),
            )))!!
            assertEquals(Stun.ERROR_WRONG_CREDENTIALS, Stun.decodeErrorCode(response.attr(Stun.ATTR_ERROR_CODE)!!.value))
            assertEquals(0, server.allocationCount())
        }
    }

    @Test
    fun `未知 nonce 触发 STALE_NONCE 挑战`() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { client ->
            val response = Stun.parse(roundTrip(client, listenAddress, authenticatedRequest(
                Stun.METHOD_ALLOCATE, newTxId(), "not-the-nonce",
                StunAttribute(Stun.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)),
                username = iceServer.username, key = md5Key(iceServer.username, iceServer.credential),
            )))!!
            assertEquals(Stun.ERROR_STALE_NONCE, Stun.decodeErrorCode(response.attr(Stun.ATTR_ERROR_CODE)!!.value))
            assertNotNull(response.attr(Stun.ATTR_NONCE))
        }
    }

    /**
     * REALM/NONCE 的 wire 编号必须是 RFC 5389 §18.2 的 0x0014/0x0015。此前实现误用
     * 0x8023/0x8024（可选区），与共享 Stun 常量的测试互测无法发现，标准 WebRTC 客户端
     * 因此无法完成 401 挑战与认证。本测试用字面量编号独立解析与构造，锁住 wire 契约。
     */
    @Test
    fun `REALM 与 NONCE 使用 RFC 5389 标准 wire 编号完成认证`() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { client ->
            fun rawAttrs(message: ByteArray): Map<Int, List<ByteArray>> {
                val out = LinkedHashMap<Int, MutableList<ByteArray>>()
                var pos = 20
                while (pos + 4 <= message.size) {
                    val type = ((message[pos].toInt() and 0xFF) shl 8) or (message[pos + 1].toInt() and 0xFF)
                    val length = ((message[pos + 2].toInt() and 0xFF) shl 8) or (message[pos + 3].toInt() and 0xFF)
                    out.getOrPut(type) { mutableListOf() }.add(message.copyOfRange(pos + 4, pos + 4 + length))
                    pos += 4 + length + ((4 - length % 4) % 4)
                }
                return out
            }

            val challenge = roundTrip(client, listenAddress, Stun.encode(
                Stun.messageType(Stun.METHOD_ALLOCATE, Stun.CLASS_REQUEST), newTxId(),
                listOf(StunAttribute(Stun.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0))),
            ))
            val challengeAttrs = rawAttrs(challenge)
            assertEquals(0x0113, ((challenge[0].toInt() and 0xFF) shl 8) or (challenge[1].toInt() and 0xFF), "401 应为 allocate 错误响应")
            assertEquals(realm, challengeAttrs[0x0014]?.single()?.decodeToString(), "REALM 必须使用标准编号 0x0014")
            val nonce = assertNotNull(challengeAttrs[0x0015]).single().toString(Charsets.UTF_8)

            // 标准客户端按字面量编号回填凭据，服务端必须接受并分配 relay
            val tx = newTxId()
            val authenticated = roundTrip(client, listenAddress, Stun.encode(
                Stun.messageType(Stun.METHOD_ALLOCATE, Stun.CLASS_REQUEST), tx,
                listOf(
                    StunAttribute(Stun.ATTR_REQUESTED_TRANSPORT, byteArrayOf(17, 0, 0, 0)),
                    StunAttribute(0x0014, realm.toByteArray()),
                    StunAttribute(0x0015, nonce.toByteArray()),
                    StunAttribute(Stun.ATTR_USERNAME, iceServer.username.toByteArray()),
                ),
                integrityKey = md5Key(iceServer.username, iceServer.credential),
            ))
            assertEquals(0x0103, ((authenticated[0].toInt() and 0xFF) shl 8) or (authenticated[1].toInt() and 0xFF), "认证后 allocate 应成功")
            val relayValue = assertNotNull(rawAttrs(authenticated)[0x0016]).single()
            assertEquals(0x01, relayValue[1].toInt(), "XOR-RELAYED-ADDRESS 应为 IPv4")
            val relayPort = (relayValue[2].toInt() and 0xFF shl 8 or (relayValue[3].toInt() and 0xFF)) xor (0x2112A442 ushr 16)

            // peer 直发 relay 端口；Data indication 必须从监听端口（客户端五元组）送达，
            // 从 relay 端口发出会被路径上的 NAT 过滤掉
            DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { peer ->
                val peerLocal = peer.localSocketAddress as InetSocketAddress
                val permTx = newTxId()
                val perm = roundTrip(client, listenAddress, Stun.encode(
                    Stun.messageType(Stun.METHOD_CREATE_PERMISSION, Stun.CLASS_REQUEST), permTx,
                    listOf(
                        StunAttribute(0x0012, Stun.encodeXorAddress(peerLocal.address, peerLocal.port, permTx)),
                        StunAttribute(0x0014, realm.toByteArray()),
                        StunAttribute(0x0015, nonce.toByteArray()),
                        StunAttribute(Stun.ATTR_USERNAME, iceServer.username.toByteArray()),
                    ),
                    integrityKey = md5Key(iceServer.username, iceServer.credential),
                ))
                assertEquals(
                    0x0108,
                    ((perm[0].toInt() and 0xFF) shl 8) or (perm[1].toInt() and 0xFF),
                    "标准编号的 createPermission 应成功",
                )

                val payload = "std-wire-probe".toByteArray()
                peer.send(datagram(payload, InetSocketAddress("127.0.0.1", relayPort)))
                val buffer = ByteArray(4096)
                val packet = java.net.DatagramPacket(buffer, buffer.size)
                client.soTimeout = 3000
                client.receive(packet)
                assertEquals(listenAddress.port, packet.port, "Data indication 必须从监听端口发出（RFC 5766 五元组）")
                assertEquals(0x0017, ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF), "应为 Data indication")
                val dataAttr = rawAttrs(buffer.copyOf(packet.length))[0x0013]
                assertEquals("std-wire-probe", assertNotNull(dataAttr).single().decodeToString())
            }
        }
    }

    // -- 辅助 --

    private fun authenticatedRequest(
        method: Int,
        txId: ByteArray,
        nonce: String,
        vararg required: StunAttribute,
        username: String,
        key: ByteArray,
        extra: List<StunAttribute> = emptyList(),
    ): ByteArray = Stun.encode(
        Stun.messageType(method, Stun.CLASS_REQUEST),
        txId,
        required.toList() + extra +
            listOf(
                StunAttribute(Stun.ATTR_USERNAME, username.toByteArray()),
                StunAttribute(Stun.ATTR_REALM, realm.toByteArray()),
                StunAttribute(Stun.ATTR_NONCE, nonce.toByteArray()),
            ),
        integrityKey = key,
    )

    private fun roundTrip(socket: DatagramSocket, target: InetSocketAddress, request: ByteArray): ByteArray {
        socket.send(DatagramPacket(request, request.size, target))
        return socket.receiveBytes()
    }

    private fun DatagramSocket.receiveBytes(): ByteArray {
        soTimeout = 3000
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        receive(packet)
        return buffer.copyOf(packet.length)
    }

    private fun md5Key(username: String, password: String): ByteArray =
        Stun.md5Utf8("$username:$realm:$password")

    private fun newTxId(): ByteArray = ByteArray(12).also(random::nextBytes)

    private fun encodeChannelData(number: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(4 + payload.size)
        frame[0] = ((number ushr 8) and 0xFF).toByte()
        frame[1] = (number and 0xFF).toByte()
        frame[2] = ((payload.size ushr 8) and 0xFF).toByte()
        frame[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 4, payload.size)
        return frame
    }

    private fun datagram(bytes: ByteArray, target: InetSocketAddress) =
        DatagramPacket(bytes, bytes.size, target)

    private fun Pair<java.net.InetAddress, Int>.toInetSocketAddress(): InetSocketAddress =
        InetSocketAddress(first, second)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

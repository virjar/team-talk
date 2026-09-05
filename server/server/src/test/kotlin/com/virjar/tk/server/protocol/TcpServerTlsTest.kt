package com.virjar.tk.server.protocol

import com.virjar.tk.protocol.PacketType
import com.virjar.tk.protocol.PingSignal
import com.virjar.tk.server.createTestServerTlsMaterial
import com.virjar.tk.server.createTestServerTlsMaterialsWithSharedCa
import com.virjar.tk.server.infra.health.TcpHealthProbe
import com.virjar.tk.server.infra.health.TcpHealthProbeConfiguration
import com.virjar.tk.server.infra.health.TcpHealthProbeSecurity
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TcpServerTlsTest {
    @Test
    fun `TLS handshake gates protocol creation and plaintext is rejected`() {
        val tls = createTestServerTlsMaterial()
        val handlerCreations = AtomicInteger()
        val handlerAdded = CountDownLatch(1)
        val handlerInactive = CountDownLatch(1)
        val firstApplicationFrame = CountDownLatch(1)
        val pipelineNames = AtomicReference<List<String>>()
        val server = TcpServer(
            TcpServerConfiguration(
                bindHost = "127.0.0.1",
                port = 0,
                security = TcpTransportSecurity.Tls(tls.tcpServerContext),
            ),
        )
        server.start { channel, _, _ ->
            handlerCreations.incrementAndGet()
            object : ChannelInboundHandlerAdapter() {
                override fun handlerAdded(context: ChannelHandlerContext) {
                    pipelineNames.set(context.pipeline().names())
                    handlerAdded.countDown()
                }

                override fun channelInactive(context: ChannelHandlerContext) {
                    handlerInactive.countDown()
                    context.fireChannelInactive()
                }

                override fun channelRead(context: ChannelHandlerContext, message: Any) {
                    if (message === PingSignal) firstApplicationFrame.countDown()
                }
            }
        }

        var tlsSocket: SSLSocket? = null
        try {
            Socket().use { plaintext ->
                plaintext.soTimeout = 3_000
                plaintext.connect(InetSocketAddress("127.0.0.1", server.actualPort), 3_000)
                // 看似 AUTH 的明文永远不会到达 PacketCodec 或 ImAgent 工厂。
                plaintext.getOutputStream().write("TEAMTALK-AUTH".encodeToByteArray())
                plaintext.getOutputStream().flush()
                runCatching { plaintext.getInputStream().read() }
            }
            assertEquals(0, handlerCreations.get())

            tlsSocket = tls.tcpHealthSocketFactory.createSocket() as SSLSocket
            tlsSocket.useClientMode = true
            tlsSocket.soTimeout = 3_000
            tlsSocket.connect(InetSocketAddress("127.0.0.1", server.actualPort), 3_000)
            tlsSocket.startHandshake()
            // 阻塞式握手完成后立即写入第一条应用记录。Netty 的 SslHandler 契约
            // 会在任何合并的明文之前发布成功事件，因此当这个 PING 被投递时，
            // 门禁必定已经安装了 PacketCodec。
            tlsSocket.outputStream.write(byteArrayOf(PacketType.PING.code.toByte(), 0, 0, 0, 0))
            tlsSocket.outputStream.flush()
            assertTrue(handlerAdded.await(3, TimeUnit.SECONDS))
            assertTrue(firstApplicationFrame.await(3, TimeUnit.SECONDS))
            assertEquals(1, handlerCreations.get())

            val names = pipelineNames.get()
            assertTrue(names.indexOf(TLS_HANDLER_NAME) < names.indexOf(IDLE_HANDLER_NAME))
            assertTrue(names.indexOf(IDLE_HANDLER_NAME) < names.indexOf(PACKET_CODEC_NAME))
            assertTrue(names.indexOf(PACKET_CODEC_NAME) < names.indexOf(IM_AGENT_NAME))

            server.stop()
            assertTrue(handlerInactive.await(3, TimeUnit.SECONDS))
        } finally {
            runCatching { tlsSocket?.close() }
            runCatching { server.stop() }
        }
    }

    @Test
    fun `health proves TLS handshake rather than an open port`() = runBlocking {
        val (tls, foreignTls) = createTestServerTlsMaterialsWithSharedCa()
        val server = TcpServer(
            TcpServerConfiguration(
                bindHost = "127.0.0.1",
                port = 0,
                security = TcpTransportSecurity.Tls(tls.tcpServerContext),
            ),
        )
        server.start { _, _, _ -> ChannelInboundHandlerAdapter() }
        try {
            val tlsHealth = TcpHealthProbe(
                TcpHealthProbeConfiguration(
                    connectHost = "127.0.0.1",
                    port = server.actualPort,
                    security = TcpHealthProbeSecurity.Tls(tls.tcpHealthSocketFactory),
                ),
            ).check()
            assertEquals("UP", tlsHealth.status)
        } finally {
            server.stop()
        }

        val foreignServer = TcpServer(
            TcpServerConfiguration(
                bindHost = "127.0.0.1",
                port = 0,
                security = TcpTransportSecurity.Tls(foreignTls.tcpServerContext),
            ),
        )
        foreignServer.start { _, _, _ -> ChannelInboundHandlerAdapter() }
        try {
            val wrongLeafHealth = TcpHealthProbe(
                TcpHealthProbeConfiguration(
                    connectHost = "127.0.0.1",
                    port = foreignServer.actualPort,
                    security = TcpHealthProbeSecurity.Tls(tls.tcpHealthSocketFactory),
                ),
            ).check()
            assertEquals("DOWN", wrongLeafHealth.status)
            assertEquals("TCP transport probe failed", wrongLeafHealth.detail)
        } finally {
            foreignServer.stop()
        }

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { plaintextListener ->
            val accepted = CountDownLatch(1)
            val acceptor = thread(name = "plaintext-health-test") {
                plaintextListener.accept().use { accepted.countDown() }
            }
            val tlsHealth = TcpHealthProbe(
                TcpHealthProbeConfiguration(
                    connectHost = "127.0.0.1",
                    port = plaintextListener.localPort,
                    security = TcpHealthProbeSecurity.Tls(tls.tcpHealthSocketFactory),
                ),
            ).check()
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            assertEquals("DOWN", tlsHealth.status)
            assertEquals("TCP transport probe failed", tlsHealth.detail)
            acceptor.join(3_000)
        }
    }
}

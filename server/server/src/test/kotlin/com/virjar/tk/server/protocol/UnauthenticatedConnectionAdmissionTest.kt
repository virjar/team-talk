package com.virjar.tk.server.protocol

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.embedded.EmbeddedChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TcpConnectionAdmissionTest {
    @Test
    fun `capacity is instance owned and leases release exactly once`() {
        val firstRuntime = TcpConnectionAdmission(capacity = 2)
        val secondRuntime = TcpConnectionAdmission(capacity = 1)

        val first = assertNotNull(firstRuntime.tryAcquire())
        val second = assertNotNull(firstRuntime.tryAcquire())
        assertNull(firstRuntime.tryAcquire())
        val independent = assertNotNull(secondRuntime.tryAcquire())
        assertNull(secondRuntime.tryAcquire())

        first.close()
        first.close()
        assertEquals(1, firstRuntime.activeCount)
        assertNotNull(firstRuntime.tryAcquire()).close()
        second.close()
        independent.close()
        assertEquals(0, firstRuntime.activeCount)
        assertEquals(0, secondRuntime.activeCount)
    }

    @Test
    fun `concurrent admission never exceeds its hard bound`() {
        val admission = TcpConnectionAdmission(capacity = 8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(64)
        val leases = ConcurrentLinkedQueue<TcpConnectionAdmission.Lease>()
        val executor = Executors.newFixedThreadPool(16)
        try {
            repeat(64) {
                executor.execute {
                    try {
                        assertTrue(start.await(2, TimeUnit.SECONDS))
                        admission.tryAcquire()?.let(leases::add)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(8, leases.size)
            assertEquals(8, admission.activeCount)
            leases.forEach { it.close() }
            assertEquals(0, admission.activeCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `parent pipeline acquires lease before worker registration`() {
        val admission = TcpConnectionAdmission(capacity = 1)
        val unauthenticated = UnauthenticatedConnectionAdmission(capacity = 1)
        val reachedRegistrationBoundary = AtomicBoolean(false)
        val parent = EmbeddedChannel(
            AcceptedChannelAdmissionHandler(admission, unauthenticated),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    val child = msg as Channel
                    assertFalse(child.isRegistered)
                    assertTrue(TcpConnectionAdmission.hasLease(child))
                    assertTrue(UnauthenticatedConnectionAdmission.hasLease(child))
                    assertEquals(1, admission.activeCount)
                    assertEquals(1, unauthenticated.activeCount)
                    reachedRegistrationBoundary.set(true)
                }
            },
        )
        val child = EmbeddedChannel(false, false)
        try {
            parent.writeInbound(child)

            assertTrue(reachedRegistrationBoundary.get())
            assertFalse(child.isRegistered)
            assertEquals(1, admission.activeCount)

            // 模拟 ServerBootstrapAcceptor 把已准入的 child 交给其 worker。Netty 的
            // unsafe.closeForcibly() 有意绕过 closeFuture 完成，并且只会被
            // 在强制关闭前显式归还两把租约的生产路径使用。
            child.register()
            assertTrue(child.isRegistered)

            // 认证成功只归还狭窄的慢认证租约。
            UnauthenticatedConnectionAdmission.release(child)
            assertTrue(TcpConnectionAdmission.hasLease(child))
            assertFalse(UnauthenticatedConnectionAdmission.hasLease(child))
            assertEquals(1, admission.activeCount)
            assertEquals(0, unauthenticated.activeCount)

            child.close().syncUninterruptibly()
            assertEquals(0, admission.activeCount)
        } finally {
            UnauthenticatedConnectionAdmission.release(child)
            TcpConnectionAdmission.releaseForAdmissionFailure(child)
            child.unsafe().closeForcibly()
            parent.finishAndReleaseAll()
        }
    }

    @Test
    fun `child initialization failure releases the pre-registration lease`() {
        val admission = TcpConnectionAdmission(capacity = 1)
        val unauthenticated = UnauthenticatedConnectionAdmission(capacity = 1)
        val initializerRan = AtomicBoolean(false)
        val parent = EmbeddedChannel(
            AcceptedChannelAdmissionHandler(admission, unauthenticated),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    val child = msg as EmbeddedChannel
                    assertFalse(child.isRegistered)
                    child.register()
                }
            },
        )
        val child = EmbeddedChannel(false, false)
        child.pipeline().addLast(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                initializerRan.set(true)
                error("modeled child initialization failure")
            }
        })
        try {
            parent.writeInbound(child)

            assertTrue(initializerRan.get())
            assertFalse(child.isOpen)
            assertEquals(0, admission.activeCount)
            assertEquals(0, unauthenticated.activeCount)
            assertFalse(TcpConnectionAdmission.hasLease(child))
            assertFalse(UnauthenticatedConnectionAdmission.hasLease(child))
        } finally {
            UnauthenticatedConnectionAdmission.release(child)
            TcpConnectionAdmission.releaseForAdmissionFailure(child)
            child.finishAndReleaseAll()
            parent.finishAndReleaseAll()
        }
    }

    @Test
    fun `saturated child is closed without reaching worker registration`() {
        val admission = TcpConnectionAdmission(capacity = 1)
        val unauthenticated = UnauthenticatedConnectionAdmission(capacity = 1)
        val held = assertNotNull(admission.tryAcquire())
        val reachedRegistrationBoundary = AtomicBoolean(false)
        val parent = EmbeddedChannel(
            AcceptedChannelAdmissionHandler(admission, unauthenticated),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    reachedRegistrationBoundary.set(true)
                }
            },
        )
        val child = EmbeddedChannel(false, false)
        try {
            parent.writeInbound(child)

            assertFalse(reachedRegistrationBoundary.get())
            assertFalse(child.isRegistered)
            assertFalse(child.isOpen)
            assertEquals(1, admission.activeCount)
            assertEquals(0, unauthenticated.activeCount)
        } finally {
            held.close()
            child.unsafe().closeForcibly()
            parent.finishAndReleaseAll()
        }
        assertEquals(0, admission.activeCount)
    }

    @Test
    fun `unauthenticated saturation returns the total lease before worker registration`() {
        val admission = TcpConnectionAdmission(capacity = 2)
        val unauthenticated = UnauthenticatedConnectionAdmission(capacity = 1)
        val held = assertNotNull(unauthenticated.tryAcquire())
        val reachedRegistrationBoundary = AtomicBoolean(false)
        val parent = EmbeddedChannel(
            AcceptedChannelAdmissionHandler(admission, unauthenticated),
            object : ChannelInboundHandlerAdapter() {
                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    reachedRegistrationBoundary.set(true)
                }
            },
        )
        val child = EmbeddedChannel(false, false)
        try {
            parent.writeInbound(child)

            assertFalse(reachedRegistrationBoundary.get())
            assertFalse(child.isRegistered)
            assertFalse(child.isOpen)
            assertEquals(0, admission.activeCount, "rejected child must not leak its total lease")
            assertEquals(1, unauthenticated.activeCount)
        } finally {
            held.close()
            child.unsafe().closeForcibly()
            parent.finishAndReleaseAll()
        }
        assertEquals(0, admission.activeCount)
        assertEquals(0, unauthenticated.activeCount)
    }
}

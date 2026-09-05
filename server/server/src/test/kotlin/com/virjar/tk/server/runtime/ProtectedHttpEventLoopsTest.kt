package com.virjar.tk.server.runtime

import com.virjar.tk.server.env.BlockingIoOnProtectedThreadException
import com.virjar.tk.server.env.ThreadIOGuard
import io.ktor.server.netty.NettyApplicationEngine
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtectedHttpEventLoopsTest {
    @Test
    fun `connection worker and call EventLoops are lifecycle protected`() {
        val configuration = NettyApplicationEngine.Configuration().apply {
            configureProtectedHttpEventLoops()
        }
        val bootstrap = ServerBootstrap().apply(configuration.configureBootstrap)
        val connectionGroup = checkNotNull(bootstrap.config().group())
        val workerGroup = checkNotNull(bootstrap.config().childGroup())

        try {
            assertTrue(configuration.shareWorkGroup)
            assertEquals(30, configuration.requestReadTimeoutSeconds)
            assertTrue(connectionGroup.javaClass == MultiThreadIoEventLoopGroup::class.java)
            assertTrue(workerGroup.javaClass == MultiThreadIoEventLoopGroup::class.java)
            assertProtected(connectionGroup)
            assertProtected(workerGroup)
        } finally {
            connectionGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
        }
    }

    private fun assertProtected(group: EventLoopGroup) {
        val failure = group.next().submit<Throwable?> {
            runCatching { ThreadIOGuard.check("Ktor EventLoop probe") }.exceptionOrNull()
        }.get(5, TimeUnit.SECONDS)
        assertIs<BlockingIoOnProtectedThreadException>(failure)
    }
}

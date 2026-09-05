package com.virjar.tk.server.protocol

import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.AttributeKey
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 每个已接受 socket 的、TCP 运行时拥有的硬上界，与鉴权状态无关。
 *
 * 准入由 [AcceptedChannelAdmissionHandler] 在 boss EventLoop 上、
 * Netty 的 `ServerBootstrapAcceptor` 把子连接提交给 worker EventLoop 之前获取。租约随后随
 * 子通道一起传递，且只在该通道关闭时释放。鉴权绝不能返还
 * 容量：已鉴权与同步中的 socket 仍拥有缓冲区、EventLoop 注册、
 * 注册表状态与服务器侧投递工作。
 */
internal class TcpConnectionAdmission(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val active = AtomicInteger(0)

    init {
        require(capacity > 0) { "TCP connection capacity must be positive" }
    }

    fun tryAcquire(): Lease? {
        while (true) {
            val current = active.get()
            if (current >= capacity) return null
            if (active.compareAndSet(current, current + 1)) return Lease(this)
        }
    }

    /**
     * 在 [channel] 仍归 boss EventLoop 拥有时安装租约。close-future 监听器
     * 覆盖注册失败、子流水线初始化失败、普通断开与
     * 服务器关闭。不允许任何协议状态迁移释放它。
     */
    internal fun tryAcquireFor(channel: Channel): Boolean {
        check(!channel.isRegistered) {
            "TCP connection admission must happen before child EventLoop registration"
        }
        val lease = tryAcquire() ?: return false
        try {
            val previous = channel.attr(CHANNEL_LEASE).setIfAbsent(lease)
            check(previous == null) { "TCP child already has a total connection admission lease" }
            channel.closeFuture().addListener(ChannelFutureListener { future ->
                releaseForAdmissionFailure(future.channel())
            })
            return true
        } catch (error: Throwable) {
            channel.attr(CHANNEL_LEASE).compareAndSet(lease, null)
            lease.close()
            throw error
        }
    }

    internal val activeCount: Int get() = active.get()

    private fun release() {
        val remaining = active.decrementAndGet()
        check(remaining >= 0) { "TCP connection admission underflow" }
    }

    internal class Lease internal constructor(
        private val owner: TcpConnectionAdmission,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) owner.release()
        }
    }

    internal companion object {
        const val DEFAULT_CAPACITY = 4_096
        private val CHANNEL_LEASE = AttributeKey.valueOf<Lease>(
            "teamtalk.tcp-connection-lease",
        )

        /** 仅在转发已接受的子连接失败、普通 close 尚未拥有它时使用。 */
        fun releaseForAdmissionFailure(channel: Channel) {
            channel.attr(CHANNEL_LEASE).getAndSet(null)?.close()
        }

        fun hasLease(channel: Channel): Boolean = channel.attr(CHANNEL_LEASE).get() != null
    }
}

/**
 * 嵌套在 [TcpConnectionAdmission] 内部的更窄的慢鉴权上界。此租约也在
 * worker 注册之前获取，但成功的鉴权会释放它，同时保留
 * 总连接租约直到通道关闭。
 */
internal class UnauthenticatedConnectionAdmission(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val active = AtomicInteger(0)

    init {
        require(capacity > 0) { "Unauthenticated connection capacity must be positive" }
    }

    fun tryAcquire(): Lease? {
        while (true) {
            val current = active.get()
            if (current >= capacity) return null
            if (active.compareAndSet(current, current + 1)) return Lease(this)
        }
    }

    internal fun tryAcquireFor(channel: Channel): Boolean {
        check(!channel.isRegistered) {
            "Unauthenticated admission must happen before child EventLoop registration"
        }
        val lease = tryAcquire() ?: return false
        try {
            val previous = channel.attr(CHANNEL_LEASE).setIfAbsent(lease)
            check(previous == null) { "TCP child already has an unauthenticated admission lease" }
            channel.closeFuture().addListener(ChannelFutureListener { future ->
                release(future.channel())
            })
            return true
        } catch (error: Throwable) {
            channel.attr(CHANNEL_LEASE).compareAndSet(lease, null)
            lease.close()
            throw error
        }
    }

    internal val activeCount: Int get() = active.get()

    private fun release() {
        val remaining = active.decrementAndGet()
        check(remaining >= 0) { "Unauthenticated connection admission underflow" }
    }

    internal class Lease internal constructor(
        private val owner: UnauthenticatedConnectionAdmission,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) owner.release()
        }
    }

    internal companion object {
        const val DEFAULT_CAPACITY = 1_024
        private val CHANNEL_LEASE = AttributeKey.valueOf<Lease>(
            "teamtalk.unauthenticated-connection-lease",
        )

        /** 鉴权与 close-future 回调在此竞态；只有一个会返还租约。 */
        fun release(channel: Channel) {
            channel.attr(CHANNEL_LEASE).getAndSet(null)?.close()
        }

        fun hasLease(channel: Channel): Boolean = channel.attr(CHANNEL_LEASE).get() != null
    }
}

/**
 * 紧邻 Netty 私有 `ServerBootstrapAcceptor` 之前的父通道门。
 * 被拒绝的子连接在仍未注册时被强制关闭，绝不会提交给
 * worker EventLoopGroup，因此连接洪泛无法把 worker 注册队列变成
 * 真正的准入边界。
 */
@ChannelHandler.Sharable
internal class AcceptedChannelAdmissionHandler(
    private val connections: TcpConnectionAdmission,
    private val unauthenticatedConnections: UnauthenticatedConnectionAdmission,
) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val child = msg as? Channel
        if (child == null) {
            ctx.fireChannelRead(msg)
            return
        }

        if (child.isRegistered) {
            child.close()
            ctx.fireExceptionCaught(
                IllegalStateException("TCP child reached pre-registration admission after registration"),
            )
            return
        }

        val totalAdmitted = try {
            connections.tryAcquireFor(child)
        } catch (error: Throwable) {
            child.unsafe().closeForcibly()
            throw error
        }
        if (!totalAdmitted) {
            // close() 需要已分配的 EventLoop。此时子连接刻意还没有。
            child.unsafe().closeForcibly()
            return
        }
        val unauthenticatedAdmitted = try {
            unauthenticatedConnections.tryAcquireFor(child)
        } catch (error: Throwable) {
            TcpConnectionAdmission.releaseForAdmissionFailure(child)
            child.unsafe().closeForcibly()
            throw error
        }
        if (!unauthenticatedAdmitted) {
            TcpConnectionAdmission.releaseForAdmissionFailure(child)
            child.unsafe().closeForcibly()
            return
        }

        try {
            ctx.fireChannelRead(child)
        } catch (error: Throwable) {
            UnauthenticatedConnectionAdmission.release(child)
            TcpConnectionAdmission.releaseForAdmissionFailure(child)
            if (child.isRegistered) child.close() else child.unsafe().closeForcibly()
            throw error
        }
    }
}

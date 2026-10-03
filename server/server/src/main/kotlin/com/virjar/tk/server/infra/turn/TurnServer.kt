package com.virjar.tk.server.infra.turn

import com.virjar.tk.protocol.model.IceServer
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.util.concurrent.Promise
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 内嵌 TURN/STUN 服务（RFC 5389 / RFC 5766 UDP 子集），与 TeamTalk 服务端同进程，
 * 不引入任何独立中继进程。
 *
 * 范围：Binding、Allocate、Refresh、CreatePermission、ChannelBind 请求，Send/Data 指示与
 * ChannelData 数据面；TCP relay、DTLS 与移动性不承担。认证使用 [TurnCredentials] 的
 * time-limited 机制，凭据仅在呼叫信令中按需签发——没有呼叫就没有 TURN 使用。
 * allocation/permission/channel 均有硬生命周期并周期清扫，进程重启即全部回收。
 */
class TurnServer(
    val config: TurnServerConfig,
    private val group: EventLoopGroup,
    private val ownsEventLoopGroup: Boolean = false,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(TurnServer::class.java)
    private val credentials = TurnCredentials(config.secret, config.realm)
    private val allocations = ConcurrentHashMap<InetSocketAddress, Allocation>()
    private val allocationLock = Any()
    /** 活跃 allocation 与已预留但 relay bind 尚未完成的槽位数。 */
    private var reservedAllocationSlots = 0
    private val allocationCount = AtomicInteger(0)
    private val nextRelayPort = AtomicInteger(config.relayPortStart)
    private val sweepExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "turn-sweep").apply { isDaemon = true }
    }
    @Volatile private var nonce: String = credentials.newNonce()
    private var listenChannel: Channel? = null
    @Volatile private var publicAddress: InetAddress? = null
    private var closed = false

    @Volatile var running: Boolean = false
        private set

    fun start() {
        synchronized(allocationLock) {
            check(!closed) { "TURN 服务已关闭" }
        }
        check(!running) { "TURN 服务已启动" }
        publicAddress = InetAddress.getByName(config.publicHost)
        listenChannel = Bootstrap()
            .group(group)
            .channel(NioDatagramChannel::class.java)
            .option(ChannelOption.SO_RCVBUF, 1 shl 20)
            .handler(object : ChannelInitializer<NioDatagramChannel>() {
                override fun initChannel(ch: NioDatagramChannel) {
                    ch.pipeline().addLast(ClientHandler())
                }
            })
            .bind(config.listenPort)
            .sync()
            .channel()
        sweepExecutor.scheduleWithFixedDelay(::sweep, SWEEP_INTERVAL_SEC, SWEEP_INTERVAL_SEC, TimeUnit.SECONDS)
        running = true
        log.info(
            "TURN 服务已启动: port={} public={} relay=[{}-{}]",
            config.listenPort, config.publicHost, config.relayPortStart, config.relayPortEnd,
        )
    }

    override fun close() {
        val activeAllocations = synchronized(allocationLock) {
            if (closed) return
            closed = true
            running = false
            val active = allocations.values.toList()
            allocations.clear()
            allocationCount.set(0)
            reservedAllocationSlots -= active.size
            active
        }
        sweepExecutor.shutdownNow()
        var closeFailure: Throwable? = null
        fun capture(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                val previous = closeFailure
                if (previous == null) closeFailure = failure
                else if (failure !== previous) previous.addSuppressed(failure)
            }
        }
        capture { listenChannel?.close()?.syncUninterruptibly() }
        capture {
            val terminated = try {
                sweepExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            if (!terminated) {
                error("TURN expiry worker did not terminate within $SHUTDOWN_TIMEOUT_SECONDS seconds")
            }
        }
        activeAllocations.forEach { allocation ->
            capture { allocation.relayChannel.close().syncUninterruptibly() }
        }
        if (ownsEventLoopGroup) {
            capture {
                group.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS).syncUninterruptibly()
            }
        }
        log.info("TURN 服务已停止")
        closeFailure?.let { throw IllegalStateException("TURN 服务未能完整关闭", it) }
    }

    /**
     * 按呼叫签发 ICE 服务器条目：STUN 与 TURN 同端口，凭据 [validForSec] 秒后过期。
     * 供呼叫信令在 invite 时下发给通话双方；未启用 TURN 的部署不应调用。
     */
    fun issueIceServer(validForSec: Long): IceServer {
        val issued = credentials.issue(validForSec)
        val host = config.publicHost
        return IceServer(
            urls = listOf("stun:$host:${config.listenPort}", "turn:$host:${config.listenPort}?transport=udp"),
            username = issued.username,
            credential = issued.password,
        )
    }

    fun allocationCount(): Int = allocationCount.get()

    /** 一次 TURN allocation：client 5-tuple 绑定一个 relay 通道，含 permission 与 channel 表。 */
    private inner class Allocation(
        val client: InetSocketAddress,
        /** 客户端分配所用的监听通道；客户端方向的 Data/ChannelData 必须从这里发出（RFC 5766 §5 五元组）。 */
        val clientChannel: Channel,
        val relayChannel: Channel,
    ) {
        val relayPort: Int
            get() = (relayChannel.localAddress() as InetSocketAddress).port

        @Volatile var expiryNanos: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.defaultLifetimeSec.toLong())
        val permissions = ConcurrentHashMap<String, Long>()
        val channelsByNumber = ConcurrentHashMap<Int, ChannelBinding>()
        val channelsByPeer = ConcurrentHashMap<InetSocketAddress, ChannelBinding>()
    }

    private class ChannelBinding(val number: Int, val peer: InetSocketAddress, @Volatile var expiryNanos: Long)

    /** 监听端口：处理客户端信令请求与 ChannelData 上行。 */
    private inner class ClientHandler : SimpleChannelInboundHandler<DatagramPacket>() {
        override fun channelRead0(ctx: ChannelHandlerContext, packet: DatagramPacket) {
            val sender = packet.sender() ?: return
            val content = packet.content()
            val bytes = ByteArray(content.readableBytes())
            content.readBytes(bytes)
            try {
                handleFromClient(ctx.channel(), sender, bytes)
            } catch (e: Exception) {
                log.warn("TURN 报文处理失败 from {}: {}", sender, e.message)
            }
        }
    }

    /** relay 端口：把 peer 的数据按 channel/indication 转回客户端。 */
    private inner class RelayHandler(private val allocation: Allocation) : SimpleChannelInboundHandler<DatagramPacket>() {
        override fun channelRead0(ctx: ChannelHandlerContext, packet: DatagramPacket) {
            val peer = packet.sender() ?: return
            val content = packet.content()
            val bytes = ByteArray(content.readableBytes())
            content.readBytes(bytes)
            val binding = allocation.channelsByPeer[peer]?.takeIf { it.expiryNanos > System.nanoTime() }
            val toClient: ByteArray = if (binding != null) {
                encodeChannelData(binding.number, bytes)
            } else {
                val txId = newTransactionId()
                Stun.encode(
                    Stun.messageType(Stun.METHOD_DATA, Stun.CLASS_INDICATION),
                    txId,
                    listOf(
                        StunAttribute(Stun.ATTR_XOR_PEER_ADDRESS, Stun.encodeXorAddress(peer.address, peer.port, txId)),
                        StunAttribute(Stun.ATTR_DATA, bytes),
                    ),
                )
            }
            allocation.clientChannel.writeAndFlush(datagram(toClient, allocation.client))
        }
    }

    private fun handleFromClient(listen: Channel, sender: InetSocketAddress, bytes: ByteArray) {
        if (Stun.isChannelData(bytes)) {
            handleChannelData(sender, bytes)
            return
        }
        val message = Stun.parse(bytes) ?: return
        when (message.method) {
            Stun.METHOD_BINDING -> if (message.clazz == Stun.CLASS_REQUEST) respondBinding(listen, sender, message)
            Stun.METHOD_ALLOCATE -> if (message.clazz == Stun.CLASS_REQUEST) handleAllocate(listen, sender, message, bytes)
            Stun.METHOD_REFRESH -> if (message.clazz == Stun.CLASS_REQUEST) handleRefresh(listen, sender, message, bytes)
            Stun.METHOD_CREATE_PERMISSION -> if (message.clazz == Stun.CLASS_REQUEST) handleCreatePermission(listen, sender, message, bytes)
            Stun.METHOD_CHANNEL_BIND -> if (message.clazz == Stun.CLASS_REQUEST) handleChannelBind(listen, sender, message, bytes)
            Stun.METHOD_SEND -> if (message.clazz == Stun.CLASS_INDICATION) handleSendIndication(sender, message)
        }
    }

    private fun respondBinding(listen: Channel, sender: InetSocketAddress, request: StunMessage) {
        respond(
            listen, sender, request,
            listOf(
                StunAttribute(Stun.ATTR_XOR_MAPPED_ADDRESS, Stun.encodeXorAddress(sender.address, sender.port, request.transactionId)),
                StunAttribute(Stun.ATTR_MAPPED_ADDRESS, Stun.encodeMappedAddress(sender.address, sender.port)),
            ),
        )
    }

    private fun handleAllocate(listen: Channel, sender: InetSocketAddress, request: StunMessage, raw: ByteArray) {
        val transport = request.attr(Stun.ATTR_REQUESTED_TRANSPORT)?.value
        if (transport == null || transport.isEmpty() || transport[0].toInt() != TRANSPORT_UDP) {
            respondError(listen, sender, request, Stun.ERROR_UNSUPPORTED_TRANSPORT, "only UDP relay")
            return
        }
        if (allocations.containsKey(sender)) {
            respondError(listen, sender, request, Stun.ERROR_ALLOCATION_MISMATCH, "allocation exists")
            return
        }
        val key = authenticate(listen, sender, request, raw) ?: return
        if (!reserveAllocationSlot()) {
            respondError(listen, sender, request, Stun.ERROR_ALLOCATION_QUOTA, "allocation quota reached", integrityKey = key)
            return
        }
        val bind = try {
            bindRelayChannel()
        } catch (failure: Exception) {
            releaseAllocationSlot()
            log.warn("relay 端口分配失败: {}", failure.message)
            respondError(listen, sender, request, Stun.ERROR_INSUFFICIENT_CAPACITY, "no relay port", integrityKey = key)
            return
        }
        bind.addListener { future ->
            if (!future.isSuccess) {
                releaseAllocationSlot()
                log.warn("relay 端口分配失败: {}", future.cause()?.message)
                respondError(listen, sender, request, Stun.ERROR_INSUFFICIENT_CAPACITY, "no relay port", integrityKey = key)
                return@addListener
            }
            val relayChannel = future.getNow() as Channel
            if (!running) {
                releaseAllocationSlot()
                relayChannel.close()
                return@addListener
            }
            val allocation = Allocation(sender, listen, relayChannel)
            relayChannel.pipeline().addLast(RelayHandler(allocation))
            if (!registerAllocation(allocation)) {
                relayChannel.close()
                if (!running) return@addListener
                respondError(listen, sender, request, Stun.ERROR_ALLOCATION_MISMATCH, "allocation exists")
                return@addListener
            }
            allocation.expiryNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(requestedLifetime(request).toLong())
            respond(
                listen, sender, request,
                listOf(
                    StunAttribute(Stun.ATTR_XOR_MAPPED_ADDRESS, Stun.encodeXorAddress(sender.address, sender.port, request.transactionId)),
                    StunAttribute(
                        Stun.ATTR_XOR_RELAYED_ADDRESS,
                        Stun.encodeXorAddress(publicAddress!!, allocation.relayPort, request.transactionId),
                    ),
                    StunAttribute(Stun.ATTR_LIFETIME, intAttribute(allocation.remainingLifetimeSec())),
                ),
                integrityKey = key,
            )
            log.debug("allocation 建立: client={} relayPort={}", sender, allocation.relayPort)
        }
    }

    private fun handleRefresh(listen: Channel, sender: InetSocketAddress, request: StunMessage, raw: ByteArray) {
        val key = authenticate(listen, sender, request, raw) ?: return
        val allocation = allocations[sender]
        if (allocation == null) {
            respondError(listen, sender, request, Stun.ERROR_ALLOCATION_MISMATCH, "no allocation", integrityKey = key)
            return
        }
        val lifetime = requestedLifetime(request)
        if (lifetime == 0) {
            removeAllocation(allocation)
        } else {
            allocation.expiryNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(lifetime.toLong())
        }
        respond(
            listen, sender, request,
            listOf(StunAttribute(Stun.ATTR_LIFETIME, intAttribute(lifetime))),
            integrityKey = key,
        )
    }

    private fun handleCreatePermission(listen: Channel, sender: InetSocketAddress, request: StunMessage, raw: ByteArray) {
        val key = authenticate(listen, sender, request, raw) ?: return
        val allocation = allocations[sender]
        if (allocation == null) {
            respondError(listen, sender, request, Stun.ERROR_ALLOCATION_MISMATCH, "no allocation", integrityKey = key)
            return
        }
        val peers = request.attributes.filter { it.type == Stun.ATTR_XOR_PEER_ADDRESS }
        if (peers.isEmpty()) {
            respondError(listen, sender, request, Stun.ERROR_BAD_REQUEST, "missing peer address", integrityKey = key)
            return
        }
        val expiry = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.permissionLifetimeSec.toLong())
        peers.forEach { attr ->
            Stun.decodeXorAddress(attr.value, request.transactionId)?.let { (address, _) ->
                allocation.permissions[address.hostAddress] = expiry
            }
        }
        respond(listen, sender, request, emptyList(), integrityKey = key)
    }

    private fun handleChannelBind(listen: Channel, sender: InetSocketAddress, request: StunMessage, raw: ByteArray) {
        val key = authenticate(listen, sender, request, raw) ?: return
        val allocation = allocations[sender]
        if (allocation == null) {
            respondError(listen, sender, request, Stun.ERROR_ALLOCATION_MISMATCH, "no allocation", integrityKey = key)
            return
        }
        val numberValue = request.attr(Stun.ATTR_CHANNEL_NUMBER)?.value
        val peerValue = request.attr(Stun.ATTR_XOR_PEER_ADDRESS)?.value
        if (numberValue == null || numberValue.size < 2 || peerValue == null) {
            respondError(listen, sender, request, Stun.ERROR_BAD_REQUEST, "missing channel or peer", integrityKey = key)
            return
        }
        val number = ((numberValue[0].toInt() and 0xFF) shl 8) or (numberValue[1].toInt() and 0xFF)
        val peer = Stun.decodeXorAddress(peerValue, request.transactionId)?.toInetSocketAddress()
        if (number !in Stun.CHANNEL_MIN..Stun.CHANNEL_MAX || peer == null) {
            respondError(listen, sender, request, Stun.ERROR_BAD_REQUEST, "invalid channel or peer", integrityKey = key)
            return
        }
        val expiry = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.permissionLifetimeSec.toLong())
        val binding = ChannelBinding(number, peer, expiry)
        allocation.channelsByNumber[number] = binding
        allocation.channelsByPeer[peer] = binding
        // ChannelBind 隐式创建 permission（RFC 5766 §11.2）
        allocation.permissions[peer.address.hostAddress] = expiry
        respond(listen, sender, request, emptyList(), integrityKey = key)
    }

    private fun handleSendIndication(sender: InetSocketAddress, message: StunMessage) {
        val allocation = allocations[sender] ?: return
        val peerAttr = message.attr(Stun.ATTR_XOR_PEER_ADDRESS)?.value ?: return
        val data = message.attr(Stun.ATTR_DATA)?.value ?: return
        val peer = Stun.decodeXorAddress(peerAttr, message.transactionId)?.toInetSocketAddress() ?: return
        if (!allocation.isPeerAllowed(peer.address.hostAddress)) return
        allocation.relayChannel.writeAndFlush(datagram(data, peer))
    }

    private fun handleChannelData(sender: InetSocketAddress, bytes: ByteArray) {
        val allocation = allocations[sender] ?: return
        if (bytes.size < 4) return
        val number = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val length = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (4 + length > bytes.size) return
        val binding = allocation.channelsByNumber[number]?.takeIf { it.expiryNanos > System.nanoTime() } ?: return
        allocation.relayChannel.writeAndFlush(datagram(bytes.copyOfRange(4, 4 + length), binding.peer))
    }

    /**
     * 请求认证：无凭据回 401 挑战；nonce 不匹配回 438；凭据过期/非法或 integrity 不符回 441。
     * 返回 MD5 long-term key；失败时已回错误响应并返回 null。
     */
    private fun authenticate(listen: Channel, sender: InetSocketAddress, request: StunMessage, raw: ByteArray): ByteArray? {
        val username = request.attr(Stun.ATTR_USERNAME)?.value?.toString(Charsets.UTF_8)
        val realm = request.attr(Stun.ATTR_REALM)?.value?.toString(Charsets.UTF_8)
        if (username == null || realm == null || request.integrityAttributeStart < 0) {
            respondError(listen, sender, request, Stun.ERROR_UNAUTHORIZED, "credentials required", challenge = true)
            return null
        }
        if (realm != config.realm) {
            respondError(listen, sender, request, Stun.ERROR_WRONG_CREDENTIALS, "wrong realm")
            return null
        }
        val providedNonce = request.attr(Stun.ATTR_NONCE)?.value?.toString(Charsets.UTF_8)
        if (providedNonce == null || providedNonce != nonce) {
            nonce = credentials.newNonce()
            respondError(listen, sender, request, Stun.ERROR_STALE_NONCE, "stale nonce", challenge = true)
            return null
        }
        val key = credentials.longTermKey(username, System.currentTimeMillis() / 1000)
        if (key == null || Stun.verifyIntegrity(raw, 0, raw.size, key) != true) {
            respondError(listen, sender, request, Stun.ERROR_WRONG_CREDENTIALS, "invalid credentials")
            return null
        }
        return key
    }

    private fun respond(
        listen: Channel,
        sender: InetSocketAddress,
        request: StunMessage,
        attributes: List<StunAttribute>,
        integrityKey: ByteArray? = null,
    ) {
        val response = Stun.encode(
            Stun.messageType(request.method, Stun.CLASS_SUCCESS),
            request.transactionId,
            attributes,
            integrityKey = integrityKey,
        )
        listen.writeAndFlush(datagram(response, sender))
    }

    private fun respondError(
        listen: Channel,
        sender: InetSocketAddress,
        request: StunMessage,
        code: Int,
        reason: String,
        integrityKey: ByteArray? = null,
        challenge: Boolean = false,
    ) {
        val attributes = ArrayList<StunAttribute>()
        attributes += StunAttribute(Stun.ATTR_ERROR_CODE, Stun.encodeErrorCode(code, reason))
        if (challenge) {
            attributes += StunAttribute(Stun.ATTR_REALM, config.realm.toByteArray(Charsets.UTF_8))
            attributes += StunAttribute(Stun.ATTR_NONCE, nonce.toByteArray(Charsets.UTF_8))
        }
        val response = Stun.encode(
            Stun.messageType(request.method, Stun.CLASS_ERROR),
            request.transactionId,
            attributes,
            integrityKey = if (challenge) null else integrityKey,
        )
        listen.writeAndFlush(datagram(response, sender))
    }

    private fun reserveAllocationSlot(): Boolean = synchronized(allocationLock) {
        if (!running || reservedAllocationSlots >= config.maxAllocations) {
            false
        } else {
            reservedAllocationSlots += 1
            true
        }
    }

    /** 将已完成的 relay bind 登记为活跃 allocation；预留槽位在移除时释放。 */
    private fun registerAllocation(allocation: Allocation): Boolean = synchronized(allocationLock) {
        if (!running || allocations.putIfAbsent(allocation.client, allocation) != null) {
            reservedAllocationSlots -= 1
            false
        } else {
            allocationCount.incrementAndGet()
            true
        }
    }

    private fun releaseAllocationSlot() {
        synchronized(allocationLock) {
            reservedAllocationSlots -= 1
        }
    }

    /** 从配置范围内轮转探测 relay 端口；范围耗尽时 promise 失败，不无限重试。 */
    private fun bindRelayChannel(): Promise<Channel> {
        val promise = group.next().newPromise<Channel>()
        val portCount = config.relayPortEnd - config.relayPortStart + 1
        bindWithFallback(promise, portCount)
        return promise
    }

    private fun bindWithFallback(promise: Promise<Channel>, attemptsRemaining: Int) {
        if (!running) {
            promise.setFailure(IllegalStateException("TURN 服务已停止"))
            return
        }
        if (attemptsRemaining <= 0) {
            promise.setFailure(IllegalStateException("TURN relay port range is exhausted"))
            return
        }
        val port = nextRelayPort.getAndUpdate { current -> if (current >= config.relayPortEnd) config.relayPortStart else current + 1 }
        val future = Bootstrap()
            .group(group)
            .channel(NioDatagramChannel::class.java)
            .option(ChannelOption.SO_RCVBUF, 1 shl 20)
            .handler(object : ChannelInitializer<NioDatagramChannel>() {
                override fun initChannel(ch: NioDatagramChannel) {}
            })
            .bind(port)
        future.addListener(
            // 显式以 ChannelFuture 类型注册，避免泛型 Void 推断拿不到 channel
            io.netty.util.concurrent.GenericFutureListener<io.netty.channel.ChannelFuture> { f ->
                if (f.isSuccess) {
                    promise.setSuccess(f.channel())
                } else if (attemptsRemaining == 1) {
                    promise.setFailure(f.cause() ?: IllegalStateException("TURN relay port range is exhausted"))
                } else {
                    bindWithFallback(promise, attemptsRemaining - 1)
                }
            },
        )
    }

    private fun removeAllocation(allocation: Allocation) {
        val removed = synchronized(allocationLock) {
            if (allocations.remove(allocation.client, allocation)) {
                allocationCount.decrementAndGet()
                reservedAllocationSlots -= 1
                true
            } else {
                false
            }
        }
        if (removed) {
            allocation.relayChannel.close()
        }
    }

    private fun sweep() {
        try {
            val now = System.nanoTime()
            allocations.values.forEach { allocation ->
                if (allocation.expiryNanos <= now) {
                    removeAllocation(allocation)
                    return@forEach
                }
                allocation.permissions.entries.removeIf { it.value <= now }
                allocation.channelsByNumber.values
                    .filter { it.expiryNanos <= now }
                    .forEach {
                        allocation.channelsByNumber.remove(it.number)
                        allocation.channelsByPeer.remove(it.peer, it)
                    }
            }
        } catch (e: Exception) {
            log.warn("TURN 清扫失败: {}", e.message)
        }
    }

    private fun Allocation.isPeerAllowed(peerIp: String): Boolean =
        (permissions[peerIp] ?: 0) > System.nanoTime()

    private fun Allocation.remainingLifetimeSec(): Int =
        TimeUnit.NANOSECONDS.toSeconds((expiryNanos - System.nanoTime()).coerceAtLeast(0)).toInt()

    private fun requestedLifetime(request: StunMessage): Int {
        val value = request.attr(Stun.ATTR_LIFETIME)?.value ?: return config.defaultLifetimeSec
        if (value.size < 4) return config.defaultLifetimeSec
        val requested = ((value[0].toInt() and 0xFF) shl 24) or ((value[1].toInt() and 0xFF) shl 16) or
            ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
        return requested.coerceIn(0, config.maxLifetimeSec)
    }

    private fun intAttribute(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

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
        DatagramPacket(Unpooled.wrappedBuffer(bytes), target)

    private fun newTransactionId(): ByteArray = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }

    private fun Pair<InetAddress, Int>.toInetSocketAddress(): InetSocketAddress = InetSocketAddress(first, second)

    companion object {
        private const val TRANSPORT_UDP = 17
        private const val SWEEP_INTERVAL_SEC = 30L
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}

/** 内嵌 TURN 的运行配置；secret 来自部署状态目录的凭据文件，绝不写入配置源码。 */
class TurnServerConfig(
    /** 监听端口，同时承载 STUN 与 TURN。 */
    val listenPort: Int,
    /** 对外宣告的主机名或公网 IP（relay 候选地址），NAT 部署必须显式配置。 */
    val publicHost: String,
    /** relay 端口范围（含端点）。 */
    val relayPortStart: Int,
    val relayPortEnd: Int,
    /** time-limited 凭据共享 secret。 */
    val secret: ByteArray,
    /** STUN realm，客户端认证域。 */
    val realm: String,
    /** allocation 总量上限（每通话最多占用 2 个）。 */
    val maxAllocations: Int = 256,
    val defaultLifetimeSec: Int = 600,
    val maxLifetimeSec: Int = 3600,
    val permissionLifetimeSec: Int = 300,
) {
    init {
        require(listenPort in 1..65535) { "TURN 监听端口非法" }
        require(relayPortStart in 1..65535 && relayPortEnd >= relayPortStart && relayPortEnd - relayPortStart <= 1024) {
            "relay 端口范围非法"
        }
        require(publicHost.isNotBlank()) { "publicHost 不能为空" }
        require(secret.size >= 16) { "TURN secret 至少 16 字节" }
        require(realm.isNotBlank()) { "realm 不能为空" }
    }
}

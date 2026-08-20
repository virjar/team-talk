package com.virjar.tk.client

import com.virjar.tk.protocol.*
import com.virjar.tk.protocol.payload.*
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.ScheduledFuture
import io.netty.channel.EventLoop
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.virjar.tk.log.TkLoggerFactory
import com.virjar.tk.auth.AuthRules
import java.util.concurrent.TimeUnit

/**
 * TCP 客户端。完全事件驱动，无阻塞等待。
 *
 * 线程模型：
 * - 单线程 EventLoop（NioEventLoopGroup(1)），所有状态操作串行
 * - Pipeline 事件驱动：channelRead / channelInactive / userEventTriggered
 * - scope dispatcher = eventLoop，协程也在同一线程执行
 *
 * 心跳：IdleStateHandler(writerIdle=30s, readerIdle=90s)
 * - 写空闲 30s → 自动发 PingSignal
 * - 读空闲 90s → 关闭连接 → channelInactive → 自动重连
 *
 * 重连：非主动 disconnect 时自动重连，指数退避 1s→2s→4s→8s→30s。
 * 重连后自动重发上次认证包（保存在 pendingAuth）。
 *
 * @param onAuthResult 认证结果回调（success, uid, username, name, refreshToken, failureReason）。
 *        ImClient 不持有用户身份（三级状态隔离），认证结果通过此回调传给 UserSession。
 */
class ImClient(
    private val host: String = "",
    private val port: Int = 0,
    private val onAuthResult: ((success: Boolean, uid: String?, username: String?, name: String?, refreshToken: String?, accessToken: String?, failureReason: String?) -> Unit)? = null,
) {
    private val logger = TkLoggerFactory.get("ImClient")

    // 单线程 EventLoop，所有状态串行。
    @Volatile
    private var workerGroup: NioEventLoopGroup = NioEventLoopGroup(1)
    @Volatile
    private var eventLoop: EventLoop = workerGroup.next()

    // 连接级状态（EventLoop 独占）
    private var channel: Channel? = null
    private var connectingChannel: Channel? = null

    /**
     * Every TCP attempt receives a new generation. Pipeline callbacks must match this value before
     * mutating global connection state; a superseded channel may report channelInactive after its
     * replacement is already authenticated.
     */
    @Volatile
    private var connectionGeneration = 0L

    /**
     * Logical transport owner. Explicit connect/auth starts a new owner, while automatic network
     * reconnects keep it. ClientSession captures this lease so a retired session cannot disconnect
     * a newer login that happens to reuse the same ImClient.
     */
    @Volatile
    private var transportOwnerGeneration = 0L

    internal val currentTransportOwnerGeneration: Long get() = transportOwnerGeneration

    /** 认证终态（AUTH_FAILED 后置位）：停止自动重连——失效 token 重试永远失败，
     *  曾致 retry=28+ 风暴反复踢翻登录窗（F30）。用户主动 login/register 时重置。 */
    @Volatile
    private var authTerminal = false
    private var scope: CoroutineScope? = null
    private val pendingAcks = mutableMapOf<String, CompletableDeferred<MessageAckPayload>>()

    // 重连
    private var retryCount = 0
    private var destroyed = false
    private var reconnectFuture: ScheduledFuture<*>? = null

    // 连接目标（connect 时设置，重连时复用）
    private var connectHost = host
    private var connectPort = port

    // 认证参数（重连时自动重发）
    private var pendingAuth: AuthRequestPayload? = null

    // 线程安全的观察状态
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Only an explicit AUTH_RESP can populate this flow. A refused socket, timeout or malformed
    // frame therefore remains a transport failure and can never trigger a forced-upgrade UI.
    private val _authenticationFailure = MutableStateFlow<AuthenticationFailure?>(null)
    val authenticationFailure: StateFlow<AuthenticationFailure?> =
        _authenticationFailure.asStateFlow()

    private val incomingPackets = MutableSharedFlow<IProto>(extraBufferCapacity = 64)
    val packets: SharedFlow<IProto> = incomingPackets.asSharedFlow()

    /** 暴露 scope 供 RpcClient / EventProcessor 复用。 */
    val coroutineScope: CoroutineScope? get() = scope

    /**
     * 持久事件同步由本地投影 owner 显式接管。AUTH 成功只进入 SYNCHRONIZING；
     * consumer 从持久 cursor 发起分页，并在每批完整落库后才请求下一批。
     */
    private data class EventSyncBinding(
        val owner: Any,
        val cursor: () -> Long,
        val processBatch: suspend (List<NotifyPayload>) -> Long,
        val reset: suspend () -> Long,
    )

    private var eventSyncBinding: EventSyncBinding? = null
    private var syncBatchInFlight = false
    private var syncResetApplied = false
    private var lastRequestedSyncCursor = -1L

    /**
     * Cursor durably projected by the active synchronization attempt, or -1 outside that phase.
     * UI/controller watchdogs observe this value as progress; each committed page renews their
     * no-progress window without treating a long but healthy replay as an authentication timeout.
     */
    private val _eventSyncCursor = MutableStateFlow(-1L)
    val eventSyncCursor: StateFlow<Long> = _eventSyncCursor.asStateFlow()

    internal fun installEventSync(
        owner: Any,
        cursor: () -> Long,
        processBatch: suspend (List<NotifyPayload>) -> Long,
        reset: suspend () -> Long,
    ) {
        doOnEventLoop {
            eventSyncBinding = EventSyncBinding(owner, cursor, processBatch, reset)
            beginEventSyncIfReady()
        }
    }

    internal fun removeEventSync(owner: Any) {
        doOnEventLoop {
            if (eventSyncBinding?.owner === owner) eventSyncBinding = null
        }
    }

    /** Report per-event durable progress while a page is still being projected on Dispatchers.IO. */
    internal fun reportEventSyncProgress(cursor: Long) {
        if (_state.value == ConnectionState.SYNCHRONIZING && cursor > _eventSyncCursor.value) {
            _eventSyncCursor.value = cursor
        }
    }

    /** A broken projection must reconnect from its last durable cursor, never skip the event. */
    internal fun closeForEventResync(reason: String, cause: Throwable? = null) {
        doOnEventLoop {
            if (cause == null) logger.fault(reason) else logger.fault(reason, cause)
            channel?.close()
        }
    }

    // ── 公共 API ──

    /**
     * 原子化认证连接：设置 pendingAuth + connect 在同一个 EventLoop 任务内完成。
     *
     * 消除协程线程与 EventLoop 的竞态：调用方在协程线程上构造 AuthRequestPayload（CPU 工作），
     * 然后调此方法——payload 已构造完毕，此方法内不做 CPU 工作，直接投递一个 EventLoop 任务，
     * 在任务内先设 pendingAuth 再 createAndConnect。TCP 回调一定排在 pendingAuth 设置之后。
     */
    fun connectAndAuth(auth: AuthRequestPayload, host: String, port: Int) {
        logger.trace("connectAndAuth: host=$host, port=$port, authType=${auth.authType}")
        if (destroyed && !ensureEventLoop()) {
            logger.trace("connectAndAuth ignored: ImClient destroyed (EventLoop shut down)")
            return
        }
        doOnEventLoop {
            // 取消待执行的重连定时器（channelInactive 排的），避免与新连接竞争
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            destroyed = false
            authTerminal = false
            _authenticationFailure.value = null
            transportOwnerGeneration += 1
            pendingAuth = auth
            connectHost = host
            connectPort = port
            createAndConnect()            // 再启动连接（TCP 回调排在 pendingAuth 设置之后）
        }
    }

    fun connect(host: String = this.connectHost, port: Int = this.connectPort) {
        logger.trace("connect: host=$host, port=$port")
        if (destroyed && !ensureEventLoop()) {
            logger.trace("connect() ignored: ImClient destroyed (EventLoop shut down)")
            return
        }
        doOnEventLoop {
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            destroyed = false
            transportOwnerGeneration += 1
            connectHost = host
            connectPort = port
            createAndConnect()
        }
    }

    fun login(
        username: String,
        password: String,
        deviceId: String,
        deviceName: String,
        host: String = connectHost,
        port: Int = connectPort,
        deviceModel: String? = null,
        deviceFlag: Int = 0,
    ) {
        AuthRules.validateLogin(username, password)
        val auth = AuthRequestPayload(authType = 0, username = username, password = password,
            deviceId = deviceId, deviceName = deviceName, deviceModel = deviceModel, deviceFlag = deviceFlag)
        authTerminal = false // 用户主动重登：清除终态
        logger.trace("login requested: username=$username")
        // pendingAuth + connect 原子化，消除协程/EventLoop 竞态
        connectAndAuth(auth, host, port)
    }

    fun register(
        username: String,
        password: String,
        name: String,
        deviceId: String,
        deviceName: String,
        host: String = connectHost,
        port: Int = connectPort,
        deviceModel: String? = null,
        deviceFlag: Int = 0,
    ) {
        AuthRules.validateRegister(username, password)
        val auth = AuthRequestPayload(authType = 1, username = username, password = password,
            name = name, deviceId = deviceId, deviceName = deviceName,
            deviceModel = deviceModel, deviceFlag = deviceFlag)
        authTerminal = false
        logger.trace("register requested: username=$username")
        connectAndAuth(auth, host, port)
    }

    fun authenticate(
        uid: String,
        token: String,
        deviceId: String,
        deviceName: String,
        host: String = connectHost,
        port: Int = connectPort,
        deviceModel: String? = null,
        deviceFlag: Int = 0,
    ) {
        val auth = AuthRequestPayload(authType = 2, refreshToken = token,
            deviceId = deviceId, deviceName = deviceName, deviceModel = deviceModel, deviceFlag = deviceFlag)
        authTerminal = false
        logger.trace("authenticate requested: uid=$uid")
        connectAndAuth(auth, host, port)
    }

    fun send(proto: IProto) {
        // SDK 出站防线：先归一化附件，再执行所有消息体/type/正文预算规则。
        // FileStore 存在性仍由服务端做权威校验。
        val outbound = if (proto is com.virjar.tk.model.Message) {
            canonicalizeOutboundMessage(proto)
        } else proto
        val ch = channel
        if (ch == null) {
            logger.trace("send() called but channel is null, type=${outbound::class.simpleName}")
        } else if (_state.value != ConnectionState.AUTHENTICATED
            && outbound !is AuthRequestPayload
            && !(_state.value == ConnectionState.SYNCHRONIZING && outbound is SyncRequestPayload)
            && outbound !is PingSignal
            && outbound !is PongSignal
        ) {
            // 业务包只在完整同步后发送；SYNCHRONIZING 只允许协议内的分页请求。
            logger.trace("send() blocked: not authenticated, state=${_state.value}, type=${outbound::class.simpleName}")
        } else {
            ch.writeAndFlush(outbound)
        }
    }

    /**
     * 发送消息并等待服务端 ACK。
     * withContext(scope) 确保 pendingAcks 操作在 EventLoop 上。
     */
    suspend fun sendAndWaitAck(message: com.virjar.tk.model.Message, timeoutMs: Long = 10_000L): MessageAckPayload {
        // 在登记 pendingAck 之前校验，避免非法消息抛错后留下永不完成的 deferred。
        val outbound = canonicalizeOutboundMessage(message)
        val s = scope ?: throw IllegalStateException("Not connected")
        return withContext(s.coroutineContext) {
            val deferred = CompletableDeferred<MessageAckPayload>()
            pendingAcks[outbound.clientMsgId] = deferred
            try {
                send(outbound)
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                MessageAckPayload(outbound.clientMsgId, 0, -1, "ACK timeout")
            } finally {
                // 正常 ACK 会先由 handleAck 移除；同步发送异常、调用方取消与超时都在此兜底。
                pendingAcks.remove(outbound.clientMsgId)
            }
        }
    }

    /**
     * 软断开：关闭当前连接 + 取消 scope + 清理 pending，但**保留 EventLoop**，
     * 允许后续 [connect] 复用同一 [ImClient] 实例（UI 登出→重新登录场景）。
     *
     * 与 [destroy] 的区别：[disconnect] 仅切断"这次连接"，[destroy] 才永久销毁线程资源。
     * 登出、认证失效应调用 [disconnect]；进程退出或彻底放弃实例才调用 [destroy]。
     */
    fun disconnect() {
        doOnEventLoop {
            disconnectCurrentTransport()
        }
    }

    /** Disconnect only while [expectedOwnerGeneration] still owns this logical transport. */
    internal fun disconnectIfOwned(expectedOwnerGeneration: Long) {
        doOnEventLoop {
            if (transportOwnerGeneration != expectedOwnerGeneration) {
                logger.trace(
                    "Ignoring disconnect from retired transport owner=$expectedOwnerGeneration, " +
                        "current=$transportOwnerGeneration",
                )
                return@doOnEventLoop
            }
            disconnectCurrentTransport()
        }
    }

    /**
     * 永久销毁：软断开 + 关闭 Netty EventLoop 线程。之后此实例不可再用。
     * 仅在进程退出或实例彻底废弃时调用；UI 登出请用 [disconnect]。
     */
    fun destroy() {
        doOnEventLoop {
            disconnectCurrentTransport()
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        }
    }

    /** EventLoop-only teardown shared by disconnect, destroy and a valid session lease. */
    private fun disconnectCurrentTransport() {
        destroyed = true
        transportOwnerGeneration += 1
        // Invalidate handlers before close() can enqueue their channelInactive callbacks.
        connectionGeneration += 1
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        val active = channel
        val connecting = connectingChannel
        channel = null
        connectingChannel = null
        scope?.cancel()
        scope = null
        cleanupOnDisconnect()
        active?.close()
        if (connecting !== active) connecting?.close()
        // 注意：不 shutdown workerGroup——保留 EventLoop 供 connect() 复用。
        _state.value = ConnectionState.DISCONNECTED
    }

    /**
     * 确保 EventLoop 存活。若已被 [destroy] 关闭则重建。
     * @return true 若 EventLoop 当前可用（原有或新建）；false 若重建失败。
     */
    private fun ensureEventLoop(): Boolean {
        if (!eventLoop.isShuttingDown && !eventLoop.isShutdown) return true
        return try {
            workerGroup = NioEventLoopGroup(1)
            eventLoop = workerGroup.next()
            logger.trace("EventLoop rebuilt after shutdown")
            true
        } catch (e: Exception) {
            logger.fault("Failed to rebuild EventLoop", e)
            false
        }
    }

    /**
     * 模拟网络断开（测试钩子，SDK 集成测试用）：关闭底层 channel 但不置 destroyed，
     * 触发 channelInactive → 自动重连路径（区别于主动 disconnect）。
     */
    fun simulateNetworkDrop() {
        doOnEventLoop { (channel ?: connectingChannel)?.close() }
    }

    // ── 连接管理（EventLoop 上执行） ──

    private fun createAndConnect() {
        // Supersede an active or in-flight attempt explicitly. Incrementing first makes every late
        // callback from that attempt observationally inert.
        val generation = connectionGeneration + 1
        connectionGeneration = generation
        val previousActive = channel
        val previousConnecting = connectingChannel
        channel = null
        connectingChannel = null
        scope?.cancel()
        scope = null
        cleanupOnDisconnect()
        previousActive?.close()
        if (previousConnecting !== previousActive) previousConnecting?.close()

        _state.value = ConnectionState.CONNECTING
        logger.trace("Connecting to $connectHost:$connectPort (generation=$generation)")

        val bootstrap = Bootstrap()
        bootstrap.group(eventLoop)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // 当前协议无独立握手层：首帧 AUTH 即连接序言
                    ch.pipeline()
                        .addLast(IdleStateHandler(
                            PacketCodec.READ_IDLE_TIMEOUT_SECONDS,
                            PacketCodec.PING_INTERVAL_SECONDS,
                            0, TimeUnit.SECONDS))
                        // The shared codec can only lift its 4 KiB pre-authentication fence when
                        // it knows this side receives AUTH_RESP. Using the role-less default keeps
                        // the client permanently at 4 KiB and turns any larger live/sync NOTIFY
                        // into a corrupt frame immediately after an otherwise successful login.
                        .addLast(PacketCodec(inboundRole = PacketInboundRole.CLIENT))
                        .addLast(PacketHandler(generation))
                }
            })

        val connectFuture = bootstrap.connect(connectHost, connectPort)
        connectingChannel = connectFuture.channel()
        connectFuture.addListener { future ->
            val connectedChannel = (future as io.netty.channel.ChannelFuture).channel()
            if (generation != connectionGeneration || destroyed) {
                logger.trace("Ignoring stale connect completion (generation=$generation, current=$connectionGeneration)")
                connectedChannel.close()
                return@addListener
            }
            if (connectingChannel === connectedChannel) connectingChannel = null
            if (!future.isSuccess) {
                logger.trace("Connect failed: ${future.cause()?.message}")
                _state.value = ConnectionState.DISCONNECTED
                if (!destroyed) scheduleReconnect()
            } else {
                // TCP 就绪 = 数据阶段就绪（无独立握手）：建 scope、置 CONNECTED、发认证
                onTcpReady(connectedChannel, generation)
            }
        }
    }

    /** TCP 就绪后在 EventLoop 上创建会话 scope、置 CONNECTED，并直接发送带序言的 AUTH。 */
    private fun onTcpReady(ch: Channel, generation: Long) {
        if (generation != connectionGeneration || destroyed) {
            ch.close()
            return
        }
        channel = ch
        scope = CoroutineScope(eventLoop.asCoroutineDispatcher() + SupervisorJob() +
            CoroutineExceptionHandler { _, throwable ->
                logger.fault("ImClient scope unhandled exception", throwable)
            })
        _state.value = ConnectionState.CONNECTED

        // 认证包只建立身份。持久事件 cursor 不再塞进 AUTH；本地投影 ready 后另行分页同步。
        pendingAuth?.let {
            logger.trace("Sending auth: type=${it.authType}")
            channel?.writeAndFlush(it)
        }
    }

    private fun scheduleReconnect() {
        if (destroyed || authTerminal || reconnectFuture != null) return
        val delay = nextRetryDelay(retryCount)
        retryCount++
        val disconnectedGeneration = connectionGeneration
        logger.trace("Schedule reconnect in ${delay}ms (retry=$retryCount)")
        reconnectFuture = eventLoop.schedule({
            reconnectFuture = null
            if (!destroyed && !authTerminal && connectionGeneration == disconnectedGeneration) {
                // Automatic reconnect belongs to the same ClientSession transport lease.
                createAndConnect()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun nextRetryDelay(count: Int): Long {
        return minOf(30_000L, 1000L * (1L shl minOf(count, 4)))
    }

    // ── 内部方法（EventLoop 上调用） ──

    private fun handleAck(ack: MessageAckPayload) {
        pendingAcks.remove(ack.clientMsgId)?.complete(ack)
            ?: logger.trace("Received ACK for unknown clientMsgId: ${ack.clientMsgId}")
    }

    private fun handleAuthResponse(response: AuthResponsePayload) {
        if (response.code == AuthResponsePayload.CODE_OK) {
            retryCount = 0
            _authenticationFailure.value = null
            // 认证成功 → pendingAuth 升级为 refresh-token 认证（authType=2）。
            // register/login 只用一次；后续重连必须使用服务端轮换后的 refresh token。
            response.refreshToken?.let { newToken ->
                pendingAuth = pendingAuth?.copy(
                    authType = 2,
                    refreshToken = newToken,
                    username = null,
                    password = null,
                    name = null,
                )
            }
            // 认证结果通过回调传给 UserSession（三级状态隔离：ImClient 不持有用户身份）
            onAuthResult?.invoke(true, response.uid, response.username, response.name, response.refreshToken, response.accessToken, null)
            syncBatchInFlight = false
            syncResetApplied = false
            lastRequestedSyncCursor = -1L
            _state.value = ConnectionState.SYNCHRONIZING
            beginEventSyncIfReady()
            logger.trace("Identity authenticated; synchronizing uid=${response.uid}, username=${response.username}")
        } else {
            val failure = checkNotNull(response.toAuthenticationFailure())
            authTerminal = true // 终态：channelInactive 不再自动重连
            _authenticationFailure.value = failure
            _state.value = ConnectionState.AUTH_FAILED
            onAuthResult?.invoke(false, null, null, null, null, null, failure.reason)
            logger.trace("Auth failed (terminal): code=${response.code}, reason=${response.reason}")
        }
        scope?.launch { incomingPackets.emit(response) }
    }

    private fun beginEventSyncIfReady() {
        if (_state.value != ConnectionState.SYNCHRONIZING || lastRequestedSyncCursor >= 0L) return
        val binding = eventSyncBinding ?: return
        val initialCursor = binding.cursor()
        if (initialCursor < 0L) {
            closeForEventResync("Persistent event cursor is negative: $initialCursor")
            return
        }
        lastRequestedSyncCursor = initialCursor
        _eventSyncCursor.value = initialCursor
        channel?.writeAndFlush(SyncRequestPayload(initialCursor))
        logger.trace("Event sync requested after cursor=$initialCursor")
    }

    private fun handleSyncBatch(batch: SyncBatchPayload) {
        handleSyncEvents(batch.events)
    }

    /** A maximum-sized durable event may be sent as a standalone NOTIFY during replay. */
    private fun handleSyncEvent(event: NotifyPayload) {
        handleSyncEvents(listOf(event))
    }

    private fun handleSyncEvents(events: List<NotifyPayload>) {
        if (_state.value != ConnectionState.SYNCHRONIZING || syncBatchInFlight) {
            closeForEventResync("Unexpected or overlapping sync batch")
            return
        }
        val binding = eventSyncBinding
        val connectionScope = scope
        if (binding == null || connectionScope == null) {
            closeForEventResync("Sync batch arrived without an active projection owner")
            return
        }
        val requestedAfter = lastRequestedSyncCursor
        if (
            requestedAfter < 0L ||
            events.isEmpty() ||
            events.any { it.eventId <= 0L } ||
            events.zipWithNext().any { (left, right) -> left.eventId >= right.eventId } ||
            events.first().eventId <= requestedAfter
        ) {
            closeForEventResync(
                "Sync events are not ordered after requested cursor=$requestedAfter",
            )
            return
        }
        syncBatchInFlight = true
        connectionScope.launch {
            try {
                val persistedCursor = binding.processBatch(events)
                val expectedCursor = events.last().eventId
                check(persistedCursor == expectedCursor) {
                    "Sync projection stopped at $persistedCursor instead of $expectedCursor"
                }
                lastRequestedSyncCursor = persistedCursor
                _eventSyncCursor.value = persistedCursor
                channel?.writeAndFlush(SyncRequestPayload(persistedCursor))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                closeForEventResync("Failed to persist sync batch", failure)
            } finally {
                syncBatchInFlight = false
            }
        }
    }

    private fun handleSyncReady() {
        if (_state.value != ConnectionState.SYNCHRONIZING || syncBatchInFlight || lastRequestedSyncCursor < 0L) {
            closeForEventResync("Unexpected SYNC_READY")
            return
        }
        _state.value = ConnectionState.AUTHENTICATED
        logger.trace("Persistent event sync ready at cursor=$lastRequestedSyncCursor")
    }

    private fun handleSyncReset() {
        if (
            _state.value != ConnectionState.SYNCHRONIZING ||
            syncBatchInFlight ||
            syncResetApplied ||
            lastRequestedSyncCursor < 0L
        ) {
            closeForEventResync("Unexpected, overlapping, or repeated SYNC_RESET")
            return
        }
        val binding = eventSyncBinding
        val connectionScope = scope
        if (binding == null || connectionScope == null) {
            closeForEventResync("SYNC_RESET arrived without an active projection owner")
            return
        }
        syncResetApplied = true
        syncBatchInFlight = true
        connectionScope.launch {
            try {
                val resetCursor = binding.reset()
                check(resetCursor == 0L) { "Projection reset returned cursor=$resetCursor" }
                check(_state.value == ConnectionState.SYNCHRONIZING) {
                    "Connection left synchronization during projection reset"
                }
                lastRequestedSyncCursor = 0L
                _eventSyncCursor.value = 0L
                channel?.writeAndFlush(SyncRequestPayload(0L))
                    ?: error("Connection closed during projection reset")
                logger.trace("Server projection reset; event sync restarted from cursor=0")
            } catch (cancelled: CancellationException) {
                closeForEventResync("Server projection reset was cancelled", cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                closeForEventResync("Failed to reset server projection", failure)
            } finally {
                syncBatchInFlight = false
            }
        }
    }

    /**
     * 连接级清理：只清连接层状态（pendingAcks）。
     *
     * uid/myUsername/myName/refreshToken 是用户层状态（三级状态设计），
     * 用户层状态（uid/refreshToken 等）在 UserSession 中，不受 TCP 断开影响。
     */
    private fun cleanupOnDisconnect() {
        syncBatchInFlight = false
        syncResetApplied = false
        lastRequestedSyncCursor = -1L
        _eventSyncCursor.value = -1L
        pendingAcks.forEach { (_, deferred) ->
            deferred.completeExceptionally(CancellationException("Connection closed"))
        }
        pendingAcks.clear()
    }

    private fun doOnEventLoop(task: () -> Unit) {
        if (eventLoop.inEventLoop()) {
            task()
        } else {
            // EventLoop 可能已被 destroy() 关闭，execute 会抛 RejectedExecutionException。
            // 退化为同步执行并记日志，避免上层协程崩溃（登录页等场景不希望崩进程）。
            try {
                eventLoop.execute(task)
            } catch (e: RejectedExecutionException) {
                logger.fault("doOnEventLoop rejected (executor ${if (eventLoop.isShutdown) "shut down" else "shutting down"}); running inline", e)
                task()
            }
        }
    }

    // ── Netty Handlers ──

    /**
     * 数据阶段 Handler：处理业务包、心跳、断连。
     */
    private inner class PacketHandler(
        private val generation: Long,
    ) : ChannelInboundHandlerAdapter() {
        private fun isCurrent(ctx: ChannelHandlerContext): Boolean =
            generation == connectionGeneration &&
                (channel === ctx.channel() || connectingChannel === ctx.channel())

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (!isCurrent(ctx)) {
                logger.trace("Ignoring packet from stale channel generation=$generation")
                return
            }
            if (msg is IProto) {
                logger.trace("Packet received: type=${msg::class.simpleName}")
                when (msg) {
                    is AuthResponsePayload -> handleAuthResponse(msg)
                    is SyncBatchPayload -> handleSyncBatch(msg)
                    is SyncReadyPayload -> handleSyncReady()
                    is SyncResetPayload -> handleSyncReset()
                    is NotifyPayload -> {
                        if (_state.value == ConnectionState.SYNCHRONIZING) {
                            handleSyncEvent(msg)
                        } else if (!incomingPackets.tryEmit(msg)) {
                            logger.fault("Inbound packet buffer full; closing type=${msg::class.simpleName}")
                            ctx.close()
                        }
                    }
                    is MessageAckPayload -> handleAck(msg)
                    is PingSignal -> send(PongSignal)
                    else -> {
                        if (!incomingPackets.tryEmit(msg)) {
                            // A durable NOTIFY or RPC response cannot be silently discarded. Closing
                            // makes the durable cursor/retry owners recover from their last commit.
                            logger.fault("Inbound packet buffer full; closing type=${msg::class.simpleName}")
                            ctx.close()
                        }
                    }
                }
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (!isCurrent(ctx)) {
                ctx.close()
                return
            }
            if (evt is IdleStateEvent) {
                when (evt.state()) {
                    IdleState.WRITER_IDLE -> {
                        logger.trace("Writer idle, sending PING")
                        send(PingSignal)
                    }
                    IdleState.READER_IDLE -> {
                        logger.trace("No data received for ${PacketCodec.READ_IDLE_TIMEOUT_SECONDS}s, closing connection")
                        ctx.close()
                    }
                    else -> {}
                }
            } else {
                super.userEventTriggered(ctx, evt)
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            if (destroyed || !isCurrent(ctx)) {
                logger.trace("Ignoring channelInactive from stale generation=$generation")
                return
            }
            cleanupOnDisconnect()
            scope?.cancel()
            scope = null
            if (channel === ctx.channel()) channel = null
            if (connectingChannel === ctx.channel()) connectingChannel = null
            if (authTerminal) {
                // 认证终态：保持 AUTH_FAILED，不再自动重连（F30：失效 token 风暴）
                _state.value = ConnectionState.AUTH_FAILED
                return
            }
            _state.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.fault("Connection error", cause)
            ctx.close()
        }
    }
}

/** SDK 所有消息发送入口共用的确定性出站防线。 */
internal fun canonicalizeOutboundMessage(message: com.virjar.tk.model.Message): com.virjar.tk.model.Message =
    com.virjar.tk.body.MessageBodyPolicy.canonicalize(
        com.virjar.tk.body.AttachmentPolicy.canonicalize(message),
    )

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SYNCHRONIZING,
    AUTHENTICATED,
    AUTH_FAILED,
}

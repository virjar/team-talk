package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import kotlin.concurrent.Volatile
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PingSignal
import com.virjar.tk.protocol.PongSignal
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.SyncRequestPayload
import com.virjar.tk.protocol.rpc.gen.SyncRpcContract
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual fun createClientTransportOwner(
    initialHost: String,
    initialPort: Int,
    beginProtocolNegotiation: (Long) -> Boolean,
    currentAuthenticationAttempt: () -> AuthenticationAttemptLease?,
    onAuthenticationTransportAttemptEnded: (AuthenticationAttemptLease?) -> Boolean,
    onAuthenticationTransportRetired: (AuthenticationAttemptLease?) -> Unit,
    authenticationTerminal: () -> Boolean,
    routePacket: (Long, IProto) -> Unit,
    onTransportDisconnected: () -> Unit,
    tcpTlsCertificatePem: String?,
): ClientTransportOwner = IosTransportConnectionOwner(
    initialHost, initialPort, beginProtocolNegotiation, currentAuthenticationAttempt,
    onAuthenticationTransportAttemptEnded, onAuthenticationTransportRetired,
    authenticationTerminal, routePacket, onTransportDisconnected, tcpTlsCertificatePem,
)

internal class IosTransportConnectionOwner(
    initialHost: String,
    initialPort: Int,
    private val beginProtocolNegotiation: (connectionGeneration: Long) -> Boolean,
    private val currentAuthenticationAttempt: () -> AuthenticationAttemptLease?,
    private val onAuthenticationTransportAttemptEnded: (AuthenticationAttemptLease?) -> Boolean,
    private val onAuthenticationTransportRetired: (AuthenticationAttemptLease?) -> Unit,
    private val authenticationTerminal: () -> Boolean,
    private val routePacket: (connectionGeneration: Long, IProto) -> Unit,
    private val onTransportDisconnected: () -> Unit,
    private val tcpTlsCertificatePem: String?,
) : ClientTransportOwner {
    private val logger = PlatformOnlyTkLogger("TransportConnectionOwner")

    private val eventLoop = IosSerialExecutor("teamtalk.tcp")
    private val timerScope = CoroutineScope(eventLoop + SupervisorJob())
    private val terminallyDestroyed = PlatformAtomicBoolean(false)

    // EventLoop 拥有的尝试状态。
    private var channel: IosTcpChannel? = null
    private var connectingChannel: IosTcpChannel? = null
    private val connectionGeneration = ConnectionGeneration()
    private var retryCount = 0
    private var reconnectJitterSeed = 0u
    private var destroyed = false
    private var reconnectFuture: Job? = null
    /** 被进程本地网络丢失测试接缝暂停的精确逻辑 owner。 */
    private var pausedReconnectOwnerForTest: Long? = null
    /** 该逻辑 transport 跨自动重连拥有的精确 AUTH 能力。 */
    private var logicalAuthenticationAttempt: AuthenticationAttemptLease? = null
    /** 一个全新逻辑 owner，在其调用方发布本地状态之前刻意不能触及 DNS/TCP。 */
    private var preparedInitialOwnerGeneration: Long? = null

    @Volatile
    private var ownerGeneration = 0L

    private val _ownerGeneration = MutableStateFlow(0L)
    override val ownerGenerationState: StateFlow<Long> = _ownerGeneration.asStateFlow()

    @Volatile
    private var activeScope: CoroutineScope? = null

    @Volatile
    private var targetHost: String = initialHost

    @Volatile
    private var targetPort: Int = initialPort

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    override val currentOwnerGeneration: Long get() = ownerGeneration
    override val currentConnectionGeneration: Long get() = connectionGeneration.current
    override val coroutineScope: CoroutineScope? get() = activeScope
    override val connectHost: String get() = targetHost
    override val connectPort: Int get() = targetPort

    /**
     * 启动新的逻辑 transport 租约。[admitAndStart] 在选定 EventLoop 上运行，并且可以在调用提供的
     * 启动边时持有调用方侧能力。把准入保持到 owner/代际推进完成，防止 disconnect 在 A 的 transport
     * owner 仍然当前时观察到已准备的 B。false 让延迟任务成为完全 no-op。
     */
    override fun connect(
        host: String,
        port: Int,
        jitterSeed: UInt?,
        admitAndStart: ((start: () -> Unit) -> Boolean)?,
    ) {
        if (terminallyDestroyed.get()) {
            logger.trace("connect ignored: transport owner is permanently destroyed")
            return
        }
        executeOn(eventLoop) {
            if (terminallyDestroyed.get()) return@executeOn
            var startInvoked = false
            val start = {
                startInvoked = true
                // 过期安装者是真正的 no-op：它绝不能清除之后 disconnect 的 destroyed 标记，
                // 也不能取消该 owner 的重连。所有生命周期变更留在被准入的启动边内部。
                reconnectFuture?.cancel()
                reconnectFuture = null
                pausedReconnectOwnerForTest = null
                destroyed = false
                retryCount = 0
                jitterSeed?.let { reconnectJitterSeed = it }
                advanceOwnerGeneration()
                targetHost = host
                targetPort = port
                logicalAuthenticationAttempt = currentAuthenticationAttempt()
                preparedInitialOwnerGeneration = null
                createAndConnect()
            }
            try {
                if (admitAndStart != null && !admitAndStart(start)) {
                    logger.trace("connect ignored: authentication attempt was retired before installation")
                    return@executeOn
                }
                if (admitAndStart == null) start()
            } catch (failure: Throwable) {
                if (startInvoked) {
                    logger.fault("Logical authentication transport failed during installation", failure)
                    // 不从部分进入的安装者内联拆除。以其精确 owner 排队清理，因此并发准入的 B
                    // 要么跟随它，要么使 cleanup(A) 失效。
                    scheduleDisconnectIfOwned(ownerGeneration)
                }
                throw failure
            }
        }
    }

    /**
     * 安装第一个逻辑 transport owner，而不解析或连接其端点。
     *
     * 持久账号启动使用这条狭窄两阶段边：owner 代际对本地 [ClientSession] 可用，而对应的 AUTH 载荷
     * 保持密封在 EventLoop 上，直到 [startPreparedInitialConnect] 被准入。该操作刻意仅限全新 owner；
     * 替代登录继续使用 [connect]，因此绝不在延迟替代者背后留下存活旧通道。
     */
    override fun prepareInitialConnect(
        host: String,
        port: Int,
        jitterSeed: UInt,
        admitAndPrepare: (prepare: () -> Unit) -> Boolean,
    ) {
        if (terminallyDestroyed.get()) {
            logger.trace("prepareInitialConnect ignored: transport owner is permanently destroyed")
            return
        }
        executeOn(eventLoop) {
            if (terminallyDestroyed.get()) return@executeOn
            var prepareInvoked = false
            val prepare = {
                prepareInvoked = true
                check(
                    ownerGeneration == 0L &&
                        channel == null &&
                        connectingChannel == null &&
                        activeScope == null &&
                        reconnectFuture == null &&
                        logicalAuthenticationAttempt == null,
                ) { "Deferred authentication requires a fresh transport owner" }
                destroyed = false
                retryCount = 0
                reconnectJitterSeed = jitterSeed
                advanceOwnerGeneration()
                targetHost = host
                targetPort = port
                logicalAuthenticationAttempt = currentAuthenticationAttempt()
                preparedInitialOwnerGeneration = ownerGeneration
            }
            try {
                if (!admitAndPrepare(prepare)) {
                    logger.trace("prepareInitialConnect ignored: authentication attempt was retired")
                }
            } catch (failure: Throwable) {
                if (prepareInvoked) {
                    logger.fault("Logical authentication transport failed during deferred preparation", failure)
                    scheduleDisconnectIfOwned(ownerGeneration)
                }
                throw failure
            }
        }
    }

    /** 恰好启动一次 [prepareInitialConnect] 安装的全新逻辑 owner。 */
    override fun startPreparedInitialConnect(
        admitAndStart: (start: () -> Unit) -> Boolean,
    ) {
        if (terminallyDestroyed.get()) return
        executeOn(eventLoop) {
            if (terminallyDestroyed.get()) return@executeOn
            val preparedGeneration = preparedInitialOwnerGeneration ?: return@executeOn
            var startInvoked = false
            val start = {
                startInvoked = true
                preparedInitialOwnerGeneration = null
                createAndConnect()
            }
            try {
                if (!admitAndStart(start)) {
                    logger.trace("startPreparedInitialConnect ignored: authentication attempt was retired")
                    preparedInitialOwnerGeneration = null
                }
            } catch (failure: Throwable) {
                if (startInvoked) {
                    logger.fault("Prepared authentication transport failed during start", failure)
                    scheduleDisconnectIfOwned(preparedGeneration)
                }
                throw failure
            }
        }
    }

    /** 调度连接拥有的工作。destroy 之后的拒绝是安全 no-op，绝不内联。 */
    override fun execute(task: () -> Unit): Boolean =
        !terminallyDestroyed.get() && executeOn(eventLoop, task)

    override fun send(proto: IProto) {
        execute { sendNow(proto) }
    }

    /**
     * 调度一次会话拥有的写入，并在 EventLoop 执行点校验其完整租约。仅在调用方线程捕获代际是不够的：
     * 一个退役中的 RPC 可以在登记后被抢占，然后在另一个账号已在该可复用 transport 上认证之后恢复。
     * 载荷绝不能落到该替代通道。
     *
     * [sendAdmission] 由请求/会话拥有，并在取消或会话 stop 时永久变 false。[onResult] 在 EventLoop
     * 上运行并报告载荷是否交给通道；任务拒绝改为通过 Boolean 返回值报告 false。
     */
    override fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sendAdmission: WireSendAdmission,
        proto: IProto,
        onResult: (Boolean) -> Unit,
    ): Boolean = execute {
        onResult(
            sendNowIfOwned(
                expectedOwnerGeneration,
                expectedConnectionGeneration,
                sendAdmission,
                proto,
            ),
        )
    }

    /** 原子 ACK-登记 + 消息发送路径使用的、仅 EventLoop 的租约写入。 */
    override fun sendNowIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sendAdmission: WireSendAdmission,
        proto: IProto,
    ): Boolean {
        requireEventLoop()
        return sendAdmission.use {
            !terminallyDestroyed.get() &&
                ownerGeneration == expectedOwnerGeneration &&
                connectionGeneration.matches(expectedConnectionGeneration) &&
                sendNow(proto)
        }
    }

    /** AUTH/SYNC/PING 控制路径使用的、仅 EventLoop 的协议写入。 */
    override fun writeProtocolNow(proto: IProto): Boolean {
        requireEventLoop()
        val active = channel
        if (active == null || !active.isActive) {
            logger.trace("write ignored: channel is absent/inactive, type=${proto::class.simpleName}")
            return false
        }
        active.writeAndFlush(proto)
        return true
    }

    /** 仅 EventLoop 的公开发送门禁。 */
    override fun sendNow(proto: IProto): Boolean {
        requireEventLoop()
        val currentState = _state.value
        if (
            currentState != ConnectionState.AUTHENTICATED &&
            proto !is AuthRequestPayload &&
            !(currentState == ConnectionState.SYNCHRONIZING && proto is SyncRequestPayload) &&
            !(
                currentState == ConnectionState.SYNCHRONIZING &&
                    proto is InvokePayload &&
                    proto.serviceId == SyncRpcContract.SERVICE
            ) &&
            proto !is PingSignal &&
            proto !is PongSignal
        ) {
            logger.trace(
                "send blocked: not authenticated, state=$currentState, " +
                    "type=${proto::class.simpleName}",
            )
            return false
        }
        return writeProtocolNow(proto)
    }

    override fun transitionTo(state: ConnectionState) {
        requireEventLoop()
        _state.value = state
    }

    override fun onAuthenticationAccepted() {
        requireEventLoop()
        retryCount = 0
        channel?.authenticated = true
    }

    override fun closeForRecoveryNow(reason: String, cause: Throwable?) {
        requireEventLoop()
        if (cause == null) logger.fault(reason) else logger.fault(reason, cause)
        (channel ?: connectingChannel)?.close()
    }

    /** 预期服务器背压：进入正常重连退避，而不产生 fault 级噪音。 */
    override fun retryAuthenticationNow(reason: String) {
        requireEventLoop()
        logger.trace(reason)
        (channel ?: connectingChannel)?.close()
    }

    /** 过期 AUTH 响应只能退役投递它的那个连接代际。 */
    override fun retryAuthenticationIfCurrent(expectedConnectionGeneration: Long, reason: String) {
        requireEventLoop()
        if (!connectionGeneration.matches(expectedConnectionGeneration)) return
        retryAuthenticationNow(reason)
    }

    override fun closeForRecoveryIfCurrent(
        expectedConnectionGeneration: Long,
        reason: String,
        cause: Throwable?,
    ) {
        requireEventLoop()
        if (!connectionGeneration.matches(expectedConnectionGeneration)) return
        closeForRecoveryNow(reason, cause)
    }

    override fun disconnectIfOwned(expectedOwnerGeneration: Long) {
        execute { disconnectIfOwnedNow(expectedOwnerGeneration) }
    }

    /**
     * 总是入队，包括从 EventLoop 本身。因此公开登出可以在 B 推进其 owner 时被重入调用，
     * 而不会拆掉 B 安装的一半。
     */
    override fun scheduleDisconnectIfOwned(expectedOwnerGeneration: Long) {
        if (terminallyDestroyed.get()) return
        val scheduled = enqueueOn(eventLoop) { disconnectIfOwnedNow(expectedOwnerGeneration) }
        check(scheduled || terminallyDestroyed.get()) {
            "Reusable transport EventLoop rejected owner-qualified disconnect"
        }
    }

    /** 幂等地拆除连接，然后释放 EventLoopGroup。 */
    override fun destroy() {
        if (!terminallyDestroyed.compareAndSet(false, true)) return
        if (!executeOn(eventLoop) {
            disconnectCurrentTransport()
            timerScope.cancel()
            eventLoop.closeAfterQueuedWork()
        }) {
            // 拒绝意味着 owner 循环已经在停止；关闭是幂等的，且不在调用方线程上运行任何
            // 连接状态变更。
            timerScope.cancel()
            eventLoop.closeAfterQueuedWork()
        }
    }

    /** 测试钩子：网络丢失保留逻辑 owner，并走重连路径。 */
    override fun simulateNetworkDrop() {
        execute { (channel ?: connectingChannel)?.close() }
    }

    /**
     * 用于确定性单客户端离线窗口的测试接缝。
     *
     * 只有该 transport owner 受影响：当前通道被正常关闭，但其自动重连保持到
     * [resumeReconnectAfterSimulatedDrop]。主机网络与每个其他 client 保持不动。
     */
    override fun simulateNetworkDropAndPauseReconnect() {
        execute {
            val active = channel ?: connectingChannel ?: return@execute
            pausedReconnectOwnerForTest = ownerGeneration
            active.close()
        }
    }

    /** 恢复被 [simulateNetworkDropAndPauseReconnect] 暂停的精确逻辑 owner。 */
    override fun resumeReconnectAfterSimulatedDrop() {
        execute {
            if (pausedReconnectOwnerForTest != ownerGeneration) return@execute
            pausedReconnectOwnerForTest = null
            if (
                _state.value == ConnectionState.DISCONNECTED &&
                !destroyed &&
                !terminallyDestroyed.get() &&
                !authenticationTerminal() &&
                reconnectFuture == null
            ) {
                scheduleReconnect()
            }
        }
    }

    private fun disconnectCurrentTransport() {
        requireEventLoop()
        if (
            destroyed &&
            channel == null &&
            connectingChannel == null &&
            activeScope == null &&
            reconnectFuture == null &&
            logicalAuthenticationAttempt == null
        ) {
            _state.value = ConnectionState.DISCONNECTED
            return
        }
        destroyed = true
        pausedReconnectOwnerForTest = null
        preparedInitialOwnerGeneration = null
        advanceOwnerGeneration()
        // 在 close() 可以把其 channelInactive 回调入队之前使 handler 失效。
        connectionGeneration.invalidate()
        reconnectFuture?.cancel()
        reconnectFuture = null
        val active = channel
        val connecting = connectingChannel
        val authenticationAttempt = logicalAuthenticationAttempt
        channel = null
        connectingChannel = null
        logicalAuthenticationAttempt = null
        _state.value = ConnectionState.DISCONNECTED
        onTransportDisconnected()
        onAuthenticationTransportRetired(authenticationAttempt)
        activeScope?.cancel()
        activeScope = null
        active?.close()
        if (connecting !== active) connecting?.close()
    }

    private fun disconnectIfOwnedNow(expectedOwnerGeneration: Long) {
        requireEventLoop()
        if (ownerGeneration != expectedOwnerGeneration) {
            logger.trace(
                "Ignoring disconnect from retired transport owner=$expectedOwnerGeneration, " +
                    "current=$ownerGeneration",
            )
            return
        }
        disconnectCurrentTransport()
    }

    private fun createAndConnect() {
        requireEventLoop()
        preparedInitialOwnerGeneration = null
        // 先递增：来自每个被取代尝试的回调在观察上变得失效。
        val generation = connectionGeneration.next()
        val previousActive = channel
        val previousConnecting = connectingChannel
        channel = null
        connectingChannel = null
        _state.value = ConnectionState.CONNECTING
        onTransportDisconnected()
        activeScope?.cancel()
        activeScope = null
        previousActive?.close()
        if (previousConnecting !== previousActive) previousConnecting?.close()

        val attemptHost = targetHost
        val attemptPort = targetPort
        logger.trace("Connecting to $attemptHost:$attemptPort (generation=$generation)")

        val connection = IosTcpChannel(
            eventLoop, attemptHost, attemptPort, tcpTlsCertificatePem,
            onReady = { ready -> onTransportReady(ready, generation) },
            onPacket = { source, packet ->
                if (connectionGeneration.matches(generation) && channel === source) routePacket(generation, packet)
            },
            onClosed = { source, failure -> onChannelClosed(source, generation, failure) },
            onWriteIdle = { source -> if (channel === source) sendNow(PingSignal) },
        )
        connectingChannel = connection
        try {
            connection.start()
        } catch (failure: Throwable) {
            // Invalid TLS material or endpoint setup is a failed connection. Like Netty's connect
            // completion, defer retirement until the authentication installation lease has returned.
            enqueueOn(eventLoop) { connection.close(failure) }
        }
    }

    private fun onTransportReady(connectedChannel: IosTcpChannel, generation: Long) {
        requireEventLoop()
        if (!connectionGeneration.matches(generation) || destroyed || !connectedChannel.isActive) {
            connectedChannel.close()
            return
        }
        if (connectingChannel === connectedChannel) connectingChannel = null
        channel = connectedChannel
        activeScope = CoroutineScope(
            eventLoop +
                SupervisorJob() +
                CoroutineExceptionHandler { _, throwable ->
                    logger.fault("ImClient connection scope unhandled exception", throwable)
                },
        )
        _state.value = ConnectionState.CONNECTED

        beginProtocolNegotiation(generation)
    }

    private fun scheduleReconnect() {
        requireEventLoop()
        if (
            destroyed ||
            terminallyDestroyed.get() ||
            authenticationTerminal() ||
            reconnectFuture != null ||
            pausedReconnectOwnerForTest == ownerGeneration
        ) return
        val delay = reconnectRetryDelayMillis(retryCount, reconnectJitterSeed)
        retryCount += 1
        val disconnectedGeneration = connectionGeneration.current
        logger.trace("Schedule reconnect in ${delay}ms (retry=$retryCount)")
        reconnectFuture = timerScope.launch {
            delay(delay)
            reconnectFuture = null
            if (!destroyed && !terminallyDestroyed.get() && !authenticationTerminal() &&
                connectionGeneration.matches(disconnectedGeneration)) createAndConnect()
        }
    }

    private fun advanceOwnerGeneration() {
        check(ownerGeneration < Long.MAX_VALUE) { "Transport owner generation exhausted" }
        ownerGeneration += 1
        _ownerGeneration.value = ownerGeneration
    }

    private fun executeOn(loop: IosSerialExecutor, task: () -> Unit): Boolean = loop.executeNowOrEnqueue(task)
    private fun enqueueOn(loop: IosSerialExecutor, task: () -> Unit): Boolean = loop.execute(task)
    private fun requireEventLoop() { check(eventLoop.inExecutor()) { "Transport state must be mutated on its serial queue" } }

    private fun onChannelClosed(source: IosTcpChannel, generation: Long, failure: Throwable?) {
        requireEventLoop()
        if (destroyed || !connectionGeneration.matches(generation) || (channel !== source && connectingChannel !== source)) return
        failure?.let { logger.trace("Connection ended: ${it::class.simpleName}") }
        val terminalAuthentication = authenticationTerminal()
        _state.value = if (terminalAuthentication) ConnectionState.AUTH_FAILED else ConnectionState.DISCONNECTED
        onTransportDisconnected()
        activeScope?.cancel()
        activeScope = null
        if (channel === source) channel = null
        if (connectingChannel === source) connectingChannel = null
        val shouldReconnect = onAuthenticationTransportAttemptEnded(logicalAuthenticationAttempt)
        if (!shouldReconnect) logicalAuthenticationAttempt = null
        if (terminalAuthentication || !shouldReconnect) {
            if (pausedReconnectOwnerForTest == ownerGeneration) pausedReconnectOwnerForTest = null
            return
        }
        scheduleReconnect()
    }
}

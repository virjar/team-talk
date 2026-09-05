package com.virjar.tk.shared.client

import com.virjar.tk.shared.log.TkLogger
import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.rpc.gen.SyncRpcContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 用于确定性证明 register -> retire -> send 竞争的窄 transport 边界。 */
internal interface RpcRequestTransport {
    val state: StateFlow<ConnectionState>
    val routedPackets: Flow<RoutedPacket>
    val transportDisconnectEpoch: StateFlow<Long>
    val currentOwnerGeneration: Long
    val currentConnectionGeneration: Long
    val negotiatedProtocolVersion: ProtocolVersion?

    suspend fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sendAdmission: WireSendAdmission,
        payload: InvokePayload,
    ): Boolean
}

/** 把不可逆退役与 EventLoop 的实际通道写入串行化。 */
internal interface WireSendAdmission {
    fun isActive(): Boolean
    fun use(block: () -> Boolean): Boolean
}

private class ImClientRpcRequestTransport(
    private val imClient: ImClient,
) : RpcRequestTransport {
    override val state: StateFlow<ConnectionState> get() = imClient.state
    override val routedPackets: Flow<RoutedPacket> get() = imClient.routedPackets
    override val transportDisconnectEpoch: StateFlow<Long> get() = imClient.transportDisconnectEpoch
    override val currentOwnerGeneration: Long get() = imClient.currentTransportOwnerGeneration
    override val currentConnectionGeneration: Long get() = imClient.currentConnectionGeneration
    override val negotiatedProtocolVersion: ProtocolVersion?
        get() = imClient.protocolCompatibility.value?.negotiated

    override suspend fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        sendAdmission: WireSendAdmission,
        payload: InvokePayload,
    ): Boolean = imClient.sendIfOwned(
        expectedOwnerGeneration = expectedOwnerGeneration,
        expectedConnectionGeneration = expectedConnectionGeneration,
        sendAdmission = sendAdmission,
        proto = payload,
    )
}

/** 一个 RpcClient 实例拥有一份不可逆的已认证会话租约。 */
private class RpcSessionLease(
    val transportOwnerGeneration: Long,
) : WireSendAdmission {
    private val lock = Any()
    @Volatile
    private var active = true

    override fun isActive(): Boolean = active
    override fun use(block: () -> Boolean): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        block()
    }
    fun retire() = synchronized(lock) { active = false }
}

/** 一次调用可以额外被调用方取消退役，而不复活其会话。 */
private class RpcRequestLease(
    val session: RpcSessionLease,
    val connectionGeneration: Long,
    private val requestAdmission: SessionOutboundLease?,
) : WireSendAdmission {
    private val lock = Any()
    @Volatile
    private var active = true

    override fun isActive(): Boolean =
        active && session.isActive() && (requestAdmission?.isActive() != false)

    override fun use(block: () -> Boolean): Boolean = session.use {
        synchronized(lock) {
            if (!active) return@synchronized false
            requestAdmission?.use(block) ?: block()
        }
    }

    fun retire() = synchronized(lock) { active = false }
}

/**
 * 会话拥有的 RPC 请求/响应 owner。
 *
 * 响应收集器在会话进入 AUTHENTICATED 之前就存在，并跨每次 TCP 重连存活。每个待处理请求租借给
 * 一个连接代际，因此来自已退役通道的迟到响应无法完成替代请求。调用方取消绝不被替换为连接 Job；
 * 断开用带类型的普通失败完成待处理请求。
 */
class RpcClient internal constructor(
    private val transport: RpcRequestTransport,
    lifecycleDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxPendingRequests: Int = DEFAULT_MAX_PENDING_REQUESTS,
) : RpcInvoker {
    constructor(imClient: ImClient) : this(ImClientRpcRequestTransport(imClient))

    override val negotiatedProtocolVersion: ProtocolVersion
        get() {
            val state = transport.state.value
            requireAvailableConnection(
                state == ConnectionState.SYNCHRONIZING || state == ConnectionState.AUTHENTICATED,
                "RPC requires an authenticated, negotiated connection",
            )
            return transport.negotiatedProtocolVersion
                ?: throw TransportUnavailableException("Protocol negotiation is not available")
        }

    private data class PendingRequest(
        val lease: RpcRequestLease,
        val deferred: CompletableDeferred<ResponsePayload>,
    )

    @Volatile
    private var logger: TkLogger = PlatformOnlyTkLogger("RpcClient")
    private val pendingLock = Any()
    private val pendingRequests = mutableMapOf<Int, PendingRequest>()
    private var nextRequestId = 1
    private val lifecycleScope = CoroutineScope(
        lifecycleDispatcher +
            SupervisorJob() +
            CoroutineExceptionHandler { _, failure ->
                logger.fault("RpcClient session listener crashed", failure)
            },
    )
    private var responseJob: Job? = null
    private var disconnectJob: Job? = null
    @Volatile
    private var started = false
    @Volatile
    private var stopped = false
    @Volatile
    private var sessionLease: RpcSessionLease? = null

    init {
        require(maxPendingRequests > 0) { "RPC pending-request capacity must be positive" }
    }

    /** 在 [start] 之前绑定；生产会话绝不借用另一个 AppLog owner 的全局 logger。 */
    internal fun bindLogger(sessionLogger: TkLogger) {
        check(!started) { "RpcClient logger must bind before start" }
        logger = sessionLogger
    }

    fun start() {
        check(!stopped) { "RpcClient is session-owned and cannot restart after stop" }
        if (started) return
        // 生产 ClientSession 在 SYNCHRONIZING 时创建并立即绑定。协议 E2E 助手刻意在 CONNECTED 时
        // 启动其响应收集器，再发出 AUTH；它们在 connectAndAuth 替换 owner 之后、首次已认证 invoke
        // 时绑定。
        if (transport.state.value == ConnectionState.SYNCHRONIZING ||
            transport.state.value == ConnectionState.AUTHENTICATED
        ) {
            val ownerGeneration = transport.currentOwnerGeneration
            check(ownerGeneration > 0L) { "RPC transport owner generation is unavailable" }
            sessionLease = RpcSessionLease(ownerGeneration)
        }
        started = true

        // UNDISTPATCHED 在 start() 返回之前安装两个收集器。这关闭了 AUTHENTICATED -> 首条响应
        // 的窗口，并且不把任一收集器绑定到 TCP scope。
        responseJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.routedPackets.collect { packet ->
                val response = packet.payload as? ResponsePayload ?: return@collect
                val pending = synchronized(pendingLock) {
                    val candidate = pendingRequests[response.requestId]?.takeIf {
                        it.lease.connectionGeneration == packet.connectionGeneration &&
                            it.lease.isActive()
                    }
                    if (candidate != null) {
                        pendingRequests.remove(response.requestId)
                    } else {
                        null
                    }
                }
                if (pending == null) {
                    logger.trace(
                        "Ignoring unknown/stale RPC response requestId=${response.requestId}, " +
                            "generation=${packet.connectionGeneration}",
                    )
                } else {
                    pending.deferred.complete(response)
                }
            }
        }
        disconnectJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var observedEpoch = transport.transportDisconnectEpoch.value
            transport.transportDisconnectEpoch.collect { epoch ->
                if (epoch == observedEpoch) return@collect
                observedEpoch = epoch
                failAllPending(TransportUnavailableException("Connection closed before RPC response"))
            }
        }
    }

    override suspend fun invoke(
        service: String,
        methodId: Int,
        payload: ByteArray?,
    ): ResponsePayload = invokeOwned(service, methodId, payload, requestAdmission = null)

    internal suspend fun invokeWhileActive(
        service: String,
        methodId: Int,
        payload: ByteArray?,
        requestAdmission: SessionOutboundLease,
    ): ResponsePayload = invokeOwned(service, methodId, payload, requestAdmission)

    /** SYNC_READY 之前唯一可用的带类型 RPC 路径；业务调用方绝不会收到它。 */
    internal suspend fun invokeDuringSynchronization(
        service: String,
        methodId: Int,
        payload: ByteArray?,
        requestAdmission: SessionOutboundLease,
    ): ResponsePayload {
        require(service == SyncRpcContract.SERVICE) {
            "Only ${SyncRpcContract.SERVICE} RPC is available during synchronization"
        }
        return invokeOwned(
            service = service,
            methodId = methodId,
            payload = payload,
            requestAdmission = requestAdmission,
            synchronizationOnly = true,
        )
    }

    private suspend fun invokeOwned(
        service: String,
        methodId: Int,
        payload: ByteArray?,
        requestAdmission: SessionOutboundLease?,
        synchronizationOnly: Boolean = false,
    ): ResponsePayload {
        check(requestAdmission?.isActive() != false) { "RPC admission is retired" }
        check(started && !stopped) { "RpcClient is not started" }
        requireAvailableConnection(
            rpcStateAvailable(service, synchronizationOnly),
            if (synchronizationOnly) {
                "Checkpoint RPC requires a synchronizing connection"
            } else {
                "RPC requires an authenticated connection"
            },
        )
        val activeSession = synchronized(pendingLock) {
            check(started && !stopped) { "RpcClient is not started" }
            sessionLease?.let { existing ->
                check(existing.isActive()) { "RpcClient session is not active" }
                existing
            } ?: run {
                val ownerGeneration = transport.currentOwnerGeneration
                requireAvailableConnection(
                    ownerGeneration > 0L,
                    "RPC transport owner generation is unavailable",
                )
                RpcSessionLease(ownerGeneration).also { sessionLease = it }
            }
        }
        requireAvailableConnection(
            transport.currentOwnerGeneration == activeSession.transportOwnerGeneration,
            "RPC transport owner changed",
        )
        val connectionGeneration = transport.currentConnectionGeneration
        requireAvailableConnection(
            connectionGeneration > 0L,
            "RPC connection generation is unavailable",
        )
        val requestLease = RpcRequestLease(activeSession, connectionGeneration, requestAdmission)
        val (request, requestId) = synchronized(pendingLock) {
            check(requestAdmission?.isActive() != false) { "RPC admission is retired" }
            check(started && !stopped && sessionLease === activeSession && activeSession.isActive()) {
                "RpcClient session is not active"
            }
            requireAvailableConnection(
                rpcStateAvailable(service, synchronizationOnly),
                "Connection changed before RPC registration",
            )
            requireAvailableConnection(
                transport.currentOwnerGeneration == activeSession.transportOwnerGeneration,
                "Transport owner changed before RPC registration",
            )
            requireAvailableConnection(
                transport.currentConnectionGeneration == connectionGeneration,
                "Connection generation changed before RPC registration",
            )
            if (pendingRequests.size >= maxPendingRequests) {
                throw RpcClientCapacityExceededException(maxPendingRequests)
            }
            val requestId = allocateRequestIdLocked()
            PendingRequest(requestLease, CompletableDeferred()).also {
                pendingRequests[requestId] = it
            } to requestId
        }
        return try {
            withTimeoutOrNull(RPC_TIMEOUT_MS) {
                val sent = transport.sendIfOwned(
                    expectedOwnerGeneration = activeSession.transportOwnerGeneration,
                    expectedConnectionGeneration = connectionGeneration,
                    sendAdmission = requestLease,
                    payload = InvokePayload(requestId, service, methodId, payload),
                )
                if (!sent) {
                    if (requestAdmission?.isActive() == false) {
                        throw CancellationException("RPC admission retired before request send")
                    }
                    if (!activeSession.isActive()) {
                        throw CancellationException("RpcClient session closed before request send")
                    }
                    throw TransportUnavailableException("Connection closed before RPC send")
                }
                request.deferred.await()
            }
                ?: ResponsePayload(requestId, 504, "Request timeout".encodeToByteArray())
        } finally {
            requestLease.retire()
            synchronized(pendingLock) {
                if (pendingRequests[requestId] === request) pendingRequests.remove(requestId)
            }
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        started = false
        sessionLease?.retire()
        responseJob?.cancel()
        disconnectJob?.cancel()
        failAllPending(CancellationException("RpcClient session closed"))
        lifecycleScope.cancel()
    }

    private fun allocateRequestIdLocked(): Int {
        repeat(Int.MAX_VALUE) {
            val candidate = nextRequestId
            nextRequestId = if (candidate == Int.MAX_VALUE) 1 else candidate + 1
            if (candidate !in pendingRequests) return candidate
        }
        error("RPC request id space exhausted")
    }

    private fun failAllPending(failure: Throwable) {
        val pending = synchronized(pendingLock) {
            pendingRequests.values.map(PendingRequest::deferred).also {
                pendingRequests.clear()
            }
        }
        pending.forEach { it.completeExceptionally(failure) }
    }

    private fun requireAvailableConnection(available: Boolean, message: String) {
        if (!available) throw TransportUnavailableException(message)
    }

    private fun rpcStateAvailable(service: String, synchronizationOnly: Boolean): Boolean =
        if (synchronizationOnly) {
            service == SyncRpcContract.SERVICE && transport.state.value == ConnectionState.SYNCHRONIZING
        } else {
            transport.state.value == ConnectionState.AUTHENTICATED
        }

    private companion object {
        const val RPC_TIMEOUT_MS = 10_000L
        const val DEFAULT_MAX_PENDING_REQUESTS = 1_024
    }
}

internal class RpcClientCapacityExceededException(capacity: Int) : IllegalStateException(
    "RPC pending-request capacity $capacity is exhausted",
)

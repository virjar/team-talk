package com.virjar.tk.client

import com.virjar.tk.log.TkLoggerFactory
import com.virjar.tk.protocol.payload.InvokePayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.rpc.RpcInvoker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

/** Narrow transport boundary used to prove the register -> retire -> send race deterministically. */
internal interface RpcRequestTransport {
    val state: StateFlow<ConnectionState>
    val routedPackets: Flow<RoutedPacket>
    val transportDisconnectEpoch: StateFlow<Long>
    val currentOwnerGeneration: Long
    val currentConnectionGeneration: Long

    suspend fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        leaseIsActive: () -> Boolean,
        payload: InvokePayload,
    ): Boolean
}

private class ImClientRpcRequestTransport(
    private val imClient: ImClient,
) : RpcRequestTransport {
    override val state: StateFlow<ConnectionState> get() = imClient.state
    override val routedPackets: Flow<RoutedPacket> get() = imClient.routedPackets
    override val transportDisconnectEpoch: StateFlow<Long> get() = imClient.transportDisconnectEpoch
    override val currentOwnerGeneration: Long get() = imClient.currentTransportOwnerGeneration
    override val currentConnectionGeneration: Long get() = imClient.currentConnectionGeneration

    override suspend fun sendIfOwned(
        expectedOwnerGeneration: Long,
        expectedConnectionGeneration: Long,
        leaseIsActive: () -> Boolean,
        payload: InvokePayload,
    ): Boolean = imClient.sendIfOwned(
        expectedOwnerGeneration = expectedOwnerGeneration,
        expectedConnectionGeneration = expectedConnectionGeneration,
        leaseIsActive = leaseIsActive,
        proto = payload,
    )
}

/** One RpcClient instance owns one irreversible authenticated-session lease. */
private class RpcSessionLease(
    val transportOwnerGeneration: Long,
) {
    @Volatile
    private var active = true

    fun isActive(): Boolean = active
    fun retire() {
        active = false
    }
}

/** One invocation can additionally be retired by caller cancellation without reviving its session. */
private class RpcRequestLease(
    val session: RpcSessionLease,
    val connectionGeneration: Long,
) {
    @Volatile
    private var active = true

    fun isActive(): Boolean = active && session.isActive()
    fun retire() {
        active = false
    }
}

internal class RpcTransportDisconnectedException : IllegalStateException(
    "Connection closed before RPC response",
)

/**
 * Session-owned RPC request/response owner.
 *
 * The response collector exists before the session enters AUTHENTICATED and survives every TCP
 * reconnect. Each pending request is leased to one connection generation, so a late response from
 * a retired channel cannot complete a replacement request. Caller cancellation is never replaced
 * with a connection Job; disconnect completes pending requests with a typed ordinary failure.
 */
class RpcClient internal constructor(
    private val transport: RpcRequestTransport,
) : RpcInvoker {
    constructor(imClient: ImClient) : this(ImClientRpcRequestTransport(imClient))

    private data class PendingRequest(
        val lease: RpcRequestLease,
        val deferred: CompletableDeferred<ResponsePayload>,
    )

    private val logger = TkLoggerFactory.get("RpcClient")
    private val pendingLock = Any()
    private val pendingRequests = mutableMapOf<Int, PendingRequest>()
    private var nextRequestId = 1
    private val lifecycleScope = CoroutineScope(
        Dispatchers.Default +
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

    fun start() {
        check(!stopped) { "RpcClient is session-owned and cannot restart after stop" }
        if (started) return
        // Production ClientSession is created at SYNCHRONIZING and binds immediately. Protocol E2E
        // helpers intentionally start their response collector at CONNECTED before issuing AUTH;
        // those bind on the first authenticated invoke, after connectAndAuth has replaced the owner.
        if (transport.state.value == ConnectionState.SYNCHRONIZING ||
            transport.state.value == ConnectionState.AUTHENTICATED
        ) {
            val ownerGeneration = transport.currentOwnerGeneration
            check(ownerGeneration > 0L) { "RPC transport owner generation is unavailable" }
            sessionLease = RpcSessionLease(ownerGeneration)
        }
        started = true

        // UNDISTPATCHED installs both collectors before start() returns. This closes the
        // AUTHENTICATED -> first response window and does not bind either collector to a TCP scope.
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
                failAllPending(RpcTransportDisconnectedException())
            }
        }
    }

    override suspend fun invoke(
        service: String,
        methodId: Int,
        payload: ByteArray?,
    ): ResponsePayload {
        check(started && !stopped) { "RpcClient is not started" }
        check(transport.state.value == ConnectionState.AUTHENTICATED) {
            "RPC requires an authenticated connection"
        }
        val activeSession = synchronized(pendingLock) {
            check(started && !stopped) { "RpcClient is not started" }
            sessionLease?.let { existing ->
                check(existing.isActive()) { "RpcClient session is not active" }
                existing
            } ?: run {
                val ownerGeneration = transport.currentOwnerGeneration
                check(ownerGeneration > 0L) { "RPC transport owner generation is unavailable" }
                RpcSessionLease(ownerGeneration).also { sessionLease = it }
            }
        }
        check(transport.currentOwnerGeneration == activeSession.transportOwnerGeneration) {
            "RPC transport owner changed"
        }
        val connectionGeneration = transport.currentConnectionGeneration
        check(connectionGeneration > 0L) { "RPC connection generation is unavailable" }
        val requestLease = RpcRequestLease(activeSession, connectionGeneration)
        val (request, requestId) = synchronized(pendingLock) {
            check(started && !stopped && sessionLease === activeSession && activeSession.isActive()) {
                "RpcClient session is not active"
            }
            check(transport.state.value == ConnectionState.AUTHENTICATED) {
                "Connection changed before RPC registration"
            }
            check(transport.currentOwnerGeneration == activeSession.transportOwnerGeneration) {
                "Transport owner changed before RPC registration"
            }
            check(transport.currentConnectionGeneration == connectionGeneration) {
                "Connection generation changed before RPC registration"
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
                    leaseIsActive = requestLease::isActive,
                    payload = InvokePayload(requestId, service, methodId, payload),
                )
                if (!sent) {
                    if (!activeSession.isActive()) {
                        throw CancellationException("RpcClient session closed before request send")
                    }
                    throw RpcTransportDisconnectedException()
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

    private companion object {
        const val RPC_TIMEOUT_MS = 10_000L
    }
}

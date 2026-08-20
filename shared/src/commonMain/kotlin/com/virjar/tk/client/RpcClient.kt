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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
class RpcClient(
    private val imClient: ImClient,
) : RpcInvoker {
    private data class PendingRequest(
        val connectionGeneration: Long,
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

    fun start() {
        check(!stopped) { "RpcClient is session-owned and cannot restart after stop" }
        if (started) return
        started = true

        // UNDISTPATCHED installs both collectors before start() returns. This closes the
        // AUTHENTICATED -> first response window and does not bind either collector to a TCP scope.
        responseJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            imClient.routedPackets.collect { packet ->
                val response = packet.payload as? ResponsePayload ?: return@collect
                val pending = synchronized(pendingLock) {
                    val candidate = pendingRequests[response.requestId]
                    if (candidate?.connectionGeneration == packet.connectionGeneration) {
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
            var observedEpoch = imClient.transportDisconnectEpoch.value
            imClient.transportDisconnectEpoch.collect { epoch ->
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
        check(imClient.state.value == ConnectionState.AUTHENTICATED) {
            "RPC requires an authenticated connection"
        }
        val connectionGeneration = imClient.currentConnectionGeneration
        check(connectionGeneration > 0L) { "RPC connection generation is unavailable" }
        val (request, requestId) = synchronized(pendingLock) {
            check(imClient.state.value == ConnectionState.AUTHENTICATED) {
                "Connection changed before RPC registration"
            }
            check(imClient.currentConnectionGeneration == connectionGeneration) {
                "Connection generation changed before RPC registration"
            }
            val requestId = allocateRequestIdLocked()
            PendingRequest(connectionGeneration, CompletableDeferred()).also {
                pendingRequests[requestId] = it
            } to requestId
        }
        return try {
            imClient.send(InvokePayload(requestId, service, methodId, payload))
            withTimeoutOrNull(RPC_TIMEOUT_MS) { request.deferred.await() }
                ?: ResponsePayload(requestId, 504, "Request timeout".encodeToByteArray())
        } finally {
            synchronized(pendingLock) {
                if (pendingRequests[requestId] === request) pendingRequests.remove(requestId)
            }
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        started = false
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

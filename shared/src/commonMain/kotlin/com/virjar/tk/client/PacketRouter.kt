package com.virjar.tk.client

import com.virjar.tk.util.PlatformOnlyTkLogger
import com.virjar.tk.protocol.DisconnectSignal
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PingSignal
import com.virjar.tk.protocol.PongSignal
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.payload.SyncReadyPayload
import com.virjar.tk.protocol.payload.SyncResetPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class RoutedPacket(
    val connectionGeneration: Long,
    val payload: IProto,
)

/**
 * Reliable inbound demultiplexer for the active connection generation.
 *
 * [TransportConnectionOwner] rejects stale channels before calling [route]. This owner then keeps
 * ACK completion, sync control packets and broadcast packets on one serial EventLoop. A full
 * broadcast buffer is never treated as success: the connection is closed so durable cursor/RPC
 * retry owners can recover instead of silently losing a packet.
 */
internal class PacketRouter(
    private val connectionState: () -> ConnectionState,
    private val connectionScope: () -> CoroutineScope?,
    private val handleAuthResponse: (AuthResponsePayload) -> Unit,
    private val handleSyncBatch: (SyncBatchPayload) -> Unit,
    private val handleSyncEvent: (NotifyPayload) -> Unit,
    private val handleSyncReady: () -> Unit,
    private val handleSyncReset: () -> Unit,
    private val writeControl: (IProto) -> Boolean,
    private val closeTransport: (reason: String) -> Unit,
    inboundBufferCapacity: Int = DEFAULT_INBOUND_BUFFER_CAPACITY,
) {
    init {
        require(inboundBufferCapacity >= 0) { "inboundBufferCapacity must be non-negative" }
    }

    private val logger = PlatformOnlyTkLogger("PacketRouter")
    private val pendingAcks = PendingAckRegistry()
    private val incomingPackets = MutableSharedFlow<RoutedPacket>(
        extraBufferCapacity = inboundBufferCapacity,
    )
    private val _transportDisconnectEpoch = MutableStateFlow(0L)

    val routedPackets: SharedFlow<RoutedPacket> = incomingPackets.asSharedFlow()
    val packets: Flow<IProto> = routedPackets.map { packet -> packet.payload }
    val transportDisconnectEpoch: StateFlow<Long> = _transportDisconnectEpoch.asStateFlow()

    fun route(connectionGeneration: Long, proto: IProto) {
        require(connectionGeneration > 0L) { "connectionGeneration must be positive" }
        logger.trace("Packet received: type=${proto::class.simpleName}")
        when (proto) {
            is AuthResponsePayload -> handleAuthResponse(proto)
            is SyncBatchPayload -> handleSyncBatch(proto)
            is SyncReadyPayload -> handleSyncReady()
            is SyncResetPayload -> handleSyncReset()
            is NotifyPayload -> {
                if (connectionState() == ConnectionState.SYNCHRONIZING) {
                    handleSyncEvent(proto)
                } else {
                    emitOrDisconnect(connectionGeneration, proto)
                }
            }
            is MessageAckPayload -> {
                if (!pendingAcks.complete(proto)) {
                    logger.trace("Received ACK for unknown clientMsgId: ${proto.clientMsgId}")
                }
            }
            is PingSignal -> writeControl(PongSignal)
            is PongSignal -> Unit
            is DisconnectSignal -> closeTransport("Server requested transport disconnect")
            else -> emitOrDisconnect(connectionGeneration, proto)
        }
    }

    /** AUTH responses historically use suspending emission; the typed failure flow is authoritative. */
    fun publishAuthResponse(connectionGeneration: Long, response: AuthResponsePayload) {
        connectionScope()?.launch {
            incomingPackets.emit(RoutedPacket(connectionGeneration, response))
        }
    }

    suspend fun sendAndAwaitAck(
        clientMsgId: String,
        timeoutMs: Long,
        sessionOwner: Any = LEGACY_ACK_OWNER,
        sessionLease: SessionOutboundLease? = null,
        send: () -> Unit,
    ): MessageAckPayload {
        val deferred = pendingAcks.register(clientMsgId, sessionOwner, sessionLease)
        return try {
            send()
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: MessageAckPayload(clientMsgId, 0, -1, "ACK timeout")
        } finally {
            // ACK completion removes first. Send failure, caller cancellation and timeout remove
            // only their own waiter, so a duplicate-id programming error cannot erase a successor.
            pendingAcks.remove(clientMsgId, deferred)
        }
    }

    /** EventLoop-only retirement of one authenticated session's ACK namespace. */
    fun retirePendingAcks(sessionOwner: Any) {
        pendingAcks.cancelOwner(sessionOwner)
    }

    fun onTransportDisconnected() {
        pendingAcks.cancelAll()
        check(_transportDisconnectEpoch.value < Long.MAX_VALUE) {
            "Transport disconnect epoch exhausted"
        }
        _transportDisconnectEpoch.value += 1L
    }

    private fun emitOrDisconnect(connectionGeneration: Long, proto: IProto) {
        if (incomingPackets.tryEmit(RoutedPacket(connectionGeneration, proto))) return
        val reason = "Inbound packet buffer full; closing type=${proto::class.simpleName}"
        logger.fault(reason)
        closeTransport(reason)
    }

    private companion object {
        const val DEFAULT_INBOUND_BUFFER_CAPACITY = 64
        val LEGACY_ACK_OWNER = Any()
    }
}

package com.virjar.tk.shared.client

import com.virjar.tk.shared.log.PlatformOnlyTkLogger
import com.virjar.tk.protocol.DisconnectSignal
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.PingSignal
import com.virjar.tk.protocol.PongSignal
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.protocol.payload.ProtocolNegotiateResponsePayload
import com.virjar.tk.protocol.payload.ConnectionTraceContextPayload
import com.virjar.tk.protocol.payload.MessageAckPayload
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.SyncBatchPayload
import com.virjar.tk.protocol.payload.SyncReadyPayload
import com.virjar.tk.protocol.payload.SyncResetPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

internal data class RoutedPacket(
    val connectionGeneration: Long,
    val payload: IProto,
)

/**
 * 面向活跃连接代际的可靠入站解复用器。
 *
 * [TransportConnectionOwner] 在调用 [route] 之前拒绝过期通道。该 owner 随后把 ACK 完成、同步控制
 * 包与广播包保持在一个串行 EventLoop 上。满的广播缓冲区绝不当作成功：连接被关闭，以便持久游标/
 * RPC 重试 owner 可以恢复，而不是静默丢失一个包。
 */
internal class PacketRouter(
    private val connectionState: () -> ConnectionState,
    private val handleAuthResponse: (
        connectionGeneration: Long,
        response: AuthResponsePayload,
    ) -> Unit,
    private val handleSyncBatch: (SyncBatchPayload) -> Unit,
    private val handleSyncEvent: (NotifyPayload) -> Unit,
    private val handleSyncReady: () -> Unit,
    private val handleSyncReset: (SyncResetPayload) -> Unit,
    private val writeControl: (IProto) -> Boolean,
    private val closeTransport: (reason: String) -> Unit,
    private val handleConnectionTraceContext: (
        connectionGeneration: Long,
        payload: ConnectionTraceContextPayload,
    ) -> Boolean = { _, _ -> false },
    private val handleProtocolNegotiationResponse: (Long, ProtocolNegotiateResponsePayload) -> Unit = { _, _ -> },
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
            is ProtocolNegotiateResponsePayload -> handleProtocolNegotiationResponse(connectionGeneration, proto)
            is AuthResponsePayload -> handleAuthResponse(connectionGeneration, proto)
            is ConnectionTraceContextPayload -> {
                val authenticatedConnection = connectionState() == ConnectionState.SYNCHRONIZING ||
                    connectionState() == ConnectionState.AUTHENTICATED
                if (!authenticatedConnection || !handleConnectionTraceContext(connectionGeneration, proto)) {
                    logger.trace(
                        "Discarding stale or unauthorized connection trace context update",
                    )
                }
            }
            is SyncBatchPayload -> handleSyncBatch(proto)
            is SyncReadyPayload -> handleSyncReady()
            is SyncResetPayload -> handleSyncReset(proto)
            is NotifyPayload -> {
                if (connectionState() == ConnectionState.SYNCHRONIZING) {
                    handleSyncEvent(proto)
                } else {
                    emitOrDisconnect(connectionGeneration, proto)
                }
            }
            is MessageAckPayload -> {
                if (!pendingAcks.complete(proto)) {
                    logger.trace("Received ACK for unknown message: ${proto.chatId}/${proto.clientMsgId}")
                }
            }
            is PingSignal -> writeControl(PongSignal)
            is PongSignal -> Unit
            is DisconnectSignal -> closeTransport("Server requested transport disconnect")
            else -> emitOrDisconnect(connectionGeneration, proto)
        }
    }

    suspend fun sendAndAwaitAck(
        chatId: String,
        clientMsgId: String,
        timeoutMs: Long,
        sessionOwner: Any,
        sessionLease: SessionOutboundLease? = null,
        send: () -> Unit,
    ): MessageAckPayload {
        val deferred = pendingAcks.register(chatId, clientMsgId, sessionOwner, sessionLease)
        return try {
            send()
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: MessageAckPayload(chatId, clientMsgId, 0, -1, "ACK timeout")
        } finally {
            // ACK 完成先移除。发送失败、调用方取消与超时只移除其自己的等待者，
            // 因此重复 id 的编程错误不能擦除后继者。
            pendingAcks.remove(chatId, clientMsgId, deferred)
        }
    }

    /** 仅 EventLoop 的一个已认证会话 ACK 命名空间退役。 */
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
    }
}

package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto
import kotlinx.coroutines.flow.StateFlow

/** Serial connection owner. Every backend enforces the same owner and connection leases. */
internal interface ClientTransportOwner : MessageSendTransport {
    val ownerGenerationState: StateFlow<Long>
    val state: StateFlow<ConnectionState>
    val connectHost: String
    val connectPort: Int
    fun connect(host: String, port: Int, jitterSeed: UInt? = null, admitAndStart: ((() -> Unit) -> Boolean)? = null)
    fun prepareInitialConnect(host: String, port: Int, jitterSeed: UInt = 0u, admitAndPrepare: (() -> Unit) -> Boolean)
    fun startPreparedInitialConnect(admitAndStart: (() -> Unit) -> Boolean)
    fun execute(task: () -> Unit): Boolean
    fun send(proto: IProto)
    fun sendIfOwned(expectedOwnerGeneration: Long, expectedConnectionGeneration: Long, sendAdmission: WireSendAdmission, proto: IProto, onResult: (Boolean) -> Unit): Boolean
    fun writeProtocolNow(proto: IProto): Boolean
    fun sendNow(proto: IProto): Boolean
    fun transitionTo(state: ConnectionState)
    fun onAuthenticationAccepted()
    fun closeForRecoveryNow(reason: String, cause: Throwable? = null)
    fun retryAuthenticationNow(reason: String)
    fun retryAuthenticationIfCurrent(expectedConnectionGeneration: Long, reason: String)
    fun closeForRecoveryIfCurrent(expectedConnectionGeneration: Long, reason: String, cause: Throwable? = null)
    fun disconnectIfOwned(expectedOwnerGeneration: Long)
    fun scheduleDisconnectIfOwned(expectedOwnerGeneration: Long)
    fun destroy()
    fun simulateNetworkDrop()
    fun simulateNetworkDropAndPauseReconnect()
    fun resumeReconnectAfterSimulatedDrop()
}

internal fun createClientTransportOwner(
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
): ClientTransportOwner = TransportConnectionOwner(
    initialHost, initialPort, beginProtocolNegotiation, currentAuthenticationAttempt,
    onAuthenticationTransportAttemptEnded, onAuthenticationTransportRetired,
    authenticationTerminal, routePacket, onTransportDisconnected,
    createClientTransportBackend(tcpTlsCertificatePem),
)

package com.virjar.tk.shared.client

import com.virjar.tk.protocol.IProto

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
): ClientTransportOwner = TransportConnectionOwner(
    initialHost, initialPort, beginProtocolNegotiation, currentAuthenticationAttempt,
    onAuthenticationTransportAttemptEnded, onAuthenticationTransportRetired,
    authenticationTerminal, routePacket, onTransportDisconnected, ClientTransportTls(tcpTlsCertificatePem),
)

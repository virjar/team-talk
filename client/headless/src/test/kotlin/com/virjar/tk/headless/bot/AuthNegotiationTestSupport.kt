package com.virjar.tk.headless.bot

import com.virjar.tk.protocol.ProtocolNegotiation
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.payload.AuthResponsePayload
import com.virjar.tk.shared.client.AuthSyncCoordinator

/** 与 SDK commonTest 相同的已协商前提；headless 侧认证准入回归共用。 */
internal fun AuthSyncCoordinator.handleAuthResponseAfterTestNegotiation(
    generation: Long,
    response: AuthResponsePayload,
) {
    if (protocolCompatibility.value == null) {
        beginProtocolNegotiation(generation)
        handleProtocolNegotiationResponse(
            generation,
            ProtocolNegotiation.negotiate(ProtocolVersions.SUPPORTED, ProtocolVersions.SUPPORTED),
        )
    }
    handleAuthResponse(generation, response)
}

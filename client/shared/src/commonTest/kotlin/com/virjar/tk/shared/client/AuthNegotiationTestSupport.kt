package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ProtocolNegotiation
import com.virjar.tk.protocol.ProtocolVersions
import com.virjar.tk.protocol.payload.AuthResponsePayload

/** 认证/同步 owner 测试共用已协商前提；不替代专门的首帧与拒绝测试。 */
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

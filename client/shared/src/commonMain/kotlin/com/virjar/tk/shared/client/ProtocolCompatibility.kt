package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ProtocolRange
import com.virjar.tk.protocol.ProtocolVersion
import com.virjar.tk.protocol.payload.ProtocolNegotiateResponsePayload

/** 当前部署最后一次有效协商结果；与账号身份和短暂的连接状态分开持有。 */
data class ProtocolCompatibility(
    val client: ProtocolRange,
    val server: ProtocolRange,
    val negotiated: ProtocolVersion?,
    val code: Int,
) {
    val requiresUpgrade: Boolean
        get() = code != ProtocolNegotiateResponsePayload.CODE_OK

    val recommendsUpgrade: Boolean
        get() = !requiresUpgrade && client.currentMinor < server.currentMinor
}

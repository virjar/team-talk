package com.virjar.tk.shared.client

import com.virjar.tk.protocol.payload.AuthRequestPayload

/** 不含任何可复用物理连接关联字段的敏感重连凭据。 */
internal data class PendingAuthenticationCredentials(
    val authType: Int,
    val username: String?,
    val password: String?,
    val name: String?,
    val refreshToken: String?,
    val deviceId: String,
    val deviceName: String?,
    val deviceModel: String?,
    val deviceFlag: Int,
) {
    fun toWirePayload(
        correlationId: String,
        connectionGeneration: Long,
    ): AuthRequestPayload = AuthRequestPayload(
        authType = authType,
        username = username,
        password = password,
        name = name,
        refreshToken = refreshToken,
        deviceId = deviceId,
        deviceName = deviceName,
        deviceModel = deviceModel,
        deviceFlag = deviceFlag,
        correlationId = correlationId,
        connectionGeneration = connectionGeneration,
    )

    companion object {
        fun from(auth: AuthRequestPayload): PendingAuthenticationCredentials =
            PendingAuthenticationCredentials(
                authType = auth.authType,
                username = auth.username,
                password = auth.password,
                name = auth.name,
                refreshToken = auth.refreshToken,
                deviceId = auth.deviceId,
                deviceName = auth.deviceName,
                deviceModel = auth.deviceModel,
                deviceFlag = auth.deviceFlag,
            )
    }
}

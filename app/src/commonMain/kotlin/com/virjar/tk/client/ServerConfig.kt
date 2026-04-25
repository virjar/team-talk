package com.virjar.tk.client

expect fun getDefaultServerBaseUrl(): String
expect fun getDefaultTcpHost(): String
expect fun getDefaultTcpPort(): Int

expect fun getBuildProfile(): String
expect fun isShowAdvancedSettings(): Boolean

data class ServerConfig(
    val baseUrl: String = getDefaultServerBaseUrl(),
    val tcpHost: String = getDefaultTcpHost(),
    val tcpPort: Int = getDefaultTcpPort(),
)

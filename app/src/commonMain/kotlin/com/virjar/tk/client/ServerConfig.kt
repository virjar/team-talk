package com.virjar.tk.client

expect fun getDefaultServerBaseUrl(): String
expect fun getDefaultTcpHost(): String
expect fun getDefaultTcpPort(): Int

data class ServerConfig(
    val baseUrl: String = getDefaultServerBaseUrl(),
    val tcpHost: String = getDefaultTcpHost(),
    val tcpPort: Int = getDefaultTcpPort(),
)

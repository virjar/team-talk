package com.virjar.tk.client

actual fun getDefaultServerBaseUrl(): String {
    System.getProperty("teamtalk.server.url")?.takeIf { it.isNotBlank() }?.let { return it }
    return "http://localhost:8080"
}

actual fun getDefaultTcpHost(): String {
    System.getProperty("teamtalk.tcp.host")?.takeIf { it.isNotBlank() }?.let { return it }
    return "localhost"
}

actual fun getDefaultTcpPort(): Int {
    System.getProperty("teamtalk.tcp.port")?.toIntOrNull()?.let { return it }
    return 5100
}

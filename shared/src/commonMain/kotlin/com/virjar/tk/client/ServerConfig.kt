package com.virjar.tk.client

/**
 * 服务端连接配置。
 * Desktop 通过 JVM 系统属性读取（Gradle run<Profile> 任务注入）。
 * Android 通过 [configureServerConfig] 注入 BuildConfig 值。
 */
data class ServerConfig(
    val serverUrl: String,
    val tcpHost: String,
    val tcpPort: Int,
    val allowCustomServer: Boolean,
)

private var overrideConfig: ServerConfig? = null

fun configureServerConfig(config: ServerConfig) {
    overrideConfig = config
}

fun defaultServerConfig(): ServerConfig {
    return overrideConfig ?: ServerConfig(
        serverUrl = System.getProperty("teamtalk.server.url") ?: "http://localhost:8080",
        tcpHost = System.getProperty("teamtalk.tcp.host") ?: "localhost",
        tcpPort = (System.getProperty("teamtalk.tcp.port") ?: "5100").toInt(),
        allowCustomServer = (System.getProperty("teamtalk.allow.custom.server") ?: "true").toBoolean(),
    )
}

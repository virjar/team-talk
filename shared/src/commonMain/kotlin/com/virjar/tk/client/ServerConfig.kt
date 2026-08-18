package com.virjar.tk.client

/**
 * 服务端连接配置。
 * Desktop 通过 JVM 系统属性读取（Gradle :desktop:run 任务注入）。
 * Android 通过 [configureServerConfig] 注入 BuildConfig 值。
 */
data class ServerConfig(
    val serverUrl: String,
    val tcpHost: String,
    val tcpPort: Int,
)

private var overrideConfig: ServerConfig? = null

fun configureServerConfig(config: ServerConfig) {
    overrideConfig = config
}

fun defaultServerConfig(): ServerConfig {
    return overrideConfig ?: ServerConfig(
        serverUrl = System.getProperty("teamtalk.server.url") ?: "https://im.virjar.com",
        tcpHost = System.getProperty("teamtalk.tcp.host") ?: "im.virjar.com",
        tcpPort = (System.getProperty("teamtalk.tcp.port") ?: "5100").toInt(),
    )
}

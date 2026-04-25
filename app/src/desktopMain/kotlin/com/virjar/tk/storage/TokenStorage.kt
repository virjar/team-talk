package com.virjar.tk.storage

import java.io.File
import java.util.Properties

actual class TokenStorage actual constructor() {
    private val dataDir = resolveDataDir()
    private val file = File(dataDir, "session.properties")

    actual fun save(token: String, uid: String, userJson: String) {
        dataDir.mkdirs()
        val props = loadProps() ?: Properties()
        props["token"] = token
        props["uid"] = uid
        props["user"] = userJson
        file.outputStream().use { props.store(it, "TeamTalk session") }
    }

    actual fun loadToken(): String? = loadProps()?.getProperty("token")
    actual fun loadUid(): String? = loadProps()?.getProperty("uid")
    actual fun loadUserJson(): String? = loadProps()?.getProperty("user")

    actual fun clear() {
        file.delete()
    }

    actual fun saveServerConfig(baseUrl: String, tcpHost: String, tcpPort: Int) {
        dataDir.mkdirs()
        val props = loadProps() ?: Properties()
        props["serverUrl"] = baseUrl
        props["tcpHost"] = tcpHost
        props["tcpPort"] = tcpPort.toString()
        file.outputStream().use { props.store(it, "TeamTalk session") }
    }

    actual fun loadSavedServerBaseUrl(): String? = loadProps()?.getProperty("serverUrl")
    actual fun loadSavedTcpHost(): String? = loadProps()?.getProperty("tcpHost")
    actual fun loadSavedTcpPort(): Int? = loadProps()?.getProperty("tcpPort")?.toIntOrNull()

    private fun loadProps(): Properties? {
        if (!file.exists()) return null
        return Properties().apply { file.inputStream().use { load(it) } }
    }
}

/** Resolve the data directory: `-Dteamtalk.data.dir` or `~/.tk/app_default/` by default. */
fun resolveDataDir(): File {
    val custom = System.getProperty("teamtalk.data.dir")
    if (!custom.isNullOrBlank()) return File(custom)
    return File(System.getProperty("user.home"), ".tk/app_default")
}

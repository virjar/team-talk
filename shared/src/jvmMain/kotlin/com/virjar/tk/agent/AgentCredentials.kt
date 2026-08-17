package com.virjar.tk.agent

import java.io.File
import java.util.Properties
import java.util.UUID

/** agent 凭据与 API token 持久化（dataDir/credentials.properties + api-token）。 */
object AgentCredentials {

    fun load(dataDir: File): Triple<String, String, String>? {
        val f = File(dataDir, "credentials.properties")
        if (!f.exists()) return null
        val p = Properties().apply { f.inputStream().use { load(it) } }
        val u = p.getProperty("username") ?: return null
        val pw = p.getProperty("password") ?: return null
        return Triple(u, pw, p.getProperty("apiToken", ""))
    }

    fun save(dataDir: File, username: String, password: String) {
        val f = File(dataDir, "credentials.properties")
        f.parentFile?.mkdirs()
        val p = Properties().apply {
            setProperty("username", username)
            setProperty("password", password)
            setProperty("apiToken", ensureToken(dataDir))
        }
        f.outputStream().use { p.store(it, "tt-agent credentials") }
    }

    /** API token：不存在则生成并持久化（CLI 与 agent 的本地握手凭据）。 */
    fun ensureToken(dataDir: File): String {
        val f = File(dataDir, "credentials.properties")
        val p = Properties()
        if (f.exists()) f.inputStream().use { p.load(it) }
        val t = p.getProperty("apiToken")?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "")
        if (t != p.getProperty("apiToken")) {
            p.setProperty("apiToken", t)
            f.parentFile?.mkdirs()
            f.outputStream().use { p.store(it, "tt-agent credentials") }
        }
        return t
    }
}

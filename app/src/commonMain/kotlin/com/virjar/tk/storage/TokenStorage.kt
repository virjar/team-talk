package com.virjar.tk.storage

/**
 * Cross-platform token/session persistence.
 * Desktop: Properties file in data directory.
 * Android: SharedPreferences.
 */
expect class TokenStorage() {
    fun save(token: String, uid: String, userJson: String)
    fun loadToken(): String?
    fun loadUid(): String?
    fun loadUserJson(): String?
    fun clear()

    fun saveServerConfig(baseUrl: String, tcpHost: String, tcpPort: Int)
    fun loadSavedServerBaseUrl(): String?
    fun loadSavedTcpHost(): String?
    fun loadSavedTcpPort(): Int?
}

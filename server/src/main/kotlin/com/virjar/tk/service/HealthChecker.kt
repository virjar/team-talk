package com.virjar.tk.service

import com.virjar.tk.db.MessageStore
import com.virjar.tk.storage.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.InetSocketAddress
import java.net.Socket

@Serializable
data class ComponentHealth(val status: String, val detail: String? = null)

@Serializable
data class HealthResponse(val status: String, val components: Map<String, ComponentHealth>)

class HealthChecker(
    private val messageStore: MessageStore,
    private val searchIndex: SearchIndex,
    private val tcpPort: Int,
) {
    suspend fun check(): HealthResponse {
        val components = mutableMapOf<String, ComponentHealth>()

        components["postgres"] = withContext(Dispatchers.IO) { checkDatabase() }
        components["file-storage"] = withContext(Dispatchers.IO) { checkFileStorage() }
        components["rocksdb"] = checkRocksDB()
        components["lucene"] = checkLucene()
        components["tcp"] = withContext(Dispatchers.IO) { checkTcp() }

        val overallStatus = if (components.values.all { it.status == "UP" }) "UP" else "DOWN"
        return HealthResponse(overallStatus, components)
    }

    private suspend fun checkDatabase(): ComponentHealth = try {
        withTimeout(5000L) {
            transaction { exec("SELECT 1") { it.next() } }
        }
        ComponentHealth("UP")
    } catch (e: Exception) {
        ComponentHealth("DOWN", e.message)
    }

    private fun checkFileStorage(): ComponentHealth =
        if (FileStore.isHealthy) ComponentHealth("UP")
        else ComponentHealth("DOWN", "file storage not initialized")

    private fun checkRocksDB(): ComponentHealth =
        if (messageStore.isRunning) ComponentHealth("UP")
        else ComponentHealth("DOWN", "RocksDB not initialized")

    private fun checkLucene(): ComponentHealth =
        if (searchIndex.isRunning) ComponentHealth("UP")
        else ComponentHealth("DOWN", "Lucene index not initialized")

    private fun checkTcp(): ComponentHealth = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", tcpPort), 3000) }
        ComponentHealth("UP")
    } catch (e: Exception) {
        ComponentHealth("DOWN", "TCP port $tcpPort not listening: ${e.message}")
    }
}

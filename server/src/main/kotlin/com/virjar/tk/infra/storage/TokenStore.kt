package com.virjar.tk.infra.storage

import com.virjar.tk.domain.auth.TokenInfo
import com.virjar.tk.domain.auth.TokenRepository
import org.rocksdb.*
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

private val logger = LoggerFactory.getLogger("TokenStore")

/**
 * 基于 RocksDB 的随机 Token 存储。
 * Key: accessToken (String)
 * Value: { uid, deviceId, deviceFlag, createdAt, expiresAt }
 */
class TokenStore(dbPath: String) : TokenRepository {

    private val random = SecureRandom()
    private val closed = AtomicBoolean(false)
    /** Serializes refresh rotation with logout/device revocation. */
    private val tokenMutationLock = Any()
    private val opened = openDatabase(dbPath)
    private val db: RocksDB = opened.db
    private val cfHandle: ColumnFamilyHandle = opened.tokenHandle

    init {
        try {
            logger.info("TokenStore initialized at $dbPath")
        } catch (error: Throwable) {
            runCatching { close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    /**
     * 生成新的 access token 和 refresh token。
     * Returns (accessToken, refreshToken)
     */
    override fun generateTokens(uid: String, deviceId: String, deviceFlag: Int): Pair<String, String> =
        synchronized(tokenMutationLock) {
            generateTokensLocked(uid, deviceId, deviceFlag)
        }

    private fun generateTokensLocked(uid: String, deviceId: String, deviceFlag: Int): Pair<String, String> {
        val accessToken = generateRandomToken()
        val refreshToken = generateRandomToken()
        val now = System.currentTimeMillis()

        put(accessToken, TokenInfo(uid, deviceId, deviceFlag, now, now + ACCESS_TOKEN_TTL))
        put("refresh:$refreshToken", TokenInfo(uid, deviceId, deviceFlag, now, now + REFRESH_TOKEN_TTL))

        return accessToken to refreshToken
    }

    override fun validateAccessToken(token: String): TokenInfo? {
        val info = get(token) ?: return null
        if (System.currentTimeMillis() > info.expiresAt) {
            delete(token)
            return null
        }
        return info
    }

    override fun refreshAccessToken(
        refreshToken: String,
        expectedDeviceId: String,
        expectedDeviceFlag: Int,
    ): Pair<String, String>? = synchronized(tokenMutationLock) {
        val key = "refresh:$refreshToken"
        val info = get(key) ?: return@synchronized null
        if (System.currentTimeMillis() > info.expiresAt) {
            delete(key)
            return@synchronized null
        }
        // Check before deleting/rotating. A forged device identity must neither consume the real
        // device's one-time refresh token nor create an online-registry alias.
        if (info.deviceId != expectedDeviceId || info.deviceFlag != expectedDeviceFlag) {
            return@synchronized null
        }
        // 删除旧 refresh token
        delete(key)
        // 生成新的 token 对
        generateTokensLocked(info.uid, info.deviceId, info.deviceFlag)
    }

    /**
     * 吊销 refresh token（仅删除，不换发新 token）。
     * 用于登出场景——区别于 [refreshAccessToken]，后者会"删旧+发新"，在登出时
     * 会产生游离的有效凭证。
     * @return true 若 token 存在且已删除；false 若 token 不存在。
     */
    override fun revokeRefreshToken(
        refreshToken: String,
        expectedUid: String?,
        expectedDeviceId: String?,
    ): Boolean = synchronized(tokenMutationLock) {
        val key = "refresh:$refreshToken"
        val info = get(key) ?: return@synchronized false
        if (expectedUid != null && info.uid != expectedUid) return@synchronized false
        if (expectedDeviceId != null && info.deviceId != expectedDeviceId) return@synchronized false
        delete(key)
        true
    }

    override fun revokeAllDeviceTokens(uid: String, deviceId: String) = synchronized(tokenMutationLock) {
        // 扫描并删除该 uid+deviceId 的所有 token
        val toDelete = mutableListOf<String>()
        db.newIterator(cfHandle).use { iter ->
            iter.seekToFirst()
            while (iter.isValid) {
                val key = String(iter.key(), Charsets.UTF_8)
                val value = decodeValue(iter.value())
                if (value != null && value.uid == uid && value.deviceId == deviceId) {
                    toDelete.add(key)
                }
                iter.next()
            }
        }
        toDelete.forEach { delete(it) }
    }

    /**
     * 吊销某用户的全部 token（封禁/重置密码用）。
     * 注：曾在 4c3a97e 以"零调用"删除——管理后台上线后恢复。
     */
    override fun revokeAllUserTokens(uid: String) = synchronized(tokenMutationLock) {
        val toDelete = mutableListOf<String>()
        db.newIterator(cfHandle).use { iter ->
            iter.seekToFirst()
            while (iter.isValid) {
                val key = String(iter.key(), Charsets.UTF_8)
                val value = decodeValue(iter.value())
                if (value != null && value.uid == uid) {
                    toDelete.add(key)
                }
                iter.next()
            }
        }
        toDelete.forEach { delete(it) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        fun closePart(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
        }
        opened.handles.asReversed().forEach { handle -> closePart { handle.close() } }
        closePart { db.close() }
        failure?.let { throw it }
    }

    // ── 内部方法 ──

    private fun put(key: String, info: TokenInfo) {
        db.put(cfHandle, key.toByteArray(Charsets.UTF_8), encodeValue(info))
    }

    private fun get(key: String): TokenInfo? {
        val bytes = db.get(cfHandle, key.toByteArray(Charsets.UTF_8)) ?: return null
        return decodeValue(bytes)
    }

    private fun delete(key: String) {
        db.delete(cfHandle, key.toByteArray(Charsets.UTF_8))
    }

    private fun encodeValue(info: TokenInfo): ByteArray {
        val parts = listOf(info.uid, info.deviceId, info.deviceFlag.toString(), info.createdAt.toString(), info.expiresAt.toString())
        return parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)
    }

    private fun decodeValue(bytes: ByteArray): TokenInfo? {
        val parts = String(bytes, Charsets.UTF_8).split("\u0000")
        if (parts.size != 5) return null
        return TokenInfo(parts[0], parts[1], parts[2].toInt(), parts[3].toLong(), parts[4].toLong())
    }

    private fun generateRandomToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val ACCESS_TOKEN_TTL = 30 * 24 * 60 * 60 * 1000L  // 30 days
        private const val REFRESH_TOKEN_TTL = 90 * 24 * 60 * 60 * 1000L // 90 days

        private data class OpenDatabase(
            val db: RocksDB,
            val handles: List<ColumnFamilyHandle>,
        ) {
            val tokenHandle: ColumnFamilyHandle get() = handles[1]
        }

        private fun openDatabase(dbPath: String): OpenDatabase {
            RocksDB.loadLibrary()
            val nativeOptions = mutableListOf<AutoCloseable>()
            try {
                val dbOptions = DBOptions().also(nativeOptions::add)
                dbOptions
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true)
                val defaultOptions = ColumnFamilyOptions().also(nativeOptions::add)
                val tokenOptions = ColumnFamilyOptions().also(nativeOptions::add)
                val handles = mutableListOf<ColumnFamilyHandle>()
                var database: RocksDB? = null
                try {
                    val openedDb = RocksDB.open(
                        dbOptions,
                        dbPath,
                        listOf(
                            ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultOptions),
                            ColumnFamilyDescriptor("tokens".toByteArray(), tokenOptions),
                        ),
                        handles,
                    )
                    database = openedDb
                    check(handles.size == 2) { "TokenStore column-family initialization was incomplete" }
                    return OpenDatabase(openedDb, handles.toList())
                } catch (error: Throwable) {
                    handles.asReversed().forEach { handle -> runCatching { handle.close() } }
                    runCatching { database?.close() }
                    throw error
                }
            } finally {
                nativeOptions.asReversed().forEach { option -> runCatching { option.close() } }
            }
        }
    }
}

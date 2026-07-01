package com.virjar.tk.domain.auth

import org.rocksdb.*
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.*

private val logger = LoggerFactory.getLogger("TokenStore")

/**
 * 基于 RocksDB 的随机 Token 存储。
 * Key: accessToken (String)
 * Value: { uid, deviceId, deviceFlag, createdAt, expiresAt }
 */
class TokenStore(dbPath: String) {

    private val db: RocksDB
    private val cfHandle: ColumnFamilyHandle
    private val random = SecureRandom()

    init {
        RocksDB.loadLibrary()
        val options = DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
        val cfDesc = ColumnFamilyDescriptor("tokens".toByteArray(), ColumnFamilyOptions())
        val cfDefault = ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, ColumnFamilyOptions())

        val handles = mutableListOf<ColumnFamilyHandle>()
        db = RocksDB.open(options, dbPath, listOf(cfDefault, cfDesc), handles)
        cfHandle = handles[1]
        logger.info("TokenStore initialized at $dbPath")
    }

    data class TokenInfo(
        val uid: String,
        val deviceId: String,
        val deviceFlag: Int,
        val createdAt: Long,
        val expiresAt: Long,
    )

    /**
     * 生成新的 access token 和 refresh token。
     * Returns (accessToken, refreshToken)
     */
    fun generateTokens(uid: String, deviceId: String, deviceFlag: Int): Pair<String, String> {
        val accessToken = generateRandomToken()
        val refreshToken = generateRandomToken()
        val now = System.currentTimeMillis()

        put(accessToken, TokenInfo(uid, deviceId, deviceFlag, now, now + ACCESS_TOKEN_TTL))
        put("refresh:$refreshToken", TokenInfo(uid, deviceId, deviceFlag, now, now + REFRESH_TOKEN_TTL))

        return accessToken to refreshToken
    }

    fun validateAccessToken(token: String): TokenInfo? {
        val info = get(token) ?: return null
        if (System.currentTimeMillis() > info.expiresAt) {
            delete(token)
            return null
        }
        return info
    }

    fun refreshAccessToken(refreshToken: String): Pair<String, String>? {
        val key = "refresh:$refreshToken"
        val info = get(key) ?: return null
        if (System.currentTimeMillis() > info.expiresAt) {
            delete(key)
            return null
        }
        // 删除旧 refresh token
        delete(key)
        // 生成新的 token 对
        return generateTokens(info.uid, info.deviceId, info.deviceFlag)
    }

    /**
     * 吊销 refresh token（仅删除，不换发新 token）。
     * 用于登出场景——区别于 [refreshAccessToken]，后者会"删旧+发新"，在登出时
     * 会产生游离的有效凭证。
     * @return true 若 token 存在且已删除；false 若 token 不存在。
     */
    fun revokeRefreshToken(refreshToken: String): Boolean {
        val key = "refresh:$refreshToken"
        if (get(key) == null) return false
        delete(key)
        return true
    }

    fun revokeAllDeviceTokens(uid: String, deviceId: String) {
        // 扫描并删除该 uid+deviceId 的所有 token
        val toDelete = mutableListOf<String>()
        val iter = db.newIterator(cfHandle)
        iter.seekToFirst()
        while (iter.isValid) {
            val key = String(iter.key(), Charsets.UTF_8)
            val value = decodeValue(iter.value())
            if (value != null && value.uid == uid && value.deviceId == deviceId) {
                toDelete.add(key)
            }
            iter.next()
        }
        iter.close()
        toDelete.forEach { delete(it) }
    }

    fun close() {
        cfHandle.close()
        db.close()
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
    }
}

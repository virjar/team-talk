package com.virjar.tk.server.infra.push

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

private fun digest(algorithm: String, value: String): String = MessageDigest.getInstance(algorithm)
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun md5Hex(value: String): String = digest("MD5", value)
internal fun sha256Hex(value: String): String = digest("SHA-256", value)

/** 厂商级令牌缓存：华为/荣耀 OAuth 与 OPPO/vivo authToken 共用；同一厂商串行刷新，失败时保留旧令牌等厂商拒绝。 */
internal class OemPushTokenCache(private val refreshMarginMillis: Long = 60_000L) {
    private val mutex = Mutex()
    private var cached: Pair<String, Long>? = null

    suspend fun accessToken(
        now: Long = System.currentTimeMillis(),
        fetch: suspend () -> Pair<String, Long>?,
    ): String? = mutex.withLock {
        val current = cached
        if (current != null && current.second - refreshMarginMillis > now) return@withLock current.first
        val fresh = fetch()
        if (fresh != null) cached = fresh
        cached?.first
    }

    fun invalidate() { cached = null }
}

private val oemPushTokenCaches = OemPushVendors.ALL.associateWith { OemPushTokenCache() }

internal fun oemPushTokenCache(vendor: String): OemPushTokenCache =
    checkNotNull(oemPushTokenCaches[vendor]) { "Unknown OEM push vendor" }

internal suspend fun cachedOemPushToken(
    vendor: String,
    fetch: suspend () -> Pair<String, Long>?,
): String? = oemPushTokenCache(vendor).accessToken { fetch() }

internal fun invalidateOemPushToken(vendor: String) = oemPushTokenCache(vendor).invalidate()

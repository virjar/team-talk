package com.virjar.tk.server.domain.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 拥有独立预算的认证工作类别。 */
internal enum class AuthenticationOperation {
    LOGIN,
    REGISTER,
    REFRESH,
    ADMIN,
    UNSUPPORTED,
}

internal data class AuthenticationAttempt(
    val operation: AuthenticationOperation,
    val sourceKey: String,
    val accountKey: String,
) {
    init {
        require(sourceKey.length in 1..MAX_KEY_LENGTH) { "authentication source key is invalid" }
        require(accountKey.length in 1..MAX_KEY_LENGTH) { "authentication account key is invalid" }
    }

    private companion object {
        const val MAX_KEY_LENGTH = 128
    }
}

internal data class AuthenticationOperationLimits(
    val operationAttempts: Int,
    val sourceAttempts: Int,
    val accountAttempts: Int,
) {
    init {
        require(operationAttempts in 1..MAX_LIMIT) { "operationAttempts must be in 1..$MAX_LIMIT" }
        require(sourceAttempts in 1..MAX_LIMIT) { "sourceAttempts must be in 1..$MAX_LIMIT" }
        require(accountAttempts in 1..MAX_LIMIT) { "accountAttempts must be in 1..$MAX_LIMIT" }
    }

    private companion object {
        const val MAX_LIMIT = 1_000_000
    }
}

/**
 * 进程本地的认证准入配置。
 *
 * 服务器刻意设计为单实例。这些上限保护其有限的 BCrypt/IO 责任者；未来的多实例运行时
 * 必须把等效的策略放在共享状态之后，而不是假装进程本地计数器能在副本之间协同。
 */
internal data class AuthenticationAttemptGuardConfig(
    val windowNanos: Long = DEFAULT_WINDOW_SECONDS * NANOS_PER_SECOND,
    val cooldownNanos: Long = DEFAULT_COOLDOWN_SECONDS * NANOS_PER_SECOND,
    val globalAttempts: Int = DEFAULT_GLOBAL_ATTEMPTS,
    val maxConcurrentAttempts: Int = DEFAULT_MAX_CONCURRENT_ATTEMPTS,
    val maxTrackedSources: Int = DEFAULT_MAX_TRACKED_SOURCES,
    val maxTrackedAccounts: Int = DEFAULT_MAX_TRACKED_ACCOUNTS,
    val limits: Map<AuthenticationOperation, AuthenticationOperationLimits> = DEFAULT_LIMITS,
) {
    init {
        require(windowNanos in 1..MAX_DURATION_NANOS) { "authentication window is out of range" }
        require(cooldownNanos in 1..MAX_DURATION_NANOS) { "authentication cooldown is out of range" }
        require(globalAttempts in 1..MAX_LIMIT) { "globalAttempts must be in 1..$MAX_LIMIT" }
        require(maxConcurrentAttempts in 1..MAX_CONCURRENT_ATTEMPTS) {
            "maxConcurrentAttempts must be in 1..$MAX_CONCURRENT_ATTEMPTS"
        }
        require(maxTrackedSources in 1..MAX_TRACKED_KEYS) {
            "maxTrackedSources must be in 1..$MAX_TRACKED_KEYS"
        }
        require(maxTrackedAccounts in 1..MAX_TRACKED_KEYS) {
            "maxTrackedAccounts must be in 1..$MAX_TRACKED_KEYS"
        }
        require(limits.keys == AuthenticationOperation.entries.toSet()) {
            "authentication limits must cover every operation exactly once"
        }
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val DEFAULT_WINDOW_SECONDS = 10L
        private const val DEFAULT_COOLDOWN_SECONDS = 30L
        private const val DEFAULT_GLOBAL_ATTEMPTS = 1_024
        private const val DEFAULT_MAX_CONCURRENT_ATTEMPTS = 16
        private const val DEFAULT_MAX_TRACKED_SOURCES = 4_096
        private const val DEFAULT_MAX_TRACKED_ACCOUNTS = 16_384
        private const val MAX_DURATION_SECONDS = 86_400L
        private const val MAX_DURATION_NANOS = MAX_DURATION_SECONDS * NANOS_PER_SECOND
        private const val MAX_LIMIT = 1_000_000
        private const val MAX_CONCURRENT_ATTEMPTS = 128
        private const val MAX_TRACKED_KEYS = 1_000_000

        private val DEFAULT_LIMITS = mapOf(
            AuthenticationOperation.LOGIN to AuthenticationOperationLimits(
                operationAttempts = 48,
                sourceAttempts = 24,
                accountAttempts = 8,
            ),
            AuthenticationOperation.REGISTER to AuthenticationOperationLimits(
                operationAttempts = 16,
                sourceAttempts = 8,
                accountAttempts = 4,
            ),
            AuthenticationOperation.REFRESH to AuthenticationOperationLimits(
                operationAttempts = 768,
                sourceAttempts = 256,
                accountAttempts = 16,
            ),
            AuthenticationOperation.ADMIN to AuthenticationOperationLimits(
                operationAttempts = 8,
                sourceAttempts = 8,
                accountAttempts = 8,
            ),
            AuthenticationOperation.UNSUPPORTED to AuthenticationOperationLimits(
                operationAttempts = 8,
                sourceAttempts = 4,
                accountAttempts = 4,
            ),
        )

        fun fromEnvironment(
            environment: (String) -> String? = System::getenv,
        ): AuthenticationAttemptGuardConfig {
            val windowSeconds = environment(WINDOW_SECONDS_ENV)
                .strictLong(WINDOW_SECONDS_ENV, DEFAULT_WINDOW_SECONDS, 1..MAX_DURATION_SECONDS)
            val cooldownSeconds = environment(COOLDOWN_SECONDS_ENV)
                .strictLong(COOLDOWN_SECONDS_ENV, DEFAULT_COOLDOWN_SECONDS, 1..MAX_DURATION_SECONDS)
            return AuthenticationAttemptGuardConfig(
                windowNanos = windowSeconds * NANOS_PER_SECOND,
                cooldownNanos = cooldownSeconds * NANOS_PER_SECOND,
                globalAttempts = environment(GLOBAL_ATTEMPTS_ENV)
                    .strictInt(GLOBAL_ATTEMPTS_ENV, DEFAULT_GLOBAL_ATTEMPTS, 1..MAX_LIMIT),
                maxConcurrentAttempts = environment(MAX_CONCURRENT_ENV)
                    .strictInt(
                        MAX_CONCURRENT_ENV,
                        DEFAULT_MAX_CONCURRENT_ATTEMPTS,
                        1..MAX_CONCURRENT_ATTEMPTS,
                    ),
                maxTrackedSources = environment(MAX_SOURCES_ENV)
                    .strictInt(MAX_SOURCES_ENV, DEFAULT_MAX_TRACKED_SOURCES, 1..MAX_TRACKED_KEYS),
                maxTrackedAccounts = environment(MAX_ACCOUNTS_ENV)
                    .strictInt(MAX_ACCOUNTS_ENV, DEFAULT_MAX_TRACKED_ACCOUNTS, 1..MAX_TRACKED_KEYS),
            )
        }

        internal const val WINDOW_SECONDS_ENV = "TEAMTALK_AUTH_GUARD_WINDOW_SECONDS"
        internal const val COOLDOWN_SECONDS_ENV = "TEAMTALK_AUTH_GUARD_COOLDOWN_SECONDS"
        internal const val GLOBAL_ATTEMPTS_ENV = "TEAMTALK_AUTH_GUARD_GLOBAL_ATTEMPTS"
        internal const val MAX_CONCURRENT_ENV = "TEAMTALK_AUTH_GUARD_MAX_CONCURRENT"
        internal const val MAX_SOURCES_ENV = "TEAMTALK_AUTH_GUARD_MAX_SOURCES"
        internal const val MAX_ACCOUNTS_ENV = "TEAMTALK_AUTH_GUARD_MAX_ACCOUNTS"

        private fun String?.strictLong(
            name: String,
            default: Long,
            range: LongRange,
        ): Long {
            if (this == null) return default
            val parsed = toLongOrNull()
                ?: throw IllegalArgumentException("$name must be an integer")
            require(parsed in range) { "$name must be in ${range.first}..${range.last}" }
            return parsed
        }

        private fun String?.strictInt(
            name: String,
            default: Int,
            range: IntRange,
        ): Int {
            if (this == null) return default
            val parsed = toIntOrNull()
                ?: throw IllegalArgumentException("$name must be an integer")
            require(parsed in range) { "$name must be in ${range.first}..${range.last}" }
            return parsed
        }
    }
}

/**
 * 认证到达 BCrypt 或 PostgreSQL 之前的有界、并发安全的准入闸门。
 *
 * 来源桶与账户桶按操作划分作用域：使用刷新令牌（refresh bearer）的重连浪潮无法消耗
 * 小得多的密码登录预算。一个单一的全局桶仍然约束着总工作量。一旦某个桶进入冷却期，
 * 重复的被拒流量不会消耗无关的桶，因此一个已被封禁的来源无法自我放大成全局拒绝服务。
 * 活跃的桶绝不会被驱逐以腾出空间，因为那样会让密钥轮换绕过限制；过期的桶会被回收，
 * 而在容量已满时，未见过的密钥默认拒绝（fail closed）。
 */
internal class AuthenticationAttemptGuard(
    private val config: AuthenticationAttemptGuardConfig = AuthenticationAttemptGuardConfig(),
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private data class DimensionKey(
        val operation: AuthenticationOperation,
        val value: String,
    )

    private data class Bucket(
        var windowStartedAt: Long,
        var attempts: Int = 0,
        var deniedAt: Long? = null,
    )

    private val lock = Any()
    private val global = Bucket(monotonicNanos())
    private val operations = EnumMap<AuthenticationOperation, Bucket>(AuthenticationOperation::class.java)
    private val sources = LinkedHashMap<DimensionKey, Bucket>()
    private val accounts = LinkedHashMap<DimensionKey, Bucket>()
    private var calls = 0
    private var lastObservedNanos = global.windowStartedAt
    private var concurrentAttempts = 0

    fun tryAcquire(
        attempt: AuthenticationAttempt,
        callerConcurrencyCeiling: Int = config.maxConcurrentAttempts,
    ): Lease? = synchronized(lock) {
        require(callerConcurrencyCeiling > 0) { "callerConcurrencyCeiling must be positive" }
        val now = monotonicNanos()
        require(now - lastObservedNanos >= 0L) { "authentication monotonic clock moved backwards" }
        lastObservedNanos = now
        val effectiveConcurrencyCeiling = minOf(
            config.maxConcurrentAttempts,
            callerConcurrencyCeiling,
        )
        if (concurrentAttempts >= effectiveConcurrencyCeiling) return@synchronized null
        calls = if (calls == Int.MAX_VALUE) 0 else calls + 1

        val limits = checkNotNull(config.limits[attempt.operation])
        val operation = operations.getOrPut(attempt.operation) { Bucket(now) }
        val sourceKey = DimensionKey(attempt.operation, attempt.sourceKey)
        val accountKey = DimensionKey(attempt.operation, attempt.accountKey)

        if (isDenied(global, now) || isDenied(operation, now)) return@synchronized null
        sources[sourceKey]?.let { if (isDenied(it, now)) return@synchronized null }
        accounts[accountKey]?.let { if (isDenied(it, now)) return@synchronized null }

        if (calls % CLEANUP_INTERVAL == 0 ||
            sources.size >= config.maxTrackedSources ||
            accounts.size >= config.maxTrackedAccounts
        ) {
            removeExpired(sources, now)
            removeExpired(accounts, now)
        }

        if (sourceKey !in sources && sources.size >= config.maxTrackedSources) return@synchronized null
        if (accountKey !in accounts && accounts.size >= config.maxTrackedAccounts) return@synchronized null

        val source = sources.getOrPut(sourceKey) { Bucket(now) }
        val account = accounts.getOrPut(accountKey) { Bucket(now) }

        // 不要短路：这一个获准进入的网络尝试同时属于全部四个维度。
        val globalAllowed = record(global, config.globalAttempts, now)
        val operationAllowed = record(operation, limits.operationAttempts, now)
        val sourceAllowed = record(source, limits.sourceAttempts, now)
        val accountAllowed = record(account, limits.accountAttempts, now)
        if (!(globalAllowed && operationAllowed && sourceAllowed && accountAllowed)) {
            return@synchronized null
        }
        concurrentAttempts += 1
        Lease(this)
    }

    internal fun trackedSourceCount(): Int = synchronized(lock) { sources.size }
    internal fun trackedAccountCount(): Int = synchronized(lock) { accounts.size }
    internal fun concurrentAttemptCount(): Int = synchronized(lock) { concurrentAttempts }

    private fun release() = synchronized(lock) {
        concurrentAttempts -= 1
        check(concurrentAttempts >= 0) { "authentication attempt admission underflow" }
    }

    internal class Lease internal constructor(
        private val owner: AuthenticationAttemptGuard,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) owner.release()
        }
    }

    private fun record(bucket: Bucket, limit: Int, now: Long): Boolean {
        resetIfExpired(bucket, now)
        if (bucket.deniedAt != null) return false
        if (bucket.attempts >= limit) {
            bucket.deniedAt = now
            return false
        }
        bucket.attempts += 1
        return true
    }

    private fun isDenied(bucket: Bucket, now: Long): Boolean {
        resetIfExpired(bucket, now)
        return bucket.deniedAt != null
    }

    private fun resetIfExpired(bucket: Bucket, now: Long) {
        val deniedAt = bucket.deniedAt
        val expired = if (deniedAt == null) {
            now - bucket.windowStartedAt >= config.windowNanos
        } else {
            now - deniedAt >= config.cooldownNanos
        }
        if (expired) {
            bucket.windowStartedAt = now
            bucket.attempts = 0
            bucket.deniedAt = null
        }
    }

    private fun removeExpired(map: MutableMap<DimensionKey, Bucket>, now: Long) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next().value
            val deniedAt = bucket.deniedAt
            val expired = if (deniedAt == null) {
                now - bucket.windowStartedAt >= config.windowNanos
            } else {
                now - deniedAt >= config.cooldownNanos
            }
            if (expired) iterator.remove()
        }
    }

    private companion object {
        const val CLEANUP_INTERVAL = 64
    }
}

/** 不透明的有界密钥，让用户名与刷新/管理凭证远离准入内存。 */
internal object AuthenticationAttemptKeys {
    private const val MISSING = "<missing>"

    fun directSource(value: String?): String = fingerprint(
        realm = "source",
        value = value?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: MISSING,
    )

    fun username(realm: String, value: String?): String {
        val normalized = value
            ?.trim()
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty)
            ?: MISSING
        return fingerprint("username:$realm", normalized)
    }

    fun bearer(realm: String, value: String?): String =
        fingerprint("bearer:$realm", value?.takeIf(String::isNotEmpty) ?: MISSING)

    fun unsupported(): String = fingerprint("unsupported", MISSING)

    private fun fingerprint(realm: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(realm.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        val bytes = digest.digest(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

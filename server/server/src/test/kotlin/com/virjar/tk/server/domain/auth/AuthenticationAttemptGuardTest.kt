package com.virjar.tk.server.domain.auth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuthenticationAttemptGuardTest {
    @Test
    fun `source account operation and global dimensions deny independently`() {
        val sourceGuard = guard(config(source = 2, account = 20, operation = 20, global = 20))
        assertAdmitted(sourceGuard, attempt(source = "one", account = "a"))
        assertAdmitted(sourceGuard, attempt(source = "one", account = "b"))
        assertNull(sourceGuard.tryAcquire(attempt(source = "one", account = "c")))

        val accountGuard = guard(config(source = 20, account = 2, operation = 20, global = 20))
        assertAdmitted(accountGuard, attempt(source = "one", account = "a"))
        assertAdmitted(accountGuard, attempt(source = "two", account = "a"))
        assertNull(accountGuard.tryAcquire(attempt(source = "three", account = "a")))

        val operationGuard = guard(config(source = 20, account = 20, operation = 2, global = 20))
        assertAdmitted(operationGuard, attempt(source = "one", account = "a"))
        assertAdmitted(operationGuard, attempt(source = "two", account = "b"))
        assertNull(operationGuard.tryAcquire(attempt(source = "three", account = "c")))

        val globalGuard = guard(config(source = 20, account = 20, operation = 20, global = 2))
        assertAdmitted(globalGuard, attempt(source = "one", account = "a"))
        assertAdmitted(globalGuard, attempt(source = "two", account = "b"))
        assertNull(
            globalGuard.tryAcquire(
                attempt(
                    source = "three",
                    account = "c",
                    operation = AuthenticationOperation.REFRESH,
                ),
            ),
        )
    }

    @Test
    fun `cooldown is monotonic and window expiry resets an allowed bucket`() {
        var now = 0L
        val cooldownGuard = guard(
            config(source = 10, account = 1, operation = 10, global = 10),
            clock = { now },
        )
        assertAdmitted(cooldownGuard, attempt())
        assertNull(cooldownGuard.tryAcquire(attempt()))

        now = 199L
        assertNull(cooldownGuard.tryAcquire(attempt()))
        now = 200L
        assertAdmitted(cooldownGuard, attempt())

        now = 1_000L
        val windowGuard = guard(
            config(source = 10, account = 1, operation = 10, global = 10),
            clock = { now },
        )
        assertAdmitted(windowGuard, attempt())
        now += 100L
        assertAdmitted(windowGuard, attempt())
    }

    @Test
    fun `tracked key capacity fails closed and only expired state is reclaimed`() {
        var now = 0L
        val guard = guard(
            config(
                source = 10,
                account = 10,
                operation = 20,
                global = 20,
                maxSources = 2,
                maxAccounts = 2,
            ),
            clock = { now },
        )
        assertAdmitted(guard, attempt(source = "one", account = "a"))
        assertAdmitted(guard, attempt(source = "two", account = "b"))
        assertNull(guard.tryAcquire(attempt(source = "three", account = "c")))
        assertEquals(2, guard.trackedSourceCount())
        assertEquals(2, guard.trackedAccountCount())

        now = 100L
        assertAdmitted(guard, attempt(source = "three", account = "c"))
        assertEquals(1, guard.trackedSourceCount())
        assertEquals(1, guard.trackedAccountCount())
    }

    @Test
    fun `in flight leases have a hard bound and idempotent release restores capacity`() {
        val guard = guard(
            config(
                source = 100,
                account = 100,
                operation = 100,
                global = 100,
                maxConcurrent = 2,
            ),
        )
        val first = assertNotNull(guard.tryAcquire(attempt(source = "one", account = "a")))
        val second = assertNotNull(guard.tryAcquire(attempt(source = "two", account = "b")))
        assertEquals(2, guard.concurrentAttemptCount())
        assertNull(guard.tryAcquire(attempt(source = "three", account = "c")))

        first.close()
        first.close()
        assertEquals(1, guard.concurrentAttemptCount())
        val replacement = assertNotNull(guard.tryAcquire(attempt(source = "three", account = "c")))
        assertEquals(2, guard.concurrentAttemptCount())

        second.close()
        replacement.close()
        assertEquals(0, guard.concurrentAttemptCount())
    }

    @Test
    fun `caller concurrency ceiling can reserve capacity below configured process bound`() {
        val guard = guard(
            config(
                source = 100,
                account = 100,
                operation = 100,
                global = 100,
                maxConcurrent = 16,
            ),
        )
        val first = assertNotNull(
            guard.tryAcquire(
                attempt(source = "one", account = "a"),
                callerConcurrencyCeiling = 2,
            ),
        )
        val second = assertNotNull(
            guard.tryAcquire(
                attempt(source = "two", account = "b"),
                callerConcurrencyCeiling = 2,
            ),
        )
        assertNull(
            guard.tryAcquire(
                attempt(source = "three", account = "c"),
                callerConcurrencyCeiling = 2,
            ),
        )

        first.close()
        assertNotNull(
            guard.tryAcquire(
                attempt(source = "three", account = "c"),
                callerConcurrencyCeiling = 2,
            ),
        ).close()
        second.close()
        assertEquals(0, guard.concurrentAttemptCount())
    }

    @Test
    fun `concurrent callers cannot exceed either rate or in flight bounds`() {
        val guard = guard(
            config(
                source = 1_000,
                account = 1_000,
                operation = 1_000,
                global = 1_000,
                maxConcurrent = 12,
            ),
        )
        val workers = 32
        val pool = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val attempted = CountDownLatch(workers)
        val release = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val admitted = AtomicInteger()
        try {
            repeat(workers) { index ->
                pool.execute {
                    ready.countDown()
                    start.await()
                    val lease = guard.tryAcquire(attempt(source = "s$index", account = "a$index"))
                    attempted.countDown()
                    if (lease != null) {
                        admitted.incrementAndGet()
                        release.await()
                        lease.close()
                    }
                    done.countDown()
                }
            }
            assertEquals(true, ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(true, attempted.await(5, TimeUnit.SECONDS))
            assertEquals(12, admitted.get())
            assertEquals(12, guard.concurrentAttemptCount())
            release.countDown()
            assertEquals(true, done.await(5, TimeUnit.SECONDS))
            assertEquals(0, guard.concurrentAttemptCount())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `account keys normalize usernames but never merge credential realms`() {
        assertEquals(
            AuthenticationAttemptKeys.username("human", "  Ａlice "),
            AuthenticationAttemptKeys.username("human", "alice"),
        )
        assertNotEquals(
            AuthenticationAttemptKeys.username("human", "alice"),
            AuthenticationAttemptKeys.username("admin", "alice"),
        )
        assertNotEquals(
            AuthenticationAttemptKeys.bearer("refresh", "token-a"),
            AuthenticationAttemptKeys.bearer("refresh", "token-b"),
        )
    }

    @Test
    fun `environment configuration rejects malformed and unsafe bounds`() {
        assertFailsWith<IllegalArgumentException> {
            AuthenticationAttemptGuardConfig.fromEnvironment { name ->
                if (name == AuthenticationAttemptGuardConfig.MAX_CONCURRENT_ENV) "0" else null
            }
        }
        assertFailsWith<IllegalArgumentException> {
            AuthenticationAttemptGuardConfig.fromEnvironment { name ->
                if (name == AuthenticationAttemptGuardConfig.WINDOW_SECONDS_ENV) "not-a-number" else null
            }
        }
        assertFailsWith<IllegalArgumentException> {
            AuthenticationAttemptGuardConfig.fromEnvironment { name ->
                if (name == AuthenticationAttemptGuardConfig.MAX_ACCOUNTS_ENV) "1000001" else null
            }
        }
        val configured = AuthenticationAttemptGuardConfig.fromEnvironment { name ->
            when (name) {
                AuthenticationAttemptGuardConfig.MAX_CONCURRENT_ENV -> "7"
                AuthenticationAttemptGuardConfig.GLOBAL_ATTEMPTS_ENV -> "99"
                else -> null
            }
        }
        assertEquals(7, configured.maxConcurrentAttempts)
        assertEquals(99, configured.globalAttempts)
    }

    @Test
    fun `production defaults keep registration source traffic bounded`() {
        val productionGuard = guard(
            AuthenticationAttemptGuardConfig.fromEnvironment { null },
        )

        repeat(8) { index ->
            assertAdmitted(
                productionGuard,
                attempt(
                    source = "shared-source",
                    account = "account-$index",
                    operation = AuthenticationOperation.REGISTER,
                ),
            )
        }
        assertNull(
            productionGuard.tryAcquire(
                attempt(
                    source = "shared-source",
                    account = "ninth-account",
                    operation = AuthenticationOperation.REGISTER,
                ),
            ),
        )
    }

    private fun assertAdmitted(
        guard: AuthenticationAttemptGuard,
        attempt: AuthenticationAttempt,
    ) {
        assertNotNull(guard.tryAcquire(attempt)).close()
    }

    private fun guard(
        config: AuthenticationAttemptGuardConfig,
        clock: () -> Long = { 0L },
    ) = AuthenticationAttemptGuard(config, clock)

    private fun attempt(
        source: String = "source",
        account: String = "account",
        operation: AuthenticationOperation = AuthenticationOperation.LOGIN,
    ) = AuthenticationAttempt(operation, source, account)

    private fun config(
        source: Int,
        account: Int,
        operation: Int,
        global: Int,
        maxConcurrent: Int = 100,
        maxSources: Int = 1_000,
        maxAccounts: Int = 1_000,
    ): AuthenticationAttemptGuardConfig {
        val limits = AuthenticationOperation.entries.associateWith {
            AuthenticationOperationLimits(
                operationAttempts = operation,
                sourceAttempts = source,
                accountAttempts = account,
            )
        }
        return AuthenticationAttemptGuardConfig(
            windowNanos = 100L,
            cooldownNanos = 200L,
            globalAttempts = global,
            maxConcurrentAttempts = maxConcurrent,
            maxTrackedSources = maxSources,
            maxTrackedAccounts = maxAccounts,
            limits = limits,
        )
    }
}

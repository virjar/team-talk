package com.virjar.tk.server.infra.security

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.server.domain.auth.PasswordHasher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用自有的 BCrypt 适配器，具有固定的 worker 数与固定的执行器队列。
 *
 * 协程信号量最多准入 `workers + queue` 个派发，因此在拥有者打开期间，
 * 协程执行器绝不会到达其拒绝回退。TCP 与 HTTP 准入为
 * 等待许可的调用方提供外层界限。
 */
class BCryptPasswordHasher internal constructor(
    workerCount: Int = DEFAULT_WORKERS,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val cost: Int = DEFAULT_COST,
    private val engine: BCryptEngine = JBCryptEngine,
) : PasswordHasher, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val executor: ThreadPoolExecutor
    private val dispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher
    private val admission: Semaphore

    init {
        require(workerCount in 1..MAX_WORKERS) { "BCrypt worker count must be between 1 and $MAX_WORKERS" }
        require(queueCapacity in 1..MAX_QUEUE_CAPACITY) {
            "BCrypt queue capacity must be between 1 and $MAX_QUEUE_CAPACITY"
        }
        require(cost in 4..MAX_COST) { "BCrypt cost must be between 4 and $MAX_COST" }
        executor = ThreadPoolExecutor(
            workerCount,
            workerCount,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            PasswordThreadFactory(),
            ThreadPoolExecutor.AbortPolicy(),
        )
        dispatcher = executor.asCoroutineDispatcher()
        admission = Semaphore(workerCount + queueCapacity)
    }

    override suspend fun hash(rawPassword: String): String {
        require(rawPassword.encodeToByteArray().size <= AuthRules.PASSWORD_MAX_UTF8_BYTES) {
            "Password exceeds the BCrypt UTF-8 byte boundary"
        }
        return onCpu { engine.hash(rawPassword, cost) }
    }

    override suspend fun verify(rawPassword: String, encodedHash: String?): Boolean = onCpu {
        if (
            rawPassword.encodeToByteArray().size > AuthRules.PASSWORD_MAX_UTF8_BYTES ||
            encodedHash == null ||
            !isSupportedBcryptHash(encodedHash)
        ) {
            engine.verify(rawPassword, DUMMY_HASH)
            return@onCpu false
        }
        try {
            engine.verify(rawPassword, encodedHash)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            // 畸形的已持久化校验值是存储故障，不是快速鉴权路径。
            // 对进程无关的哑哈希消耗相同的 BCrypt 预算并 fail closed。
            engine.verify(rawPassword, DUMMY_HASH)
            false
        }
    }

    private suspend fun <T> onCpu(block: () -> T): T = admission.withPermit {
        check(!closed.get()) { "Password hasher is closed" }
        withContext(dispatcher) { block() }
    }

    private fun isSupportedBcryptHash(encodedHash: String): Boolean =
        BCRYPT_HASH.matches(encodedHash) && encodedHash.substring(4, 6).toInt() in 4..MAX_COST

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        dispatcher.close()
        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            check(executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "BCrypt executor did not terminate"
            }
        }
    }

    private class PasswordThreadFactory : ThreadFactory {
        private val sequence = AtomicInteger(0)

        override fun newThread(task: Runnable): Thread =
            Thread(task, "tk-password-cpu-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }

    private companion object {
        const val MAX_WORKERS = 4
        const val MAX_QUEUE_CAPACITY = 64
        const val MAX_COST = 14
        const val DEFAULT_WORKERS = MAX_WORKERS
        const val DEFAULT_QUEUE_CAPACITY = MAX_QUEUE_CAPACITY
        const val DEFAULT_COST = 10
        const val SHUTDOWN_TIMEOUT_SECONDS = 10L
        val BCRYPT_HASH = Regex("^\\$2[aby]\\$[0-3][0-9]\\$[./A-Za-z0-9]{53}$")

        // BCrypt cost 10，仅在没有可咨询的合格人类校验值时使用。匹配
        // 被领域策略刻意忽略，因此此公共哑哈希绝不是凭据。
        const val DUMMY_HASH = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    }
}

internal interface BCryptEngine {
    fun hash(rawPassword: String, cost: Int): String
    fun verify(rawPassword: String, encodedHash: String): Boolean
}

private object JBCryptEngine : BCryptEngine {
    override fun hash(rawPassword: String, cost: Int): String =
        BCrypt.hashpw(rawPassword, BCrypt.gensalt(cost))

    override fun verify(rawPassword: String, encodedHash: String): Boolean =
        BCrypt.checkpw(rawPassword, encodedHash)
}

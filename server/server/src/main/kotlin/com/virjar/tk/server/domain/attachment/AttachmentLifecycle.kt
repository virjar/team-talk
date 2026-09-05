package com.virjar.tk.server.domain.attachment

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * 用于创建与退役附件引用的、有界的、按路径分片（striped）的单进程跨存储围栏（fence）。
 *
 * TeamTalk 目前拥有一个服务器进程。消息引用存放在 RocksDB 中，群文件引用存放在
 * PostgreSQL 中，字节数据存放在 FileStore 中；没有任何事务能同时跨越这三者。从附件
 * 最终校验一直到权威引用提交期间持有该路径的分片锁，可以防止保留（retention）工作线程
 * 在中间观察到"未被引用"并删除该对象。崩溃是安全的，因为下一次扫描会从持久化的引用中
 * 重建该决策。多条路径总是按数值顺序获取它们各自不同的分片。
 */
class AttachmentLifecycleGate(
    stripeCount: Int = DEFAULT_STRIPE_COUNT,
) {
    private val stripes: Array<Mutex>

    init {
        require(stripeCount in 1..MAX_STRIPE_COUNT) { "attachment lifecycle stripe count is out of range" }
        stripes = Array(stripeCount) { Mutex() }
    }

    suspend fun <T> withReferenceMutation(
        paths: Collection<String>,
        block: suspend () -> T,
    ): T = withPathStripes(paths, block)

    internal suspend fun <T> withRetirementDecision(
        paths: Collection<String>,
        block: suspend () -> T,
    ): T = withPathStripes(paths, block)

    private suspend fun <T> withPathStripes(
        paths: Collection<String>,
        block: suspend () -> T,
    ): T {
        require(paths.isNotEmpty()) { "attachment lifecycle mutation requires at least one path" }
        val stripeIndexes = paths.mapTo(sortedSetOf()) { path ->
            (path.hashCode() and Int.MAX_VALUE) % stripes.size
        }
        val acquired = ArrayList<Mutex>(stripeIndexes.size)
        try {
            stripeIndexes.forEach { index ->
                stripes[index].lock()
                acquired += stripes[index]
            }
            return block()
        } finally {
            acquired.asReversed().forEach { it.unlock() }
        }
    }

    private companion object {
        const val DEFAULT_STRIPE_COUNT = 256
        const val MAX_STRIPE_COUNT = 4_096
    }
}

internal data class AttachmentRetentionConfig(
    val unreferencedTtlMillis: Long = DEFAULT_TTL_HOURS * MILLIS_PER_HOUR,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val maxPagesPerRun: Int = DEFAULT_MAX_PAGES_PER_RUN,
) {
    init {
        require(unreferencedTtlMillis in 1..MAX_TTL_MILLIS) {
            "unreferenced attachment TTL is out of range"
        }
        require(pageSize in 1..MAX_PAGE_SIZE) { "attachment retention page size is out of range" }
        require(maxPagesPerRun in 1..MAX_PAGES_PER_RUN) {
            "attachment retention run page count is out of range"
        }
    }

    companion object {
        private const val MILLIS_PER_HOUR = 60L * 60L * 1_000L
        private const val DEFAULT_TTL_HOURS = 7L * 24L
        private const val MAX_TTL_HOURS = 365L * 24L
        private const val MAX_TTL_MILLIS = MAX_TTL_HOURS * MILLIS_PER_HOUR
        private const val DEFAULT_PAGE_SIZE = 512
        private const val MAX_PAGE_SIZE = 4_096
        private const val DEFAULT_MAX_PAGES_PER_RUN = 16
        private const val MAX_PAGES_PER_RUN = 256

        fun fromEnvironment(
            environment: (String) -> String? = System::getenv,
        ): AttachmentRetentionConfig {
            val configuredHours = environment(TTL_HOURS_ENV)
            val ttlHours = if (configuredHours == null) {
                DEFAULT_TTL_HOURS
            } else {
                configuredHours.toLongOrNull()
                    ?: throw IllegalArgumentException("$TTL_HOURS_ENV must be an integer")
            }
            require(ttlHours in 1..MAX_TTL_HOURS) {
                "$TTL_HOURS_ENV must be in 1..$MAX_TTL_HOURS"
            }
            return AttachmentRetentionConfig(
                unreferencedTtlMillis = Math.multiplyExact(ttlHours, MILLIS_PER_HOUR),
            )
        }

        internal const val TTL_HOURS_ENV = "TEAMTALK_UNREFERENCED_ATTACHMENT_TTL_HOURS"
    }
}

/** 对从未获得业务引用的已上传对象进行有界维护的责任者。 */
internal class AttachmentRetentionService(
    private val files: AttachmentRetirementStore,
    private val references: AttachmentReferences,
    private val lifecycle: AttachmentLifecycleGate,
    private val config: AttachmentRetentionConfig = AttachmentRetentionConfig(),
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private val scanMutex = Mutex()
    private var scanAfterPath: String? = null

    suspend fun cleanupExpiredUnreferenced(): Int = scanMutex.withLock {
        cleanupOneRun()
    }

    private suspend fun cleanupOneRun(): Int {
        val now = wallClockMillis()
        val cutoff = if (now <= config.unreferencedTtlMillis) 0L else now - config.unreferencedTtlMillis
        var retired = 0

        repeat(config.maxPagesPerRun) {
            val page = files.scanRetirementCandidates(
                uploadedAtOrBefore = cutoff,
                afterPath = scanAfterPath,
                limit = config.pageSize,
            )
            val lastScannedPath = page.lastScannedPath
            if (lastScannedPath == null) {
                scanAfterPath = null
                return retired
            }

            page.candidates.chunked(RETIREMENT_DECISION_BATCH_SIZE).forEach { decisionBatch ->
                val paths = decisionBatch.mapTo(linkedSetOf()) { it.path }
                retired += lifecycle.withRetirementDecision(paths) {
                    val referenced = references.getReferencedPaths(paths)
                    decisionBatch.count { candidate ->
                        candidate.path !in referenced &&
                            files.retireIfExpiredAndUnchanged(candidate, cutoff)
                    }
                }
            }
            scanAfterPath = if (page.hasMore) lastScannedPath else null
            if (!page.hasMore) {
                scanAfterPath = null
                return retired
            }
            yield()
        }
        return retired
    }

    private companion object {
        const val RETIREMENT_DECISION_BATCH_SIZE = 64
    }
}

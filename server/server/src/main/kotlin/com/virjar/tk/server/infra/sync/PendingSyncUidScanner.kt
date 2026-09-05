package com.virjar.tk.server.infra.sync

import com.virjar.tk.server.infra.db.SyncEvents
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 一页稳定、排他键的、其持久事件日志现在可以分发的用户。
 *
 * 仓库同时拥有键比较器与游标谓词。特别地，数据库适配器
 * 可能使用与 Kotlin [String] 比较不同的数据库排序规则，
 * 但每个页面与游标都必须使用同一仓库拥有的顺序。
 */
internal fun interface PendingSyncUidPageRepository {
    fun loadPage(nowMillis: Long, afterUidExclusive: String?, limit: Int): List<String>
}

/** 分发器有界待处理用户扫描的 PostgreSQL 适配器。 */
internal class ExposedPendingSyncUidPageRepository(
    private val database: Database,
) : PendingSyncUidPageRepository {
    override fun loadPage(nowMillis: Long, afterUidExclusive: String?, limit: Int): List<String> {
        require(limit > 0) { "Pending sync uid page limit must be positive" }
        return transaction(database) {
            SyncEvents.select(SyncEvents.uid)
                .where {
                    val due = SyncEvents.dispatchedAt.isNull() and
                        (SyncEvents.nextAttemptAt lessEq nowMillis)
                    afterUidExclusive?.let { after ->
                        due and (SyncEvents.uid greater after)
                    } ?: due
                }
                .groupBy(SyncEvents.uid)
                .orderBy(SyncEvents.uid to SortOrder.ASC)
                .limit(limit)
                .map { row -> row[SyncEvents.uid] }
        }
    }
}

internal data class PendingSyncUidPage(
    val uids: List<String>,
    /** 此页到达当前末尾后为 True，下一次调用将回绕到第一个 uid。 */
    val cycleCompleted: Boolean,
)

/**
 * 进程拥有的持久待处理用户公平游标。
 *
 * 仓库契约刻意按页塑形：周期性路径与邮箱
 * 溢出恢复都不能请求无界结果。短页完成当前循环；
 * 下一次调用从最小 uid 重新开始，因此在 worker 轮次之间
 * 无需保留数据库快照，也能最终回访插入在游标之后的用户。
 */
internal class PendingSyncUidScanner(
    private val repository: PendingSyncUidPageRepository,
    private val pageSize: Int,
) {
    private var afterUidExclusive: String? = null

    init {
        require(pageSize > 0) { "Pending sync uid page size must be positive" }
    }

    fun restartCycle() {
        afterUidExclusive = null
    }

    fun loadNextPage(nowMillis: Long): PendingSyncUidPage {
        val after = afterUidExclusive
        val uids = repository.loadPage(nowMillis, after, pageSize)
        require(uids.size <= pageSize) {
            "Pending sync uid repository exceeded requested page size"
        }
        require(uids.distinct().size == uids.size) {
            "Pending sync uid repository returned duplicate keys"
        }
        require(after == null || after !in uids) {
            "Pending sync uid repository returned its exclusive cursor"
        }

        val cycleCompleted = uids.size < pageSize
        afterUidExclusive = if (cycleCompleted) null else uids.last()
        return PendingSyncUidPage(uids = uids, cycleCompleted = cycleCompleted)
    }
}

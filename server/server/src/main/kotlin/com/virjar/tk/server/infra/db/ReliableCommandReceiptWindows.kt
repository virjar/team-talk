package com.virjar.tk.server.infra.db

import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll

/**
 * 可靠回执保留窗口的统一语义：先机会式清理该 actor 的过期行，再在族窗口处拒绝全新预留。
 * 各族窗口大小是频率取舍（草稿同步显著高于任务/文档），数值不在此统一；调用方必须已
 * 进入所属聚合的写事务。
 */
internal object ReliableCommandReceiptWindows {
    /** 草稿同步是最高频的可靠命令族，使用更宽的窗口。 */
    const val CHAT_DRAFT_WINDOW = 16_384L

    /** 其余命令族的默认保留窗口。 */
    const val DEFAULT_WINDOW = 10_000L

    fun require(
        table: Table,
        actorColumn: Column<String>,
        expiresAt: Column<Long>,
        actorUid: String,
        now: Long,
        failureMessage: String,
        window: Long = DEFAULT_WINDOW,
    ) {
        pruneExpired(table, actorColumn, expiresAt, actorUid, now)
        val retained = table.selectAll().where { actorColumn eq actorUid }.count()
        if (retained >= window) throw ReliableCommandCapacityException(failureMessage)
    }

    fun pruneExpired(
        table: Table,
        actorColumn: Column<String>,
        expiresAt: Column<Long>,
        actorUid: String,
        now: Long,
    ) {
        table.deleteWhere { (actorColumn eq actorUid) and (expiresAt less now) }
    }
}

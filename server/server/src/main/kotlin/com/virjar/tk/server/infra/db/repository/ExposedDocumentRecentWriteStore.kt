package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.document.DocumentCapacityPolicy
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentUserRecents
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.execRawSql
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.upsert

/**
 * 拥有有界的每用户文档最近访问索引。
 *
 * 不可变 User 行既是锁顺序锚点，也是容量 fence。因此两个
 * 进程触碰同一用户的最后一个名额时，不可能各自多保留一行，而不相关的
 * 用户保持独立。`accessedAt` 既是展示时间戳，也是逻辑排序令牌：倒流的
 * 墙钟会被推进到当前头部之后，而实际上不可达的
 * [Long.MAX_VALUE] 边界会在不改变现有最新优先顺序的情况下被修复。
 */
internal class ExposedDocumentRecentWriteStore {
    fun touch(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        documentId: String,
        accessedAt: Long,
    ) {
        val exposedTransaction = transaction.requireExposedTransaction()
        val actor = Users.select(Users.uid, Users.role, Users.status).where { Users.uid eq actorUid }
            .forUpdate().singleOrNull() ?: throw IllegalArgumentException("用户不存在")
        require(actor[Users.role] == UserRole.HUMAN && actor[Users.status] == USER_STATUS_ACTIVE) {
            "只有活动普通用户可以更新文档最近访问记录"
        }

        val latestAccess = DocumentUserRecents
            .select(DocumentUserRecents.accessedAt)
            .where { DocumentUserRecents.uid eq actorUid }
            .orderBy(
                DocumentUserRecents.accessedAt to SortOrder.DESC,
                DocumentUserRecents.documentId to SortOrder.ASC,
            )
            .limit(1)
            .singleOrNull()
            ?.get(DocumentUserRecents.accessedAt)

        val orderedAccessAt = when (latestAccess) {
            null -> maxOf(accessedAt, 1L)
            Long.MAX_VALUE -> {
                // 在一次性 O(capacity) 重排序之前，先约束任何遗留的超大状态。
                pruneOverflow(exposedTransaction, actorUid)
                rebaseAccessOrder(exposedTransaction, actorUid, accessedAt)
            }
            else -> maxOf(accessedAt, latestAccess + 1)
        }

        DocumentUserRecents.upsert(DocumentUserRecents.uid, DocumentUserRecents.documentId) {
            it[uid] = actorUid
            it[DocumentUserRecents.documentId] = documentId
            it[DocumentUserRecents.accessedAt] = orderedAccessAt
        }
        pruneOverflow(exposedTransaction, actorUid)
    }

    /** 删除超过确定性最新 [MAX_RECENT_DOCUMENTS_PER_USER] 窗口的每一行。 */
    private fun pruneOverflow(transaction: Transaction, actorUid: String) {
        transaction.execRawSql(
            stmt = """
                DELETE FROM document_user_recents AS recent
                WHERE recent.uid = ?::varchar
                  AND recent.document_id IN (
                      SELECT candidate.document_id
                      FROM document_user_recents AS candidate
                      WHERE candidate.uid = ?::varchar
                      ORDER BY candidate.accessed_at DESC, candidate.document_id ASC
                      OFFSET ${DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER}
                  )
            """.trimIndent(),
            args = listOf(
                DocumentUserRecents.uid.columnType to actorUid,
                DocumentUserRecents.uid.columnType to actorUid,
            ),
            explicitStatementType = StatementType.DELETE,
        )
    }

    /**
     * 在保持精确顺序的同时，把保留的时间戳压缩到下一次访问之下。
     * 行集已经有界，SQL 原子地更新它，因此即使此方法
     * 在当前事务之外被复用，读取者也永远不会观察到部分重排序的索引。
     */
    private fun rebaseAccessOrder(
        transaction: Transaction,
        actorUid: String,
        accessedAt: Long,
    ): Long {
        val nextAccessAt = accessedAt.coerceIn(
            minimumValue = DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER.toLong(),
            maximumValue = Long.MAX_VALUE - 1,
        )
        val result: RebaseResult = transaction.execRawSql(
            stmt = """
                WITH ordered AS (
                    SELECT document_id,
                           ROW_NUMBER() OVER (
                               ORDER BY accessed_at ASC, document_id DESC
                           ) AS position,
                           COUNT(*) OVER () AS retained_count
                    FROM document_user_recents
                    WHERE uid = ?::varchar
                ),
                rebased AS (
                    UPDATE document_user_recents AS recent
                    SET accessed_at =
                        (?::bigint - ordered.retained_count) + ordered.position - 1
                    FROM ordered
                    WHERE recent.uid = ?::varchar
                      AND recent.document_id = ordered.document_id
                    RETURNING recent.accessed_at
                )
                SELECT COUNT(*) AS updated_count,
                       MAX(accessed_at) AS latest_access
                FROM rebased
            """.trimIndent(),
            args = listOf(
                DocumentUserRecents.uid.columnType to actorUid,
                DocumentUserRecents.accessedAt.columnType to nextAccessAt,
                DocumentUserRecents.uid.columnType to actorUid,
            ),
            explicitStatementType = StatementType.SELECT,
        ) { resultSet ->
            check(resultSet.next()) { "Document recent resequence returned no result row" }
            RebaseResult(
                updatedCount = resultSet.getInt("updated_count"),
                latestAccess = resultSet.getLong("latest_access"),
            )
        } ?: error("Document recent resequence returned no result set")

        check(result.updatedCount in 1..DocumentCapacityPolicy.MAX_RECENT_DOCUMENTS_PER_USER) {
            "Document recent resequence escaped its per-user capacity fence"
        }
        check(result.latestAccess == nextAccessAt - 1) {
            "Document recent resequence did not preserve a contiguous ordering window"
        }
        return nextAccessAt
    }

    private data class RebaseResult(
        val updatedCount: Int,
        val latestAccess: Long,
    )
}

private const val USER_STATUS_ACTIVE = 1

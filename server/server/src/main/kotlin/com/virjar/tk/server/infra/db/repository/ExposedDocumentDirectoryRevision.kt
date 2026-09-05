package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentDirectoryState
import com.virjar.tk.server.infra.db.OrganizationState
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.DocumentDirectorySnapshotVersion
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/**
 * 完整、多页 Document 目录扫描的版本 fence。
 *
 * [advance] 是每个影响投影的命令中的最后一个 Document 域锁。调用方必须
 * 先完成所有 User、Space、Unit、membership、grant 与回执工作。保持一个规范
 * 顺序可防止两个不相关的空间变更引入 State -> 聚合死锁。
 */
internal object ExposedDocumentDirectoryRevision {
    fun read(
        transaction: PgReadTransactionContext,
        actorUid: String,
    ): DocumentDirectorySnapshotVersion = transaction.inExposedReadTransaction {
        // 这三个值构成一个游标版本，每个页面都会读取。把两个
        // 单例行 join 到所寻址的 actor 上，使固定成本的 fence 保持为一条 SQL 语句，
        // 而不是让目录查询预算翻三倍。
        val row = Users
            .join(
                otherTable = DocumentDirectoryState,
                joinType = JoinType.INNER,
                additionalConstraint = { DocumentDirectoryState.id eq STATE_ID },
            )
            .join(
                otherTable = OrganizationState,
                joinType = JoinType.INNER,
                additionalConstraint = { OrganizationState.id eq STATE_ID },
            )
            .select(
                DocumentDirectoryState.revision,
                OrganizationState.revision,
                Users.credentialEpoch,
            )
            .where { Users.uid eq actorUid }
            .singleOrNull()
        checkNotNull(row) {
            "Document directory state, organization state, or actor is missing"
        }

        DocumentDirectorySnapshotVersion(
            documentDirectoryRevision = row[DocumentDirectoryState.revision],
            organizationRevision = row[OrganizationState.revision],
            actorCredentialEpoch = row[Users.credentialEpoch],
        )
    }

    /** 在一个事务改变了任何目录投影事实后，恰好推进一次。 */
    fun advance(
        transaction: PgWriteTransactionContext,
        updatedAt: Long,
    ): Long = transaction.inExposedTransaction {
        require(updatedAt >= 0L) { "Document directory revision timestamp is invalid" }
        val locked = DocumentDirectoryState.selectAll().where {
            DocumentDirectoryState.id eq STATE_ID
        }.forUpdate().singleOrNull()
        checkNotNull(locked) { "Document directory state singleton is missing" }
        val current = locked[DocumentDirectoryState.revision]
        check(current < Long.MAX_VALUE) { "Document directory revision is exhausted" }
        val next = current + 1L
        check(DocumentDirectoryState.update({ DocumentDirectoryState.id eq STATE_ID }) {
            it[revision] = next
            it[DocumentDirectoryState.updatedAt] = updatedAt
        } == 1) { "Document directory state singleton disappeared" }
        next
    }

    private const val STATE_ID = 1
}

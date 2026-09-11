package com.virjar.tk.server.infra.db

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

internal data class ReliableCommandReceiptCleanupConfig(
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val maxBatchesPerTablePerRun: Int = DEFAULT_MAX_BATCHES_PER_TABLE_PER_RUN,
) {
    init {
        require(batchSize in 1..MAX_BATCH_SIZE) { "Reliable-command cleanup batch size is out of range" }
        require(maxBatchesPerTablePerRun in 1..MAX_BATCHES_PER_TABLE_PER_RUN) {
            "Reliable-command cleanup batch count is out of range"
        }
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 512
        const val MAX_BATCH_SIZE = 4_096
        const val DEFAULT_MAX_BATCHES_PER_TABLE_PER_RUN = 8
        const val MAX_BATCHES_PER_TABLE_PER_RUN = 128
    }
}

internal data class ReliableCommandReceiptCleanupResult(
    val contactReceiptsDeleted: Int,
    val inviteReceiptsDeleted: Int,
    val documentPolicyReceiptsDeleted: Int,
    val documentNodeMoveReceiptsDeleted: Int,
    /** 最后一个批次已满，意味着可能仍需要再做一轮有界清理。 */
    val contactBacklogMayRemain: Boolean,
    /** 最后一个批次已满，意味着可能仍需要再做一轮有界清理。 */
    val inviteBacklogMayRemain: Boolean,
    /** 最后一个批次已满，意味着可能仍需要再做一轮有界清理。 */
    val documentPolicyBacklogMayRemain: Boolean,
    /** 最后一个批次已满，意味着可能仍需要再做一轮有界清理。 */
    val documentNodeMoveBacklogMayRemain: Boolean,
) {
    val backlogMayRemain: Boolean
        get() = contactBacklogMayRemain || inviteBacklogMayRemain || documentPolicyBacklogMayRemain ||
            documentNodeMoveBacklogMayRemain
}

/**
 * 有限可靠命令身份的全局有界收集器。
 *
 * 按 actor 的命令路径会机会式地删除自己过期的行，但变沉寂
 * 或已被删除的账户绝不能让回执永久保留。每张表每轮运行
 * 获得固定数量的定长事务。共享的过期规则保证，在本收集器
 * 被允许看到这些行之前，它们已不能再作为变更被重放。
 */
internal class ReliableCommandReceiptMaintenance(
    private val database: Database,
    private val config: ReliableCommandReceiptCleanupConfig = ReliableCommandReceiptCleanupConfig(),
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private data class BatchResult(val scanned: Int, val deleted: Int)

    private class TableSweep(val batch: (Long) -> BatchResult) {
        var deleted = 0
        var complete = false
    }

    private val runMutex = Mutex()

    suspend fun cleanupExpiredReceipts(): ReliableCommandReceiptCleanupResult = runMutex.withLock {
        val nowMillis = wallClockMillis()
        require(nowMillis >= 0L) { "Reliable-command cleanup clock is invalid" }
        val contact = TableSweep(::cleanupExpiredContactBatch)
        val invite = TableSweep(::cleanupExpiredInviteBatch)
        val documentPolicy = TableSweep(::cleanupExpiredDocumentPolicyBatch)
        val documentNodeMove = TableSweep(::cleanupExpiredDocumentNodeMoveBatch)
        val sweeps = listOf(contact, invite, documentPolicy, documentNodeMove)

        repeat(config.maxBatchesPerTablePerRun) {
            for (sweep in sweeps) {
                if (sweep.complete) continue
                val batch = sweep.batch(nowMillis)
                sweep.deleted += batch.deleted
                sweep.complete = batch.scanned < config.batchSize
            }
            if (sweeps.all(TableSweep::complete)) {
                return@withLock result(contact, invite, documentPolicy, documentNodeMove, backlog = false)
            }
            yield()
        }
        result(contact, invite, documentPolicy, documentNodeMove, backlog = true)
    }

    private fun result(
        contact: TableSweep,
        invite: TableSweep,
        documentPolicy: TableSweep,
        documentNodeMove: TableSweep,
        backlog: Boolean,
    ) = ReliableCommandReceiptCleanupResult(
        contactReceiptsDeleted = contact.deleted,
        inviteReceiptsDeleted = invite.deleted,
        documentPolicyReceiptsDeleted = documentPolicy.deleted,
        documentNodeMoveReceiptsDeleted = documentNodeMove.deleted,
        contactBacklogMayRemain = backlog && !contact.complete,
        inviteBacklogMayRemain = backlog && !invite.complete,
        documentPolicyBacklogMayRemain = backlog && !documentPolicy.complete,
        documentNodeMoveBacklogMayRemain = backlog && !documentNodeMove.complete,
    )

    private fun cleanupExpiredContactBatch(nowMillis: Long): BatchResult =
        cleanupExpiredEntityIdBatch(ContactDecisionReceipts, ContactDecisionReceipts.expiresAt, nowMillis)

    private fun cleanupExpiredInviteBatch(nowMillis: Long): BatchResult =
        cleanupExpiredEntityIdBatch(InviteLinkCreationReceipts, InviteLinkCreationReceipts.expiresAt, nowMillis)

    private fun cleanupExpiredDocumentPolicyBatch(nowMillis: Long): BatchResult =
        cleanupExpiredLongIdBatch(
            DocumentSpacePolicyCommands,
            DocumentSpacePolicyCommands.retentionId,
            DocumentSpacePolicyCommands.expiresAt,
            nowMillis,
        )

    private fun cleanupExpiredDocumentNodeMoveBatch(nowMillis: Long): BatchResult =
        cleanupExpiredLongIdBatch(
            DocumentNodeMoveCommands,
            DocumentNodeMoveCommands.retentionId,
            DocumentNodeMoveCommands.expiresAt,
            nowMillis,
        )

    /** 主键即保留身份的回执表（LongIdTable 形态）。 */
    private fun cleanupExpiredEntityIdBatch(
        table: LongIdTable,
        expiresAt: Column<Long>,
        nowMillis: Long,
    ): BatchResult = transaction(database) {
        val ids = table.select(table.id)
            .where { expiresAt less nowMillis }
            .orderBy(
                expiresAt to SortOrder.ASC,
                table.id to SortOrder.ASC,
            )
            .limit(config.batchSize)
            .map { it[table.id] }
        BatchResult(
            scanned = ids.size,
            deleted = if (ids.isEmpty()) 0 else table.deleteWhere { table.id inList ids },
        )
    }

    /** 以独立列保存保留身份的回执表（retentionId 形态）。 */
    private fun cleanupExpiredLongIdBatch(
        table: Table,
        retentionId: Column<Long>,
        expiresAt: Column<Long>,
        nowMillis: Long,
    ): BatchResult = transaction(database) {
        val ids = table.select(retentionId)
            .where { expiresAt less nowMillis }
            .orderBy(
                expiresAt to SortOrder.ASC,
                retentionId to SortOrder.ASC,
            )
            .limit(config.batchSize)
            .map { it[retentionId] }
        BatchResult(
            scanned = ids.size,
            deleted = if (ids.isEmpty()) 0 else table.deleteWhere { retentionId inList ids },
        )
    }
}

package com.virjar.tk.server.infra.db

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
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

    private val runMutex = Mutex()

    suspend fun cleanupExpiredReceipts(): ReliableCommandReceiptCleanupResult = runMutex.withLock {
        val nowMillis = wallClockMillis()
        require(nowMillis >= 0L) { "Reliable-command cleanup clock is invalid" }
        var contactDeleted = 0
        var inviteDeleted = 0
        var documentPolicyDeleted = 0
        var documentNodeMoveDeleted = 0
        var contactComplete = false
        var inviteComplete = false
        var documentPolicyComplete = false
        var documentNodeMoveComplete = false

        repeat(config.maxBatchesPerTablePerRun) {
            if (!contactComplete) {
                val batch = cleanupExpiredContactBatch(nowMillis)
                contactDeleted += batch.deleted
                contactComplete = batch.scanned < config.batchSize
            }
            if (!inviteComplete) {
                val batch = cleanupExpiredInviteBatch(nowMillis)
                inviteDeleted += batch.deleted
                inviteComplete = batch.scanned < config.batchSize
            }
            if (!documentPolicyComplete) {
                val batch = cleanupExpiredDocumentPolicyBatch(nowMillis)
                documentPolicyDeleted += batch.deleted
                documentPolicyComplete = batch.scanned < config.batchSize
            }
            if (!documentNodeMoveComplete) {
                val batch = cleanupExpiredDocumentNodeMoveBatch(nowMillis)
                documentNodeMoveDeleted += batch.deleted
                documentNodeMoveComplete = batch.scanned < config.batchSize
            }
            if (contactComplete && inviteComplete && documentPolicyComplete && documentNodeMoveComplete) {
                return@withLock ReliableCommandReceiptCleanupResult(
                    contactReceiptsDeleted = contactDeleted,
                    inviteReceiptsDeleted = inviteDeleted,
                    documentPolicyReceiptsDeleted = documentPolicyDeleted,
                    documentNodeMoveReceiptsDeleted = documentNodeMoveDeleted,
                    contactBacklogMayRemain = false,
                    inviteBacklogMayRemain = false,
                    documentPolicyBacklogMayRemain = false,
                    documentNodeMoveBacklogMayRemain = false,
                )
            }
            yield()
        }
        ReliableCommandReceiptCleanupResult(
            contactReceiptsDeleted = contactDeleted,
            inviteReceiptsDeleted = inviteDeleted,
            documentPolicyReceiptsDeleted = documentPolicyDeleted,
            documentNodeMoveReceiptsDeleted = documentNodeMoveDeleted,
            contactBacklogMayRemain = !contactComplete,
            inviteBacklogMayRemain = !inviteComplete,
            documentPolicyBacklogMayRemain = !documentPolicyComplete,
            documentNodeMoveBacklogMayRemain = !documentNodeMoveComplete,
        )
    }

    private fun cleanupExpiredContactBatch(nowMillis: Long): BatchResult = transaction(database) {
        val ids = ContactDecisionReceipts.select(ContactDecisionReceipts.id)
            .where { ContactDecisionReceipts.expiresAt less nowMillis }
            .orderBy(
                ContactDecisionReceipts.expiresAt to SortOrder.ASC,
                ContactDecisionReceipts.id to SortOrder.ASC,
            )
            .limit(config.batchSize)
            .map { it[ContactDecisionReceipts.id] }
        BatchResult(
            scanned = ids.size,
            deleted = deleteContactReceipts(ids),
        )
    }

    private fun cleanupExpiredInviteBatch(nowMillis: Long): BatchResult = transaction(database) {
        val ids = InviteLinkCreationReceipts.select(InviteLinkCreationReceipts.id)
            .where { InviteLinkCreationReceipts.expiresAt less nowMillis }
            .orderBy(
                InviteLinkCreationReceipts.expiresAt to SortOrder.ASC,
                InviteLinkCreationReceipts.id to SortOrder.ASC,
            )
            .limit(config.batchSize)
            .map { it[InviteLinkCreationReceipts.id] }
        BatchResult(
            scanned = ids.size,
            deleted = deleteInviteReceipts(ids),
        )
    }

    private fun cleanupExpiredDocumentPolicyBatch(nowMillis: Long): BatchResult = transaction(database) {
        val ids = DocumentSpacePolicyCommands.select(DocumentSpacePolicyCommands.retentionId)
            .where { DocumentSpacePolicyCommands.expiresAt less nowMillis }
            .orderBy(
                DocumentSpacePolicyCommands.expiresAt to SortOrder.ASC,
                DocumentSpacePolicyCommands.retentionId to SortOrder.ASC,
            )
            .limit(config.batchSize)
            .map { it[DocumentSpacePolicyCommands.retentionId] }
        BatchResult(
            scanned = ids.size,
            deleted = deleteDocumentPolicyReceipts(ids),
        )
    }

    private fun cleanupExpiredDocumentNodeMoveBatch(nowMillis: Long): BatchResult = transaction(database) {
        val ids = DocumentNodeMoveCommands.select(DocumentNodeMoveCommands.retentionId)
            .where { DocumentNodeMoveCommands.expiresAt less nowMillis }
            .orderBy(
                DocumentNodeMoveCommands.expiresAt to SortOrder.ASC,
                DocumentNodeMoveCommands.retentionId to SortOrder.ASC,
            )
            .limit(config.batchSize)
            .map { it[DocumentNodeMoveCommands.retentionId] }
        BatchResult(
            scanned = ids.size,
            deleted = deleteDocumentNodeMoveReceipts(ids),
        )
    }

    private fun deleteContactReceipts(ids: List<EntityID<Long>>): Int =
        if (ids.isEmpty()) 0 else ContactDecisionReceipts.deleteWhere {
            ContactDecisionReceipts.id inList ids
        }

    private fun deleteInviteReceipts(ids: List<EntityID<Long>>): Int =
        if (ids.isEmpty()) 0 else InviteLinkCreationReceipts.deleteWhere {
            InviteLinkCreationReceipts.id inList ids
        }

    private fun deleteDocumentPolicyReceipts(ids: List<Long>): Int =
        if (ids.isEmpty()) 0 else DocumentSpacePolicyCommands.deleteWhere {
            DocumentSpacePolicyCommands.retentionId inList ids
        }

    private fun deleteDocumentNodeMoveReceipts(ids: List<Long>): Int =
        if (ids.isEmpty()) 0 else DocumentNodeMoveCommands.deleteWhere {
            DocumentNodeMoveCommands.retentionId inList ids
        }
}

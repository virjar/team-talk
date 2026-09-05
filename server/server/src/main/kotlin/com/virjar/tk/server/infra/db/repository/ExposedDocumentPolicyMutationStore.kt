package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.document.DocumentCapacityPolicy
import com.virjar.tk.server.domain.document.DocumentPolicyMutationCommit
import com.virjar.tk.server.domain.document.DocumentPolicyMutationFence
import com.virjar.tk.server.domain.document.DocumentPolicyMutationReceipt
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentSpacePolicyCommands
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.protocol.model.UserRole
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/** 可靠文档 ACL 命令的固定锁与不可变回执存储。 */
internal class ExposedDocumentPolicyMutationStore {
    fun lockFence(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
        requiredUserIds: Set<String>,
    ): DocumentPolicyMutationFence = transaction.inExposedTransaction {
        val addressedUserIds = (requiredUserIds + actorUid).sorted()
        val users = Users.selectAll().where {
            Users.uid inList addressedUserIds
        }.orderBy(Users.uid to SortOrder.ASC).forUpdate()
            .associateBy { row -> row[Users.uid] }
        val actor = users[actorUid]

        val spaceRow = DocumentSpaces.selectAll().where {
            DocumentSpaces.spaceId eq spaceId
        }.forUpdate().singleOrNull()
        DocumentPolicyMutationFence(
            actorIsActiveHuman = actor != null &&
                actor[Users.role] == UserRole.HUMAN &&
                actor[Users.status] == STATUS_ACTIVE,
            space = spaceRow?.toDocumentSpace(),
            spaceIsActive = spaceRow?.get(DocumentSpaces.status) == STATUS_ACTIVE,
        )
    }

    fun findReceipt(
        transaction: PgReadTransactionContext,
        actorUid: String,
        operationId: String,
    ): DocumentPolicyMutationReceipt? = transaction.inExposedReadTransaction {
        DocumentSpacePolicyCommands.selectAll().where {
            (DocumentSpacePolicyCommands.actorUid eq actorUid) and
                (DocumentSpacePolicyCommands.operationId eq operationId)
        }.singleOrNull()?.let { row ->
            val receiptIssuedAt = row[DocumentSpacePolicyCommands.issuedAt]
            val receiptExpiresAt = row[DocumentSpacePolicyCommands.expiresAt]
            check(receiptExpiresAt == ReliableCommandPolicy.expiresAt(receiptIssuedAt)) {
                "Document policy receipt lifetime is inconsistent"
            }
            DocumentPolicyMutationReceipt(
                actorUid = row[DocumentSpacePolicyCommands.actorUid],
                operationId = row[DocumentSpacePolicyCommands.operationId],
                spaceId = row[DocumentSpacePolicyCommands.spaceId],
                fingerprint = row[DocumentSpacePolicyCommands.fingerprint],
                fromPolicyRevision = row[DocumentSpacePolicyCommands.fromPolicyRevision],
                resultingPolicyRevision = row[DocumentSpacePolicyCommands.resultingPolicyRevision],
                issuedAt = receiptIssuedAt,
                expiresAt = receiptExpiresAt,
            )
        }
    }

    fun pruneExpiredPolicyMutationReceiptsAndRequireCapacity(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        nowMillis: Long,
    ) = transaction.inExposedTransaction {
        require(nowMillis >= 0L) { "服务器时钟非法" }
        DocumentSpacePolicyCommands.deleteWhere {
            (DocumentSpacePolicyCommands.actorUid eq actorUid) and
                (DocumentSpacePolicyCommands.expiresAt less nowMillis)
        }
        val retained = DocumentSpacePolicyCommands.selectAll().where {
            DocumentSpacePolicyCommands.actorUid eq actorUid
        }.count()
        if (retained >= DocumentCapacityPolicy.MAX_POLICY_MUTATION_RECEIPTS_PER_ACTOR.toLong()) {
            throw ReliableCommandCapacityException("文档权限可靠重试窗口已满")
        }
    }

    /** 调用方已在此同一事务内应用了授权增量。 */
    fun commitRevisionAndReceipt(
        transaction: PgWriteTransactionContext,
        command: DocumentPolicyMutationCommit,
    ) = transaction.inExposedTransaction {
        require(command.fromPolicyRevision > 0L) { "文档权限版本非法" }
        require(command.issuedAt >= 0L) { "文档权限操作签发时间非法" }
        if (command.changed) {
            require(
                command.fromPolicyRevision < Long.MAX_VALUE &&
                    command.resultingPolicyRevision == command.fromPolicyRevision + 1L,
            ) { "文档权限结果版本非法" }
            val updated = DocumentSpaces.update({
                (DocumentSpaces.spaceId eq command.spaceId) and
                    (DocumentSpaces.status eq STATUS_ACTIVE) and
                    (DocumentSpaces.policyRevision eq command.fromPolicyRevision)
            }) {
                it[policyRevision] = command.resultingPolicyRevision
                it[updatedAt] = command.createdAt
            }
            if (updated != 1) throw ReliableCommandConflictException("文档空间权限已被其他操作更新")
        } else {
            require(command.resultingPolicyRevision == command.fromPolicyRevision) {
                "无变化的文档权限命令不能推进版本"
            }
            val current = DocumentSpaces.selectAll().where {
                (DocumentSpaces.spaceId eq command.spaceId) and
                    (DocumentSpaces.status eq STATUS_ACTIVE)
            }.forUpdate().singleOrNull()
            if (current?.get(DocumentSpaces.policyRevision) != command.fromPolicyRevision) {
                throw ReliableCommandConflictException("文档空间权限已被其他操作更新")
            }
        }

        val inserted = DocumentSpacePolicyCommands.insertIgnore {
            it[actorUid] = command.actorUid
            it[operationId] = command.operationId
            it[spaceId] = command.spaceId
            it[mutationType] = command.kind.databaseValue
            it[fingerprint] = command.fingerprint
            it[fromPolicyRevision] = command.fromPolicyRevision
            it[resultingPolicyRevision] = command.resultingPolicyRevision
            it[issuedAt] = command.issuedAt
            it[expiresAt] = ReliableCommandPolicy.expiresAt(command.issuedAt)
            it[createdAt] = command.createdAt
        }.insertedCount == 1
        if (!inserted) {
            throw ReliableCommandConflictException("文档权限操作标识已用于其他请求")
        }
        if (command.changed) {
            // Grant 行、每空间 CAS 与不可变回执都已完成。此时才获取全局
            // 目录版本，使每个 Document 写入器共享同一个终结锁顺序。
            ExposedDocumentDirectoryRevision.advance(transaction, command.createdAt)
        }
    }

    private companion object {
        const val STATUS_ACTIVE = 1
    }
}

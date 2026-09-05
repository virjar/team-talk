package com.virjar.tk.server.infra.db.repository

import com.virjar.tk.server.domain.command.ReliableCommandCapacityException
import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentCapacityPolicy
import com.virjar.tk.server.domain.document.DocumentNodeMoveReceipt
import com.virjar.tk.server.domain.document.DocumentNotFoundException
import com.virjar.tk.server.domain.transaction.PgReadTransactionContext
import com.virjar.tk.server.domain.transaction.PgWriteTransactionContext
import com.virjar.tk.server.infra.db.DocumentNodeMoveCommands
import com.virjar.tk.server.infra.db.DocumentSpaces
import com.virjar.tk.server.infra.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll

/** 文档移动/重命名命令的 PostgreSQL fence 与有限回执存储。 */
internal class ExposedDocumentNodeMoveStore {
    fun lockFence(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        spaceId: String,
    ) = transaction.inExposedTransaction {
        Users.select(Users.uid).where { Users.uid eq actorUid }
            .forUpdate().singleOrNull() ?: throw DocumentAccessDeniedException("没有文档空间权限")
        DocumentSpaces.select(DocumentSpaces.spaceId).where { DocumentSpaces.spaceId eq spaceId }
            .forUpdate().singleOrNull() ?: throw DocumentNotFoundException("文档空间不存在")
    }

    fun findReceipt(
        transaction: PgReadTransactionContext,
        actorUid: String,
        operationId: String,
    ): DocumentNodeMoveReceipt? = transaction.inExposedReadTransaction {
        DocumentNodeMoveCommands.selectAll().where {
            (DocumentNodeMoveCommands.actorUid eq actorUid) and
                (DocumentNodeMoveCommands.operationId eq operationId)
        }.singleOrNull()?.let { row ->
            val issuedAt = row[DocumentNodeMoveCommands.issuedAt]
            val expiresAt = row[DocumentNodeMoveCommands.expiresAt]
            check(expiresAt == ReliableCommandPolicy.expiresAt(issuedAt)) {
                "Document node move receipt lifetime is inconsistent"
            }
            DocumentNodeMoveReceipt(
                actorUid = row[DocumentNodeMoveCommands.actorUid],
                operationId = row[DocumentNodeMoveCommands.operationId],
                spaceId = row[DocumentNodeMoveCommands.spaceId],
                nodeId = row[DocumentNodeMoveCommands.nodeId],
                fingerprint = row[DocumentNodeMoveCommands.fingerprint],
                fromRevision = row[DocumentNodeMoveCommands.fromRevision],
                resultingRevision = row[DocumentNodeMoveCommands.resultingRevision],
                issuedAt = issuedAt,
                expiresAt = expiresAt,
            )
        }
    }

    fun pruneExpiredAndRequireCapacity(
        transaction: PgWriteTransactionContext,
        actorUid: String,
        nowMillis: Long,
    ) = transaction.inExposedTransaction {
        require(nowMillis >= 0L) { "服务器时钟非法" }
        DocumentNodeMoveCommands.deleteWhere {
            (DocumentNodeMoveCommands.actorUid eq actorUid) and
                (DocumentNodeMoveCommands.expiresAt less nowMillis)
        }
        val retained = DocumentNodeMoveCommands.select(DocumentNodeMoveCommands.operationId).where {
            DocumentNodeMoveCommands.actorUid eq actorUid
        }.count()
        if (retained >= DocumentCapacityPolicy.MAX_NODE_MOVE_RECEIPTS_PER_ACTOR.toLong()) {
            throw ReliableCommandCapacityException("文档移动可靠重试窗口已满")
        }
    }

    fun append(
        transaction: PgWriteTransactionContext,
        receipt: DocumentNodeMoveReceipt,
        createdAt: Long,
    ) = transaction.inExposedTransaction {
        require(receipt.fromRevision > 0L && receipt.resultingRevision >= receipt.fromRevision) {
            "文档移动结果版本非法"
        }
        require(receipt.expiresAt == ReliableCommandPolicy.expiresAt(receipt.issuedAt) && createdAt >= 0L) {
            "文档移动收据期限非法"
        }
        val inserted = DocumentNodeMoveCommands.insertIgnore {
            it[actorUid] = receipt.actorUid
            it[operationId] = receipt.operationId
            it[spaceId] = receipt.spaceId
            it[nodeId] = receipt.nodeId
            it[fingerprint] = receipt.fingerprint
            it[fromRevision] = receipt.fromRevision
            it[resultingRevision] = receipt.resultingRevision
            it[issuedAt] = receipt.issuedAt
            it[expiresAt] = receipt.expiresAt
            it[DocumentNodeMoveCommands.createdAt] = createdAt
        }.insertedCount == 1
        if (!inserted) {
            throw ReliableCommandConflictException("文档移动操作标识已用于不同请求")
        }
    }
}

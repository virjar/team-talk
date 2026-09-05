package com.virjar.tk.server.domain.document

import com.virjar.tk.server.domain.command.ReliableCommandConflictException
import com.virjar.tk.server.domain.command.ReliableCommandPolicy
import com.virjar.tk.server.domain.command.canonicalOperationId
import com.virjar.tk.server.domain.command.reliableCommandFingerprint
import com.virjar.tk.protocol.model.DocumentMoveCommandResult
import com.virjar.tk.protocol.model.DocumentPolicy
import java.util.UUID

/**
 * 执行可靠的节点移动/重命名命令。
 *
 * 准入、实时授权、节点变更及其不可变回执被刻意保留在 [DocumentAccessControl] 拥有的一个
 * 写入事务内。精确重试在操作者到空间的命令围栏之后、当前 ACL 或修订检查之前被解析。
 */
internal class DocumentNodeMoveCommandHandler(
    private val repository: DocumentRepository,
    private val accessControl: DocumentAccessControl,
    private val wallClockMillis: () -> Long,
) {
    suspend fun execute(
        actorUid: String,
        spaceId: String,
        nodeId: String,
        parentId: String?,
        name: String,
        expectedRevision: Long,
        operationId: String,
        issuedAt: Long,
    ): DocumentMoveCommandResult {
        require(expectedRevision in 1 until Long.MAX_VALUE) { "节点版本非法" }
        val validatedSpaceId = validateResourceId(spaceId, "文档空间标识")
        val validatedNodeId = validateResourceId(nodeId, "文档节点标识")
        val validatedParentId = parentId?.let { validateResourceId(it, "父文档标识") }
        val validatedName = DocumentPolicy.normalizeNodeName(name)
        val validatedOperationId = canonicalOperationId(operationId, "文档移动")
        val fingerprint = reliableCommandFingerprint(
            "document-node-move-v1",
            actorUid,
            validatedSpaceId,
            validatedNodeId,
            validatedParentId,
            validatedName,
            expectedRevision.toString(),
            issuedAt.toString(),
        )

        return accessControl.moveNodeAuthorizedOrCompleted(
            actorUid = actorUid,
            spaceId = validatedSpaceId,
            alreadyCompleted = {
                val receipt = repository.findNodeMoveReceipt(transaction, actorUid, validatedOperationId)
                if (receipt != null) {
                    if (
                        receipt.spaceId != validatedSpaceId ||
                        receipt.nodeId != validatedNodeId ||
                        receipt.fingerprint != fingerprint
                    ) {
                        throw ReliableCommandConflictException("文档移动操作标识已用于不同请求")
                    }
                    ReliableCommandPolicy.requireActiveIssuedAt(
                        issuedAt,
                        wallClockMillis(),
                        "文档移动操作",
                    )
                    DocumentMoveCommandResult(
                        operationId = validatedOperationId,
                        result = null,
                    )
                } else {
                    val admittedAt = wallClockMillis()
                    ReliableCommandPolicy.requireActiveIssuedAt(issuedAt, admittedAt, "文档移动操作")
                    repository.pruneExpiredNodeMoveReceiptsAndRequireCapacity(transaction, actorUid, admittedAt)
                    null
                }
            },
        ) { _, _ ->
            require(validatedParentId != validatedNodeId) { "文档不能移动到自身" }
            val committedAt = wallClockMillis()
            ReliableCommandPolicy.requireActiveIssuedAt(issuedAt, committedAt, "文档移动操作")
            val moved = repository.moveNode(
                transaction,
                validatedSpaceId,
                validatedNodeId,
                expectedRevision,
                validatedParentId,
                validatedName,
                actorUid,
                committedAt,
            )
            repository.appendNodeMoveReceipt(
                transaction,
                DocumentNodeMoveReceipt(
                    actorUid = actorUid,
                    operationId = validatedOperationId,
                    spaceId = validatedSpaceId,
                    nodeId = validatedNodeId,
                    fingerprint = fingerprint,
                    fromRevision = expectedRevision,
                    resultingRevision = moved.node.revision,
                    issuedAt = issuedAt,
                    expiresAt = ReliableCommandPolicy.expiresAt(issuedAt),
                ),
                committedAt,
            )
            DocumentMoveCommandResult(validatedOperationId, moved)
        }
    }

    private fun validateResourceId(value: String, label: String): String {
        val canonicalUuid = runCatching { UUID.fromString(value).toString() }.getOrNull()
        require(value.length == UUID_TEXT_LENGTH && canonicalUuid == value) {
            "$label 非法"
        }
        return value
    }

    private companion object {
        const val UUID_TEXT_LENGTH = 36
    }
}

package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.DocumentPolicy
import com.virjar.tk.protocol.ReliableCommandContract
import java.util.UUID

/** 一个不可变文档 move/rename，保留直到其精确服务器结果已知。 */
data class PendingDocumentMoveCommand(
    val operationId: String,
    val spaceId: String,
    val nodeId: String,
    /** 本地收敛提示；服务器指纹从 [targetParentId] 开始。 */
    val oldParentId: String?,
    val targetParentId: String?,
    val name: String,
    val expectedRevision: Long,
    val issuedAt: Long,
) {
    val targetKey: String get() = "$spaceId:$nodeId"

    fun hasSamePayload(other: PendingDocumentMoveCommand): Boolean =
        spaceId == other.spaceId && nodeId == other.nodeId &&
            oldParentId == other.oldParentId && targetParentId == other.targetParentId &&
            name == other.name && expectedRevision == other.expectedRevision

    fun requireCanonical(): PendingDocumentMoveCommand {
        check(operationId.isCanonicalUuid()) { "Pending document move operation id is invalid" }
        check(spaceId.isCanonicalUuid()) { "Pending document move space id is invalid" }
        check(nodeId.isCanonicalUuid()) { "Pending document move node id is invalid" }
        check(oldParentId == null || oldParentId.isCanonicalUuid()) {
            "Pending document move old parent id is invalid"
        }
        check(targetParentId == null || targetParentId.isCanonicalUuid()) {
            "Pending document move target parent id is invalid"
        }
        check(nodeId != targetParentId) { "A document cannot move below itself" }
        check(expectedRevision in 1L until Long.MAX_VALUE) {
            "Pending document move revision is invalid"
        }
        check(issuedAt >= 0L) { "Pending document move issued time is invalid" }
        check(name == DocumentPolicy.normalizeNodeName(name)) {
            "Pending document move name is not canonical"
        }
        return this
    }

    companion object {
        fun create(
            operationId: String = UUID.randomUUID().toString(),
            spaceId: String,
            nodeId: String,
            oldParentId: String?,
            targetParentId: String?,
            name: String,
            expectedRevision: Long,
            issuedAt: Long,
        ): PendingDocumentMoveCommand = PendingDocumentMoveCommand(
            operationId = operationId,
            spaceId = spaceId,
            nodeId = nodeId,
            oldParentId = oldParentId,
            targetParentId = targetParentId,
            name = DocumentPolicy.normalizeNodeName(name),
            expectedRevision = expectedRevision,
            issuedAt = issuedAt,
        ).requireCanonical()

        internal fun restore(
            operationId: String,
            spaceId: String,
            nodeId: String,
            oldParentId: String?,
            targetParentId: String?,
            name: String,
            expectedRevision: Long,
            issuedAt: Long,
        ): PendingDocumentMoveCommand = PendingDocumentMoveCommand(
            operationId,
            spaceId,
            nodeId,
            oldParentId,
            targetParentId,
            name,
            expectedRevision,
            issuedAt,
        ).requireCanonical()
    }
}

class PendingDocumentMoveCommandConflictException(message: String) : IllegalStateException(message)

internal fun nextDocumentMoveCommandExpiryAt(
    commands: List<PendingDocumentMoveCommand>,
): Long? = commands.minOfOrNull { ReliableCommandContract.firstExpiredAt(it.issuedAt) }

const val MAX_PENDING_DOCUMENT_MOVE_COMMANDS = 256

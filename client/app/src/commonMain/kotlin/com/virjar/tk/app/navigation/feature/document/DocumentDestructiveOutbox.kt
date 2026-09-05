package com.virjar.tk.app.navigation.feature.document

import java.util.UUID

internal const val MAX_DOCUMENT_DESTRUCTIVE_INTENTS = 512

/**
 * 一个可持久化的、不可变的破坏性文档修改意图。
 *
 * [operationId] 标识一个准入纪元而不是远程资源，适合用作未来的服务端幂等 key。
 * 集成层必须在完成或取消一个纪元之前持久地给它立墓碑，这样之后的用户动作
 * 才会使用新的 operation ID 和恢复 key。
 */
internal sealed interface DocumentDestructiveIntent {
    val operationId: String
    val spaceId: String
}

/** 归档一个确切的文档空间。 */
internal data class PendingDocumentSpaceArchiveIntent(
    override val operationId: String,
    override val spaceId: String,
) : DocumentDestructiveIntent

/** 使用 RPC 开始之前捕获的服务器 revision，删除一个叶子文档。 */
internal data class PendingDocumentLeafDeleteIntent(
    override val operationId: String,
    override val spaceId: String,
    val documentId: String,
    val parentId: String?,
    val expectedRevision: Long,
) : DocumentDestructiveIntent

/** 一个破坏性准入纪元的稳定持久化身份。 */
internal fun DocumentDestructiveIntent.draftRecoveryKey(): String =
    "document-destructive-command-$operationId"

/** 返回一个规范的、持久化安全的副本；对无效的恢复记录返回 null。 */
internal fun DocumentDestructiveIntent.normalized(): DocumentDestructiveIntent? {
    return when (this) {
        is PendingDocumentSpaceArchiveIntent -> {
            val canonicalOperationId = operationId.canonicalDestructiveUuidOrNull() ?: return null
            val canonicalSpaceId = spaceId.canonicalDestructiveUuidOrNull() ?: return null
            copy(operationId = canonicalOperationId, spaceId = canonicalSpaceId)
        }

        is PendingDocumentLeafDeleteIntent -> {
            val canonicalOperationId = operationId.canonicalDestructiveUuidOrNull() ?: return null
            val canonicalSpaceId = spaceId.canonicalDestructiveUuidOrNull() ?: return null
            val canonicalDocumentId = documentId.canonicalDestructiveUuidOrNull() ?: return null
            val canonicalParentId = parentId?.canonicalDestructiveUuidOrNull()
                ?: parentId?.let { return null }
            if (expectedRevision <= 0L || canonicalParentId == canonicalDocumentId) return null
            copy(
                operationId = canonicalOperationId,
                spaceId = canonicalSpaceId,
                documentId = canonicalDocumentId,
                parentId = canonicalParentId,
            )
        }
    }
}

/**
 * 线程安全的、session 拥有的、由持久草稿存储镜像的破坏性命令索引。
 *
 * 相同的获取返回同一个冻结对象。对于一个已经挂起的文档，不同的删除 revision 或 parent
 * 会被拒绝，而不是在载荷可能已经到达服务器之后静默地改变它。[restore] 原子地用一份
 * 持久快照中无歧义的部分替换索引；完全相同的重复会折叠，而每一个卷入 operation-ID
 * 或逻辑目标冲突的记录都会被 fail-closed 地丢弃。
 *
 * outbox 刻意不执行任何 I/O。在获取之后、启动 RPC 之前持久化并 flush [pending]。
 * 在 [complete] 或 [cancel] 之前，持久地给该意图的 [draftRecoveryKey] 立墓碑。
 */
internal class DocumentDestructiveOutbox(
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private val intentsByOperationId = linkedMapOf<String, DocumentDestructiveIntent>()
    private val operationIdsByTarget = mutableMapOf<DocumentDestructiveTarget, String>()

    val capacity: Int
        get() = MAX_DOCUMENT_DESTRUCTIVE_INTENTS

    fun acquireArchive(spaceId: String): PendingDocumentSpaceArchiveIntent = synchronized(lock) {
        val canonicalSpaceId = requireCanonicalDestructiveUuid(spaceId, "文档空间标识")
        val target = DocumentDestructiveTarget.ArchiveSpace(canonicalSpaceId)
        existingForTarget(target)?.let { existing ->
            check(existing is PendingDocumentSpaceArchiveIntent) {
                "文档空间归档目标已属于其他破坏性操作"
            }
            return@synchronized existing
        }
        requireNewIntentCapacity()
        admit(
            target,
            PendingDocumentSpaceArchiveIntent(
                operationId = nextOperationId(),
                spaceId = canonicalSpaceId,
            ),
        )
    }

    fun acquireDeleteLeaf(
        spaceId: String,
        documentId: String,
        parentId: String?,
        expectedRevision: Long,
    ): PendingDocumentLeafDeleteIntent = synchronized(lock) {
        val candidate = PendingDocumentLeafDeleteIntent(
            operationId = "",
            spaceId = spaceId,
            documentId = documentId,
            parentId = parentId,
            expectedRevision = expectedRevision,
        ).normalizedPayload()
        val target = candidate.target()
        existingForTarget(target)?.let { existing ->
            check(
                existing is PendingDocumentLeafDeleteIntent &&
                    existing.sameFrozenDelete(candidate),
            ) {
                "文档删除目标已存在不同的冻结意图"
            }
            return@synchronized existing
        }
        requireNewIntentCapacity()
        admit(target, candidate.copy(operationId = nextOperationId()))
    }

    /** 即使所有新槽位都被占用，容量预检仍允许幂等 retry。 */
    fun canAcquireArchive(spaceId: String): Boolean = synchronized(lock) {
        val canonicalSpaceId = spaceId.canonicalDestructiveUuidOrNull() ?: return@synchronized false
        val target = DocumentDestructiveTarget.ArchiveSpace(canonicalSpaceId)
        target in operationIdsByTarget || intentsByOperationId.size < capacity
    }

    /** 容量预检基于目标；冻结载荷相等性由 acquire 强制执行。 */
    fun canAcquireDeleteLeaf(spaceId: String, documentId: String): Boolean = synchronized(lock) {
        val canonicalSpaceId = spaceId.canonicalDestructiveUuidOrNull() ?: return@synchronized false
        val canonicalDocumentId = documentId.canonicalDestructiveUuidOrNull()
            ?: return@synchronized false
        val target = DocumentDestructiveTarget.DeleteLeaf(canonicalSpaceId, canonicalDocumentId)
        target in operationIdsByTarget || intentsByOperationId.size < capacity
    }

    /** 只移除服务器确认的那个确切的命令。 */
    fun complete(intent: DocumentDestructiveIntent): Boolean = synchronized(lock) {
        removeExact(intent)
    }

    /** 只移除在 RPC 变得权威之前被显式取消的那个确切的命令。 */
    fun cancel(intent: DocumentDestructiveIntent): Boolean = synchronized(lock) {
        removeExact(intent)
    }

    fun contains(intent: DocumentDestructiveIntent): Boolean = synchronized(lock) {
        intentsByOperationId[intent.operationId] == intent
    }

    fun pending(): List<DocumentDestructiveIntent> = synchronized(lock) {
        intentsByOperationId.values.toList()
    }

    /**
     * 不可变的、按插入顺序的快照，可以在重启或未知 RPC 结果之后安全 retry。
     */
    fun replay(): List<DocumentDestructiveIntent> = synchronized(lock) {
        intentsByOperationId.values.toList()
    }

    fun pendingArchives(): List<PendingDocumentSpaceArchiveIntent> = synchronized(lock) {
        intentsByOperationId.values.filterIsInstance<PendingDocumentSpaceArchiveIntent>()
    }

    fun pendingLeafDeletes(): List<PendingDocumentLeafDeleteIntent> = synchronized(lock) {
        intentsByOperationId.values.filterIsInstance<PendingDocumentLeafDeleteIntent>()
    }

    /**
     * 用一份持久快照替换热索引。无效和歧义的记录被忽略。
     * 当无歧义快照超过全局容量上限时，返回 false 且不改变当前索引。
     */
    fun restore(persisted: List<DocumentDestructiveIntent>): Boolean = synchronized(lock) {
        val unambiguous = normalizeDocumentDestructiveIntents(persisted)
            ?: return@synchronized false

        intentsByOperationId.clear()
        operationIdsByTarget.clear()
        unambiguous.forEach { intent ->
            intentsByOperationId[intent.operationId] = intent
            operationIdsByTarget[intent.target()] = intent.operationId
        }
        true
    }

    private fun existingForTarget(target: DocumentDestructiveTarget): DocumentDestructiveIntent? =
        operationIdsByTarget[target]?.let(intentsByOperationId::get)

    private fun <T : DocumentDestructiveIntent> admit(
        target: DocumentDestructiveTarget,
        intent: T,
    ): T {
        check(intentsByOperationId.size < capacity) {
            "待处理文档破坏性操作过多"
        }
        check(intent.operationId !in intentsByOperationId) {
            "文档破坏性操作标识已被占用"
        }
        intentsByOperationId[intent.operationId] = intent
        operationIdsByTarget[target] = intent.operationId
        return intent
    }

    private fun removeExact(intent: DocumentDestructiveIntent): Boolean {
        val current = intentsByOperationId[intent.operationId] ?: return false
        if (current != intent) return false
        intentsByOperationId.remove(intent.operationId)
        val target = current.target()
        if (operationIdsByTarget[target] == current.operationId) {
            operationIdsByTarget.remove(target)
        }
        return true
    }

    private fun requireNewIntentCapacity() {
        check(intentsByOperationId.size < capacity) {
            "待处理文档破坏性操作过多"
        }
    }

    private fun nextOperationId(): String =
        requireCanonicalDestructiveUuid(newOperationId(), "文档破坏性操作标识")
}

/** 热恢复和持久快照校验共享的规范 fail-closed 投影。 */
internal fun normalizeDocumentDestructiveIntents(
    persisted: List<DocumentDestructiveIntent>,
): List<DocumentDestructiveIntent>? {
    val distinct = persisted.mapNotNull(DocumentDestructiveIntent::normalized).distinct()
    val operationCounts = distinct.groupingBy(DocumentDestructiveIntent::operationId).eachCount()
    val targetCounts = distinct.groupingBy(DocumentDestructiveIntent::target).eachCount()
    return distinct.filter { intent ->
        operationCounts[intent.operationId] == 1 && targetCounts[intent.target()] == 1
    }.takeIf { it.size <= MAX_DOCUMENT_DESTRUCTIVE_INTENTS }
}

private sealed interface DocumentDestructiveTarget {
    data class ArchiveSpace(val spaceId: String) : DocumentDestructiveTarget
    data class DeleteLeaf(val spaceId: String, val documentId: String) : DocumentDestructiveTarget
}

private fun DocumentDestructiveIntent.target(): DocumentDestructiveTarget = when (this) {
    is PendingDocumentSpaceArchiveIntent -> DocumentDestructiveTarget.ArchiveSpace(spaceId)
    is PendingDocumentLeafDeleteIntent -> DocumentDestructiveTarget.DeleteLeaf(spaceId, documentId)
}

private fun PendingDocumentLeafDeleteIntent.normalizedPayload(): PendingDocumentLeafDeleteIntent {
    val canonicalSpaceId = requireCanonicalDestructiveUuid(spaceId, "文档空间标识")
    val canonicalDocumentId = requireCanonicalDestructiveUuid(documentId, "文档标识")
    val canonicalParentId = parentId?.let { requireCanonicalDestructiveUuid(it, "父文档标识") }
    require(expectedRevision > 0L) { "文档删除版本必须大于 0" }
    require(canonicalParentId != canonicalDocumentId) { "文档不能是自己的父节点" }
    return copy(
        spaceId = canonicalSpaceId,
        documentId = canonicalDocumentId,
        parentId = canonicalParentId,
    )
}

private fun PendingDocumentLeafDeleteIntent.sameFrozenDelete(
    other: PendingDocumentLeafDeleteIntent,
): Boolean = spaceId == other.spaceId && documentId == other.documentId &&
    parentId == other.parentId && expectedRevision == other.expectedRevision

private fun requireCanonicalDestructiveUuid(value: String, label: String): String =
    requireNotNull(value.canonicalDestructiveUuidOrNull()) { "${label}必须是规范 UUID" }

private fun String.canonicalDestructiveUuidOrNull(): String? = try {
    UUID.fromString(this).toString().takeIf { it == this }
} catch (_: IllegalArgumentException) {
    null
}

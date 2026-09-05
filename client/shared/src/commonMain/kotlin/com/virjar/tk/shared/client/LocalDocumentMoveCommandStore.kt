package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries

/** 文档 move/rename 命令的小型持久单槽存储。 */
internal class LocalDocumentMoveCommandStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) {
    private var state: DocumentMoveStoreState = loadState()

    fun prepare(candidate: PendingDocumentMoveCommand): PendingDocumentMoveCommand = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = healthyLocked()
            val canonical = candidate.requireCanonical()
            healthy.byTarget[canonical.targetKey]?.let { existing ->
                if (!existing.hasSamePayload(canonical)) {
                    throw PendingDocumentMoveCommandConflictException(
                        "该文档已有一项位置或名称变更等待确认",
                    )
                }
                return@synchronized existing
            }
            if (healthy.byOperation.containsKey(canonical.operationId)) {
                throw PendingDocumentMoveCommandConflictException("文档变更操作标识已用于其他请求")
            }
            check(healthy.byOperation.size < MAX_PENDING_DOCUMENT_MOVE_COMMANDS) {
                "待确认文档位置或名称变更数量已达上限"
            }
            queries.insertPendingDocumentMoveCommand(
                canonical.operationId,
                canonical.spaceId,
                canonical.nodeId,
                canonical.oldParentId,
                canonical.targetParentId,
                canonical.name,
                canonical.expectedRevision,
                canonical.issuedAt,
            )
            healthy.byTarget[canonical.targetKey] = canonical
            healthy.byOperation[canonical.operationId] = canonical
            canonical
        }
    }

    fun snapshot(): List<PendingDocumentMoveCommand> = cacheUseGate.use {
        synchronized(stateLock) { healthyLocked().byOperation.values.toList() }
    }

    fun clear(operationId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = healthyLocked()
            val existing = healthy.byOperation[operationId] ?: return@synchronized false
            queries.deletePendingDocumentMoveCommand(operationId)
            healthy.byOperation.remove(operationId)
            healthy.byTarget.remove(existing.targetKey)
            true
        }
    }

    private fun loadState(): DocumentMoveStoreState = try {
        val commands = queries.selectPendingDocumentMoveCommands().executeAsList().map { row ->
            PendingDocumentMoveCommand.restore(
                operationId = row.operation_id,
                spaceId = row.space_id,
                nodeId = row.node_id,
                oldParentId = row.old_parent_id,
                targetParentId = row.target_parent_id,
                name = row.name,
                expectedRevision = row.expected_revision,
                issuedAt = row.issued_at,
            )
        }
        check(commands.size <= MAX_PENDING_DOCUMENT_MOVE_COMMANDS) {
            "Persisted document move outbox exceeds its fixed capacity"
        }
        val byTarget = commands.associateByTo(linkedMapOf(), PendingDocumentMoveCommand::targetKey)
        check(byTarget.size == commands.size) { "Persisted document move targets are duplicated" }
        val byOperation = commands.associateByTo(
            linkedMapOf(),
            PendingDocumentMoveCommand::operationId,
        )
        check(byOperation.size == commands.size) { "Persisted document move operation ids are duplicated" }
        DocumentMoveStoreState.Healthy(byTarget, byOperation)
    } catch (corrupt: IllegalArgumentException) {
        DocumentMoveStoreState.Poisoned(CorruptDocumentMoveCommandOutboxException(corrupt))
    } catch (corrupt: IllegalStateException) {
        DocumentMoveStoreState.Poisoned(CorruptDocumentMoveCommandOutboxException(corrupt))
    }

    private fun healthyLocked(): DocumentMoveStoreState.Healthy = when (val current = state) {
        is DocumentMoveStoreState.Healthy -> current
        is DocumentMoveStoreState.Poisoned -> throw current.failure
    }
}

internal class CorruptDocumentMoveCommandOutboxException(cause: Throwable) : IllegalStateException(
    "本地文档位置变更待办损坏，已禁止覆盖未知操作",
    cause,
)

private sealed interface DocumentMoveStoreState {
    data class Healthy(
        val byTarget: LinkedHashMap<String, PendingDocumentMoveCommand>,
        val byOperation: LinkedHashMap<String, PendingDocumentMoveCommand>,
    ) : DocumentMoveStoreState

    data class Poisoned(
        val failure: CorruptDocumentMoveCommandOutboxException,
    ) : DocumentMoveStoreState
}

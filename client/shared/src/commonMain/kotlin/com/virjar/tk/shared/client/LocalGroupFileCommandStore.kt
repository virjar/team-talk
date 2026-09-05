package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries
import com.virjar.tk.protocol.model.Attachment

/** 面向网络结果不明确的群文件变更的有界持久可靠发件箱。 */
internal class LocalGroupFileCommandStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
    private val limits: LocalOutboxLimits,
) {
    private var state: StoreState = loadState()

    fun prepare(candidate: PendingGroupFileCommand): PendingGroupFileCommand = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = healthyLocked()
            val canonical = candidate.requireCanonical()
            healthy.byIntent[canonical.intentKey]?.let { existing ->
                if (!existing.hasSameIntentPayload(canonical)) {
                    throw PendingGroupFileCommandConflictException(
                        "该群文件位置已有另一项结果未确认的操作",
                    )
                }
                return@synchronized existing
            }
            if (healthy.byCommand.containsKey(canonical.commandId)) {
                throw PendingGroupFileCommandConflictException("群文件命令标识已用于其他请求")
            }
            if (healthy.byCommand.size >= limits.groupFileCommandCount) {
                localOutboxCapacityExceeded(
                    LocalOutboxKind.GROUP_FILE_COMMAND,
                    LocalOutboxCapacityDimension.ENTRY_COUNT,
                    limits.groupFileCommandCount.toLong(),
                )
            }
            if (canonical.payloadBytes > limits.groupFileCommandBytes - healthy.totalPayloadBytes) {
                localOutboxCapacityExceeded(
                    LocalOutboxKind.GROUP_FILE_COMMAND,
                    LocalOutboxCapacityDimension.STORED_BYTES,
                    limits.groupFileCommandBytes,
                )
            }
            val attachment = canonical.attachment
            queries.insertPendingGroupFileCommand(
                canonical.commandId,
                canonical.intentKey,
                canonical.kind.code,
                canonical.entryId,
                canonical.chatId,
                canonical.parentId,
                canonical.name,
                attachment?.path,
                attachment?.name,
                attachment?.contentType,
                attachment?.size,
                canonical.expectedRevision,
                canonical.createdAt,
                canonical.payloadBytes,
            )
            healthy.byIntent[canonical.intentKey] = canonical
            healthy.byCommand[canonical.commandId] = canonical
            healthy.totalPayloadBytes += canonical.payloadBytes
            canonical
        }
    }

    fun snapshot(): List<PendingGroupFileCommand> = cacheUseGate.use {
        synchronized(stateLock) { healthyLocked().byCommand.values.toList() }
    }

    fun clear(commandId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val healthy = healthyLocked()
            val existing = healthy.byCommand[commandId] ?: return@synchronized false
            queries.deletePendingGroupFileCommand(commandId)
            healthy.byCommand.remove(commandId)
            healthy.byIntent.remove(existing.intentKey)
            healthy.totalPayloadBytes -= existing.payloadBytes
            check(healthy.totalPayloadBytes >= 0L) { "Group-file outbox byte accounting underflow" }
            true
        }
    }

    private fun loadState(): StoreState {
        val rows = queries.selectPendingGroupFileCommands().executeAsList()
        return try {
            check(rows.size <= MAX_PENDING_GROUP_FILE_COMMANDS) {
                "Persisted group-file command outbox exceeds its fixed capacity"
            }
            val commands = rows.map { row ->
                val attachment = row.attachment_path?.let { path ->
                    Attachment(
                        path = path,
                        name = checkNotNull(row.attachment_name),
                        contentType = checkNotNull(row.attachment_content_type),
                        size = checkNotNull(row.attachment_size),
                    )
                }
                check((attachment == null) == (row.attachment_name == null)) {
                    "Persisted group-file attachment fields are incomplete"
                }
                PendingGroupFileCommand.restore(
                    commandId = row.command_id,
                    intentKey = row.intent_key,
                    kind = PendingGroupFileCommandKind.fromCode(row.command_kind),
                    entryId = row.entry_id,
                    chatId = row.chat_id,
                    parentId = row.parent_id,
                    name = row.name,
                    attachment = attachment,
                    expectedRevision = row.expected_revision,
                    createdAt = row.created_at,
                    payloadBytes = row.payload_bytes,
                )
            }
            val byIntent = commands.associateByTo(linkedMapOf(), PendingGroupFileCommand::intentKey)
            check(byIntent.size == commands.size) { "Persisted group-file intents are duplicated" }
            val byCommand = commands.associateByTo(linkedMapOf(), PendingGroupFileCommand::commandId)
            check(byCommand.size == commands.size) { "Persisted group-file command ids are duplicated" }
            val totalBytes = commands.sumOf(PendingGroupFileCommand::payloadBytes)
            check(totalBytes <= MAX_PENDING_GROUP_FILE_COMMAND_STORED_BYTES) {
                "Persisted group-file command outbox exceeds its byte capacity"
            }
            StoreState.Healthy(byIntent, byCommand, totalBytes)
        } catch (corrupt: IllegalArgumentException) {
            StoreState.Poisoned(CorruptGroupFileCommandOutboxException(corrupt))
        } catch (corrupt: IllegalStateException) {
            StoreState.Poisoned(CorruptGroupFileCommandOutboxException(corrupt))
        }
    }

    private fun healthyLocked(): StoreState.Healthy = when (val current = state) {
        is StoreState.Healthy -> current
        is StoreState.Poisoned -> throw current.failure
    }
}

internal class CorruptGroupFileCommandOutboxException(cause: Throwable) : IllegalStateException(
    "本地群文件可靠命令记录损坏，已禁止覆盖未知操作",
    cause,
)

private sealed interface StoreState {
    data class Healthy(
        val byIntent: LinkedHashMap<String, PendingGroupFileCommand>,
        val byCommand: LinkedHashMap<String, PendingGroupFileCommand>,
        var totalPayloadBytes: Long,
    ) : StoreState

    data class Poisoned(val failure: CorruptGroupFileCommandOutboxException) : StoreState
}

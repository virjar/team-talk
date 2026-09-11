package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries

/** 单槽持久存储；第二个未知的凭据变更绝不能覆盖它。 */
internal class LocalGroupBotCredentialCommandStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) {
    private val slot =
        PendingCommandSlot(
            load = {
                queries.selectPendingGroupBotCredentialCommand().executeAsOneOrNull()?.let { row ->
                    PendingGroupBotCredentialCommand.restore(
                        operationId = row.operation_id,
                        ownerUid = row.owner_uid,
                        kindCode = row.command_kind,
                        chatId = row.chat_id,
                        botId = row.bot_id,
                        name = row.name,
                        webhookToken = row.webhook_token,
                    )
                }
            },
            corrupt = ::CorruptPendingGroupBotCredentialCommandException,
        )

    fun get(): PendingGroupBotCredentialCommand? = cacheUseGate.use {
        synchronized(stateLock) { slot.healthy() }
    }

    fun prepare(command: PendingGroupBotCredentialCommand): PendingGroupBotCredentialCommand = cacheUseGate.use {
        synchronized(stateLock) {
            val canonical = command.requireCanonical()
            slot.healthy()?.let { existing ->
                if (!existing.hasSameIntent(canonical)) {
                    throw PendingGroupBotCredentialCommandConflictException()
                }
                return@synchronized existing
            }
            queries.insertPendingGroupBotCredentialCommand(
                canonical.operationId,
                canonical.ownerUid,
                canonical.kind.code,
                canonical.chatId,
                canonical.botId,
                canonical.name,
                canonical.webhookToken,
            )
            slot.update(canonical)
            canonical
        }
    }

    fun clearIfOperation(operationId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val current = slot.healthy() ?: return@synchronized false
            if (current.operationId != operationId) return@synchronized false
            queries.deletePendingGroupBotCredentialCommand(operationId)
            slot.update(null)
            true
        }
    }
}

internal class CorruptPendingGroupBotCredentialCommandException(cause: Throwable) : IllegalStateException(
    "本地待处理群机器人凭据命令损坏，已禁止覆盖该未知操作",
    cause,
)

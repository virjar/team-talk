package com.virjar.tk.shared.client

import com.virjar.tk.shared.database.AppDatabaseQueries

/** 单槽持久存储；第二个未知的凭据变更绝不能覆盖它。 */
internal class LocalGroupBotCredentialCommandStore(
    private val queries: AppDatabaseQueries,
    private val cacheUseGate: CacheUseGate,
    private val stateLock: Any,
) {
    private var state: GroupBotCredentialCommandStoreState =
        queries.selectPendingGroupBotCredentialCommand().executeAsOneOrNull()?.let { row ->
            try {
                GroupBotCredentialCommandStoreState.Healthy(
                    PendingGroupBotCredentialCommand.restore(
                        operationId = row.operation_id,
                        ownerUid = row.owner_uid,
                        kindCode = row.command_kind,
                        chatId = row.chat_id,
                        botId = row.bot_id,
                        name = row.name,
                        webhookToken = row.webhook_token,
                    ),
                )
            } catch (corrupt: IllegalStateException) {
                GroupBotCredentialCommandStoreState.Poisoned(
                    CorruptPendingGroupBotCredentialCommandException(corrupt),
                )
            }
        } ?: GroupBotCredentialCommandStoreState.Healthy(null)

    fun get(): PendingGroupBotCredentialCommand? = cacheUseGate.use {
        synchronized(stateLock) { healthyStateLocked().pending }
    }

    fun prepare(command: PendingGroupBotCredentialCommand): PendingGroupBotCredentialCommand = cacheUseGate.use {
        synchronized(stateLock) {
            val canonical = command.requireCanonical()
            healthyStateLocked().pending?.let { existing ->
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
            state = GroupBotCredentialCommandStoreState.Healthy(canonical)
            canonical
        }
    }

    fun clearIfOperation(operationId: String): Boolean = cacheUseGate.use {
        synchronized(stateLock) {
            val current = healthyStateLocked().pending ?: return@synchronized false
            if (current.operationId != operationId) return@synchronized false
            queries.deletePendingGroupBotCredentialCommand(operationId)
            state = GroupBotCredentialCommandStoreState.Healthy(null)
            true
        }
    }

    private fun healthyStateLocked(): GroupBotCredentialCommandStoreState.Healthy = when (val current = state) {
        is GroupBotCredentialCommandStoreState.Healthy -> current
        is GroupBotCredentialCommandStoreState.Poisoned -> throw current.failure
    }
}

internal class CorruptPendingGroupBotCredentialCommandException(cause: Throwable) : IllegalStateException(
    "本地待处理群机器人凭据命令损坏，已禁止覆盖该未知操作",
    cause,
)

private sealed interface GroupBotCredentialCommandStoreState {
    data class Healthy(val pending: PendingGroupBotCredentialCommand?) : GroupBotCredentialCommandStoreState
    data class Poisoned(val failure: CorruptPendingGroupBotCredentialCommandException) :
        GroupBotCredentialCommandStoreState
}

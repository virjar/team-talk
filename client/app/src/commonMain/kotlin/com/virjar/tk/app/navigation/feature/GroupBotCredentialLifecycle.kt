package com.virjar.tk.app.navigation.feature

import com.virjar.tk.shared.Outcome
import com.virjar.tk.protocol.http.GroupBotCredentials
import com.virjar.tk.app.navigation.UiLocalDataBoundary
import com.virjar.tk.shared.repository.GroupBotManagementRepository
import com.virjar.tk.shared.repository.PendingGroupBotCredentialRecovery
import com.virjar.tk.shared.repository.RecoveredGroupBotCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GroupBotCredentialRecoverySnapshot(
    val recovered: RecoveredGroupBotCredentials?,
    val pending: PendingGroupBotCredentialRecovery?,
    val failure: Throwable?,
)

/** 客户端持有的一次性机器人凭据的 session 级恢复/发布边界。 */
internal class GroupBotCredentialLifecycle(
    private val repository: GroupBotManagementRepository,
    private val localData: UiLocalDataBoundary,
    private val isOwnerActive: () -> Boolean,
    private val publishSnapshot: (GroupBotCredentialRecoverySnapshot) -> Unit,
) {
    private val lifecycleMutex = Mutex()
    private var publishedSnapshot = EMPTY_SNAPSHOT
    private var retiredOperationId: String? = null

    /** 返回错误和当前脱敏身份；成功的恢复仍然等待确认。 */
    suspend fun recover(): GroupBotCredentialRecoverySnapshot = lifecycleMutex.withLock {
        val snapshot = try {
            val (recovery, pending) = localData.run {
                repository.recoverPendingCredential() to repository.pendingCredentialRecovery()
            }
            when (recovery) {
                is Outcome.Failure -> failureSnapshot(recovery.error, pending)
                is Outcome.Success -> recoveredSnapshot(recovery.value, pending)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // 本地存储/编解码失败不能证明先前发布的凭据或脱敏的挂起身份消失了。
            // 保留两者并附上新的失败。
            publishedSnapshot.copy(failure = failure)
        }
        publishLocked(snapshot)
    }

    /**
     * 通过同一个串行化生命周期发布前台 create/rotate 的结果。
     * 持久的身份在持有锁的情况下重新读取，因此更早的确认或显式放弃
     * 不可能被迟到的命令延续撤销。
     */
    suspend fun publishCommandResult(
        chatId: String,
        result: Outcome<GroupBotCredentials>,
    ): GroupBotCredentialRecoverySnapshot = lifecycleMutex.withLock {
        val snapshot = try {
            val pending = localData.run { repository.pendingCredentialRecovery() }
            when (result) {
                is Outcome.Failure -> failureSnapshot(result.error, pending)
                is Outcome.Success -> {
                    val recovered = RecoveredGroupBotCredentials(chatId, result.value)
                    if (pending == null && retiredOperationId == recovered.credentials.operationId) {
                        publishedSnapshot
                    } else {
                        recoveredSnapshot(recovered, pending)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            publishedSnapshot.copy(failure = failure)
        }
        publishLocked(snapshot)
    }

    /** 只有显式的凭据对话框动作进入这个有条件的本地提交。 */
    suspend fun acknowledge(credentials: GroupBotCredentials): Boolean = lifecycleMutex.withLock {
        if (!isOwnerActive()) return false
        val cleared = localData.run {
            repository.acknowledgeCredential(credentials.operationId)
        }
        if (cleared && isOwnerActive()) {
            retiredOperationId = credentials.operationId
            publishLocked(EMPTY_SNAPSHOT)
            true
        } else {
            false
        }
    }

    /** 结果不确定命令的显式破坏性逃生舱；绝不隐式调用。 */
    suspend fun abandon(pending: PendingGroupBotCredentialRecovery): Boolean = lifecycleMutex.withLock {
        if (!isOwnerActive()) return false
        val cleared = localData.run {
            repository.abandonPendingCredential(pending.operationId)
        }
        if (cleared && isOwnerActive()) {
            retiredOperationId = pending.operationId
            publishLocked(EMPTY_SNAPSHOT)
            true
        } else {
            false
        }
    }

    private fun recoveredSnapshot(
        recovered: RecoveredGroupBotCredentials?,
        pending: PendingGroupBotCredentialRecovery?,
    ): GroupBotCredentialRecoverySnapshot {
        val inconsistency = groupBotCredentialRecoveryInconsistency(recovered, pending)
        return if (inconsistency == null) {
            GroupBotCredentialRecoverySnapshot(recovered, pending, failure = null)
        } else {
            failureSnapshot(inconsistency, pending)
        }
    }

    private fun failureSnapshot(
        failure: Throwable,
        pending: PendingGroupBotCredentialRecovery?,
    ) = GroupBotCredentialRecoverySnapshot(
        recovered = publishedSnapshot.recovered?.takeIf { recovered ->
            pending?.operationId == recovered.credentials.operationId
        },
        pending = pending,
        failure = failure,
    )

    private fun publishLocked(
        snapshot: GroupBotCredentialRecoverySnapshot,
    ): GroupBotCredentialRecoverySnapshot {
        if (!isOwnerActive()) return EMPTY_SNAPSHOT
        publishedSnapshot = snapshot
        publishSnapshot(snapshot)
        return snapshot
    }

    private companion object {
        val EMPTY_SNAPSHOT = GroupBotCredentialRecoverySnapshot(
            recovered = null,
            pending = null,
            failure = null,
        )
    }
}

internal fun groupBotCredentialRecoveryInconsistency(
    recovered: RecoveredGroupBotCredentials?,
    pending: PendingGroupBotCredentialRecovery?,
): IllegalStateException? {
    if (recovered == null && pending == null) return null
    if (
        recovered == null || pending == null ||
        recovered.chatId != pending.chatId ||
        recovered.credentials.operationId != pending.operationId ||
        (pending.botId != null && recovered.credentials.bot.botId != pending.botId)
    ) {
        return IllegalStateException("群机器人凭据恢复结果与本地待确认记录不一致")
    }
    return null
}

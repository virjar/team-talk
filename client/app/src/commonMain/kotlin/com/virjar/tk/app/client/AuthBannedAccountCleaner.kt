package com.virjar.tk.app.client

import com.virjar.tk.shared.client.AccountDataCleanup
import com.virjar.tk.shared.client.AccountDataOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 封禁账号数据清理的编排：保存清理标记必须先于清除任何凭据（写标记失败保留凭据供
 * 下次重新确认封禁）；删除序列在不可取消的 IO 边界上执行，数据、凭据全部清理完成后
 * 才解除标记。begin 的普通失败以返回值交给控制器决定后续，不在这里抛出。
 */
internal class AuthBannedAccountCleaner(
    private val cleanup: AccountDataCleanup?,
    private val clearBannedCredentials: (AccountDataOwner) -> Unit,
    private val beforeDelete: suspend (AccountDataOwner) -> Unit,
) {
    /** 写入清理标记；配置缺失或标记失败返回异常，账号数据与凭据保持原样。 */
    fun begin(owner: AccountDataOwner): Throwable? = try {
        checkNotNull(cleanup) { "Account cleanup is not configured" }
        cleanup.begin(owner)
        null
    } catch (failure: Exception) {
        failure
    }

    /** 不可取消地删除该 owner 的本地资料与凭据；致命生命周期缺陷原样传播，其余返回 false。 */
    suspend fun delete(owner: AccountDataOwner): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        val cleanupInstance = cleanup ?: return@withContext false
        try {
            beforeDelete(owner)
            cleanupInstance.deleteOwnedData(owner)
            clearBannedCredentials(owner)
            cleanupInstance.complete(owner)
            true
        } catch (failure: Throwable) {
            if (isFatalClientLifecycleFailure(failure)) throw failure
            false
        }
    }

    /** 在控制器 scope 中执行删除并回调结果；结果映射（封禁状态迁移）由控制器完成。 */
    fun launchDelete(
        scope: CoroutineScope,
        owner: AccountDataOwner,
        onOutcome: (cleared: Boolean) -> Unit,
    ) {
        scope.launch { onOutcome(delete(owner)) }
    }
}

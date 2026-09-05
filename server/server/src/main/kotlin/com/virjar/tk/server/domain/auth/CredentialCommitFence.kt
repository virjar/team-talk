package com.virjar.tk.server.domain.auth

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 把一次不可逆的凭证变更及其进程本地会话围栏作为一个终结性的编排阶段来执行。
 * 一旦 [commit] 开始，调用方取消就不能落在成功的 PostgreSQL 提交与 [publishFence] 之间。
 * 进程崩溃是安全的，因为没有任何旧 TCP 会话能在进程重启后存活。
 */
internal suspend fun <T> commitCredentialMutationAndFence(
    commit: suspend () -> T,
    publishFence: suspend (T) -> Unit,
): T = withContext(NonCancellable) {
    commit().also { committed -> publishFence(committed) }
}

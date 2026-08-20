package com.virjar.tk.domain.auth

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs an irreversible credential mutation and its process-local session fence as one terminal
 * orchestration stage. Once [commit] starts, caller cancellation cannot land between a successful
 * PostgreSQL commit and [publishFence]. A process crash is safe because no old TCP session survives
 * process restart.
 */
internal suspend fun <T> commitCredentialMutationAndFence(
    commit: suspend () -> T,
    publishFence: suspend (T) -> Unit,
): T = withContext(NonCancellable) {
    commit().also { committed -> publishFence(committed) }
}

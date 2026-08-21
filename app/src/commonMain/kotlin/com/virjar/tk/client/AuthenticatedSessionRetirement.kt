package com.virjar.tk.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Enters retirement before the first dispatcher hop and always runs an idempotent close fallback,
 * including when the owning Compose scope was already cancelled before launch.
 */
internal fun CoroutineScope.launchRetirementWithFallback(
    fallback: () -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val job = launch(start = CoroutineStart.UNDISPATCHED, block = block)
    job.invokeOnCompletion { runCatching(fallback) }
    return job
}

/**
 * Runs a platform/UI retirement pair around the controller's local session boundary.
 * Hook failures are diagnostic only: a broken shell must never prevent the credential/cache owner
 * from reaching its terminal state. [after] is still invoked when [before] or [retirement] fails.
 */
internal fun <T> withAuthenticatedSessionRetirementBoundary(
    before: () -> Unit,
    retirement: () -> T,
    after: () -> Unit,
    onHookFailure: (stage: String, failure: Throwable) -> Unit = { _, _ -> },
): T {
    try {
        before()
    } catch (failure: Throwable) {
        runCatching { onHookFailure("before", failure) }
    }
    return try {
        retirement()
    } finally {
        try {
            after()
        } catch (failure: Throwable) {
            runCatching { onHookFailure("after", failure) }
        }
    }
}

/** AUTH_FAILED is terminal even before a ClientSession exists; always release the raw socket. */
internal inline fun retireAuthFailureAndDisconnect(
    endSession: () -> Unit,
    disconnectTransport: () -> Unit,
) {
    try {
        endSession()
    } finally {
        disconnectTransport()
    }
}

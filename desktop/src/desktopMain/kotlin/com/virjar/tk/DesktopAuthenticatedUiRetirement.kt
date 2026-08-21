package com.virjar.tk

import com.virjar.tk.client.ClientSession
import com.virjar.tk.client.SessionEndReason
import com.virjar.tk.util.AppLog
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Desktop's synchronous authenticated-session retirement boundary.
 *
 * The navigation graph owns ViewModels that still call LocalCache from destroy hooks, while the
 * platform resource root owns media jobs that still borrow session credentials. Both owners must
 * retire before AuthController is allowed to quiesce ClientSession and close LocalCache. The
 * controller calls [beforeSessionRetirement] before its local linearization point and always calls
 * [afterSessionRetirement] in finally. Concurrent followers join CLOSING and cannot return until
 * both the platform owners and controller session boundary have completed.
 */
internal class DesktopAuthenticatedUiRetirement(
    private val destroyNavigation: () -> Unit,
    private val closePlatformResources: () -> Unit,
) {
    private val lifecycleLock = ReentrantLock()
    private val retirementCompleted = lifecycleLock.newCondition()
    private var phase = DesktopAuthenticatedUiRetirementPhase.OPEN
    private var leaderThread: Thread? = null
    private var terminalFailures = emptyList<Throwable>()

    fun beforeSessionRetirement() {
        val leader = lifecycleLock.withLock {
            when (phase) {
                DesktopAuthenticatedUiRetirementPhase.OPEN -> {
                    phase = DesktopAuthenticatedUiRetirementPhase.CLOSING
                    leaderThread = Thread.currentThread()
                    true
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSING -> {
                    // AuthController does not re-enter its own boundary. Keep this guard so an
                    // accidental same-thread callback cannot deadlock while still making every
                    // genuinely concurrent follower join terminal completion.
                    if (leaderThread === Thread.currentThread()) return
                    while (phase == DesktopAuthenticatedUiRetirementPhase.CLOSING) {
                        retirementCompleted.awaitUninterruptibly()
                    }
                    false
                }
                DesktopAuthenticatedUiRetirementPhase.CLOSED -> false
            }
        }
        if (!leader) return

        val failures = mutableListOf<Throwable>()
        fun release(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                failures += failure
            }
        }

        release(destroyNavigation)
        release(closePlatformResources)
        lifecycleLock.withLock { terminalFailures = failures.toList() }
    }

    fun afterSessionRetirement() {
        val failures = lifecycleLock.withLock {
            if (phase != DesktopAuthenticatedUiRetirementPhase.CLOSING) return
            phase = DesktopAuthenticatedUiRetirementPhase.CLOSED
            leaderThread = null
            retirementCompleted.signalAll()
            terminalFailures
        }
        failures.firstOrNull()?.let { failure ->
            AppLog.fault(
                "DesktopRetirement",
                "Authenticated UI retirement completed with ${failures.size} cleanup failure(s)",
                failure,
            )
        }
    }
}

private enum class DesktopAuthenticatedUiRetirementPhase { OPEN, CLOSING, CLOSED }

/**
 * Stable bridge created before AuthController and bound after the session-scoped Desktop owner is
 * ready. An in-flight binding survives Compose disposal until the matching after hook arrives.
 */
internal class DesktopAuthenticatedUiRetirementBridge {
    private val lock = Any()
    private var activeBinding: Binding? = null
    private val inFlight = mutableListOf<Binding>()

    fun bind(session: ClientSession, retirement: DesktopAuthenticatedUiRetirement): Closeable {
        val binding = Binding(session, retirement)
        synchronized(lock) { activeBinding = binding }
        return Closeable {
            synchronized(lock) {
                if (activeBinding === binding) activeBinding = null
            }
        }
    }

    fun beforeSessionRetirement(session: ClientSession, reason: SessionEndReason) {
        val retirement = synchronized(lock) {
            val existing = inFlight.firstOrNull { it.session === session }
            if (existing != null) return@synchronized existing.retirement
            val active = activeBinding?.takeIf { it.session === session } ?: return@synchronized null
            inFlight += active
            active.retirement
        }
        retirement?.beforeSessionRetirement()
    }

    fun afterSessionRetirement(session: ClientSession, reason: SessionEndReason) {
        val retirement = synchronized(lock) {
            val index = inFlight.indexOfFirst { it.session === session }
            if (index >= 0) inFlight.removeAt(index).retirement
            else activeBinding?.takeIf { it.session === session }?.retirement
        }
        retirement?.afterSessionRetirement()
    }

    private data class Binding(
        val session: ClientSession,
        val retirement: DesktopAuthenticatedUiRetirement,
    )
}

/**
 * Session-scoped Desktop owner that closes the circular lifecycle boundary deliberately:
 * [DesktopNav] reports authentication expiry back into the retirement owner that destroys it.
 */
internal class DesktopAuthenticatedUiOwner(
    session: ClientSession,
    closePlatformResources: () -> Unit,
    requestAuthExpired: () -> Unit,
) {
    val navigation = DesktopNav(session, requestAuthExpired)
    val retirement = DesktopAuthenticatedUiRetirement(
        destroyNavigation = { navigation.destroy(clearComposerContexts = true) },
        closePlatformResources = closePlatformResources,
    )
}

/**
 * Compose disposal is a follower of the authenticated retirement boundary. [closeResources] may
 * therefore replay the exact terminal failure already observed by the retirement leader. Disposal
 * must record that platform failure without throwing it back through ComposeScene teardown.
 */
internal fun disposeDesktopAuthenticatedResources(
    closeResources: () -> Unit,
    recordFailure: (Throwable) -> Unit = { failure ->
        AppLog.fault(
            "DesktopRetirement",
            "Authenticated resource disposal replayed a terminal close failure",
            failure,
        )
    },
) {
    val failure = runCatching(closeResources).exceptionOrNull() ?: return
    runCatching { recordFailure(failure) }
}

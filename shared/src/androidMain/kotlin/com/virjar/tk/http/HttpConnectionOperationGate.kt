package com.virjar.tk.http

import com.virjar.tk.client.SessionBoundaryReentrantCloseException
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Android counterpart of the JVM hard close-return boundary for bearer HTTP operations. */
class HttpConnectionOperationGate(
    private val ownerName: String,
) {
    private val lock = ReentrantLock()
    private val stateChanged = lock.newCondition()
    private val operations = linkedSetOf<Operation>()
    private val currentOperations = ThreadLocal<MutableMap<Operation, Int>>()

    private var phase = Phase.OPEN
    private var closingThread: Thread? = null
    private var disconnectSweepComplete = false
    private val closeFailures = mutableListOf<Throwable>()
    private var completedCloseFailure: HttpConnectionCloseException? = null

    fun register(
        connection: HttpURLConnection,
        onRejectedDisconnectFailure: (Throwable) -> Unit = {},
        closedFailure: () -> Throwable,
    ): Operation {
        lock.withLock {
            if (phase == Phase.OPEN) {
                return Operation(connection, closedFailure).also(operations::add)
            }
        }
        val failure = closedFailure()
        disconnectRejected(connection)?.let { cleanupFailure ->
            runCatching { onRejectedDisconnectFailure(cleanupFailure) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }

    fun close() {
        val role = lock.withLock {
            when (phase) {
                Phase.OPEN -> {
                    phase = Phase.CLOSING
                    closingThread = Thread.currentThread()
                    CloseRole.Leader(operations.toList(), currentOperationSnapshot())
                }

                Phase.CLOSING -> {
                    if (closingThread === Thread.currentThread() || isInsideOperation()) {
                        CloseRole.Reentrant
                    } else {
                        CloseRole.Follower
                    }
                }

                Phase.CLOSED -> CloseRole.Complete
            }
        }
        when (role) {
            is CloseRole.Leader -> closeAsLeader(role)
            CloseRole.Follower -> awaitCompletedClose()?.let { throw it }
            CloseRole.Complete -> completedFailure()?.let { throw it }
            CloseRole.Reentrant -> throw HttpConnectionReentrantCloseException(ownerName)
        }
    }

    fun completedCloseFailureOrNull(): Throwable? = lock.withLock {
        if (phase == Phase.CLOSED) completedCloseFailure else null
    }

    private fun closeAsLeader(role: CloseRole.Leader) {
        role.operations.forEach { operation ->
            if (operation in role.reentrantOperations) return@forEach
            operation.ensureDisconnected()?.let(::recordCloseFailure)
        }
        val reentrantFailure = lock.withLock {
            disconnectSweepComplete = true
            closingThread = null
            completeCloseIfDrainedLocked()
            stateChanged.signalAll()
            if (role.reentrantOperations.isNotEmpty()) {
                HttpConnectionReentrantCloseException(ownerName)
            } else {
                null
            }
        }
        if (reentrantFailure != null) {
            completedFailure()?.let(reentrantFailure::addSuppressed)
            throw reentrantFailure
        }
        awaitCompletedClose()?.let { throw it }
    }

    private fun awaitCompletedClose(): HttpConnectionCloseException? = lock.withLock {
        while (phase != Phase.CLOSED) stateChanged.awaitUninterruptibly()
        completedCloseFailure
    }

    private fun completedFailure(): HttpConnectionCloseException? = lock.withLock {
        completedCloseFailure
    }

    private fun recordCloseFailure(failure: Throwable) = lock.withLock {
        if (closeFailures.none { it === failure }) closeFailures += failure
    }

    private fun completeCloseIfDrainedLocked() {
        if (phase != Phase.CLOSING || !disconnectSweepComplete || operations.isNotEmpty()) return
        completedCloseFailure = if (closeFailures.isEmpty()) {
            null
        } else {
            HttpConnectionCloseException(ownerName, closeFailures.toList())
        }
        phase = Phase.CLOSED
        stateChanged.signalAll()
    }

    private fun isInsideOperation(): Boolean = !currentOperations.get().isNullOrEmpty()

    private fun currentOperationSnapshot(): Set<Operation> =
        currentOperations.get()?.keys?.toSet().orEmpty()

    private fun enterOperationThread(operation: Operation) {
        val depths = currentOperations.get() ?: mutableMapOf<Operation, Int>().also(currentOperations::set)
        depths[operation] = (depths[operation] ?: 0) + 1
    }

    private fun leaveOperationThread(operation: Operation) {
        val depths = currentOperations.get() ?: return
        val remaining = (depths[operation] ?: 1) - 1
        if (remaining == 0) depths -= operation else depths[operation] = remaining
        if (depths.isEmpty()) currentOperations.remove()
    }

    inner class Operation internal constructor(
        private val connection: HttpURLConnection,
        private val closedFailure: () -> Throwable,
    ) {
        private var disconnectPhase = DisconnectPhase.PENDING
        private var disconnectThread: Thread? = null
        private var disconnectFailure: Throwable? = null

        fun <T> execute(
            beforeFirstIoAdmission: () -> Unit = {},
            block: () -> T,
        ): T {
            enterOperationThread(this)
            return try {
                executeBody(beforeFirstIoAdmission, block)
            } finally {
                leaveOperationThread(this)
            }
        }

        suspend fun <T> executeSuspending(
            additionalContext: CoroutineContext = EmptyCoroutineContext,
            beforeFirstIoAdmission: () -> Unit = {},
            block: suspend () -> T,
        ): T {
            var executionEntered = false
            var dispatchFailure: Throwable? = null
            try {
                return withContext(OperationThreadContext() + additionalContext) {
                    executionEntered = true
                    executeBodySuspending(beforeFirstIoAdmission, block)
                }
            } catch (failure: Throwable) {
                dispatchFailure = failure
                throw failure
            } finally {
                if (!executionEntered) finish(dispatchFailure)
            }
        }

        fun abort() {
            ensureDisconnected()
        }

        private fun <T> executeBody(
            beforeFirstIoAdmission: () -> Unit,
            block: () -> T,
        ): T {
            var primaryFailure: Throwable? = null
            try {
                beforeFirstIoAdmission()
                requireFirstIoAdmission()
                return block()
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                finish(primaryFailure)
            }
        }

        private suspend fun <T> executeBodySuspending(
            beforeFirstIoAdmission: () -> Unit,
            block: suspend () -> T,
        ): T {
            var primaryFailure: Throwable? = null
            try {
                beforeFirstIoAdmission()
                requireFirstIoAdmission()
                return block()
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                finish(primaryFailure)
            }
        }

        private fun requireFirstIoAdmission() {
            lock.withLock {
                if (phase != Phase.OPEN) throw closedFailure()
            }
        }

        private fun finish(primaryFailure: Throwable?) {
            val cleanupFailure = ensureDisconnected()
            cleanupFailure?.let(::recordCloseFailure)
            lock.withLock {
                operations -= this
                completeCloseIfDrainedLocked()
                stateChanged.signalAll()
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    if (primaryFailure !== cleanupFailure) primaryFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }

        internal fun ensureDisconnected(): Throwable? {
            val shouldDisconnect = lock.withLock {
                when (disconnectPhase) {
                    DisconnectPhase.PENDING -> {
                        disconnectPhase = DisconnectPhase.RUNNING
                        disconnectThread = Thread.currentThread()
                        true
                    }

                    DisconnectPhase.RUNNING -> {
                        if (disconnectThread === Thread.currentThread()) {
                            return HttpConnectionReentrantDisconnectException(ownerName)
                        }
                        while (disconnectPhase == DisconnectPhase.RUNNING) {
                            stateChanged.awaitUninterruptibly()
                        }
                        return disconnectFailure
                    }

                    DisconnectPhase.DONE -> return disconnectFailure
                }
            }
            check(shouldDisconnect)
            val failure = runCatching(connection::disconnect).exceptionOrNull()
            lock.withLock {
                disconnectFailure = failure
                disconnectThread = null
                disconnectPhase = DisconnectPhase.DONE
                stateChanged.signalAll()
            }
            return failure
        }

        private inner class OperationThreadContext :
            ThreadContextElement<Unit>,
            AbstractCoroutineContextElement(OperationContextKey()) {
            override fun updateThreadContext(context: CoroutineContext) {
                enterOperationThread(this@Operation)
            }

            override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
                leaveOperationThread(this@Operation)
            }
        }
    }

    private fun disconnectRejected(connection: HttpURLConnection): Throwable? =
        runCatching(connection::disconnect).exceptionOrNull()

    private enum class Phase { OPEN, CLOSING, CLOSED }
    private enum class DisconnectPhase { PENDING, RUNNING, DONE }

    private sealed interface CloseRole {
        data class Leader(
            val operations: List<Operation>,
            val reentrantOperations: Set<Operation>,
        ) : CloseRole

        data object Follower : CloseRole
        data object Complete : CloseRole
        data object Reentrant : CloseRole
    }

    private class OperationContextKey : CoroutineContext.Key<CoroutineContext.Element>
}

interface HttpConnectionReentrantCloseFailure

internal class HttpConnectionReentrantCloseException(ownerName: String) :
    SessionBoundaryReentrantCloseException(
        "$ownerName cannot close reentrantly from an admitted HTTP operation",
    ), HttpConnectionReentrantCloseFailure

private class HttpConnectionReentrantDisconnectException(ownerName: String) : IllegalStateException(
    "$ownerName attempted to join its own in-progress HTTP disconnect",
)

internal class HttpConnectionCloseException(
    ownerName: String,
    failures: List<Throwable>,
) : IllegalStateException("$ownerName failed to disconnect ${failures.size} HTTP connection(s)") {
    init {
        failures.forEach(::addSuppressed)
    }
}

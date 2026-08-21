package com.virjar.tk

import com.virjar.tk.client.SessionHttpCredentials
import com.virjar.tk.client.DeploymentIdentity
import com.virjar.tk.http.HttpConnectionOperationGate
import com.virjar.tk.http.HttpConnectionReentrantCloseFailure
import com.virjar.tk.repository.FileRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Opaque, token-independent cache namespace for one authenticated deployment account. */
internal fun mediaCacheNamespace(
    deploymentIdentity: DeploymentIdentity,
    ownerUid: String,
): String {
    require(ownerUid.isNotBlank()) { "media owner uid must not be empty" }
    return sha256Hex(
        "teamtalk-media-v3\u0000${deploymentIdentity.fingerprint}\u0000uid\u0000$ownerUid",
    ).take(32)
}

/**
 * Immutable credentials captured when an authenticated Android UI session is composed.
 *
 * Every blocking operation is registered here. Closing the owner first seals callbacks and final
 * cache publication, then synchronously disconnects or closes all bearer-bearing resources.
 */
class AndroidMediaSession private constructor(
    val deploymentIdentity: DeploymentIdentity,
    private val ownerUid: String,
    private val ownerIdentityEpoch: Long,
    private val credentialsProvider: () -> SessionHttpCredentials,
    private val connectionFactory: (String) -> HttpURLConnection,
    private val beforeHttpGateRegistration: () -> Unit,
    private val beforeFirstIo: () -> Unit,
    private val afterHttpGateClose: () -> Unit,
    val cacheNamespace: String,
) : AutoCloseable {
    val serverUrl: String = deploymentIdentity.httpBaseUrl
    private val lifecycleLock = ReentrantLock()
    private val operationsDrained = lifecycleLock.newCondition()
    private var phase = AndroidMediaClosePhase.OPEN
    private var closingThread: Thread? = null
    private var closeWorkComplete = false
    private val closeFailures = mutableListOf<Throwable>()
    private var completedCloseFailure: Throwable? = null
    private val activeOperations = mutableSetOf<AndroidMediaOperationLease>()
    private val operationContext = ThreadLocal<Set<AndroidMediaOperationLease>?>()
    private val connectionOperations = HttpConnectionOperationGate("Android media HTTP")
    internal val fileRepository = FileRepository(serverUrl, ownerUid, credentialsProvider)

    /** Reads a rotated token while rejecting a replacement identity, even when the uid is reused. */
    fun accessTokenForRequest(): String = lifecycleLock.withLock {
        check(phase == AndroidMediaClosePhase.OPEN) { "媒体会话已经关闭" }
        val credentials = credentialsProvider()
        requireCurrentOwner(credentials)
        credentials.accessToken?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("认证凭据不可用，请重新登录")
    }

    fun isCurrentOwner(): Boolean = lifecycleLock.withLock {
        phase == AndroidMediaClosePhase.OPEN && runCatching { requireCurrentOwner(credentialsProvider()) }.isSuccess
    }

    internal fun ensureOpen() = lifecycleLock.withLock {
        check(phase == AndroidMediaClosePhase.OPEN) { "媒体会话已经关闭" }
        requireCurrentOwner(credentialsProvider())
    }

    private fun registerOperation(abort: () -> Unit): AndroidMediaOperationLease {
        val lease = AndroidMediaOperationLease(this, abort)
        try {
            lifecycleLock.withLock {
                check(phase == AndroidMediaClosePhase.OPEN) { "媒体会话已经关闭" }
                requireCurrentOwner(credentialsProvider())
                activeOperations += lease
            }
        } catch (failure: Throwable) {
            runCatching(abort).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        return lease
    }

    internal suspend fun <T> withRegisteredOperation(
        abort: () -> Unit,
        block: suspend () -> T,
    ): T {
        val lease = registerOperation(abort)
        return try {
            withContext(operationContextElement(lease)) {
                ensureOpen()
                block()
            }
        } finally {
            lease.close()
        }
    }

    internal suspend fun <T> withAuthenticatedConnection(
        url: String,
        configure: (HttpURLConnection) -> Unit = {},
        block: suspend (HttpURLConnection) -> T,
    ): T {
        val accessToken = accessTokenForRequest()
        val connection = connectionFactory(url)
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.instanceFollowRedirects = false
        configure(connection)
        val sessionLease = try {
            // The HTTP gate owns the physical disconnect. This outer lease only keeps the complete
            // media owner in CLOSING until the gate operation has left.
            registerOperation {}
        } catch (failure: Throwable) {
            runCatching(connection::disconnect).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        val httpOperation = try {
            beforeHttpGateRegistration()
            connectionOperations.register(
                connection = connection,
                closedFailure = { IllegalStateException("媒体会话已经关闭") },
                onRejectedDisconnectFailure = ::recordCloseFailure,
            )
        } catch (failure: Throwable) {
            sessionLease.close()
            throw failure
        }
        return try {
            withCancellationAbort(httpOperation::abort) {
                httpOperation.executeSuspending(
                    additionalContext = operationContextElement(sessionLease),
                    beforeFirstIoAdmission = {
                        beforeFirstIo()
                        ensureOpen()
                    },
                ) {
                    connection.connect()
                    block(connection)
                }
            }
        } finally {
            connectionOperations.completedCloseFailureOrNull()?.let(::recordCloseFailure)
            sessionLease.close()
        }
    }

    /** Progress, UI callbacks, and final cache publication share the close linearization monitor. */
    internal fun runIfOpen(block: () -> Unit): Boolean = lifecycleLock.withLock {
        if (phase != AndroidMediaClosePhase.OPEN) return@withLock false
        if (runCatching { requireCurrentOwner(credentialsProvider()) }.isFailure) return@withLock false
        block()
        true
    }

    internal fun installCacheFile(partial: File, target: File): Unit = lifecycleLock.withLock {
        check(phase == AndroidMediaClosePhase.OPEN) { "媒体会话已经关闭" }
        requireCurrentOwner(credentialsProvider())
        try {
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }

    override fun close() {
        val decision = lifecycleLock.withLock {
            when (phase) {
                AndroidMediaClosePhase.OPEN -> {
                    phase = AndroidMediaClosePhase.CLOSING
                    closingThread = Thread.currentThread()
                    val currentOperations = operationContext.get().orEmpty()
                    AndroidMediaCloseDecision.Leader(
                        operations = activeOperations.toList(),
                        currentOperations = currentOperations,
                        reentrant = currentOperations.isNotEmpty(),
                    )
                }

                AndroidMediaClosePhase.CLOSING -> {
                    if (closingThread === Thread.currentThread() || operationContext.get().orEmpty().isNotEmpty()) {
                        AndroidMediaCloseDecision.Reentrant
                    } else {
                        AndroidMediaCloseDecision.Follower
                    }
                }

                AndroidMediaClosePhase.CLOSED -> AndroidMediaCloseDecision.Complete
            }
        }

        when (decision) {
            is AndroidMediaCloseDecision.Leader -> closeAsLeader(decision)
            AndroidMediaCloseDecision.Follower -> awaitCompletedClose()?.let { throw it }
            AndroidMediaCloseDecision.Complete -> lifecycleLock.withLock {
                completedCloseFailure
            }?.let { throw it }
            AndroidMediaCloseDecision.Reentrant -> throw AndroidMediaReentrantCloseException()
        }
    }

    private fun closeAsLeader(decision: AndroidMediaCloseDecision.Leader) {
        val failures = decision.operations
            .asSequence()
            .filterNot(decision.currentOperations::contains)
            .mapNotNull(AndroidMediaOperationLease::abort)
            .toMutableList()
        val httpCloseFailure = runCatching(connectionOperations::close).exceptionOrNull()
        if (httpCloseFailure != null && httpCloseFailure !is HttpConnectionReentrantCloseFailure) {
            failures += httpCloseFailure
        }
        runCatching(afterHttpGateClose).exceptionOrNull()?.let(failures::add)
        runCatching(fileRepository::close).exceptionOrNull()?.let(failures::add)
        val boundaryFailure = if (decision.reentrant) AndroidMediaReentrantCloseException() else null
        httpCloseFailure?.let { failure -> boundaryFailure?.addSuppressed(failure) }
        failures.forEach { failure ->
            if (failure !== httpCloseFailure) boundaryFailure?.addSuppressed(failure)
        }
        lifecycleLock.withLock {
            failures.forEach(::recordCloseFailureLocked)
            closeWorkComplete = true
            closingThread = null
            completeCloseIfDrainedLocked()
            operationsDrained.signalAll()
        }
        boundaryFailure?.let { throw it }
        awaitCompletedClose()?.let { throw it }
    }

    companion object {
        fun create(
            deploymentIdentity: DeploymentIdentity,
            ownerUid: String,
            credentialsProvider: () -> SessionHttpCredentials,
        ): AndroidMediaSession = create(
            deploymentIdentity = deploymentIdentity,
            ownerUid = ownerUid,
            credentialsProvider = credentialsProvider,
            connectionFactory = { raw -> URL(raw).openConnection() as HttpURLConnection },
            beforeHttpGateRegistration = {},
            beforeFirstIo = {},
            afterHttpGateClose = {},
        )

        internal fun create(
            deploymentIdentity: DeploymentIdentity,
            ownerUid: String,
            credentialsProvider: () -> SessionHttpCredentials,
            connectionFactory: (String) -> HttpURLConnection,
            beforeHttpGateRegistration: () -> Unit = {},
            beforeFirstIo: () -> Unit = {},
            afterHttpGateClose: () -> Unit = {},
        ): AndroidMediaSession {
            val initial = credentialsProvider()
            check(initial.uid == ownerUid) { "媒体会话初始认证身份不匹配" }
            return AndroidMediaSession(
                deploymentIdentity = deploymentIdentity,
                ownerUid = ownerUid,
                ownerIdentityEpoch = initial.identityEpoch,
                credentialsProvider = credentialsProvider,
                connectionFactory = connectionFactory,
                beforeHttpGateRegistration = beforeHttpGateRegistration,
                beforeFirstIo = beforeFirstIo,
                afterHttpGateClose = afterHttpGateClose,
                cacheNamespace = mediaCacheNamespace(deploymentIdentity, ownerUid),
            )
        }
    }

    private fun requireCurrentOwner(credentials: SessionHttpCredentials) {
        check(credentials.uid == ownerUid && credentials.identityEpoch == ownerIdentityEpoch) {
            "媒体任务所属登录会话已失效"
        }
    }

    private fun operationContextElement(operation: AndroidMediaOperationLease) =
        operationContext.asContextElement(operationContext.get().orEmpty() + operation)

    private fun release(operation: AndroidMediaOperationLease) = lifecycleLock.withLock {
        if (activeOperations.remove(operation)) {
            completeCloseIfDrainedLocked()
            operationsDrained.signalAll()
        }
    }

    private fun completeCloseIfDrainedLocked() {
        if (
            phase == AndroidMediaClosePhase.CLOSING &&
            closeWorkComplete &&
            activeOperations.isEmpty()
        ) {
            completedCloseFailure = closeFailures
                .takeIf(List<*>::isNotEmpty)
                ?.let(::AndroidMediaCloseException)
            phase = AndroidMediaClosePhase.CLOSED
            operationsDrained.signalAll()
        }
    }

    private fun awaitCompletedClose(): Throwable? = lifecycleLock.withLock {
        while (phase != AndroidMediaClosePhase.CLOSED) operationsDrained.awaitUninterruptibly()
        completedCloseFailure
    }

    private fun recordCloseFailure(failure: Throwable) = lifecycleLock.withLock {
        recordCloseFailureLocked(failure)
    }

    private fun recordCloseFailureLocked(failure: Throwable) {
        if (closeFailures.none { existing -> existing === failure }) closeFailures += failure
    }

    internal class AndroidMediaOperationLease(
        private val owner: AndroidMediaSession,
        private val abortAction: () -> Unit,
    ) : AutoCloseable {
        private val aborted = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        fun abort(): Throwable? = if (aborted.compareAndSet(false, true)) {
            runCatching(abortAction).exceptionOrNull()
        } else {
            null
        }

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            owner.release(this)
        }
    }
}

private enum class AndroidMediaClosePhase { OPEN, CLOSING, CLOSED }

private sealed interface AndroidMediaCloseDecision {
    data class Leader(
        val operations: List<AndroidMediaSession.AndroidMediaOperationLease>,
        val currentOperations: Set<AndroidMediaSession.AndroidMediaOperationLease>,
        val reentrant: Boolean,
    ) : AndroidMediaCloseDecision

    data object Follower : AndroidMediaCloseDecision
    data object Complete : AndroidMediaCloseDecision
    data object Reentrant : AndroidMediaCloseDecision
}

private class AndroidMediaReentrantCloseException : IllegalStateException(
    "媒体会话不能从仍在执行的认证操作内重入关闭",
)

private class AndroidMediaCloseException(failures: List<Throwable>) : IllegalStateException(
    "媒体会话关闭时有 ${failures.size} 个认证资源未能正常中止",
) {
    init {
        failures.forEach(::addSuppressed)
    }
}

/** Turns coroutine cancellation into an immediate close while the caller is blocked in read(). */
internal suspend fun <T> withCancellationAbort(
    abort: () -> Unit,
    block: suspend () -> T,
): T = coroutineScope {
    val bodyExited = AtomicBoolean(false)
    val cancellationWatcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!bodyExited.get()) runCatching(abort)
        }
    }
    try {
        block()
    } finally {
        bodyExited.set(true)
        cancellationWatcher.cancel()
    }
}

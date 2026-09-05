package com.virjar.tk.android

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.http.HttpConnectionOperationGate
import com.virjar.tk.shared.http.HttpConnectionReentrantCloseFailure
import com.virjar.tk.shared.repository.FileRepository
import kotlinx.coroutines.CancellationException
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
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 为一个权威部署数据集账户提供的不透明、与令牌无关的缓存命名空间。 */
internal fun mediaCacheNamespace(
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    ownerUid: String,
): String {
    com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
    require(ownerUid.isNotBlank()) { "media owner uid must not be empty" }
    return sha256Hex(
        "teamtalk-media-v4\u0000${deploymentIdentity.fingerprint}" +
            "\u0000dataset\u0000$datasetId\u0000uid\u0000$ownerUid",
    ).take(32)
}

/**
 * 在已认证的 Android UI 会话组合时捕获的不可变凭据。
 *
 * 每个阻塞操作都会在此注册。关闭所有者时会先封存回调和最终的缓存发布，
 * 然后同步断开或关闭所有携带凭证的资源。
 */
class AndroidMediaSession private constructor(
    val deploymentIdentity: DeploymentIdentity,
    val datasetId: String,
    private val ownerUid: String,
    private val ownerIdentityEpoch: Long,
    private val credentialsProvider: () -> SessionHttpCredentials,
    private val onAuthExpired: (rejectedAccessToken: String) -> Unit,
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
    internal val fileRepository = FileRepository(
        serverUrl,
        ownerUid,
        credentialsProvider,
        onAuthExpired = ::reportAuthExpired,
    )

    /** 读取轮换后的令牌，同时拒绝替换身份，即使 uid 被复用也一样。 */
    fun accessTokenForRequest(): String = lifecycleLock.withLock {
        check(phase == AndroidMediaClosePhase.OPEN) { "媒体会话已经关闭" }
        val credentials = credentialsProvider()
        requireCurrentOwner(credentials)
        credentials.accessToken?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("认证凭据不可用，请重新登录")
    }

    fun isCurrentOwner(): Boolean = lifecycleLock.withLock {
        if (phase != AndroidMediaClosePhase.OPEN) return@withLock false
        try {
            requireCurrentOwner(credentialsProvider())
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
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
            val abortFailure = lease.abort()
            throw mergeAndroidMediaFailures(failure, abortFailure)
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
        val sessionLease = try {
            // 物理断开由 HTTP 门控负责。这个外层租约只是让整个媒体所有者保持 CLOSING 状态，
            // 直到门控操作离开为止。
            registerOperation {}
        } catch (failure: Throwable) {
            val disconnectFailure = captureAndroidMediaFailure(connection::disconnect)
            throw mergeAndroidMediaFailures(failure, disconnectFailure)
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
                    // 配置本身也是一项已准入的操作：在连接归属于两个关闭门控之前，
                    // 平台实现或未来的回调绝不能执行隐式的首次 I/O。
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                    connection.instanceFollowRedirects = false
                    configure(connection)
                    connection.connect()
                    block(connection)
                }
            }
        } catch (expired: AppError.AuthExpired) {
            throw authoritativeAuthFailure(accessToken, expired)
        } finally {
            connectionOperations.completedCloseFailureOrNull()?.let(::recordCloseFailure)
            sessionLease.close()
        }
    }

    /** 只用于小的所有者发布变更；回调不得阻塞，也不得进入缓存容量逻辑。 */
    internal fun runIfOpen(block: () -> Unit): Boolean = lifecycleLock.withLock {
        if (phase != AndroidMediaClosePhase.OPEN) return@withLock false
        try {
            requireCurrentOwner(credentialsProvider())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock false
        }
        block()
        true
    }

    /** FileRepository 在上报这个被拒绝的凭证之前已经对其设防。 */
    internal fun reportAuthExpired(rejectedAccessToken: String) {
        lifecycleLock.withLock {
            check(phase == AndroidMediaClosePhase.OPEN) { "媒体会话已经关闭" }
            requireCurrentOwner(credentialsProvider())
        }
        onAuthExpired(rejectedAccessToken)
    }

    internal fun installCacheFile(partial: File, target: File) {
        lifecycleLock.withLock {
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
        }
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
        val failures = mutableListOf<Throwable>()
        decision.operations
            .filterNot(decision.currentOperations::contains)
            .forEach { operation -> operation.abort()?.let(failures::addIdentityDistinct) }
        val httpCloseFailure = captureAndroidMediaFailure(connectionOperations::close)
        if (httpCloseFailure != null && httpCloseFailure !is HttpConnectionReentrantCloseFailure) {
            failures.addIdentityDistinct(httpCloseFailure)
        }
        if (httpCloseFailure is HttpConnectionReentrantCloseFailure) {
            firstFatalAndroidMediaFailure(listOf(httpCloseFailure))
                ?.let { failure -> failures.addIdentityDistinct(failure) }
        }
        captureAndroidMediaFailure(afterHttpGateClose)?.let(failures::addIdentityDistinct)
        captureAndroidMediaFailure(fileRepository::close)?.let(failures::addIdentityDistinct)
        val boundaryFailure = if (decision.reentrant) AndroidMediaReentrantCloseException() else null
        val fatalFailure = firstFatalAndroidMediaFailure(failures)
        val immediateFailure = boundaryFailure?.let { boundary ->
            fun retainForBoundary(failure: Throwable) {
                if (
                    fatalFailure == null ||
                    (failure !== fatalFailure && !referencesAndroidMediaFailure(failure, fatalFailure))
                ) {
                    addSuppressedAndroidMediaFailure(boundary, failure)
                }
            }
            httpCloseFailure?.let(::retainForBoundary)
            failures.forEach(::retainForBoundary)
            fatalFailure?.also { fatal ->
                flattenAndroidMediaFailures(failures).forEach { failure ->
                    addSuppressedAndroidMediaFailure(fatal, failure)
                }
                addSuppressedAndroidMediaFailure(fatal, boundary)
            } ?: boundary
        }
        lifecycleLock.withLock {
            failures.forEach(::recordCloseFailureLocked)
            closeWorkComplete = true
            closingThread = null
            completeCloseIfDrainedLocked()
            operationsDrained.signalAll()
        }
        immediateFailure?.let { throw it }
        awaitCompletedClose()?.let { throw it }
    }

    companion object {
        fun create(
            deploymentIdentity: DeploymentIdentity,
            datasetId: String,
            ownerUid: String,
            credentialsProvider: () -> SessionHttpCredentials,
            onAuthExpired: (rejectedAccessToken: String) -> Unit = {},
        ): AndroidMediaSession = create(
            deploymentIdentity = deploymentIdentity,
            datasetId = datasetId,
            ownerUid = ownerUid,
            credentialsProvider = credentialsProvider,
            onAuthExpired = onAuthExpired,
            connectionFactory = { raw -> URL(raw).openConnection() as HttpURLConnection },
            beforeHttpGateRegistration = {},
            beforeFirstIo = {},
            afterHttpGateClose = {},
        )

        internal fun create(
            deploymentIdentity: DeploymentIdentity,
            datasetId: String,
            ownerUid: String,
            credentialsProvider: () -> SessionHttpCredentials,
            onAuthExpired: (rejectedAccessToken: String) -> Unit = {},
            connectionFactory: (String) -> HttpURLConnection,
            beforeHttpGateRegistration: () -> Unit = {},
            beforeFirstIo: () -> Unit = {},
            afterHttpGateClose: () -> Unit = {},
        ): AndroidMediaSession {
            com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
            val initial = credentialsProvider()
            check(initial.uid == ownerUid) { "媒体会话初始认证身份不匹配" }
            return AndroidMediaSession(
                deploymentIdentity = deploymentIdentity,
                datasetId = datasetId,
                ownerUid = ownerUid,
                ownerIdentityEpoch = initial.identityEpoch,
                credentialsProvider = credentialsProvider,
                onAuthExpired = onAuthExpired,
                connectionFactory = connectionFactory,
                beforeHttpGateRegistration = beforeHttpGateRegistration,
                beforeFirstIo = beforeFirstIo,
                afterHttpGateClose = afterHttpGateClose,
                cacheNamespace = mediaCacheNamespace(deploymentIdentity, datasetId, ownerUid),
            )
        }
    }

    private fun requireCurrentOwner(credentials: SessionHttpCredentials) {
        check(credentials.uid == ownerUid && credentials.identityEpoch == ownerIdentityEpoch) {
            "媒体任务所属登录会话已失效"
        }
    }

    /** 来自本地已被取代的访问令牌的响应，不是权威性的会话失败。 */
    private fun authoritativeAuthFailure(
        requestAccessToken: String,
        expired: AppError.AuthExpired,
    ): Exception {
        val authoritative = lifecycleLock.withLock {
            check(phase == AndroidMediaClosePhase.OPEN) { "媒体会话已经关闭" }
            val current = credentialsProvider()
            requireCurrentOwner(current)
            if (current.accessToken != requestAccessToken) {
                AndroidMediaSupersededCredentialException()
            } else {
                expired
            }
        }
        if (authoritative is AppError.AuthExpired) onAuthExpired(requestAccessToken)
        return authoritative
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
            completedCloseFailure = terminalAndroidMediaCloseFailure(closeFailures)
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
            captureAndroidMediaFailure(abortAction)
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

internal class AndroidMediaSupersededCredentialException :
    IllegalStateException("媒体请求使用的认证凭据已经轮换")

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

/** 在调用方阻塞于 read() 期间，把协程取消转化为立即关闭。 */
internal suspend fun <T> withCancellationAbort(
    abort: () -> Unit,
    block: suspend () -> T,
): T {
    val bodyExited = AtomicBoolean(false)
    val abortFailure = AtomicReference<Throwable?>()
    return try {
        coroutineScope {
            val cancellationWatcher = launch(
                Dispatchers.Unconfined,
                start = CoroutineStart.UNDISPATCHED,
            ) {
                try {
                    awaitCancellation()
                } finally {
                    if (!bodyExited.get()) {
                        captureAndroidMediaFailure(abort)?.let { failure ->
                            abortFailure.compareAndSet(null, failure)
                        }
                    }
                }
            }
            try {
                block()
            } finally {
                bodyExited.set(true)
                cancellationWatcher.cancel()
            }
        }
    } catch (failure: Throwable) {
        throw mergeAndroidMediaFailures(abortFailure.get(), failure)
    }
}

private fun captureAndroidMediaFailure(action: () -> Unit): Throwable? = try {
    action()
    null
} catch (failure: Throwable) {
    failure
}

private fun mergeAndroidMediaFailures(primary: Throwable?, additional: Throwable?): Throwable {
    val failures = listOfNotNull(primary, additional)
    check(failures.isNotEmpty()) { "At least one Android media failure is required" }
    val observed = flattenAndroidMediaFailures(failures)
    val selected = observed.firstOrNull(::isFatalAndroidMediaFailure) ?: failures.first()
    observed.forEach { failure -> addSuppressedAndroidMediaFailure(selected, failure) }
    return selected
}

private fun terminalAndroidMediaCloseFailure(failures: List<Throwable>): Throwable? {
    if (failures.isEmpty()) return null
    val observed = flattenAndroidMediaFailures(failures)
    val fatal = observed.firstOrNull(::isFatalAndroidMediaFailure)
        ?: return AndroidMediaCloseException(failures)
    observed.forEach { failure -> addSuppressedAndroidMediaFailure(fatal, failure) }
    return fatal
}

private fun firstFatalAndroidMediaFailure(failures: List<Throwable>): Throwable? =
    flattenAndroidMediaFailures(failures).firstOrNull(::isFatalAndroidMediaFailure)

private fun isFatalAndroidMediaFailure(failure: Throwable): Boolean =
    failure is CancellationException || failure !is Exception

private fun flattenAndroidMediaFailures(failures: List<Throwable>): List<Throwable> {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val observed = mutableListOf<Throwable>()

    fun visit(failure: Throwable) {
        if (!visited.add(failure)) return
        observed += failure
        failure.cause?.let(::visit)
        failure.suppressed.forEach(::visit)
    }

    failures.forEach(::visit)
    return observed
}

private fun addSuppressedAndroidMediaFailure(primary: Throwable, additional: Throwable) {
    if (primary === additional || primary.suppressed.any { it === additional }) return
    if (referencesAndroidMediaFailure(primary, additional)) return
    if (referencesAndroidMediaFailure(additional, primary)) return
    primary.addSuppressed(additional)
}

private fun referencesAndroidMediaFailure(root: Throwable, target: Throwable): Boolean {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())

    fun visit(failure: Throwable): Boolean {
        if (failure === target) return true
        if (!visited.add(failure)) return false
        return failure.cause?.let(::visit) == true || failure.suppressed.any(::visit)
    }

    return visit(root)
}

private fun MutableList<Throwable>.addIdentityDistinct(failure: Throwable) {
    if (none { existing -> existing === failure }) add(failure)
}

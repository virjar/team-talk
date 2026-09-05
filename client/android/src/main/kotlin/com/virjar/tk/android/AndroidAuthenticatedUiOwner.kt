package com.virjar.tk.android

import android.media.MediaRecorder
import android.util.Log
import com.virjar.tk.shared.client.SessionEndReason
import com.virjar.tk.app.navigation.AppDataState
import com.virjar.tk.app.telemetry.ClientFaultCode
import com.virjar.tk.app.telemetry.ClientFaultReason
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import kotlinx.coroutines.CancellationException

internal enum class AndroidUiRetirementPolicy {
    DISCARD_DRAFTS,
    PRESERVE_DURABLE_DRAFTS,
    PRESERVE_SAME_USER_CONTINUATION,
}

internal data class AndroidAuthenticatedUiSession(
    val dataState: AppDataState,
    val resourceOwner: AndroidAuthenticatedResourceOwner,
)

/**
 * 为一次生命周期边界捕获确切的会话 sink，并最多发出一个带类型的故障。
 * 任何异常文本、资源身份或生命周期边界标签都不会越过此适配器。
 */
internal class AndroidPlatformLifecycleFaultReporter(
    private val telemetry: ClientUiTelemetrySink,
    private val page: ClientUiPage? = null,
    private val action: ClientUiAction? = null,
) {
    private val lock = Any()
    private var reported = false

    fun report() {
        val shouldReport = synchronized(lock) {
            if (reported) false else {
                reported = true
                true
            }
        }
        if (!shouldReport) return
        telemetry.recordFault(
            code = ClientFaultCode.PLATFORM_LIFECYCLE_FAILURE,
            page = page,
            action = action,
            origin = FeedbackOrigin.SYSTEM,
            reason = ClientFaultReason.LIFECYCLE,
        )
    }
}

internal fun SessionEndReason.androidUiRetirementPolicy(): AndroidUiRetirementPolicy = when (this) {
    SessionEndReason.USER_LOGOUT -> AndroidUiRetirementPolicy.DISCARD_DRAFTS
    SessionEndReason.AUTH_REVOKED,
    SessionEndReason.PROTOCOL_UPGRADE -> AndroidUiRetirementPolicy.PRESERVE_DURABLE_DRAFTS
    SessionEndReason.PROCESS_REPLACED,
    SessionEndReason.SHUTDOWN -> AndroidUiRetirementPolicy.PRESERVE_SAME_USER_CONTINUATION
}

/** 由 Activity 重建、认证退役和 ViewModel 清理共享的引用性所有者门控。 */
internal class AndroidSessionOwnerGate<T : Any> {
    private val lock = Any()
    private var owner: T? = null

    fun <R> replaceOwner(nextOwner: T, transition: (previousOwner: T?) -> R): R = synchronized(lock) {
        val result = transition(owner)
        owner = nextOwner
        result
    }

    fun retireIfOwner(expectedOwner: T, retire: () -> Unit): Boolean = synchronized(lock) {
        if (owner !== expectedOwner) return@synchronized false
        owner = null
        retire()
        true
    }

    /**
     * 针对所有者替换/退役，线性化终结回调。
     *
     * 动作始终保持在可重入监视器之下：仅仅检查身份然后释放锁，
     * 会让替换在回调到达 AuthController 之前抢先成功。因此过期的组合
     * 永远无法注销或吊销其后继会话。
     */
    fun runIfOwner(expectedOwner: T, action: () -> Unit): Boolean = synchronized(lock) {
        if (owner !== expectedOwner) return@synchronized false
        action()
        true
    }

    fun retireCurrent(retire: (owner: T?) -> Unit) = synchronized(lock) {
        val previousOwner = owner
        owner = null
        retire(previousOwner)
    }

    fun <R> withOwner(block: (owner: T?) -> R): R = synchronized(lock) { block(owner) }
}

/**
 * 面向可持有凭证或活跃 I/O 的 Android 资源的会话作用域屏障。
 *
 * 注册、租约处置与所有者封印共享同一个监视器。因此退役会等待并发的获取
 * 或 Compose 销毁完成。一旦被封存，获取就不会再执行其工厂，
 * 因此过期的组合无法构造或暴露新的可携带凭证资源。
 */
internal class AndroidAuthenticatedResourceOwner {
    private val lock = Any()
    private var sealed = false
    private val leases = LinkedHashSet<AndroidAuthenticatedResourceLease<*>>()

    fun <T : AutoCloseable> acquire(
        createResource: () -> T,
    ): AndroidAuthenticatedResourceLease<T> = synchronized(lock) {
        if (sealed) return@synchronized AndroidAuthenticatedResourceLease.closed(this)
        AndroidAuthenticatedResourceLease.open(this, createResource()).also(leases::add)
    }

    /** 永久封存所有者，并尽力关闭每一个已准入的资源。 */
    fun closeAll(): List<Throwable> = synchronized(lock) {
        sealed = true
        val failures = mutableListOf<Throwable>()
        leases.toList().forEach { lease ->
            leases.remove(lease)
            lease.closeLocked()?.let(failures::add)
        }
        throwFatalAndroidLifecycleFailures(failures)
        failures
    }

    internal fun closeLease(lease: AndroidAuthenticatedResourceLease<*>): Throwable? = synchronized(lock) {
        leases.remove(lease)
        lease.closeLocked()
    }

    internal fun <T : AutoCloseable> resourceOrNull(
        lease: AndroidAuthenticatedResourceLease<T>,
    ): T? = synchronized(lock) { lease.resourceLocked() }
}

internal class AndroidAuthenticatedResourceLease<T : AutoCloseable> private constructor(
    private val owner: AndroidAuthenticatedResourceOwner,
    private var resource: T?,
) : AutoCloseable {
    fun resourceOrNull(): T? = owner.resourceOrNull(this)

    override fun close() {
        owner.closeLease(this)?.let { throw it }
    }

    internal fun closeLocked(): Throwable? {
        val closing = resource ?: return null
        resource = null
        return try {
            closing.close()
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    internal fun resourceLocked(): T? = resource

    companion object {
        fun <T : AutoCloseable> open(
            owner: AndroidAuthenticatedResourceOwner,
            resource: T,
        ) = AndroidAuthenticatedResourceLease(owner, resource)

        fun <T : AutoCloseable> closed(
            owner: AndroidAuthenticatedResourceOwner,
        ) = AndroidAuthenticatedResourceLease<T>(owner, null)
    }
}

/** 关闭顺序对安全性很重要：先停止生产者/控制器，再关闭携带凭证的 I/O。 */
internal class AndroidAuthenticatedMediaResources private constructor(
    val mediaSession: AndroidMediaSession,
    val fileDownloads: AndroidFileDownloadController?,
    private val stopVoice: () -> Unit,
) : AutoCloseable {
    override fun close() {
        closeAndroidAuthenticatedMediaResources(
            closeControllers = { fileDownloads?.close() },
            stopVoice = stopVoice,
            closeMedia = mediaSession::close,
        )
    }

    companion object {
        fun create(
            createMediaSession: () -> AndroidMediaSession,
            createFileDownloads: (AndroidMediaSession) -> AndroidFileDownloadController? = { null },
            stopVoice: (AndroidMediaSession) -> Unit = {},
        ): AndroidAuthenticatedMediaResources {
            val mediaSession = createMediaSession()
            return try {
                AndroidAuthenticatedMediaResources(
                    mediaSession = mediaSession,
                    fileDownloads = createFileDownloads(mediaSession),
                    stopVoice = { stopVoice(mediaSession) },
                )
            } catch (failure: Throwable) {
                val terminalFailure = try {
                    mediaSession.close()
                    failure
                } catch (cleanupFailure: Throwable) {
                    mergeAndroidLifecycleFailures(failure, cleanupFailure)
                }
                throw terminalFailure
            }
        }
    }
}

internal fun closeAndroidAuthenticatedMediaResources(
    closeControllers: () -> Unit,
    stopVoice: () -> Unit,
    closeMedia: () -> Unit,
) {
    val failures = mutableListOf<Throwable>()
    listOf(closeControllers, stopVoice, closeMedia).forEach { closeResource ->
        try {
            closeResource()
        } catch (failure: Throwable) {
            failures += failure
        }
    }
    throwFatalAndroidLifecycleFailures(failures)
    if (failures.isNotEmpty()) throw AndroidAuthenticatedResourceCloseException(failures)
}

internal fun closeAndroidChatVoiceResources(
    permissionGate: VoiceRecordPermissionGate,
    recording: VoiceRecordingLease<MediaRecorder>,
    mediaSession: AndroidMediaSession,
) {
    val failures = mutableListOf<Throwable>()
    listOf<() -> Unit>(
        permissionGate::clear,
        {
            recording.sealAndDiscard(
                stop = MediaRecorder::stop,
                release = MediaRecorder::release,
            )
        },
        { VoicePlayer.stop(mediaSession) },
    ).forEach { closeVoice ->
        try {
            closeVoice()
        } catch (failure: Throwable) {
            failures += failure
        }
    }
    throwFatalAndroidLifecycleFailures(failures)
    if (failures.isNotEmpty()) throw AndroidAuthenticatedResourceCloseException(failures)
}

internal class AndroidAuthenticatedResourceCloseException(
    val failures: List<Throwable>,
) : IllegalStateException("Failed to close ${failures.size} Android authenticated resource(s)", failures.first()) {
    init {
        failures.drop(1).forEach(::addSuppressed)
    }
}

internal fun isFatalAndroidLifecycleFailure(failure: Throwable): Boolean =
    failure is CancellationException || failure !is Exception

internal fun mergeAndroidLifecycleFailures(primary: Throwable?, additional: Throwable): Throwable {
    if (primary == null || primary === additional) return additional
    return if (!isFatalAndroidLifecycleFailure(primary) && isFatalAndroidLifecycleFailure(additional)) {
        addSuppressedAndroidLifecycleFailure(additional, primary)
        additional
    } else {
        addSuppressedAndroidLifecycleFailure(primary, additional)
        primary
    }
}

internal fun collapseAndroidLifecycleFailures(failures: List<Throwable>): Throwable? {
    if (failures.isEmpty()) return null
    val primary = failures.firstOrNull(::isFatalAndroidLifecycleFailure) ?: failures.first()
    failures.forEach { failure -> addSuppressedAndroidLifecycleFailure(primary, failure) }
    return primary
}

internal fun throwFatalAndroidLifecycleFailures(failures: List<Throwable>) {
    if (failures.none(::isFatalAndroidLifecycleFailure)) return
    throw checkNotNull(collapseAndroidLifecycleFailures(failures))
}

/** Compose 销毁会诊断普通关闭缺陷，但绝不会降级取消或 Error。 */
internal fun disposeAndroidAuthenticatedResources(
    closeResources: () -> Unit,
    recordFailure: (Throwable) -> Unit,
) = disposeAndroidNativeMediaResources(
    closeResources = listOf(closeResources),
    recordFailure = recordFailure,
)

/** 排空所有原生所有者，聚合上报一次，然后保留致命异常本体。 */
internal fun disposeAndroidNativeMediaResources(
    closeResources: List<() -> Unit>,
    recordFailure: (Throwable) -> Unit,
) {
    val failures = mutableListOf<Throwable>()
    closeResources.forEach { closeResource ->
        try {
            closeResource()
        } catch (failure: Throwable) {
            failures += failure
        }
    }
    val closeFailure = collapseAndroidLifecycleFailures(failures) ?: return
    try {
        recordFailure(closeFailure)
    } catch (diagnosticFailure: Throwable) {
        failures += diagnosticFailure
    }
    throwFatalAndroidLifecycleFailures(failures)
}

/** 初始化之前创建的资源会在原始失败逃逸之前被回滚。 */
internal fun <T> createAndroidNativeMediaResource(
    createResource: () -> T,
    initializeResource: (T) -> Unit,
    closeResource: (T) -> Unit,
): T {
    val resource = createResource()
    return try {
        initializeResource(resource)
        resource
    } catch (failure: Throwable) {
        val cleanupFailure = try {
            closeResource(resource)
            null
        } catch (thrown: Throwable) {
            thrown
        }
        throw cleanupFailure?.let { mergeAndroidLifecycleFailures(failure, it) } ?: failure
    }
}

internal fun reportAndroidRetirementFailures(
    boundary: String,
    observedFailures: List<Pair<String, Throwable>>,
    telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
) {
    if (observedFailures.isEmpty()) return
    val failures = observedFailures.toMutableList()
    recordAndroidRetirementFault(telemetry, failures)
    finishAndroidRetirementFailures(boundary, failures)
}

/** 在会话遥测执行最终持久化之前记录已知失败。绝不抛出。 */
internal fun recordAndroidRetirementFault(
    telemetry: ClientUiTelemetrySink,
    failures: MutableList<Pair<String, Throwable>>,
) {
    if (failures.isEmpty()) return
    try {
        AndroidPlatformLifecycleFaultReporter(telemetry).report()
    } catch (diagnosticFailure: Throwable) {
        // 让原始清理失败保持在首位，使取消/致命异常本体始终是主异常。
        failures += "telemetry diagnostics" to diagnosticFailure
    }
}

/**
 * 先持久化已知的故障，然后即使诊断失败也仍然关闭会话。
 * [closeRetainedSession] 发现的失败有意不通过正在关闭的遥测所有者上报；
 * 其日志记录/传播由 ClientSession 和最终边界保留。
 */
internal fun closeAndroidRetainedSessionAfterFaultCapture(
    telemetry: ClientUiTelemetrySink,
    failures: MutableList<Pair<String, Throwable>>,
    closeRetainedSession: () -> Unit,
) {
    recordAndroidRetirementFault(telemetry, failures)
    try {
        closeRetainedSession()
    } catch (failure: Throwable) {
        failures += "retained session" to failure
    }
}

/** 记录完整的失败集合，并在不经过遥测的情况下重放其中第一个致命/取消异常。 */
internal fun finishAndroidRetirementFailures(
    boundary: String,
    failures: MutableList<Pair<String, Throwable>>,
) {
    if (failures.isEmpty()) return
    failures.toList().forEachIndexed { index, (owner, failure) ->
        try {
            Log.e(
                "AndroidAuth",
                "$boundary cleanup failed for $owner (failure ${index + 1})",
                failure,
            )
        } catch (diagnosticFailure: Throwable) {
            failures += "cleanup diagnostics" to diagnosticFailure
        }
    }
    throwFatalAndroidLifecycleFailures(failures.map { it.second })
}

private fun addSuppressedAndroidLifecycleFailure(primary: Throwable, additional: Throwable) {
    if (
        primary !== additional &&
        primary.suppressedExceptions.none { suppressed -> suppressed === additional }
    ) {
        primary.addSuppressed(additional)
    }
}

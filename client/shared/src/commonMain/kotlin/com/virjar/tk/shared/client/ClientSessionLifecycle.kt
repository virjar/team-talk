package com.virjar.tk.shared.client

import com.virjar.tk.shared.Outcome
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.shared.log.AppLog
import com.virjar.tk.shared.log.AppLogOwner
import com.virjar.tk.shared.log.LogBuffer
import kotlinx.coroutines.CancellationException

/** 已认证 owner 跨越其不可逆业务资源边界的原因。 */
enum class SessionEndReason {
    USER_LOGOUT,
    AUTH_REVOKED,
    PROCESS_REPLACED,
    PROTOCOL_UPGRADE,
    SHUTDOWN,
}

internal fun SessionEndReason.outgoingDisposition(): SendQueueCloseDisposition = when (this) {
    SessionEndReason.USER_LOGOUT,
    SessionEndReason.AUTH_REVOKED -> SendQueueCloseDisposition.CANCEL
    SessionEndReason.PROCESS_REPLACED,
    SessionEndReason.PROTOCOL_UPGRADE,
    SessionEndReason.SHUTDOWN -> SendQueueCloseDisposition.PRESERVE
}

enum class SessionLifecyclePhase { ACTIVE, QUIESCED, CLOSED }

/** 在 quiesce 时永久退役，并跨 EventLoop 的实际通道写入被持有。 */
internal class SessionOutboundLease : WireSendAdmission {
    private val lock = Any()
    val ackOwner: Any = Any()
    @Volatile
    private var active = true

    override fun isActive(): Boolean = active

    /** 把退役挡在实际 EventLoop 写入临界区之外。 */
    override fun use(block: () -> Boolean): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        block()
    }

    fun retire() = synchronized(lock) { active = false }
}

/** 仓库访问器与门禁 RPC 适配器共享的小型线性化点。 */
internal class SessionLifecycleGate {
    private val lock = Any()

    @Volatile
    var phase: SessionLifecyclePhase = SessionLifecyclePhase.ACTIVE
        private set

    @Volatile
    var endReason: SessionEndReason? = null
        private set

    fun beginQuiesce(
        reason: SessionEndReason,
        retireOutbound: () -> Unit = {},
    ): Boolean = synchronized(lock) {
        if (phase != SessionLifecyclePhase.ACTIVE) return@synchronized false
        // 在发布 QUIESCED 之前退役 EventLoop 可见的线格式租约。不存在观察者看到逻辑边界已越过
        // 而旧写租约仍存活的状态。
        try {
            retireOutbound()
        } finally {
            // 即使关闭钩子暴露出 VM 致命缺陷，退役也不可逆。调用方仍会收到该失败，但之后的任何
            // 请求都无法重新打开此 owner。
            endReason = reason
            phase = SessionLifecyclePhase.QUIESCED
        }
        true
    }

    fun markClosed(): Boolean = synchronized(lock) {
        if (phase == SessionLifecyclePhase.CLOSED) return@synchronized false
        check(phase == SessionLifecyclePhase.QUIESCED) { "ClientSession must quiesce before close" }
        phase = SessionLifecyclePhase.CLOSED
        true
    }

    fun requireBusinessActive() = synchronized(lock) {
        check(phase == SessionLifecyclePhase.ACTIVE) {
            "ClientSession no longer accepts business work (${endReason ?: SessionEndReason.SHUTDOWN})"
        }
    }

    fun isBusinessActive(): Boolean = phase == SessionLifecyclePhase.ACTIVE

    fun <T> whileBusinessActive(block: () -> T): T = synchronized(lock) {
        requireBusinessActive()
        block()
    }
}

/**
 * 裸 logout RPC 被密封在这个一次性能力内部。它只能由 USER_LOGOUT-quiesced 的 [ClientSession]
 * 铸造，且完成时总会恰好关闭裸 RPC owner 一次。
 */
internal class UserLogoutRetirementCapability(
    private val logoutRpc: suspend () -> Outcome<Unit>,
    private val closeSession: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var completed = false

    suspend fun complete(disconnectTransport: () -> Boolean): Outcome<Unit> {
        synchronized(lock) {
            check(!completed) { "User logout retirement already completed" }
            completed = true
        }
        var result: Outcome<Unit>? = null
        val failures = mutableListOf<Throwable>()
        try {
            result = logoutRpc()
        } catch (failure: Throwable) {
            failures += failure
        }
        val disconnect = try {
            disconnectTransport()
        } catch (failure: Throwable) {
            failures += failure
            true
        }
        try {
            closeSession(disconnect)
        } catch (failure: Throwable) {
            failures += failure
        }
        collapseSessionLifecycleFailures(failures)?.let { throw it }
        return checkNotNull(result)
    }
}

/**
 * 每个业务仓库都使用此视图而非裸 RPC owner。它同时隔断请求准入与响应发布，同时让裸 client 保持
 * 可用于 logout RPC。
 */
internal class SessionBusinessRpcInvoker(
    private val delegate: RpcInvoker,
    private val lifecycle: SessionLifecycleGate,
    private val outboundLease: SessionOutboundLease,
) : RpcInvoker {
    override val negotiatedProtocolVersion
        get() = delegate.negotiatedProtocolVersion.also { lifecycle.requireBusinessActive() }

    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
        lifecycle.requireBusinessActive()
        val response = if (delegate is RpcClient) {
            delegate.invokeWhileActive(service, methodId, payload, outboundLease)
        } else {
            delegate.invoke(service, methodId, payload)
        }
        lifecycle.requireBusinessActive()
        return response
    }
}

/**
 * 把每个终态调用方接入一次同步资源排空和一个稳定的失败对象。
 *
 * monitor 在整个排空期间保持持有，因此其他线程无法提前观察到完成。若一个调用在持有同一 monitor
 * 的同时确实观察到 [draining]，那必然是同一线程的重入；它必须立即失败，而不是等待自己的清理回调。
 */
internal class ClientSessionTerminalLifecycle {
    private val lock = Any()
    private var draining = false
    private var terminalFailure: Throwable? = null

    fun runUntil(
        isComplete: () -> Boolean,
        drain: () -> Throwable?,
    ): Unit = synchronized(lock) {
        if (draining) {
            val failure = terminalFailure ?: SessionBoundaryReentrantCloseException(
                "ClientSession lifecycle cannot reenter its resource cleanup",
            ).also { terminalFailure = it }
            throw failure
        }
        if (isComplete()) {
            terminalFailure?.let { throw it }
            return@synchronized
        }

        draining = true
        try {
            val failure = try {
                drain()
            } catch (unexpected: Throwable) {
                unexpected
            }
            if (failure != null) {
                val published = terminalFailure
                if (published == null) {
                    terminalFailure = failure
                } else {
                    // 一旦调用方观察到终态对象，其身份就不可变。后续关闭阶段的缺陷保持可见，
                    // 而不改变重放值。
                    addSuppressedDistinct(published, failure)
                }
            }
        } finally {
            draining = false
        }
        val failure = terminalFailure
        if (failure != null) throw failure
    }
}

/** 即使任意关闭钩子失败，也按声明顺序执行每一个退役动作。 */
internal fun releaseAllSessionResources(
    vararg releases: Pair<String, () -> Unit>,
): List<Pair<String, Throwable>> {
    val failures = mutableListOf<Pair<String, Throwable>>()
    releases.forEach { (owner, release) ->
        try {
            release()
        } catch (failure: Throwable) {
            failures += owner to failure
        }
    }
    failures.firstOrNull { (_, failure) -> isFatalSessionLifecycleFailure(failure) }
        ?.second
        ?.let { fatal ->
            failures.forEach { (_, failure) -> addSuppressedDistinct(fatal, failure) }
            throw fatal
        }
    return failures
}

/** 取消与非 [Exception] 失败绝不能被降级为清理诊断。 */
internal fun isFatalSessionLifecycleFailure(failure: Throwable): Boolean =
    failure is CancellationException || failure !is Exception

/** 选择致命失败而非普通失败，并把每个被挤出的原因保留为 suppressed。 */
internal fun mergeSessionLifecycleFailures(primary: Throwable?, additional: Throwable): Throwable {
    if (primary == null || primary === additional) return additional
    return if (!isFatalSessionLifecycleFailure(primary) && isFatalSessionLifecycleFailure(additional)) {
        addSuppressedDistinct(additional, primary)
        additional
    } else {
        addSuppressedDistinct(primary, additional)
        primary
    }
}

internal fun collapseSessionLifecycleFailures(failures: List<Throwable>): Throwable? {
    if (failures.isEmpty()) return null
    val primary = failures.firstOrNull(::isFatalSessionLifecycleFailure) ?: failures.first()
    failures.forEach { failure -> addSuppressedDistinct(primary, failure) }
    return primary
}

internal fun addSuppressedDistinct(primary: Throwable, additional: Throwable) {
    if (primary !== additional) primary.addSuppressed(additional)
}

/** 在 ClientSession 返回之前创建的资源的事务性 owner 栈。 */
internal class SessionConstructionRollback {
    private val releases = mutableListOf<Pair<String, () -> Unit>>()
    private var handedOff = false

    fun own(owner: String, release: () -> Unit) {
        check(!handedOff) { "Session construction ownership already handed off" }
        releases += owner to release
    }

    fun handOff() {
        check(!handedOff) { "Session construction ownership already handed off" }
        handedOff = true
        releases.clear()
    }

    fun rollback(): List<Pair<String, Throwable>> {
        if (handedOff) return emptyList()
        handedOff = true
        return try {
            releaseAllSessionResources(*releases.asReversed().toTypedArray())
        } finally {
            releases.clear()
        }
    }
}

/** 无头/禁用的会话绝不能替换图形客户端的进程级日志 owner。 */
internal fun installAppLogOwnershipIfEnabled(
    enabled: Boolean,
    traceBuffer: LogBuffer,
    faultBuffer: LogBuffer,
    faultHandler: (() -> Unit)?,
    crashDumper: CrashDumper? = null,
    telemetrySink: ((String, String, String, Throwable?) -> Unit)? = null,
    crashSink: ((java.io.File, String) -> Unit)? = null,
    previousOwnerSink: ((AppLogOwner?) -> Unit)? = null,
): AppLogOwner? {
    if (!enabled) return null
    val owner = AppLogOwner(
        traceBuffer = traceBuffer,
        faultBuffer = faultBuffer,
        onFault = faultHandler,
        crashSink = crashSink ?: crashDumper?.let { dumper -> { _, content -> dumper.flushPending(content) } },
        telemetrySink = telemetrySink,
    )
    val previous = AppLog.installReturningPrevious(owner)
    previousOwnerSink?.invoke(previous)
    return owner
}

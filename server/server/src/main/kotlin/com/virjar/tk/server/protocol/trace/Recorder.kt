package com.virjar.tk.server.protocol.trace

import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import com.virjar.tk.protocol.telemetry.ConnectionTraceContextPolicy
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.util.ArrayDeque
import java.util.UUID
import java.util.function.Supplier

/**
 * 每物理连接诊断记录器。
 *
 * 鉴权之前，它最多保留 [MAX_CACHE_SIZE] 条延迟的有界条目。缓存
 * 只会在从控制面读取到有效、未过期的 DIAGNOSTIC 策略之后才交给写入器。
 * BASELINE 策略始终清空缓存并禁用写入器。
 */
class Recorder internal constructor(
    private val runtime: TraceRuntime,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    companion object {
        internal const val MAX_CACHE_SIZE = 30
        private val NETTY_ATTR = AttributeKey.newInstance<Recorder>("TRACE_RECORD")

        @Synchronized
        internal fun touch(channel: Channel, runtime: TraceRuntime): Recorder {
            val attr = channel.attr(NETTY_ATTR)
            attr.get()?.let { return it }
            return Recorder(runtime).also { recorder ->
                attr.set(recorder)
                channel.closeFuture().addListener {
                    recorder.record(
                        ConnectionTracePhase.SHUTDOWN,
                        ConnectionTraceOutcome.CLOSED,
                        Supplier { "event=channelClosed" },
                    )
                    recorder.release()
                }
            }
        }
    }

    private val lifecycleLock = Any()
    private val cache = ArrayDeque<RecordEntry>()
    private var writer: RealWriter? = null
    private var connectionBudget: TraceConnectionBudget? = null
    private var binding: AuthenticationBinding? = null
    private var currentContext: ConnectionTraceContext? = null
    private var lastPolicyRevision: Long = 0L
    private var preAuthentication = true
    private var released = false

    /** 把第一个 AUTH 请求绑定到此物理连接；之后每个 AUTH 都是 fail-closed。 */
    fun bindAuthentication(
        correlationId: String,
        connectionGeneration: Long,
        serverSessionId: String = idFactory(),
    ): Boolean = synchronized(lifecycleLock) {
        if (released || binding != null) return@synchronized false
        try {
            ConnectionTraceContextPolicy.requireToken(correlationId, "auth.correlationId")
            ConnectionTraceContextPolicy.requirePositive(connectionGeneration, "auth.connectionGeneration")
            ConnectionTraceContextPolicy.requireToken(serverSessionId, "trace.sessionId")
            val traceId = idFactory()
            ConnectionTraceContextPolicy.requireToken(traceId, "trace.traceId")
            binding = AuthenticationBinding(correlationId, connectionGeneration, serverSessionId, traceId)
        } catch (_: IllegalArgumentException) {
            return@synchronized false
        }
        true
    }

    fun authenticationIdentity(): RecorderAuthenticationIdentity? = synchronized(lifecycleLock) {
        binding?.let {
            RecorderAuthenticationIdentity(it.correlationId, it.connectionGeneration)
        }
    }

    fun context(): ConnectionTraceContext? = synchronized(lifecycleLock) {
        currentContext?.takeUnless { it.isExpired(clock()) }
    }

    /**
     * 返回此物理连接最后一次客户端可见的策略决策。
     *
     * 策略可能在已鉴权连接仍处于同步期间变化。在该状态下
     * ClientRegistry 还不能发送瞬时控制帧，且在 SYNC_READY 之后重新应用同一
     * 版本刻意是空操作。把决策保留在此处，使激活可以
     * 发布已应用的版本（包括 BASELINE、终结禁用或容量
     * 拒绝），而不会削弱单调应用 fence。
     */
    fun currentPolicyDecision(): RecorderPolicyUpdate? = synchronized(lifecycleLock) {
        if (released || lastPolicyRevision <= 0L) return@synchronized null
        val bound = binding ?: return@synchronized null
        if (currentContext?.isExpired(clock()) == true) {
            disableLocked(lastPolicyRevision)
        }
        RecorderPolicyUpdate(
            correlationId = bound.correlationId,
            connectionGeneration = bound.connectionGeneration,
            policyRevision = lastPolicyRevision,
            context = currentContext,
        )
    }

    /**
     * 为有效的 DIAGNOSTIC 策略启用或刷新写入器。容量拒绝
     * 以 null context 表示，因此客户端绝不会发出服务器
     * 未准入的 context。
     */
    fun applyDiagnosticPolicy(
        uid: String,
        deviceId: String,
        policyRevision: Long,
        expiresAtEpochMs: Long,
    ): RecorderPolicyUpdate? = synchronized(lifecycleLock) {
        val bound = binding ?: return@synchronized null
        preAuthentication = false
        val now = clock()
        // 已发布的策略版本对此物理连接是终结性的。特别地，
        // 容量拒绝以版本 R 且无 context 的形式发送给客户端；允许服务器
        // 之后为同一 R 获取写入器，会被客户端严格的版本
        // fence 拒绝，并造成仅服务器的采集。因此重试准入需要一个
        // 真正更新的控制面版本。
        if (released || policyRevision <= 0L || policyRevision <= lastPolicyRevision) {
            return@synchronized null
        }
        if (expiresAtEpochMs <= now) {
            disableLocked(policyRevision)
            return@synchronized RecorderPolicyUpdate(
                bound.correlationId,
                bound.connectionGeneration,
                policyRevision,
                null,
            )
        }

        val context = ConnectionTraceContext(
            correlationId = bound.correlationId,
            traceId = bound.traceId,
            sessionId = bound.serverSessionId,
            connectionGeneration = bound.connectionGeneration,
            policyRevision = policyRevision,
            expiresAtEpochMs = expiresAtEpochMs,
        )
        val physicalConnectionBudget = connectionBudget
            ?: runtime.createConnectionBudget().also { connectionBudget = it }

        val activeWriter = writer
        val admitted: RealWriter? = when {
            activeWriter == null -> runtime.acquireWriter(uid, deviceId, context, physicalConnectionBudget)
            runtime.updateWriter(activeWriter, context) -> activeWriter
            else -> {
                runtime.releaseWriter(activeWriter)
                runtime.acquireWriter(uid, deviceId, context, physicalConnectionBudget)
            }
        }
        lastPolicyRevision = policyRevision
        if (admitted == null || !admitted.enable()) {
            if (activeWriter != null && activeWriter !== admitted) runtime.releaseWriter(activeWriter)
            writer = null
            currentContext = null
            cache.clear()
            return@synchronized RecorderPolicyUpdate(
                bound.correlationId,
                bound.connectionGeneration,
                policyRevision,
                null,
            )
        }

        writer = admitted
        currentContext = context
        val cached = cache.toList()
        cache.clear()
        cached.forEach { admitted.write(it.phase, it.outcome, it.detail, it.throwable, it.occurredAt) }
        RecorderPolicyUpdate(
            bound.correlationId,
            bound.connectionGeneration,
            policyRevision,
            context,
        )
    }

    /** 应用 BASELINE/终结状态，并在存在时返回精确的实时更新 fence。 */
    fun disablePolicy(policyRevision: Long): RecorderPolicyUpdate? = synchronized(lifecycleLock) {
        if (released || policyRevision < lastPolicyRevision) return@synchronized null
        preAuthentication = false
        val bound = binding
        val wasEnabled = writer != null || currentContext != null
        val revisionAdvanced = policyRevision > lastPolicyRevision
        disableLocked(policyRevision)
        if (bound == null || policyRevision <= 0L || (!wasEnabled && !revisionAdvanced)) null else RecorderPolicyUpdate(
            correlationId = bound.correlationId,
            connectionGeneration = bound.connectionGeneration,
            policyRevision = policyRevision,
            context = null,
        )
    }

    /**
     * 不可逆地禁用此物理连接的策略采集。
     *
     * 与普通 BASELINE 更新不同，此调用刻意在每次调用时都返回终结的 null-context
     * 帧。临时连接可能在其到达 SYNC_READY 之前被围住；
     * 因此一旦客户端被允许接收，激活就能发布同一 fence。
     * 只有新的物理连接才拥有新的 Recorder，并可以评估普通版本。
     */
    fun terminalDisablePolicy(): RecorderPolicyUpdate? = synchronized(lifecycleLock) {
        if (released) return@synchronized null
        preAuthentication = false
        val bound = binding
        disableLocked(Long.MAX_VALUE)
        bound?.let {
            RecorderPolicyUpdate(
                correlationId = it.correlationId,
                connectionGeneration = it.connectionGeneration,
                policyRevision = Long.MAX_VALUE,
                context = null,
            )
        }
    }

    /** 鉴权失败绝不会提升鉴权前缓存。 */
    fun discardPreAuthentication() = synchronized(lifecycleLock) {
        if (!released) {
            preAuthentication = false
            cache.clear()
        }
    }

    fun record(
        phase: ConnectionTracePhase,
        outcome: ConnectionTraceOutcome,
        detail: Supplier<String>? = null,
        throwable: Throwable? = null,
    ) {
        val occurredAt = clock().coerceAtLeast(1L)
        synchronized(lifecycleLock) {
            if (released) {
                runtime.recordAfterRecorderRelease()
                return
            }
            val activeWriter = writer
            if (activeWriter == null) {
                if (!preAuthentication) return
                if (cache.size < MAX_CACHE_SIZE) {
                    cache.add(
                        RecordEntry(
                            phase,
                            outcome,
                            detail,
                            throwable?.let(TraceThrowableSnapshot::capture),
                            occurredAt,
                        ),
                    )
                } else {
                    runtime.recordPreAuthOverflow()
                }
                return
            }
            // submit() 是非阻塞的。在持有生命周期锁时预留，决定了
            // 此调用是否先于 release()：被准入的工作能在之后的终结标记下存活。
            activeWriter.write(
                phase,
                outcome,
                detail,
                throwable?.let(TraceThrowableSnapshot::capture),
                occurredAt,
            )
        }
    }

    fun record(
        phase: ConnectionTracePhase,
        outcome: ConnectionTraceOutcome,
        detail: String,
        throwable: Throwable? = null,
    ) = record(phase, outcome, Supplier { detail }, throwable)

    fun release() {
        val writerToRelease = synchronized(lifecycleLock) {
            if (released) return
            released = true
            preAuthentication = false
            cache.clear()
            currentContext = null
            connectionBudget = null
            writer.also { writer = null }
        }
        writerToRelease?.let(runtime::releaseWriter)
    }

    private fun disableLocked(policyRevision: Long) {
        lastPolicyRevision = maxOf(lastPolicyRevision, policyRevision)
        cache.clear()
        currentContext = null
        writer?.let(runtime::releaseWriter)
        writer = null
    }
}

data class RecorderPolicyUpdate(
    val correlationId: String,
    val connectionGeneration: Long,
    val policyRevision: Long,
    val context: ConnectionTraceContext?,
)

data class RecorderAuthenticationIdentity(
    val correlationId: String,
    val connectionGeneration: Long,
)

private data class AuthenticationBinding(
    val correlationId: String,
    val connectionGeneration: Long,
    val serverSessionId: String,
    val traceId: String,
)

private data class RecordEntry(
    val phase: ConnectionTracePhase,
    val outcome: ConnectionTraceOutcome,
    val detail: Supplier<String>?,
    val throwable: TraceThrowableSnapshot?,
    val occurredAt: Long,
)

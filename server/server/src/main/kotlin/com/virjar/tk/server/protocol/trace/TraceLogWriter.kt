package com.virjar.tk.server.protocol.trace

import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventDraft
import com.virjar.tk.server.domain.telemetry.ConnectionTraceEventSink
import com.virjar.tk.server.domain.telemetry.ConnectionTraceOutcome
import com.virjar.tk.server.domain.telemetry.ConnectionTracePhase
import com.virjar.tk.server.domain.telemetry.sanitizeConnectionTraceDetail
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/** 有界的异常身份；绝不保留 Throwable、消息、cause、suppressed 数据或文件路径。 */
internal data class TraceThrowableSnapshot(
    val typeName: String,
    val frameClassName: String?,
    val frameMethodName: String?,
    val frameLineNumber: Int?,
) {
    companion object {
        fun capture(throwable: Throwable): TraceThrowableSnapshot {
            val top = throwable.stackTrace.firstOrNull()
            return TraceThrowableSnapshot(
                typeName = throwable::class.java.name.take(MAX_COMPONENT_CHARS),
                frameClassName = top?.className?.take(MAX_COMPONENT_CHARS),
                frameMethodName = top?.methodName?.take(MAX_COMPONENT_CHARS),
                frameLineNumber = top?.lineNumber?.coerceAtLeast(0),
            )
        }

        private const val MAX_COMPONENT_CHARS = 128
    }
}

/**
 * 硬预算属于一个物理 TCP 连接，而不是单个策略写入器。
 * 把这些计数器放在 [RealWriter] 之外，可防止策略开关重置它们。
 */
internal class TraceConnectionBudget(
    private val eventLimit: Long,
    private val byteLimit: Long,
) {
    private val reservedEvents = AtomicLong(0)
    private val persistedBytes = AtomicLong(0)

    internal fun reserveEvent(): Boolean {
        while (true) {
            val current = reservedEvents.get()
            if (current >= eventLimit) return false
            if (reservedEvents.compareAndSet(current, current + 1L)) return true
        }
    }

    internal fun reserveBytes(bytes: Long): Boolean {
        while (true) {
            val current = persistedBytes.get()
            if (bytes > byteLimit - current) return false
            if (persistedBytes.compareAndSet(current, current + bytes)) return true
        }
    }
}

internal class RealWriter(
    val uid: String,
    val deviceId: String,
    initialContext: ConnectionTraceContext,
    private val owner: TraceRuntime,
    private val connectionBudget: TraceConnectionBudget,
) {
    private val terminal = AtomicBoolean(false)
    private val contextRef = AtomicReference(initialContext)

    fun write(
        phase: ConnectionTracePhase,
        outcome: ConnectionTraceOutcome,
        detail: Supplier<String>? = null,
        throwable: TraceThrowableSnapshot? = null,
        occurredAt: Long = System.currentTimeMillis().coerceAtLeast(1L),
    ) {
        owner.submit(this, phase, outcome, detail, throwable, occurredAt)
    }

    fun enable(): Boolean = !terminal.get() && !contextRef.get().isExpired(owner.now())

    internal fun context(): ConnectionTraceContext = contextRef.get()

    internal fun updateContext(context: ConnectionTraceContext): Boolean {
        while (!terminal.get()) {
            val current = contextRef.get()
            if (!current.samePhysicalConnection(context) || context.policyRevision < current.policyRevision) {
                return false
            }
            if (contextRef.compareAndSet(current, context)) return true
        }
        return false
    }

    internal fun reserveEvent(now: Long): ReserveResult {
        if (terminal.get()) return ReserveResult.Released
        // 在预留事件之前冻结 context。并发的策略更新可能在此之后发布
        // 更新的 context，但此调用属于此处可观察的版本。
        val context = contextRef.get()
        if (context.isExpired(now)) return ReserveResult.Expired
        return if (connectionBudget.reserveEvent()) {
            ReserveResult.Accepted(context)
        } else {
            ReserveResult.EventBudget
        }
    }

    internal fun reserveBytes(bytes: Long): Boolean = connectionBudget.reserveBytes(bytes)

    internal fun terminate(): Boolean = terminal.compareAndSet(false, true)
    internal fun isOwnedBy(candidate: TraceRuntime): Boolean = owner === candidate

    internal sealed interface ReserveResult {
        data class Accepted(val context: ConnectionTraceContext) : ReserveResult
        data object Released : ReserveResult
        data object Expired : ReserveResult
        data object EventBudget : ReserveResult
    }
}

/**
 * 原子的策略批准写入器准入，加上有界、非阻塞的 trace 投递；一个 TCP 服务器拥有一个
 * 实例，不存在进程全局线程或隐式写入器注册表。
 *
 * 准入失败返回 null（容量满、进程关闭、context 过期），由 [Recorder] 映射为
 * null-context 的策略更新；普通 trace 日志文件通道已删除，唯一落盘通道是 [eventSink]。
 */
internal class TraceRuntime(
    threadName: String = "trace",
    private val maxWriters: Int = DEFAULT_MAX_WRITERS,
    queueCapacity: Int = DEFAULT_MAX_PENDING_TRACE_EVENTS,
    workerJoinTimeoutMillis: Long = DEFAULT_WORKER_JOIN_TIMEOUT_MILLIS,
    private val maxEventsPerConnection: Long = DEFAULT_MAX_EVENTS_PER_CONNECTION,
    private val maxBytesPerConnection: Long = DEFAULT_MAX_BYTES_PER_CONNECTION,
    private val maxDetailChars: Int = DEFAULT_MAX_DETAIL_CHARS,
    private val maxThrowableChars: Int = DEFAULT_MAX_THROWABLE_CHARS,
    private val eventSink: ConnectionTraceEventSink = DISCARDING_EVENT_SINK,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    internal fun now(): Long = clock()

    private val registry = mutableSetOf<RealWriter>()
    private val closeLock = Any()
    private val closed = AtomicBoolean(false)
    private val deniedWriters = AtomicLong(0)
    private val writerWritesAfterRelease = AtomicLong(0)
    private val recorderWritesAfterRelease = AtomicLong(0)
    private val droppedExpired = AtomicLong(0)
    private val droppedEventBudget = AtomicLong(0)
    private val droppedByteBudget = AtomicLong(0)
    private val droppedUnsafeDetail = AtomicLong(0)
    private val preAuthOverflow = AtomicLong(0)
    private val dispatcher = TraceDispatchQueue(
        threadName = threadName,
        capacity = queueCapacity,
        workerJoinTimeoutMillis = workerJoinTimeoutMillis,
    ) { work ->
        // 连接轨迹的唯一落盘通道是遥测 sink（Lucene connection-trace-index，经
        // 定向诊断策略与客户端事件流同开同关）。独立的文件日志通道已删除：
        // 分析 bug 时客户端与服务端轨迹本就要按五字段上下文联查。
        check(eventSink.tryAppend(work.toDraft())) { "connection trace sink rejected event" }
    }

    init {
        require(maxWriters > 0) { "trace writer capacity must be positive" }
        require(maxEventsPerConnection > 0L) { "trace event budget must be positive" }
        require(maxBytesPerConnection > 0L) { "trace byte budget must be positive" }
        require(maxDetailChars in 1..MAX_SAFE_DETAIL_CHARS) { "invalid trace detail bound" }
        require(maxThrowableChars in 1..MAX_SAFE_DETAIL_CHARS) { "invalid trace throwable bound" }
    }

    fun createConnectionBudget(): TraceConnectionBudget = TraceConnectionBudget(
        eventLimit = maxEventsPerConnection,
        byteLimit = maxBytesPerConnection,
    )

    fun acquireWriter(
        uid: String,
        deviceId: String,
        context: ConnectionTraceContext,
        connectionBudget: TraceConnectionBudget,
    ): RealWriter? = synchronized(registry) {
        reapExpiredLocked(clock())
        if (closed.get() || context.isExpired(clock()) || registry.size >= maxWriters) {
            deniedWriters.incrementAndGet()
            null
        } else {
            RealWriter(
                uid = uid,
                deviceId = deviceId,
                initialContext = context,
                owner = this,
                connectionBudget = connectionBudget,
            ).also(registry::add)
        }
    }

    fun updateWriter(writer: RealWriter, context: ConnectionTraceContext): Boolean {
        if (!writer.isOwnedBy(this)) return false
        if (context.isExpired(clock())) {
            expire(writer)
            return false
        }
        return writer.updateContext(context)
    }

    fun releaseWriter(writer: RealWriter) {
        if (!writer.isOwnedBy(this)) return
        synchronized(registry) {
            writer.terminate()
            registry.remove(writer)
        }
    }

    fun recordAfterRecorderRelease() {
        recorderWritesAfterRelease.incrementAndGet()
    }

    fun recordPreAuthOverflow() {
        preAuthOverflow.incrementAndGet()
    }

    fun submit(
        writer: RealWriter,
        phase: ConnectionTracePhase,
        outcome: ConnectionTraceOutcome,
        detail: Supplier<String>?,
        throwable: TraceThrowableSnapshot?,
        occurredAt: Long,
    ) {
        when (val reservation = writer.reserveEvent(occurredAt)) {
            RealWriter.ReserveResult.Released -> writerWritesAfterRelease.incrementAndGet()
            RealWriter.ReserveResult.Expired -> {
                droppedExpired.incrementAndGet()
                expire(writer)
            }
            RealWriter.ReserveResult.EventBudget -> droppedEventBudget.incrementAndGet()
            is RealWriter.ReserveResult.Accepted -> {
                dispatcher.offer(
                    PendingTraceWork {
                        resolve(
                            writer,
                            reservation.context,
                            occurredAt,
                            phase,
                            outcome,
                            detail,
                            throwable,
                        )
                    },
                )
            }
        }
    }

    private fun resolve(
        writer: RealWriter,
        context: ConnectionTraceContext,
        occurredAt: Long,
        phase: ConnectionTracePhase,
        outcome: ConnectionTraceOutcome,
        detail: Supplier<String>?,
        throwable: TraceThrowableSnapshot?,
    ): TraceWork? {
        val now = clock()
        if (context.isExpired(now)) {
            droppedExpired.incrementAndGet()
            expire(writer)
            return null
        }
        val safeDetail = safeDetail(phase, detail, throwable)
        val byteCount = encodedSize(context, writer.uid, writer.deviceId, safeDetail)
        if (!writer.reserveBytes(byteCount)) {
            droppedByteBudget.incrementAndGet()
            return null
        }
        return TraceWork(
            uid = writer.uid,
            deviceId = writer.deviceId,
            context = context,
            occurredAt = occurredAt.coerceAtLeast(1L),
            phase = phase,
            outcome = outcome,
            detail = safeDetail,
        )
    }

    private fun safeDetail(
        phase: ConnectionTracePhase,
        detail: Supplier<String>?,
        throwable: TraceThrowableSnapshot?,
    ): String? {
        val raw = try {
            detail?.get()
        } catch (_: Throwable) {
            droppedUnsafeDetail.incrementAndGet()
            null
        }
        val parsed = raw?.let { parseWhitelistedDetail(phase, it) }
        if (raw != null && parsed == null) droppedUnsafeDetail.incrementAndGet()
        val throwableSummary = throwable?.let(::safeThrowableSummary)
        val combined = listOfNotNull(parsed, throwableSummary)
            .joinToString(" ")
            .takeIf(String::isNotBlank)
            ?.take(maxDetailChars)
        return try {
            sanitizeConnectionTraceDetail(combined)
        } catch (_: IllegalArgumentException) {
            droppedUnsafeDetail.incrementAndGet()
            null
        }
    }

    private fun safeThrowableSummary(throwable: TraceThrowableSnapshot): String {
        val type = throwable.typeName.filter(::isSafeDetailChar).take(MAX_DETAIL_VALUE_CHARS)
            .ifEmpty { "Throwable" }
        val frame = if (throwable.frameClassName != null && throwable.frameMethodName != null) {
            val owner = throwable.frameClassName.filter(::isSafeDetailChar).take(MAX_DETAIL_VALUE_CHARS)
            val method = throwable.frameMethodName.filter(::isSafeDetailChar).take(MAX_DETAIL_VALUE_CHARS)
            " frame=${owner}#${method}:${throwable.frameLineNumber ?: 0}"
        } else {
            ""
        }
        return "errorType=$type$frame".take(maxThrowableChars)
    }

    private fun parseWhitelistedDetail(phase: ConnectionTracePhase, raw: String): String? {
        val normalized = raw.trim()
        if (normalized.isEmpty() || normalized.length > maxDetailChars) return null
        val allowed = ALLOWED_DETAIL_KEYS.getValue(phase)
        val tokens = normalized.split(' ')
        if (tokens.size > MAX_DETAIL_FIELDS) return null
        val accepted = ArrayList<String>(tokens.size)
        for (token in tokens) {
            val split = token.indexOf('=')
            if (split <= 0 || split == token.lastIndex) return null
            val key = token.substring(0, split)
            val value = token.substring(split + 1)
            if (key !in allowed || value.length > MAX_DETAIL_VALUE_CHARS || value.any { !isSafeDetailChar(it) }) {
                return null
            }
            accepted += "$key=$value"
        }
        return accepted.joinToString(" ")
    }

    private fun encodedSize(
        context: ConnectionTraceContext,
        uid: String,
        deviceId: String,
        detail: String?,
    ): Long = listOf(
        context.correlationId,
        context.traceId,
        context.sessionId,
        uid,
        deviceId,
        detail.orEmpty(),
    ).sumOf { it.toByteArray(StandardCharsets.UTF_8).size.toLong() } + FIXED_EVENT_BYTES

    private fun expire(writer: RealWriter) {
        synchronized(registry) {
            writer.terminate()
            registry.remove(writer)
        }
    }

    private fun reapExpiredLocked(now: Long) {
        val iterator = registry.iterator()
        while (iterator.hasNext()) {
            val writer = iterator.next()
            if (writer.context().isExpired(now)) {
                writer.terminate()
                iterator.remove()
            }
        }
    }

    fun snapshot(): TraceRuntimeSnapshot {
        val active = synchronized(registry) {
            reapExpiredLocked(clock())
            registry.size
        }
        val dispatch = dispatcher.snapshot()
        return TraceRuntimeSnapshot(
            activeWriters = active,
            maxWriters = maxWriters,
            queuedEvents = dispatch.queued,
            queueCapacity = dispatch.capacity,
            deniedWriters = deniedWriters.get(),
            acceptedEvents = dispatch.accepted,
            deliveredEvents = dispatch.delivered,
            droppedQueueFull = dispatch.droppedQueueFull,
            droppedAfterRelease = writerWritesAfterRelease.get() + recorderWritesAfterRelease.get(),
            droppedDispatcherClosed = dispatch.droppedDispatcherClosed,
            deliveryFailures = dispatch.deliveryFailures,
            droppedExpired = droppedExpired.get(),
            droppedEventBudget = droppedEventBudget.get(),
            droppedByteBudget = droppedByteBudget.get(),
            droppedUnsafeDetail = droppedUnsafeDetail.get(),
            droppedPreAuthOverflow = preAuthOverflow.get(),
        )
    }

    override fun close() = synchronized(closeLock) {
        if (closed.compareAndSet(false, true)) {
            synchronized(registry) {
                registry.forEach(RealWriter::terminate)
                registry.clear()
            }
        }
        dispatcher.close()
    }

    private fun TraceWork.toDraft(): ConnectionTraceEventDraft = ConnectionTraceEventDraft(
        uid = uid,
        deviceId = deviceId,
        correlationId = context.correlationId,
        traceId = context.traceId,
        sessionId = context.sessionId,
        connectionGeneration = context.connectionGeneration,
        policyRevision = context.policyRevision,
        occurredAt = occurredAt,
        phase = phase,
        outcome = outcome,
        detail = detail,
    )

    private companion object {
        val DISCARDING_EVENT_SINK = ConnectionTraceEventSink { true }
        const val DEFAULT_MAX_WRITERS = 100
        const val DEFAULT_MAX_PENDING_TRACE_EVENTS = 1_024
        const val DEFAULT_WORKER_JOIN_TIMEOUT_MILLIS = 1_000L
        const val DEFAULT_MAX_EVENTS_PER_CONNECTION = 4_096L
        const val DEFAULT_MAX_BYTES_PER_CONNECTION = 1_048_576L
        const val DEFAULT_MAX_DETAIL_CHARS = 512
        const val DEFAULT_MAX_THROWABLE_CHARS = 192
        const val MAX_SAFE_DETAIL_CHARS = 512
        const val MAX_DETAIL_VALUE_CHARS = 96
        const val MAX_DETAIL_FIELDS = 8
        const val FIXED_EVENT_BYTES = 96L

        val ALLOWED_DETAIL_KEYS: Map<ConnectionTracePhase, Set<String>> = mapOf(
            ConnectionTracePhase.CONNECTION to setOf("event", "state", "type", "timeoutSeconds"),
            ConnectionTracePhase.AUTHENTICATION to setOf("event", "state", "authType", "reason", "timeoutSeconds"),
            ConnectionTracePhase.POLICY to setOf("event", "state", "revision", "mode"),
            ConnectionTracePhase.RPC to setOf("event", "service", "method", "status"),
            ConnectionTracePhase.SYNC to setOf("event", "state", "cursor", "count", "timeoutSeconds"),
            ConnectionTracePhase.EVENT to setOf("event", "type"),
            ConnectionTracePhase.MESSAGE to setOf("event", "type", "status", "serverSeq"),
            ConnectionTracePhase.HEARTBEAT to setOf("event", "state"),
            ConnectionTracePhase.SHUTDOWN to setOf("event", "state", "reason"),
        )

        fun isSafeDetailChar(value: Char): Boolean =
            value.isLetterOrDigit() || value == '_' || value == '-' || value == '.' || value == ':' || value == '#'
    }
}

internal data class TraceRuntimeSnapshot(
    val activeWriters: Int,
    val maxWriters: Int,
    val queuedEvents: Int,
    val queueCapacity: Int,
    val deniedWriters: Long,
    val acceptedEvents: Long,
    val deliveredEvents: Long,
    val droppedQueueFull: Long,
    val droppedAfterRelease: Long,
    val droppedDispatcherClosed: Long,
    val deliveryFailures: Long,
    val droppedExpired: Long,
    val droppedEventBudget: Long,
    val droppedByteBudget: Long,
    val droppedUnsafeDetail: Long,
    val droppedPreAuthOverflow: Long,
)

private fun ConnectionTraceContext.samePhysicalConnection(other: ConnectionTraceContext): Boolean =
    correlationId == other.correlationId &&
        traceId == other.traceId &&
        sessionId == other.sessionId &&
        connectionGeneration == other.connectionGeneration

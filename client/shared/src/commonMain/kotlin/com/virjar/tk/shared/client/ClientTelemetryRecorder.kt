package com.virjar.tk.shared.client

import com.virjar.tk.protocol.telemetry.ClientRuntimeInfo
import com.virjar.tk.protocol.telemetry.ClientTelemetryLimits
import com.virjar.tk.protocol.telemetry.ClientTelemetryValidation
import com.virjar.tk.protocol.telemetry.TelemetryActionOutcome
import com.virjar.tk.protocol.telemetry.TelemetryActionPayload
import com.virjar.tk.protocol.telemetry.TelemetryBatch
import com.virjar.tk.protocol.telemetry.TelemetryEvent
import com.virjar.tk.protocol.telemetry.TelemetryEventKind
import com.virjar.tk.protocol.telemetry.TelemetryFaultPayload
import com.virjar.tk.protocol.telemetry.TelemetryLogLevel
import com.virjar.tk.protocol.telemetry.TelemetryLogPayload
import com.virjar.tk.protocol.telemetry.TelemetryMediaKind
import com.virjar.tk.protocol.telemetry.TelemetryMediaOperation
import com.virjar.tk.protocol.telemetry.TelemetryMediaPayload
import com.virjar.tk.protocol.telemetry.TelemetryNoticeLevel
import com.virjar.tk.protocol.telemetry.TelemetryNoticeOrigin
import com.virjar.tk.protocol.telemetry.TelemetryOutgoingQueuePayload
import com.virjar.tk.protocol.telemetry.TelemetryPageDwellPayload
import com.virjar.tk.protocol.telemetry.TelemetryPageExitReason
import com.virjar.tk.protocol.telemetry.TelemetryPolicy
import com.virjar.tk.protocol.telemetry.TelemetryPolicyMode
import com.virjar.tk.protocol.telemetry.TelemetryStackFrame
import com.virjar.tk.protocol.telemetry.TelemetrySystemPayload
import com.virjar.tk.protocol.telemetry.TelemetryUserNoticePayload
import com.virjar.tk.protocol.telemetry.TELEMETRY_OUTGOING_QUEUE_EVENT_NAME
import com.virjar.tk.protocol.telemetry.ConnectionTraceContext
import kotlinx.serialization.encodeToString
import java.util.ArrayDeque
import java.util.UUID
import kotlin.text.CharCategory

data class ClientTelemetryRecorderStats(
    val pendingEvents: Int,
    val droppedEvents: Long,
)

private data class InMemoryTelemetryBatch(
    val batch: TelemetryBatch,
    val encodedBytes: Int,
)

/** 会话拥有的策略状态。过期的 DIAGNOSTIC 策略会自动坍缩为 BASELINE。 */
class ClientTelemetryPolicyState(
    initialPolicy: TelemetryPolicy = TelemetryPolicy.baseline(),
) {
    private var current = initialPolicy.also(ClientTelemetryValidation::requireValid)

    @Synchronized
    fun snapshot(nowEpochMs: Long = System.currentTimeMillis()): TelemetryPolicy =
        if (current.mode == TelemetryPolicyMode.DIAGNOSTIC && nowEpochMs >= current.expiresAtEpochMs) {
            TelemetryPolicy.baseline()
        } else {
            current
        }

    @Synchronized
    fun apply(policy: TelemetryPolicy): Boolean {
        ClientTelemetryValidation.requireValid(policy)
        if (policy.issuedAtEpochMs < current.issuedAtEpochMs || policy == current) return false
        current = policy
        return true
    }
}

/**
 * 结构化事件准入、策略过滤与批处理。它从不捕获任意 map、消息正文、throwable 消息、URL 或本地
 * 绝对路径。
 */
class ClientTelemetryRecorder internal constructor(
    val runtimeInfo: ClientRuntimeInfo,
    private val spool: ClientTelemetrySpool,
    internal val policyState: ClientTelemetryPolicyState = ClientTelemetryPolicyState(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val connectionTraceContextProvider: () -> ConnectionTraceContext? = { null },
) {
    private val runId = newId()
    private var nextSequence = 0L
    private var pendingBatchId: String? = null
    private var pendingCreatedAt = 0L
    private var lastBatchCreatedAt = 0L
    private val pendingEvents = mutableListOf<TelemetryEvent>()
    private var pendingEventBytes = 0L
    private val readyBatches = ArrayDeque<InMemoryTelemetryBatch>()
    private var inFlightBatch: InMemoryTelemetryBatch? = null
    private val flushLock = Any()
    private val admittedEventTimes = ArrayDeque<Long>()
    private var byteBudgetDay = Long.MIN_VALUE
    private var admittedBytesToday = 0L
    private var rejectedEventCount = 0L

    init {
        ClientTelemetryValidation.requireValid(runtimeInfo)
        requireSafeGeneratedId(runId)
    }

    fun recordPageDwell(
        page: String,
        durationMillis: Long,
        exitReason: TelemetryPageExitReason = TelemetryPageExitReason.UNKNOWN,
    ): Boolean = record(
        eventName = "page.dwell",
        kind = TelemetryEventKind.PAGE_DWELL,
        payload = TelemetryPageDwellPayload(page, durationMillis, exitReason),
    )

    fun recordAction(
        page: String,
        action: String,
        outcome: TelemetryActionOutcome = TelemetryActionOutcome.UNKNOWN,
    ): Boolean = record(
        eventName = action,
        kind = TelemetryEventKind.ACTION,
        payload = TelemetryActionPayload(page, action, outcome),
    )

    fun recordSystem(
        name: String,
        state: String? = null,
        critical: Boolean = false,
    ): Boolean = record(
        eventName = name,
        kind = TelemetryEventKind.SYSTEM,
        payload = TelemetrySystemPayload(name, state?.let(::sanitizeTelemetryText), critical),
        flushImmediately = critical,
    )

    fun recordUserNotice(
        feedbackCode: String,
        page: String? = null,
        action: String? = null,
        origin: TelemetryNoticeOrigin = TelemetryNoticeOrigin.UNKNOWN,
        message: String,
        level: TelemetryNoticeLevel,
    ): Boolean = record(
        eventName = feedbackCode,
        kind = TelemetryEventKind.USER_NOTICE,
        payload = TelemetryUserNoticePayload(
            feedbackCode = feedbackCode,
            page = page,
            action = action,
            origin = origin,
            message = sanitizeTelemetryText(message),
            level = level,
        ),
        flushImmediately = true,
    )

    fun recordMedia(
        mediaKind: TelemetryMediaKind,
        operation: TelemetryMediaOperation,
        outcome: TelemetryActionOutcome,
        byteCount: Long? = null,
        durationMillis: Long? = null,
        reasonCode: String? = null,
    ): Boolean = record(
        eventName = reasonCode ?: "media.${operation.name.lowercase()}.${outcome.name.lowercase()}",
        kind = TelemetryEventKind.MEDIA,
        payload = TelemetryMediaPayload(
            mediaKind = mediaKind,
            operation = operation,
            outcome = outcome,
            byteCount = byteCount,
            durationMillis = durationMillis,
            reasonCode = reasonCode,
        ),
        flushImmediately = outcome == TelemetryActionOutcome.FAILED,
    )

    /**
     * 只记录有界的聚合队列健康度。没有任何重载接受消息、会话、路径、标识符、错误或任意标签，
     * 因此调用方无法意外扩大诊断范围。
     */
    fun recordOutgoingQueue(
        pendingCount: Int,
        retryWaitCount: Int,
        terminalFailedCount: Int,
        oldestActiveAgeMillis: Long,
        maxAttemptCount: Long,
    ): Boolean = record(
        eventName = TELEMETRY_OUTGOING_QUEUE_EVENT_NAME,
        kind = TelemetryEventKind.OUTGOING_QUEUE,
        payload = TelemetryOutgoingQueuePayload(
            pendingCount = pendingCount,
            retryWaitCount = retryWaitCount,
            terminalFailedCount = terminalFailedCount,
            oldestActiveAgeMillis = oldestActiveAgeMillis,
            maxAttemptCount = maxAttemptCount,
        ),
    )

    /** 安全的产品 fault API：只接受稳定 code，绝不接受 Throwable 或自由文本。 */
    fun recordFault(
        code: String,
        page: String? = null,
        action: String? = null,
        origin: String? = null,
        reasonCode: String? = null,
    ): Boolean = record(
        eventName = code,
        kind = TelemetryEventKind.FAULT,
        payload = TelemetryFaultPayload(
            logger = "ClientTelemetry",
            summary = reasonCode ?: code,
            faultCode = code,
            page = page,
            action = action,
            origin = origin,
            reasonCode = reasonCode,
        ),
        flushImmediately = true,
    )

    internal fun recordAppLog(
        level: TelemetryLogLevel,
        logger: String,
        message: String,
        throwable: Throwable? = null,
    ): Boolean {
        val safeLogger = stableTelemetryName(logger, "logger")
        return if (throwable == null && level != TelemetryLogLevel.ERROR) {
            record(
                eventName = "log.${level.name.lowercase()}",
                kind = TelemetryEventKind.LOG,
                payload = TelemetryLogPayload(level, safeLogger, sanitizeTelemetryText(message)),
            )
        } else {
            record(
                eventName = "fault.reported",
                kind = TelemetryEventKind.FAULT,
                payload = TelemetryFaultPayload(
                    logger = safeLogger,
                    summary = sanitizeTelemetryText(message),
                    faultCode = "legacy.app_log",
                    origin = "app_log",
                    exceptionClass = throwable?.javaClass?.name?.let { stableTelemetryName(it, "Throwable") },
                    stackFrames = throwable?.stackTrace.orEmpty()
                        .take(ClientTelemetryLimits.MAX_STACK_FRAMES)
                        .map(::safeStackFrame),
                    fatal = false,
                ),
                flushImmediately = true,
            )
        }
    }

    /** 崩溃处理器刻意不转发其原始的多行崩溃文本。 */
    internal fun recordFatalCrash(): Boolean = record(
        eventName = "fault.uncaught",
        kind = TelemetryEventKind.FAULT,
        payload = TelemetryFaultPayload(
            logger = "UncaughtException",
            summary = "Process terminated by an uncaught exception",
            faultCode = "process.uncaught_exception",
            origin = "platform",
            fatal = true,
        ),
        flushImmediately = true,
    )

    /** 只由 uploader 的阻塞 IO worker 调用；record 路径绝不进入此边界。 */
    fun flush(): Boolean = synchronized(flushLock) {
        var complete = false
        while (!complete) {
            val batch = synchronized(this) {
                inFlightBatch ?: run {
                    if (readyBatches.isEmpty() && pendingEvents.isNotEmpty()) sealPendingLocked()
                    readyBatches.pollFirst()?.also { inFlightBatch = it }
                }
            }
            if (batch == null) {
                complete = true
                continue
            }
            val accepted = spool.append(
                batch.batch,
                highPriority = batch.batch.events.any(::isHighPriorityEvent),
            )
            if (!accepted) return@synchronized false
            synchronized(this) {
                check(inFlightBatch == batch) { "Telemetry flush ownership changed" }
                inFlightBatch = null
            }
        }
        true
    }

    @Synchronized
    fun stats(): ClientTelemetryRecorderStats = ClientTelemetryRecorderStats(
        pendingEvents = pendingEvents.size +
            readyBatches.sumOf { it.batch.events.size } +
            inFlightBatch?.batch?.events.orEmpty().size,
        droppedEvents = rejectedEventCount + spool.evictedEvents(),
    )

    @Synchronized
    internal fun heartbeatBatch(): TelemetryBatch {
        val now = positiveNow()
        return TelemetryBatch(
            batchId = checkedNewId(),
            createdAtEpochMs = now,
            runtimeInfo = runtimeInfo,
            events = emptyList(),
            heartbeat = true,
        ).also(ClientTelemetryValidation::requireValid)
    }

    internal fun applyPolicy(policy: TelemetryPolicy) = policyState.apply(policy)

    /** 用于对诊断队列快照去重的精确生效代际。 */
    @Synchronized
    internal fun outgoingQueuePolicySnapshot(): TelemetryPolicy =
        policyState.snapshot(positiveNow())

    @Synchronized
    private fun record(
        eventName: String,
        kind: TelemetryEventKind,
        payload: com.virjar.tk.protocol.telemetry.TelemetryEventPayload,
        flushImmediately: Boolean = false,
    ): Boolean {
        val now = positiveNow()
        val policy = policyState.snapshot(now)
        val event = TelemetryEvent(
            eventId = checkedNewId(),
            runId = runId,
            sequence = nextSequence,
            occurredAtEpochMs = now,
            eventName = eventName,
            kind = kind,
            payload = payload,
            connectionTraceContext = connectionTraceContextProvider()
                ?.takeUnless { context -> context.isExpired(now) },
        )
        ClientTelemetryValidation.requireValid(event)
        if (!ClientTelemetryValidation.allows(policy, event, now)) return false
        val eventBytes = ClientTelemetrySpool.TELEMETRY_JSON.encodeToString(event).encodeToByteArray().size.toLong()
        if (eventBytes > MAX_SINGLE_EVENT_BYTES) {
            rejectedEventCount++
            return false
        }
        if (!admitBudget(policy, now, eventBytes)) {
            rejectedEventCount++
            return false
        }

        val highPriority = isHighPriorityEvent(event)
        val crossesPolicyBoundary = pendingEvents.firstOrNull()
            ?.let { pending -> isHighPriorityEvent(pending) != highPriority }
            ?: false
        val exceedsEventCount = pendingEvents.size >= policy.maxBatchEvents
        val exceedsByteBudget = pendingEvents.isNotEmpty() &&
            pendingEventBytes + eventBytes + BATCH_ENVELOPE_RESERVE_BYTES > MAX_IN_MEMORY_BATCH_BYTES.toLong()
        if ((crossesPolicyBoundary || exceedsEventCount || exceedsByteBudget) && !sealPendingLocked(highPriority)) {
            rejectedEventCount++
            return false
        }

        if (pendingEvents.isEmpty()) {
            pendingBatchId = checkedNewId()
            check(lastBatchCreatedAt < Long.MAX_VALUE) { "Telemetry batch clock exhausted" }
            pendingCreatedAt = maxOf(now, lastBatchCreatedAt + 1L)
            lastBatchCreatedAt = pendingCreatedAt
        }
        pendingEvents += event
        pendingEventBytes += eventBytes
        nextSequence++
        admittedEventTimes.addLast(now)
        admittedBytesToday += eventBytes
        if (flushImmediately || pendingEvents.size >= policy.maxBatchEvents) {
            // 封存只是一次内存列表拷贝。如果就绪队列饱和，当前批仍保持内存有界，
            // 等待 uploader worker 稍后排空。
            sealPendingLocked(highPriority)
        }
        return true
    }

    private fun sealPendingLocked(incomingHighPriority: Boolean = false): Boolean {
        if (pendingEvents.isEmpty()) return true
        val batch = TelemetryBatch(
            batchId = checkNotNull(pendingBatchId),
            createdAtEpochMs = pendingCreatedAt,
            runtimeInfo = runtimeInfo,
            events = pendingEvents.toList(),
            heartbeat = false,
        )
        ClientTelemetryValidation.requireValid(batch)
        val encodedBytes = ClientTelemetrySpool.TELEMETRY_JSON.encodeToString(batch).encodeToByteArray().size
        if (encodedBytes > MAX_IN_MEMORY_BATCH_BYTES) {
            // 准入预留了信封空间，因此只有未来的序列化器形状变化才会走到这里。丢弃这个有界批，
            // 而不是永久毒化队列。
            rejectedEventCount += batch.events.size
            clearPendingLocked()
            return false
        }
        val batchHighPriority = isHighPriorityEvent(batch.events.first())
        check(batch.events.all { isHighPriorityEvent(it) == batchHighPriority }) {
            "Telemetry batch crossed the BASELINE policy boundary"
        }
        while (
            readyBatches.size + (if (inFlightBatch == null) 0 else 1) >= MAX_IN_MEMORY_BATCHES ||
            readyBatches.sumOf(InMemoryTelemetryBatch::encodedBytes) +
                (inFlightBatch?.encodedBytes ?: 0) + encodedBytes > MAX_TOTAL_IN_MEMORY_BYTES
        ) {
            if (!incomingHighPriority && !batchHighPriority) return false
            val evictable = readyBatches.firstOrNull { ready -> ready.batch.events.none(::isHighPriorityEvent) }
                ?: return false
            check(readyBatches.remove(evictable)) { "Telemetry ready batch disappeared" }
            rejectedEventCount += evictable.batch.events.size
        }
        readyBatches.addLast(InMemoryTelemetryBatch(batch, encodedBytes))
        clearPendingLocked()
        return true
    }

    private fun clearPendingLocked() {
        pendingEvents.clear()
        pendingBatchId = null
        pendingCreatedAt = 0L
        pendingEventBytes = 0L
    }

    private fun admitBudget(policy: TelemetryPolicy, now: Long, encodedBytes: Long): Boolean {
        val minuteCutoff = now - 60_000L
        while (admittedEventTimes.isNotEmpty() && admittedEventTimes.first() <= minuteCutoff) {
            admittedEventTimes.removeFirst()
        }
        if (admittedEventTimes.size >= policy.maxEventsPerMinute) return false
        val day = now / MILLIS_PER_DAY
        if (day != byteBudgetDay) {
            byteBudgetDay = day
            admittedBytesToday = 0L
        }
        return admittedBytesToday + encodedBytes <= policy.maxBytesPerDay
    }

    private fun checkedNewId(): String = newId().also(::requireSafeGeneratedId)

    private fun positiveNow(): Long = clock().coerceAtLeast(1L)

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
        const val MAX_IN_MEMORY_BATCHES = 4
        const val MAX_SINGLE_EVENT_BYTES = 64L * 1024L
        const val MAX_IN_MEMORY_BATCH_BYTES = 256 * 1024
        const val BATCH_ENVELOPE_RESERVE_BYTES = 8L * 1024L
        const val MAX_TOTAL_IN_MEMORY_BYTES = MAX_IN_MEMORY_BATCHES * MAX_IN_MEMORY_BATCH_BYTES
    }
}

private fun isHighPriorityEvent(event: TelemetryEvent): Boolean = ClientTelemetryValidation.allows(
    TelemetryPolicy.baseline(),
    event,
    event.occurredAtEpochMs,
)

private fun safeStackFrame(frame: StackTraceElement): TelemetryStackFrame = TelemetryStackFrame(
    className = frame.className.take(ClientTelemetryLimits.MAX_STACK_FIELD_CHARS),
    methodName = frame.methodName.take(ClientTelemetryLimits.MAX_STACK_FIELD_CHARS),
    fileName = frame.fileName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.take(ClientTelemetryLimits.MAX_STACK_FIELD_CHARS),
    lineNumber = frame.lineNumber,
)

/** 面向遗留 AppLog 文本的保守单行桥接。 */
internal fun sanitizeTelemetryText(raw: String): String {
    var value = raw
        .replace('\r', ' ')
        .replace('\n', ' ')
        // 在匹配之前先移除不可见格式字符，这样零宽分隔符就无法拆开本来可识别的凭据、
        // 电话号码、URL 或路径。
        .filterNot { it.category == CharCategory.FORMAT }
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(CLIENT_TELEMETRY_URL, "[url-redacted]")
        .replace(CLIENT_TELEMETRY_AUTHORIZATION, "[credential-redacted]")
        .replace(CLIENT_TELEMETRY_STRUCTURED_CREDENTIAL, "[credential-redacted]")
        .replace(CLIENT_TELEMETRY_BEARER, "[credential-redacted]")
        .replace(CLIENT_TELEMETRY_JWT, "[credential-redacted]")
        .replace(CLIENT_TELEMETRY_CREDENTIAL, "[credential-redacted]")
        .replace(CLIENT_TELEMETRY_EMAIL, "[email-redacted]")
        .replace(CLIENT_TELEMETRY_CHINA_PHONE, "[phone-redacted]")
        .replace(CLIENT_TELEMETRY_INTERNATIONAL_PHONE, "[phone-redacted]")
        .replace(CLIENT_TELEMETRY_UNC_PATH, "[path-redacted]")
        .replace(CLIENT_TELEMETRY_WINDOWS_PATH, "[path-redacted]")
        .replace(CLIENT_TELEMETRY_RELATIVE_PATH, "[path-redacted]")
        .replace(CLIENT_TELEMETRY_UNIX_PATH, "[path-redacted]")
        .replace(CLIENT_TELEMETRY_UUID, "[id-redacted]")
        .replace(CLIENT_TELEMETRY_OPAQUE_SECRET, "[opaque-redacted]")
        .replace(CLIENT_TELEMETRY_WHITESPACE, " ")
        .trim()
    if (value.isEmpty()) value = "empty diagnostic"
    return value.take(ClientTelemetryLimits.MAX_MESSAGE_CHARS)
}

private val CLIENT_TELEMETRY_URL = Regex("(?i)\\b(?:https?|ftp|wss?|file|content)://\\S+")
private val CLIENT_TELEMETRY_STRUCTURED_CREDENTIAL = Regex(
    """(?i)["']?(?:password|passwd|token|authorization|cookie|secret|api[_-]?key|access[_-]?key|""" +
        """private[_-]?key|client[_-]?secret|refresh[_-]?token|access[_-]?token|session[_-]?token)["']?""" +
        """\s*[:=]\s*(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,}\]]+)""",
)
private val CLIENT_TELEMETRY_AUTHORIZATION = Regex(
    "(?i)\\bauthorization\\b\\s*[:=]?\\s*\\S+(?:\\s+\\S+)?",
)
private val CLIENT_TELEMETRY_BEARER = Regex("(?i)\\bbearer\\s+\\S+")
private val CLIENT_TELEMETRY_JWT = Regex(
    "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}" +
        "(?![A-Za-z0-9_-])",
)
private val CLIENT_TELEMETRY_CREDENTIAL = Regex(
    "(?i)\\b(?:password|passwd|token|cookie|secret|api[_-]?key|access[_-]?key|private[_-]?key|" +
        "client[_-]?secret|refresh[_-]?token|access[_-]?token|session[_-]?token)\\b" +
        "\\s*(?::|=|\\bis\\b)?\\s*\\S+",
)
private val CLIENT_TELEMETRY_EMAIL = Regex(
    "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])",
)
private val CLIENT_TELEMETRY_CHINA_PHONE = Regex(
    "(?<!\\d)(?:\\+?86[ -]?)?1[3-9](?:[ -]?\\d){9}(?!\\d)",
)
private val CLIENT_TELEMETRY_INTERNATIONAL_PHONE = Regex(
    "(?<![A-Za-z0-9])(?:\\+|00)[1-9](?:[ ()-]*\\d){7,14}(?!\\d)",
)
private val CLIENT_TELEMETRY_UNC_PATH = Regex(
    """(?<![A-Za-z0-9_])\\\\[^\s\\]+\\[^\s\\]+(?:\\[^\s\\]+)*""",
)
private val CLIENT_TELEMETRY_WINDOWS_PATH = Regex("[A-Za-z]:\\\\(?:[^\\s\\\\]+\\\\)*[^\\s\\\\]+")
private val CLIENT_TELEMETRY_RELATIVE_PATH = Regex(
    """(?<![A-Za-z0-9_.])\.\.?[/\\](?:[^\s/\\]+[/\\])*[^\s/\\]+""",
)
private val CLIENT_TELEMETRY_UNIX_PATH = Regex(
    """(?<![\p{L}\p{N}])/(?!/)(?:[^\s/]+/)*[^\s/]+""",
)
private val CLIENT_TELEMETRY_UUID = Regex(
    "(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-" +
        "[0-9a-f]{12}(?![0-9a-f])",
)
private val CLIENT_TELEMETRY_OPAQUE_SECRET = Regex(
    "(?<![A-Za-z0-9_+/=-])(?=[A-Za-z0-9_+/=-]{24,}(?![A-Za-z0-9_+/=-]))" +
        "(?=[A-Za-z0-9_+/=-]*[A-Za-z])(?=[A-Za-z0-9_+/=-]*\\d)[A-Za-z0-9_+/=-]+",
)
private val CLIENT_TELEMETRY_WHITESPACE = Regex("\\s+")

private fun stableTelemetryName(raw: String, fallback: String): String {
    val normalized = raw
        .map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_' || char == '.' || char == ':') {
                char
            } else {
                '_'
            }
        }
        .joinToString("")
        .trim('_')
        .take(ClientTelemetryLimits.MAX_NAME_CHARS)
    return normalized.takeIf { it.isNotEmpty() && (it.first().isLetterOrDigit() || it.first() == '_') }
        ?: fallback
}

private fun requireSafeGeneratedId(id: String) {
    require(id.length in 1..ClientTelemetryLimits.MAX_ID_CHARS) { "Generated telemetry id has invalid length" }
    require(id.first().isLetterOrDigit()) { "Generated telemetry id has invalid prefix" }
    require(id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
        "Generated telemetry id contains invalid characters"
    }
}

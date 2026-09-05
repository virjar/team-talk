package com.virjar.tk.shared.client

import com.virjar.tk.protocol.telemetry.CLIENT_TELEMETRY_ENDPOINT
import com.virjar.tk.protocol.telemetry.ClientTelemetryValidation
import com.virjar.tk.protocol.telemetry.TelemetryBatch
import com.virjar.tk.protocol.telemetry.TelemetryUploadResponse
import com.virjar.tk.shared.repository.canonicalHttpServerBase
import com.virjar.tk.shared.log.LogBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.virjar.tk.protocol.telemetry.TelemetryPolicyMode
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** 用于不可变结构化遥测批与策略心跳的会话拥有的 uploader。 */
internal class ClientTelemetryUploader(
    private val recorder: ClientTelemetryRecorder,
    private val spool: ClientTelemetrySpool,
    serverUrl: String,
    private val ownerUid: String,
    private val ownerIdentityEpoch: Long,
    private val credentialsProvider: () -> SessionHttpCredentials,
    private val localDiagnostics: LogBuffer,
    private val emergencyCrashDumper: CrashDumper,
    private val transport: PlatformTelemetryHttpTransport,
    private val ioWorker: PlatformTelemetryHttpIoWorker = createPlatformTelemetryHttpIoWorker(),
    private val onAuthExpired: (rejectedAccessToken: String) -> Unit = {},
    private val onPolicyApplied: () -> Unit = {},
) {
    internal constructor(
        recorder: ClientTelemetryRecorder,
        spool: ClientTelemetrySpool,
        serverUrl: String,
        ownerUid: String,
        credentialsProvider: () -> SessionHttpCredentials,
        localDiagnostics: LogBuffer,
        emergencyCrashDumper: CrashDumper,
        onAuthExpired: (rejectedAccessToken: String) -> Unit = {},
        onPolicyApplied: () -> Unit = {},
    ) : this(
        recorder = recorder,
        spool = spool,
        serverUrl = serverUrl,
        ownerUid = ownerUid,
        ownerIdentityEpoch = credentialsProvider().identityEpoch,
        credentialsProvider = credentialsProvider,
        localDiagnostics = localDiagnostics,
        emergencyCrashDumper = emergencyCrashDumper,
        transport = createPlatformTelemetryHttpTransport(),
        onAuthExpired = onAuthExpired,
        onPolicyApplied = onPolicyApplied,
    )

    private val uploadUrl = canonicalHttpServerBase(serverUrl) + CLIENT_TELEMETRY_ENDPOINT
    private val lifecycleLock = Any()
    private val stopLock = Any()
    private val workGate = SessionWorkGate("ClientTelemetryUploader")
    private val workLease = workGate.lease()
    private val lifecycleJob = SupervisorJob()
    private val scope = CoroutineScope(
        Dispatchers.Default + lifecycleJob + CoroutineExceptionHandler { _, _ ->
            localDiagnostics.append("trace", "ClientTelemetryUploader", "Control worker failed")
        },
    )
    private var timerJob: Job? = null
    private var faultDebounceJob: Job? = null
    /** 策略代际会打断旧的间隔；合并只保留活跃策略。 */
    private val policyTimerWake = Channel<Boolean>(Channel.CONFLATED)
    @Volatile
    private var acceptingTriggers = true
    @Volatile
    private var finalFlushRequested = false
    /** 仅 IO worker 持有的交接状态；在一个固定标记被保留期间防止重复事件。 */
    private var recordedEmergencyContent: String? = null

    fun start() = workGate.use(workLease) {
        synchronized(lifecycleLock) {
            timerJob?.cancel()
            faultDebounceJob?.cancel()
            scheduleIoWork(::uploadCycle)
            timerJob = scope.launch {
                while (isActive) {
                    val interval = recorder.policyState.snapshot().uploadIntervalSeconds * 1_000L
                    val immediate = withTimeoutOrNull(interval) { policyTimerWake.receive() }
                    // 超时保持正常节奏。新的 DIAGNOSTIC 代际会立即上传其刚记录的队列种子；
                    // BASELINE 只重新加载间隔。
                    if (immediate != false && acceptingTriggers) scheduleIoWork(::uploadCycle)
                }
            }
        }
    }

    /** fault 触发的上传，保持 recorder 顺序，并在阻塞 worker 之外去抖。 */
    fun trigger() {
        if (!acceptingTriggers) return
        synchronized(lifecycleLock) {
            if (!acceptingTriggers) return
            faultDebounceJob?.cancel()
            faultDebounceJob = scope.launch {
                delay(FAULT_UPLOAD_DEBOUNCE_MILLIS)
                if (acceptingTriggers) scheduleIoWork(::uploadCycle)
            }
        }
    }

    internal fun retryPending(): Job = workGate.use(workLease) { scheduleIoWork(::uploadCycle) }

    /**
     * 实时 TCP trace-policy 转换只是权威 HTTP 策略可能已变化的提示。在 uploader 的单一 IO owner
     * 上用一个空心跳刷新它。这刻意不刷写 recorder，也不检查持久假脱机：在旧策略下准入的事件
     * 绝不能在新策略应用之前越线发出。
     */
    internal fun refreshPolicyForConnectionTraceChange(): Job =
        workGate.use(workLease) { scheduleIoWork(::policyHeartbeatCycle) }

    fun stop() = synchronized(stopLock) {
        acceptingTriggers = false
        finalFlushRequested = true
        var boundaryFailure: SessionWorkGateReentrantCloseException? = null
        val newlyClosed = try {
            workGate.close()
        } catch (failure: SessionWorkGateReentrantCloseException) {
            boundaryFailure = failure
            true
        }
        val failures = mutableListOf<Throwable>()
        if (newlyClosed) {
            synchronized(lifecycleLock) {
                runCatching { timerJob?.cancel() }.exceptionOrNull()?.let(failures::add)
                runCatching { faultDebounceJob?.cancel() }.exceptionOrNull()?.let(failures::add)
                timerJob = null
                faultDebounceJob = null
            }
            runCatching { lifecycleJob.cancel() }.exceptionOrNull()?.let(failures::add)
        }
        if (!ioWorker.execute(::persistRecorderForClose)) {
            localDiagnostics.append("trace", "ClientTelemetryUploader", "Final telemetry flush was not admitted")
        }
        runCatching { transport.close() }.exceptionOrNull()?.let(failures::add)
        runCatching { ioWorker.closeAndDrain() }.exceptionOrNull()?.let(failures::add)
        failures.firstOrNull(::isFatalSessionLifecycleFailure)?.let { fatal ->
            boundaryFailure?.let { addSuppressedDistinct(fatal, it) }
            failures.forEach { addSuppressedDistinct(fatal, it) }
            throw fatal
        }
        boundaryFailure?.let { boundary ->
            failures.forEach { addSuppressedDistinct(boundary, it) }
            throw boundary
        }
        if (failures.isNotEmpty()) throw SessionResourceCloseException("ClientTelemetryUploader", failures)
    }

    private fun uploadCycle() {
        var pendingCrash: String? = null
        if (!workGate.runIfActive(workLease) { pendingCrash = emergencyCrashDumper.pendingContent() }) return
        if (pendingCrash != null) {
            val fixedCrash = checkNotNull(pendingCrash)
            if (recordedEmergencyContent != fixedCrash) {
                var recorded = false
                if (!workGate.runIfActive(workLease) { recorded = recorder.recordFatalCrash() } || !recorded) return
                recordedEmergencyContent = fixedCrash
            }
        }
        var receivedBatchResponse = false
        while (true) {
            var recorderFlushed = false
            if (!workGate.runIfActive(workLease) { recorderFlushed = recorder.flush() }) return
            if (recorderFlushed && pendingCrash != null) {
                val fixedCrash = checkNotNull(pendingCrash)
                if (!workGate.runIfActive(workLease) {
                        emergencyCrashDumper.markPendingUploaded(fixedCrash)
                    }
                ) return
                recordedEmergencyContent = null
                pendingCrash = null
            }

            var uploadedThisPass = false
            while (true) {
                var queued: QueuedTelemetryBatch? = null
                if (!workGate.runIfActive(workLease) { queued = spool.oldest() }) return
                val fixed = queued ?: break
                if (!upload(fixed.batch, fixed.encodedJson, persistent = true)) return
                receivedBatchResponse = true
                uploadedThisPass = true
            }
            if (recorderFlushed) break
            // 满的假脱机可能拒绝一个已封存的内存批。成功的 ACK 删除会释放容量，因此在保留队列
            // 排空后重试那个精确的稳定批。
            if (!uploadedThisPass) return
        }
        // 事件 ACK 已经携带活跃策略。心跳保留给本来为空的循环，这样活跃客户端不会仅仅为了刷新
        // 策略而把请求速率翻倍。
        if (receivedBatchResponse) return
        var heartbeat: TelemetryBatch? = null
        if (workGate.runIfActive(workLease) { heartbeat = recorder.heartbeatBatch() }) {
            val fixedHeartbeat = checkNotNull(heartbeat)
            upload(
                batch = fixedHeartbeat,
                encoded = ClientTelemetrySpool.TELEMETRY_JSON.encodeToString(fixedHeartbeat),
                persistent = false,
            )
        }
    }

    /** 仅 IO worker 的策略刷新，它不能封存或上传事件批。 */
    private fun policyHeartbeatCycle() {
        var heartbeat: TelemetryBatch? = null
        if (!workGate.runIfActive(workLease) { heartbeat = recorder.heartbeatBatch() }) return
        val fixedHeartbeat = checkNotNull(heartbeat)
        upload(
            batch = fixedHeartbeat,
            encoded = ClientTelemetrySpool.TELEMETRY_JSON.encodeToString(fixedHeartbeat),
            persistent = false,
        )
    }

    private fun upload(batch: TelemetryBatch, encoded: String, persistent: Boolean): Boolean {
        var accessToken: String? = null
        val admitted = try {
            workGate.runIfActive(workLease) {
                accessToken = ownedHttpAccessToken(
                    ownerUid = ownerUid,
                    credentials = credentialsProvider(),
                    ownerIdentityEpoch = ownerIdentityEpoch,
                )
            }
        } catch (_: Exception) {
            localDiagnostics.append("trace", "ClientTelemetryUploader", "Credential gate rejected upload")
            return false
        }
        if (!admitted) return false
        val rejectedAccessToken = checkNotNull(accessToken)
        val response = try {
            transport.postGzipJson(
                url = uploadUrl,
                compressed = gzip(encoded),
                headers = mapOf(
                    "Authorization" to "Bearer $rejectedAccessToken",
                    "Content-Type" to "application/json",
                    "Content-Encoding" to "gzip",
                ),
            )
        } catch (_: Exception) {
            localDiagnostics.append("trace", "ClientTelemetryUploader", "Telemetry transport failed")
            return false
        }
        if (response.statusCode != HTTP_OK) {
            localDiagnostics.append(
                "trace",
                "ClientTelemetryUploader",
                "Telemetry upload failed with HTTP ${response.statusCode}",
            )
            if (response.statusCode == HTTP_UNAUTHORIZED) dispatchAuthExpired(rejectedAccessToken)
            if (
                response.statusCode == HTTP_FORBIDDEN &&
                persistent &&
                spool.discardRejectedExact(batch.batchId, encoded)
            ) {
                // 被策略拒绝的诊断批刻意是有损的。精确删除让同一个 IO 循环可以继续处理后续的
                // BASELINE 批，而不是毒化 FIFO。
                return true
            }
            return false
        }
        val decoded = try {
            ClientTelemetrySpool.TELEMETRY_JSON.decodeFromString<TelemetryUploadResponse>(
                checkNotNull(response.body) { "Telemetry response body is missing" },
            ).also { ClientTelemetryValidation.requireValid(it, batch) }
        } catch (_: Exception) {
            localDiagnostics.append("trace", "ClientTelemetryUploader", "Telemetry ACK was rejected")
            return false
        }
        var committed = false
        val active = workGate.runIfActive(workLease) {
            decoded.policy?.let { policy ->
                if (recorder.applyPolicy(policy)) {
                    runCatching(onPolicyApplied).onFailure {
                        localDiagnostics.append(
                            "trace",
                            "ClientTelemetryUploader",
                            "Telemetry policy observer failed",
                        )
                    }
                    policyTimerWake.trySend(policy.mode == TelemetryPolicyMode.DIAGNOSTIC)
                }
            }
            committed = if (persistent) {
                spool.acknowledge(batch.batchId, checkNotNull(decoded.ack.acceptedThroughSequence))
            } else {
                true
            }
        }
        return active && committed
    }

    private fun scheduleIoWork(block: () -> Unit): Job {
        val completion = CompletableDeferred<Unit>()
        val accepted = ioWorker.execute {
            try {
                block()
                completion.complete(Unit)
            } catch (failure: Throwable) {
                completion.completeExceptionally(failure)
                if (isFatalSessionLifecycleFailure(failure)) throw failure
                workGate.runIfActive(workLease) {
                    localDiagnostics.append("trace", "ClientTelemetryUploader", "IO worker failed")
                }
            } finally {
                if (finalFlushRequested) persistRecorderForClose()
            }
        }
        if (!accepted) {
            completion.cancel()
            workGate.runIfActive(workLease) {
                localDiagnostics.append("trace", "ClientTelemetryUploader", "IO worker rejected upload")
            }
        }
        return completion
    }

    private fun persistRecorderForClose() {
        if (!finalFlushRequested) return
        val persisted = try {
            recorder.flush()
        } catch (_: Exception) {
            false
        }
        if (persisted) {
            finalFlushRequested = false
        } else {
            localDiagnostics.append("trace", "ClientTelemetryUploader", "Final telemetry flush remains pending")
        }
    }

    private fun isCurrentRejectedBearer(rejectedAccessToken: String): Boolean {
        var current = false
        val active = workGate.runIfActive(workLease) {
            current = ownedHttpAccessTokenMatches(
                ownerUid = ownerUid,
                ownerIdentityEpoch = ownerIdentityEpoch,
                rejectedAccessToken = rejectedAccessToken,
                credentials = credentialsProvider(),
            )
        }
        return active && current
    }

    private fun dispatchAuthExpired(rejectedAccessToken: String) {
        scope.launch {
            if (isCurrentRejectedBearer(rejectedAccessToken)) onAuthExpired(rejectedAccessToken)
        }
    }

    private fun gzip(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(text.encodeToByteArray()) }
        return output.toByteArray()
    }

    private companion object {
        const val FAULT_UPLOAD_DEBOUNCE_MILLIS = 3_000L
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/** 原始未捕获异常文本绝不会写入结构化紧急交接槽。 */
internal const val CLIENT_TELEMETRY_FATAL_MARKER = "client-telemetry-fatal-v1"

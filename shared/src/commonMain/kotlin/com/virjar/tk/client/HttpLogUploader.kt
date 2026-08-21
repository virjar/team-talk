package com.virjar.tk.client

import com.virjar.tk.repository.canonicalHttpServerBase
import com.virjar.tk.util.HttpUtil
import com.virjar.tk.util.LogBuffer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Session-owned HTTP log uploader. Credential reads and response-side mutations are generation
 * gated; the blocking transport itself is closeable so quiesce can disconnect active requests.
 */
class HttpLogUploader internal constructor(
    private val traceBuffer: LogBuffer,
    private val faultBuffer: LogBuffer,
    serverUrl: String,
    private val ownerUid: String,
    private val ownerIdentityEpoch: Long,
    private val credentialsProvider: () -> SessionHttpCredentials,
    private val crashDumper: CrashDumper,
    private val intervalMs: Long,
    private val transport: PlatformLogHttpTransport,
) {
    constructor(
        traceBuffer: LogBuffer,
        faultBuffer: LogBuffer,
        serverUrl: String,
        ownerUid: String,
        credentialsProvider: () -> SessionHttpCredentials,
        crashDumper: CrashDumper,
        intervalMs: Long = 5 * 60 * 1000L,
    ) : this(
        traceBuffer = traceBuffer,
        faultBuffer = faultBuffer,
        serverUrl = serverUrl,
        ownerUid = ownerUid,
        ownerIdentityEpoch = credentialsProvider().identityEpoch,
        credentialsProvider = credentialsProvider,
        crashDumper = crashDumper,
        intervalMs = intervalMs,
        transport = createPlatformLogHttpTransport(),
    )

    private val uploadUrl = canonicalHttpServerBase(serverUrl) + "/api/client-logs"
    private val lifecycleLock = Any()
    private val stopLock = Any()
    private val uploadLock = Any()
    private val workGate = SessionWorkGate("HttpLogUploader")
    private val workLease = workGate.lease()
    private val lifecycleJob = SupervisorJob()
    private val scope = CoroutineScope(
        Dispatchers.Default + lifecycleJob + CoroutineExceptionHandler { _, throwable ->
            // Fixed buffer: a late old-session failure can never enter a replacement AppLog owner.
            workGate.runIfActive(workLease) {
                traceBuffer.append("trace", "HttpLogUploader", "Worker failed: ${throwable.message}")
            }
        },
    )
    private var timerJob: Job? = null
    private var faultDebounceJob: Job? = null

    fun start() = workGate.use(workLease) {
        synchronized(lifecycleLock) {
            timerJob?.cancel()
            faultDebounceJob?.cancel()
            scope.launch { uploadPendingCrash() }
            timerJob = scope.launch {
                while (isActive) {
                    delay(intervalMs)
                    uploadAll()
                }
            }
        }
    }

    fun stop() = synchronized(stopLock) {
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
        // Cancellation cannot interrupt a blocking HttpURLConnection; close is the hard stop.
        runCatching { transport.close() }.exceptionOrNull()?.let(failures::add)
        boundaryFailure?.let { boundary ->
            failures.forEach(boundary::addSuppressed)
            throw boundary
        }
        if (failures.isNotEmpty()) {
            throw SessionResourceCloseException("HttpLogUploader", failures)
        }
    }

    /** Fault-triggered upload, debounced without accepting work after quiesce. */
    fun trigger() {
        workGate.use(workLease) {
            synchronized(lifecycleLock) {
                faultDebounceJob?.cancel()
                faultDebounceJob = scope.launch {
                    delay(3_000)
                    uploadAll()
                }
            }
        }
    }

    fun manualUpload() {
        workGate.use(workLease) { scope.launch { uploadAll() } }
    }

    private fun uploadPendingCrash() {
        synchronized(uploadLock) {
            var content: String? = null
            if (!workGate.runIfActive(workLease) { content = crashDumper.pendingContent() }) {
                return@synchronized
            }
            val fixedContent = content ?: return@synchronized
            uploadPayload(fixedContent, persistOnFailure = false) {
                crashDumper.markPendingUploaded(fixedContent)
            }
        }
    }

    private fun uploadAll() = synchronized(uploadLock) {
        var combined = ""
        if (!workGate.runIfActive(workLease) {
                val traceText = traceBuffer.drain().orEmpty()
                val faultText = faultBuffer.drain().orEmpty()
                combined = buildString {
                    if (traceText.isNotBlank()) appendLine("=== TRACE ===").appendLine(traceText)
                    if (faultText.isNotBlank()) appendLine("=== FAULT ===").appendLine(faultText)
                }
            }
        ) {
            return@synchronized
        }
        if (combined.isBlank()) return@synchronized
        uploadPayload(combined, persistOnFailure = true)
    }

    private fun uploadPayload(
        content: String,
        persistOnFailure: Boolean,
        onSuccess: () -> Unit = {},
    ) {
        var accessToken: String? = null
        val admitted = try {
            workGate.runIfActive(workLease) {
                accessToken = ownedHttpAccessToken(
                    ownerUid = ownerUid,
                    credentials = credentialsProvider(),
                    ownerIdentityEpoch = ownerIdentityEpoch,
                )
            }
        } catch (failure: Exception) {
            workGate.runIfActive(workLease) {
                if (persistOnFailure) crashDumper.flushPending(content)
                traceBuffer.append(
                    "trace",
                    "HttpLogUploader",
                    "Credential gate rejected upload for fixed owner: ${failure.message}",
                )
            }
            return
        }
        // Once stop wins, an in-flight drained payload is deliberately discarded. Persisting it
        // from the old worker would race a same-uid replacement session's fixed crash namespace.
        if (!admitted) return

        try {
            val code = transport.postGzip(
                uploadUrl,
                HttpUtil.gzip(content),
                mapOf("Authorization" to "Bearer ${checkNotNull(accessToken)}"),
            )
            if (code != 200) throw RuntimeException("HTTP $code")
            // Stop wins over a late response. In particular it cannot delete/replace state after
            // the account's retirement generation has changed.
            workGate.runIfActive(workLease) { onSuccess() }
        } catch (failure: Exception) {
            workGate.runIfActive(workLease) {
                if (persistOnFailure) crashDumper.flushPending(content)
                traceBuffer.append(
                    "trace",
                    "HttpLogUploader",
                    "Upload failed, retained by fixed owner: ${failure.message}",
                )
            }
        }
    }
}

internal class SessionResourceCloseException(
    owner: String,
    val failures: List<Throwable>,
) : IllegalStateException("$owner close failed in ${failures.size} operation(s)", failures.firstOrNull())

/** Prevent a retired uploader from borrowing a different identity or a same-uid re-login token. */
internal fun ownedHttpAccessToken(
    ownerUid: String,
    credentials: SessionHttpCredentials,
    ownerIdentityEpoch: Long? = null,
): String {
    check(ownerUid.isNotBlank()) { "HTTP resource owner uid must not be blank" }
    check(credentials.uid == ownerUid) { "Authenticated HTTP identity changed" }
    if (ownerIdentityEpoch != null) {
        check(credentials.identityEpoch == ownerIdentityEpoch) { "Authenticated HTTP session changed" }
    }
    val token = credentials.accessToken?.takeIf(String::isNotBlank)
        ?: error("No authenticated access token for HTTP request")
    require(token.all { it.code in 0x21..0x7e }) { "HTTP access token contains illegal characters" }
    return token
}

internal interface PlatformLogHttpTransport {
    fun postGzip(url: String, compressed: ByteArray, headers: Map<String, String>): Int
    fun close()
}

internal expect fun createPlatformLogHttpTransport(): PlatformLogHttpTransport

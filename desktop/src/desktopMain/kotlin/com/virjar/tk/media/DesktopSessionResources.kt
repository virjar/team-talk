package com.virjar.tk.media

import com.virjar.tk.DesktopFileTransfer
import com.virjar.tk.DesktopMediaSender
import com.virjar.tk.DesktopVoiceRecorder
import com.virjar.tk.client.DeploymentIdentity
import com.virjar.tk.client.SessionHttpCredentials
import com.virjar.tk.http.HttpConnectionReentrantCloseFailure
import com.virjar.tk.log.TkLogger
import com.virjar.tk.repository.FileRepository
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Desktop 已认证用户拥有的资源根。
 *
 * 构造时固定复制 uid 和服务器身份；access token 在每次请求时从同一用户会话读取，
 * 既支持重连轮换，又通过 uid 门禁阻止旧会话读取后续账号凭据。退出、认证失效或
 * Compose 会话替换时由唯一所有者调用 [close]，统一取消媒体任务、关闭凭据门禁并
 * 清理未完成文件。
 */
internal class DesktopSessionResources(
    val ownerUid: String,
    deploymentIdentity: DeploymentIdentity,
    credentialProvider: () -> SessionHttpCredentials,
    dataDir: File,
    diagnosticLogger: TkLogger,
    quotaBytes: Long = DEFAULT_DESKTOP_MEDIA_QUOTA_BYTES,
    downloader: DesktopMediaDownloader = HttpDesktopMediaDownloader(),
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = ReentrantLock()
    private val closeCompleted = lifecycleLock.newCondition()
    private var closePhase = DesktopSessionClosePhase.OPEN
    private var closingThread: Thread? = null
    private val closeFailures = mutableListOf<Throwable>()
    private var completedCloseFailure: Throwable? = null
    private val rootJob = SupervisorJob()
    internal val ioScope = CoroutineScope(
        rootJob + Dispatchers.IO + CoroutineName("desktop-session-$ownerUid"),
    )

    val serverBaseUrl: String = deploymentIdentity.httpBaseUrl
    val serverFingerprint: String = deploymentIdentity.fingerprint
    val sessionFingerprint: String = desktopSha256("${deploymentIdentity.fingerprint}\n$ownerUid")
    internal val credentialGate = DesktopCredentialGate(ownerUid, credentialProvider)
    internal val diagnostics = DesktopSessionDiagnostics(diagnosticLogger)
    val mediaDirectory: File = File(dataDir, "media_e1/$sessionFingerprint")
    val mediaCache = DesktopMediaCache(
        serverBaseUrl = serverBaseUrl,
        credentialGate = credentialGate,
        diagnostics = diagnostics,
        cacheDir = mediaDirectory,
        scope = ioScope,
        downloader = downloader,
        quotaBytes = quotaBytes,
    )
    val fileRepository = FileRepository(serverBaseUrl, ownerUid, credentialProvider)
    val fileTransfer = DesktopFileTransfer(this)
    val mediaSender = DesktopMediaSender(this, fileTransfer)
    val voiceRecorder = DesktopVoiceRecorder(this, fileTransfer)

    fun ensureOpen() {
        check(!closed.get()) { "Desktop 会话资源已经关闭" }
        credentialGate.ensureOwner()
    }

    /** 为页面控制器创建受会话根约束的子作用域。 */
    fun childScope(name: String): CoroutineScope {
        ensureOpen()
        return CoroutineScope(
            SupervisorJob(rootJob) + Dispatchers.IO + CoroutineName("desktop-$name-$ownerUid"),
        )
    }

    override fun close() {
        while (true) {
            val role = lifecycleLock.withLock {
                when (closePhase) {
                    DesktopSessionClosePhase.OPEN -> {
                        closePhase = DesktopSessionClosePhase.CLOSING
                        closingThread = Thread.currentThread()
                        closed.set(true)
                        DesktopSessionCloseRole.LEADER
                    }

                    DesktopSessionClosePhase.CLOSING -> when {
                        closingThread === Thread.currentThread() -> DesktopSessionCloseRole.REENTRANT
                        closingThread == null -> {
                            closingThread = Thread.currentThread()
                            DesktopSessionCloseRole.LEADER
                        }
                        else -> DesktopSessionCloseRole.FOLLOWER
                    }

                    DesktopSessionClosePhase.CLOSED -> DesktopSessionCloseRole.COMPLETE
                }
            }

            when (role) {
                DesktopSessionCloseRole.LEADER -> return closeAsLeader()
                DesktopSessionCloseRole.FOLLOWER -> awaitLeaderRelease()
                DesktopSessionCloseRole.COMPLETE -> {
                    lifecycleLock.withLock { completedCloseFailure }?.let { throw it }
                    return
                }
                DesktopSessionCloseRole.REENTRANT -> throw DesktopSessionReentrantCloseException()
            }
        }
    }

    private fun closeAsLeader() {
        val failures = mutableListOf<Throwable>()
        fun release(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let(failures::add)
        }

        // Close the diagnostic admission before cancelling jobs: retained tasks may unwind later,
        // but none can write after this Desktop owner has crossed its close boundary.
        release(diagnostics::close)
        release(voiceRecorder::close)
        release(mediaSender::close)
        release(fileRepository::close)
        release(credentialGate::close)
        // Credentials and diagnostics are already sealed. Mark the complete task tree cancelled
        // before disconnecting its blocking reads so every resumed transfer observes one stable
        // CancellationException terminal outcome rather than racing a transport exception.
        release { rootJob.cancel() }
        release(mediaCache::close)

        val reentrantFailures = failures.filter { it is HttpConnectionReentrantCloseFailure }
        val terminalFailures = failures.filterNot { it is HttpConnectionReentrantCloseFailure }
        val boundaryFailure = reentrantFailures
            .takeIf(List<*>::isNotEmpty)
            ?.let { childFailures ->
                DesktopSessionReentrantCloseException().also { failure ->
                    childFailures.forEach(failure::addSuppressed)
                    terminalFailures.forEach(failure::addSuppressed)
                }
            }
        var completedFailure: Throwable? = null
        lifecycleLock.withLock {
            terminalFailures.forEach(::recordCloseFailureLocked)
            closingThread = null
            if (boundaryFailure == null) {
                completedCloseFailure = closeFailures
                    .takeIf(List<*>::isNotEmpty)
                    ?.let(::DesktopSessionCloseException)
                completedFailure = completedCloseFailure
                closePhase = DesktopSessionClosePhase.CLOSED
            }
            closeCompleted.signalAll()
        }
        boundaryFailure?.let { throw it }
        completedFailure?.let { throw it }
    }

    private fun awaitLeaderRelease() = lifecycleLock.withLock {
        while (closePhase == DesktopSessionClosePhase.CLOSING && closingThread != null) {
            closeCompleted.awaitUninterruptibly()
        }
    }

    private fun recordCloseFailureLocked(failure: Throwable) {
        if (closeFailures.none { existing -> existing === failure }) closeFailures += failure
    }
}

private enum class DesktopSessionClosePhase { OPEN, CLOSING, CLOSED }

private enum class DesktopSessionCloseRole { LEADER, FOLLOWER, COMPLETE, REENTRANT }

private class DesktopSessionReentrantCloseException : IllegalStateException(
    "Desktop 会话资源不能从正在执行的关闭流程中重入关闭",
)

private class DesktopSessionCloseException(failures: List<Throwable>) : IllegalStateException(
    "Desktop 会话关闭时有 ${failures.size} 个资源未能正常释放",
) {
    init {
        failures.forEach(::addSuppressed)
    }
}

internal enum class DesktopSessionDiagnosticEvent(
    internal val message: String,
    internal val kind: DesktopSessionDiagnosticKind,
) {
    MEDIA_CACHE_STORED("media cache stored", DesktopSessionDiagnosticKind.TRACE),
    FILE_DOWNLOAD_FAILED("file download failed", DesktopSessionDiagnosticKind.FAULT),
    FILE_OPEN_FAILED("file open failed", DesktopSessionDiagnosticKind.FAULT),
    IMAGE_DECODE_FAILED("image decode failed", DesktopSessionDiagnosticKind.FAULT),
    VOICE_PLAYBACK_OPENING("voice playback opening", DesktopSessionDiagnosticKind.TRACE),
    VOICE_PLAYBACK_FAILED("voice playback failed", DesktopSessionDiagnosticKind.FAULT),
    VOICE_RECORD_FAILED("voice recording failed", DesktopSessionDiagnosticKind.FAULT),
    GROUP_FILE_UPLOAD_FAILED("group file upload failed", DesktopSessionDiagnosticKind.FAULT),
}

internal enum class DesktopSessionDiagnosticKind { TRACE, FAULT }

/**
 * Session-fixed and close-gated diagnostics for retained Desktop tasks. Callers can only emit a
 * finite redacted event vocabulary: attachment references, paths, names and exception text never
 * cross this boundary.
 */
internal class DesktopSessionDiagnostics(
    private val logger: TkLogger,
) : Closeable {
    private val gate = Any()
    private var open = true

    /** Returns whether the event was admitted; logging failures never break the media operation. */
    fun record(event: DesktopSessionDiagnosticEvent): Boolean = synchronized(gate) {
        if (!open) return@synchronized false
        runCatching {
            when (event.kind) {
                DesktopSessionDiagnosticKind.TRACE -> logger.trace(event.message)
                DesktopSessionDiagnosticKind.FAULT -> logger.fault(event.message)
            }
        }
        true
    }

    override fun close() = synchronized(gate) {
        open = false
    }
}

internal class DesktopCredentialGate(
    val ownerUid: String,
    private val credentialProvider: () -> SessionHttpCredentials,
) : Closeable {
    private val open = AtomicBoolean(true)
    private val ownerIdentityEpoch: Long

    init {
        val initial = credentialProvider()
        check(initial.uid == ownerUid) { "Desktop 初始认证身份不匹配" }
        ownerIdentityEpoch = initial.identityEpoch
    }

    fun ensureOpen() {
        check(open.get()) { "Desktop 认证会话已经失效" }
    }

    fun ensureOwner() {
        ensureOpen()
        requireCurrentOwner(credentialProvider())
    }

    fun requireAccessToken(): String {
        ensureOpen()
        val credentials = credentialProvider()
        // uid 门禁是安全边界：同一个 UserSession 容器可在退出后承载下一账号，旧资源
        // 绝不能读取新账号 token；同 uid 重连轮换 token 则应立即读取最新值。
        requireCurrentOwner(credentials)
        return credentials.accessToken?.takeIf(String::isNotBlank)
            ?: error("认证会话缺少文件访问凭据")
    }

    override fun close() {
        open.set(false)
    }

    private fun requireCurrentOwner(credentials: SessionHttpCredentials) {
        check(credentials.uid == ownerUid && credentials.identityEpoch == ownerIdentityEpoch) {
            "Desktop 认证会话已经变更"
        }
    }
}

/** 只保留实际部署基址；拒绝凭据、query 和 fragment 混入服务器身份。 */
internal fun canonicalDesktopServerBase(serverUrl: String): String {
    val parsed = URI(serverUrl.trim())
    val scheme = parsed.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "Desktop 服务器必须使用 HTTP(S)" }
    require(parsed.host != null) { "Desktop 服务器地址缺少主机" }
    require(parsed.userInfo == null) { "Desktop 服务器地址不能包含凭据" }
    require(parsed.rawQuery == null && parsed.rawFragment == null) {
        "Desktop 服务器地址不能包含 query 或 fragment"
    }

    val normalizedPort = when {
        parsed.port < 0 -> -1
        scheme == "http" && parsed.port == 80 -> -1
        scheme == "https" && parsed.port == 443 -> -1
        else -> parsed.port
    }
    val path = parsed.path.orEmpty().trimEnd('/').ifBlank { null }
    return URI(
        scheme,
        null,
        parsed.host.lowercase(),
        normalizedPort,
        path,
        null,
        null,
    ).toASCIIString().trimEnd('/')
}

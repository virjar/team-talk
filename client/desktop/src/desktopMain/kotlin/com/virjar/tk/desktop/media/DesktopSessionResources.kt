package com.virjar.tk.desktop.media

import com.virjar.tk.shared.AppError
import com.virjar.tk.desktop.DesktopFileTransfer
import com.virjar.tk.desktop.DesktopVideoSender
import com.virjar.tk.desktop.DesktopVoiceRecorder
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.shared.http.HttpConnectionReentrantCloseFailure
import com.virjar.tk.shared.log.TkLogger
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.NoopClientUiTelemetrySink
import com.virjar.tk.shared.repository.FileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private fun validatedDesktopMediaDatasetId(datasetId: String): String {
    com.virjar.tk.protocol.payload.SyncDatasetIdPolicy.requireValid(datasetId)
    return datasetId
}

/**
 * Desktop 已认证用户拥有的资源根。
 *
 * 构造时固定复制 uid、canonical datasetId 和服务器身份；access token 在每次请求时从同一用户会话读取，
 * 既支持重连轮换，又通过 uid 门禁阻止旧会话读取后续账号凭据。退出、认证失效或
 * Compose 会话替换时由唯一所有者调用 [close]，统一取消媒体任务、关闭凭据门禁并
 * 清理未完成文件。
 */
internal class DesktopSessionResources(
    val ownerUid: String,
    datasetId: String,
    deploymentIdentity: DeploymentIdentity,
    credentialProvider: () -> SessionHttpCredentials,
    dataDir: File,
    diagnosticLogger: TkLogger,
    val telemetry: ClientUiTelemetrySink = NoopClientUiTelemetrySink,
    private val onAuthExpired: (rejectedAccessToken: String) -> Unit = {},
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
    val datasetId: String = validatedDesktopMediaDatasetId(datasetId)
    val sessionFingerprint: String = desktopSha256(
        "teamtalk-media-v2\u0000${deploymentIdentity.fingerprint}" +
            "\u0000dataset\u0000${this.datasetId}\u0000uid\u0000$ownerUid",
    )
    internal val credentialGate = DesktopCredentialGate(ownerUid, credentialProvider)
    internal val diagnostics = DesktopSessionDiagnostics(diagnosticLogger)
    val mediaDirectory: File = File(dataDir, "media_e2/$sessionFingerprint")
    val mediaCache: DesktopMediaCache
    val fileRepository: FileRepository
    val fileTransfer: DesktopFileTransfer
    val videoSender: DesktopVideoSender
    val voiceRecorder: DesktopVoiceRecorder

    init {
        var cacheCandidate: DesktopMediaCache? = null
        var repositoryCandidate: FileRepository? = null
        var senderCandidate: DesktopVideoSender? = null
        var recorderCandidate: DesktopVoiceRecorder? = null
        try {
            cacheCandidate = DesktopMediaCache(
                serverBaseUrl = serverBaseUrl,
                credentialGate = credentialGate,
                diagnostics = diagnostics,
                cacheDir = mediaDirectory,
                scope = ioScope,
                downloader = downloader,
                onAuthExpired = ::reportAuthExpired,
                quotaBytes = quotaBytes,
            )
            repositoryCandidate = FileRepository(
                serverBaseUrl,
                ownerUid,
                credentialProvider,
                onAuthExpired = ::reportAuthExpired,
            )
            val transferCandidate = DesktopFileTransfer(this)
            senderCandidate = DesktopVideoSender(this, transferCandidate)
            recorderCandidate = DesktopVoiceRecorder(this, transferCandidate)

            mediaCache = requireNotNull(cacheCandidate)
            fileRepository = requireNotNull(repositoryCandidate)
            fileTransfer = transferCandidate
            videoSender = requireNotNull(senderCandidate)
            voiceRecorder = requireNotNull(recorderCandidate)
        } catch (failure: Throwable) {
            // 构造失败时没有外部 owner 可以调用 close()。先把迄今创建的所有组件释放掉，
            // 再把原始失败返回给重试 UI。
            val cleanupFailures = mutableListOf<Throwable>()
            listOf<() -> Unit>(
                diagnostics::close,
                { recorderCandidate?.close() },
                { senderCandidate?.close() },
                { repositoryCandidate?.close() },
                credentialGate::close,
            ).forEach { release ->
                try {
                    release()
                } catch (closeFailure: Throwable) {
                    cleanupFailures.addIdentityDistinct(closeFailure)
                }
            }
            try {
                rootJob.cancel()
            } catch (closeFailure: Throwable) {
                cleanupFailures.addIdentityDistinct(closeFailure)
            }
            try {
                cacheCandidate?.close()
            } catch (closeFailure: Throwable) {
                cleanupFailures.addIdentityDistinct(closeFailure)
            }
            throw terminalDesktopMediaConstructionFailure(failure, cleanupFailures)
        }
    }

    fun ensureOpen() {
        if (closed.get()) throw DesktopSessionUnavailableException("Desktop 会话资源已经关闭")
        credentialGate.ensureOwner()
    }

    /** 由 FileRepository 与直接平台媒体 HTTP 使用的精确会话终结信号。 */
    internal fun reportAuthExpired(rejectedAccessToken: String) {
        if (closed.get()) return
        credentialGate.ensureOwner()
        onAuthExpired(rejectedAccessToken)
    }

    /**
     * 页面异步结果只能回写仍属于同一认证 owner 的 UI。关闭或身份替换是正常的
     * non-delivery；协程取消和非 [Exception] 缺陷必须保持原终态，不能被伪装成关闭。
     */
    fun canDeliverUiResult(): Boolean = try {
        ensureOpen()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: DesktopSessionUnavailableException) {
        false
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
            try {
                action()
            } catch (failure: Throwable) {
                failures.addIdentityDistinct(failure)
            }
        }

        // 在取消任务之前先关闭诊断准入：被保留的任务可能稍后才展开回退，
        // 但在本 Desktop owner 越过关闭边界之后，任何任务都不能再写入。
        release(diagnostics::close)
        release(voiceRecorder::close)
        release(videoSender::close)
        release(fileRepository::close)
        release(credentialGate::close)
        // 凭据与诊断已被封存。在断开其阻塞读取之前先把整个任务树标记为取消，
        // 让每个恢复的传输都能观测到同一个稳定的 CancellationException 终结结果，
        // 而不是与传输异常竞争。
        release { rootJob.cancel() }
        release(mediaCache::close)

        val reentrantFailures = failures.filter { it is HttpConnectionReentrantCloseFailure }
        val terminalFailures = failures
            .filterNotTo(mutableListOf()) { it is HttpConnectionReentrantCloseFailure }
        firstFatalDesktopMediaFailure(reentrantFailures)
            ?.let { failure -> terminalFailures.addIdentityDistinct(failure) }
        val fatalFailure = firstFatalDesktopMediaFailure(failures)
        val boundaryFailure = reentrantFailures
            .takeIf(List<*>::isNotEmpty)
            ?.let { childFailures ->
                DesktopSessionReentrantCloseException().also { failure ->
                    childFailures.forEach { child ->
                        if (
                            fatalFailure == null ||
                            (child !== fatalFailure && !referencesDesktopMediaFailure(child, fatalFailure))
                        ) {
                            addSuppressedDesktopMediaFailure(failure, child)
                        }
                    }
                    terminalFailures.forEach { terminal ->
                        if (
                            fatalFailure == null ||
                            (terminal !== fatalFailure && !referencesDesktopMediaFailure(terminal, fatalFailure))
                        ) {
                            addSuppressedDesktopMediaFailure(failure, terminal)
                        }
                    }
                }
            }
        val immediateFailure = boundaryFailure?.let { boundary ->
            fatalFailure?.also { fatal ->
                flattenDesktopMediaFailures(failures).forEach { observed ->
                    addSuppressedDesktopMediaFailure(fatal, observed)
                }
                addSuppressedDesktopMediaFailure(fatal, boundary)
            } ?: boundary
        }
        var completedFailure: Throwable? = null
        lifecycleLock.withLock {
            terminalFailures.forEach(::recordCloseFailureLocked)
            closingThread = null
            if (boundaryFailure == null) {
                completedCloseFailure = terminalDesktopMediaCloseFailure(closeFailures)
                completedFailure = completedCloseFailure
                closePhase = DesktopSessionClosePhase.CLOSED
            }
            closeCompleted.signalAll()
        }
        immediateFailure?.let { throw it }
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

internal class DesktopSessionUnavailableException(message: String) : IllegalStateException(message)

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
    VIDEO_PLAYER_DISPOSE_FAILED("video player dispose failed", DesktopSessionDiagnosticKind.FAULT),
    GROUP_FILE_UPLOAD_FAILED("group file upload failed", DesktopSessionDiagnosticKind.FAULT),
}

internal enum class DesktopSessionDiagnosticKind { TRACE, FAULT }

/**
 * 面向被保留 Desktop 任务的、会话固定且受关闭门控的诊断。调用方只能发出有限的
 * 脱敏事件词汇：附件引用、路径、名称与异常文本绝不越过此边界。
 */
internal class DesktopSessionDiagnostics(
    private val logger: TkLogger,
) : Closeable {
    private val gate = Any()
    private var open = true

    /** 普通的日志失败被隔离；取消与 VM 级致命缺陷向上传播。 */
    fun record(event: DesktopSessionDiagnosticEvent): Boolean = synchronized(gate) {
        if (!open) return@synchronized false
        try {
            when (event.kind) {
                DesktopSessionDiagnosticKind.TRACE -> logger.trace(event.message)
                DesktopSessionDiagnosticKind.FAULT -> logger.fault(event.message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 诊断是尽力而为的。VM 级致命缺陷有意越过此边界。
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
        if (!open.get()) throw DesktopSessionUnavailableException("Desktop 认证会话已经失效")
    }

    fun ensureOwner() {
        ensureOpen()
        requireCurrentOwner(credentialProvider())
    }

    fun requireAccessToken(): String {
        ensureOpen()
        val credentials = credentialProvider()
        // uid 门禁是安全边界：同一个 UserSession 容器可在退出后承载下一账号，旧资源
        // 绝不能读取新账号 token；同 uid 重连轮换 access 则应立即读取最新值。
        requireCurrentOwner(credentials)
        return credentials.accessToken?.takeIf(String::isNotBlank)
            ?: error("认证会话缺少文件访问凭据")
    }

    /** 来自本地已被替换的 bearer 的 401 不能作为对当前会话不利的证据。 */
    fun authoritativeFailure(requestAccessToken: String, failure: AppError.AuthExpired): Exception {
        ensureOpen()
        val credentials = credentialProvider()
        requireCurrentOwner(credentials)
        return if (credentials.accessToken == requestAccessToken) {
            failure
        } else {
            DesktopMediaSupersededCredentialException()
        }
    }

    override fun close() {
        open.set(false)
    }

    private fun requireCurrentOwner(credentials: SessionHttpCredentials) {
        if (credentials.uid != ownerUid || credentials.identityEpoch != ownerIdentityEpoch) {
            throw DesktopSessionUnavailableException("Desktop 认证会话已经变更")
        }
    }
}

internal class DesktopMediaSupersededCredentialException :
    IllegalStateException("Desktop 媒体请求使用的认证凭据已经轮换")

private fun terminalDesktopMediaCloseFailure(failures: List<Throwable>): Throwable? {
    if (failures.isEmpty()) return null
    val observed = flattenDesktopMediaFailures(failures)
    val fatal = observed.firstOrNull(::isFatalDesktopMediaFailure)
        ?: return DesktopSessionCloseException(failures)
    observed.forEach { failure -> addSuppressedDesktopMediaFailure(fatal, failure) }
    return fatal
}

internal fun terminalDesktopMediaConstructionFailure(
    constructionFailure: Throwable,
    cleanupFailures: List<Throwable>,
): Throwable {
    val observed = buildList {
        add(constructionFailure)
        addAll(cleanupFailures)
    }
    val terminal = firstFatalDesktopMediaFailure(observed) ?: constructionFailure
    flattenDesktopMediaFailures(observed).forEach { failure ->
        addSuppressedDesktopMediaFailure(terminal, failure)
    }
    return terminal
}

private fun firstFatalDesktopMediaFailure(failures: List<Throwable>): Throwable? =
    flattenDesktopMediaFailures(failures).firstOrNull(::isFatalDesktopMediaFailure)

private fun isFatalDesktopMediaFailure(failure: Throwable): Boolean =
    failure is CancellationException || failure !is Exception

private fun flattenDesktopMediaFailures(failures: List<Throwable>): List<Throwable> {
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

private fun addSuppressedDesktopMediaFailure(primary: Throwable, additional: Throwable) {
    if (primary === additional || primary.suppressed.any { it === additional }) return
    if (referencesDesktopMediaFailure(primary, additional)) return
    if (referencesDesktopMediaFailure(additional, primary)) return
    primary.addSuppressed(additional)
}

private fun referencesDesktopMediaFailure(root: Throwable, target: Throwable): Boolean {
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

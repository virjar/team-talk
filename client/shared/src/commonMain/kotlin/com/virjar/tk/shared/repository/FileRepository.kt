package com.virjar.tk.shared.repository

import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.http.UploadResult
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.shared.outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.random.Random

/** 显式内存上传便捷 API 可接受的最大 payload。 */
const val MAX_SMALL_UPLOAD_BYTES: Int = 1024 * 1024

/** 可以有意地物化为单个 [ByteArray] 的最大下载大小。 */
const val MAX_SMALL_DOWNLOAD_BYTES: Long = 8L * 1024 * 1024

/** 可重复使用的内存文件源与平台文件源共用的默认分块大小。 */
const val DEFAULT_UPLOAD_CHUNK_BYTES: Int = 64 * 1024

/**
 * [UploadSource] 使用的同步消费型 sink。本调用返回后源可以复用 [bytes]，
 * 因此实现绝不能保留该缓冲区。
 */
fun interface UploadSink {
    suspend fun write(bytes: ByteArray, offset: Int, length: Int)
}

/**
 * [FileRepository.downloadTo] 使用的同步消费型 sink。传输层持有并可以
 * 在本调用返回后复用 [bytes]，因此实现必须复制其保留的任何数据。
 */
fun interface DownloadSink {
    suspend fun write(bytes: ByteArray, offset: Int, length: Int)
}

/**
 * 可重复使用的流式上传体。
 *
 * [contentLength] 在 HTTP 开始前即固定，且每次 [writeTo] 调用都必须恰好发出
 * 那么多个字节。实现必须为每次调用打开一个新的底层源。该契约保留在
 * common 代码中，并且刻意不暴露任何 `java.io` 类型；JVM/Android 会补充 `File.asUploadSource()`。
 */
interface UploadSource {
    val contentLength: Long

    suspend fun writeTo(sink: UploadSink)
}

/**
 * 面向真正小 payload 的显式便捷方法。一次防御性拷贝使返回的源
 * 在调用方随后修改其原始数组时依然可重复使用。
 */
fun ByteArray.asSmallUploadSource(): UploadSource {
    require(size <= MAX_SMALL_UPLOAD_BYTES) {
        "内存上传只允许不超过 ${MAX_SMALL_UPLOAD_BYTES / 1024} KiB；大文件必须使用流式 UploadSource"
    }
    val snapshot = copyOf()
    return object : UploadSource {
        override val contentLength: Long = snapshot.size.toLong()

        override suspend fun writeTo(sink: UploadSink) {
            var offset = 0
            while (offset < snapshot.size) {
                currentCoroutineContext().ensureActive()
                val length = minOf(DEFAULT_UPLOAD_CHUNK_BYTES, snapshot.size - offset)
                sink.write(snapshot, offset, length)
                offset += length
            }
        }
    }
}

/**
 * 由会话持有的 HTTP 文件 repository。
 *
 * owner uid 与服务器身份在此对象的整个生命周期内固定。凭据提供方
 * 每次请求求值一次，因此重连可以为同一 uid 轮换 token，而复用一个
 * 携带其他 uid 的 [com.virjar.tk.shared.client.UserSession] 会失败关闭。[close] 是
 * 幂等的，并会中止活跃的平台连接。
 */
class FileRepository internal constructor(
    serverUrl: String,
    ownerUid: String,
    credentialsProvider: () -> SessionHttpCredentials,
    private val transport: PlatformFileTransport,
    private val onAuthExpired: (rejectedAccessToken: String) -> Unit = {},
    private val newUploadIdentity: () -> AttachmentUploadIdentity = ::createAttachmentUploadIdentity,
) : AutoCloseable {
    constructor(
        serverUrl: String,
        ownerUid: String,
        credentialsProvider: () -> SessionHttpCredentials,
    ) : this(serverUrl, ownerUid, credentialsProvider, createPlatformFileTransport())

    constructor(
        serverUrl: String,
        ownerUid: String,
        credentialsProvider: () -> SessionHttpCredentials,
        onAuthExpired: (rejectedAccessToken: String) -> Unit,
    ) : this(
        serverUrl,
        ownerUid,
        credentialsProvider,
        createPlatformFileTransport(),
        onAuthExpired,
    )

    private val serverBaseUrl = canonicalHttpServerBase(serverUrl)
    private val credentialGate = FileCredentialGate(ownerUid, credentialsProvider)

    /** 上传一个可重复使用的源，并返回服务器权威的附件描述符。 */
    suspend fun upload(
        source: UploadSource,
        fileName: String,
        contentType: String,
    ): Outcome<Attachment> = uploadWithMeta(source, fileName, contentType).map(UploadResult::file)

    /** 在先前响应变得不可知后，复用 [identity] 做一次精确重放。 */
    suspend fun upload(
        source: UploadSource,
        fileName: String,
        contentType: String,
        identity: AttachmentUploadIdentity,
    ): Outcome<Attachment> = uploadWithMeta(source, fileName, contentType, identity).map(UploadResult::file)

    /** 上传一个可重复使用的源，并返回服务器推导的媒体元数据。 */
    suspend fun uploadWithMeta(
        source: UploadSource,
        fileName: String,
        contentType: String,
    ): Outcome<UploadResult> = uploadWithMeta(
        source,
        fileName,
        contentType,
        newUploadIdentity(),
    )

    /** 复用 [identity] 做一次精确重放，并返回服务器推导的媒体元数据。 */
    suspend fun uploadWithMeta(
        source: UploadSource,
        fileName: String,
        contentType: String,
        identity: AttachmentUploadIdentity,
    ): Outcome<UploadResult> = uploadWithMeta(source, fileName, contentType, identity) { }

    /** 上传时仅在当前 owner 与协程仍然活跃期间发出进度。 */
    suspend fun uploadWithMeta(
        source: UploadSource,
        fileName: String,
        contentType: String,
        onProgress: (Float) -> Unit,
    ): Outcome<UploadResult> = uploadWithMeta(
        source,
        fileName,
        contentType,
        newUploadIdentity(),
        onProgress,
    )

    /** 精确重放变体；本层绝不替换 [identity]，并且只重放一次已被取代的 bearer。 */
    suspend fun uploadWithMeta(
        source: UploadSource,
        fileName: String,
        contentType: String,
        identity: AttachmentUploadIdentity,
        onProgress: (Float) -> Unit,
    ): Outcome<UploadResult> = outcome {
        // 恶意/自定义的源可能暴露动态 getter。这个契约只捕获一次，
        // 使 HTTP Content-Length 与校验包装器永远不可能观察到不同的值。
        val sourceLength = source.contentLength
        require(sourceLength in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES) {
            "附件不能超过 ${AttachmentPolicy.MAX_UPLOAD_BYTES / (1024 * 1024)} MB"
        }
        val plan = MultipartUploadPlan.create(
            fileName = fileName,
            contentType = contentType,
            fileLength = sourceLength,
        )
        var latestProgress = -1f
        val checkedSource = ExactLengthUploadSource(
            delegate = source,
            declaredLength = sourceLength,
            credentialGate = credentialGate,
            onProgress = { progress ->
                if (progress > latestProgress) {
                    onProgress(progress)
                    latestProgress = progress
                }
            },
        )

        suspend fun uploadAttempt(): UploadResult {
            val credentials = credentialGate.requireCredentials()
            val response = try {
                transport.upload(
                    url = "$serverBaseUrl/api/v1/files/upload",
                    bearerToken = credentials.accessToken,
                    identity = identity,
                    plan = plan,
                    source = checkedSource,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val authoritative = credentialGate.authoritativeFailure(credentials, failure)
                if (authoritative is AppError.AuthExpired) onAuthExpired(credentials.accessToken)
                throw authoritative
            }
            return credentialGate.publishUploadResponse {
                FileOps.parseUploadResult(response)
            }
        }

        try {
            uploadAttempt()
        } catch (_: SupersededFileCredentialException) {
            // 可重复使用的源与稳定 identity 使这次有界重放是安全的，
            // 无论旧 bearer 是在准入之前失败，还是在服务器持久提交其回执之后失败。
            uploadAttempt()
        }
    }

    /** 显式 ByteArray 便捷方法；超过 [MAX_SMALL_UPLOAD_BYTES] 的 payload 会被拒绝。 */
    suspend fun uploadSmallBytes(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): Outcome<Attachment> = outcome {
        upload(bytes.asSmallUploadSource(), fileName, contentType).getOrThrow()
    }

    /** 显式 ByteArray 便捷方法，包含服务器推导的媒体元数据。 */
    suspend fun uploadSmallBytesWithMeta(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
        onProgress: (Float) -> Unit = {},
    ): Outcome<UploadResult> = outcome {
        uploadWithMeta(
            bytes.asSmallUploadSource(),
            fileName,
            contentType,
            onProgress,
        ).getOrThrow()
    }

    /**
     * 流式下载一个已认证的附件，且不在 repository 内存中物化它。
     * 失败可能在 [sink] 中留下部分字节；原子发布或清理由调用方负责。
     */
    suspend fun downloadTo(
        attachment: Attachment,
        sink: DownloadSink,
        onProgress: (Float) -> Unit = {},
    ): Outcome<Unit> = outcome {
        require(attachment.size in 0L..AttachmentPolicy.MAX_UPLOAD_BYTES) {
            "附件大小必须在 0..${AttachmentPolicy.MAX_UPLOAD_BYTES} 字节内"
        }
        val credentials = credentialGate.requireCredentials()
        val checkedSink = ExactLengthDownloadSink(
            delegate = sink,
            expectedLength = attachment.size,
            credentials = credentials,
            credentialGate = credentialGate,
            onProgress = onProgress,
        )
        try {
            transport.downloadTo(
                url = resolveUrl(attachment),
                bearerToken = credentials.accessToken,
                expectedBytes = attachment.size,
                sink = checkedSink,
            )
            currentCoroutineContext().ensureActive()
            credentialGate.publishResponse(credentials) { checkedSink.finish() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val authoritative = credentialGate.authoritativeFailure(credentials, failure)
            if (authoritative is AppError.AuthExpired) onAuthExpired(credentials.accessToken)
            throw authoritative
        }
    }

    /** 显式内存便捷方法；所有 HTTP 与精确长度校验都复用 [downloadTo]。 */
    suspend fun downloadSmall(attachment: Attachment): Outcome<ByteArray> = outcome {
        require(attachment.size in 0L..MAX_SMALL_DOWNLOAD_BYTES) {
            "附件超过内存下载上限，必须使用流式 downloadTo"
        }
        val bytes = ByteArray(attachment.size.toInt())
        var offset = 0
        downloadTo(
            attachment = attachment,
            sink = DownloadSink { chunk, chunkOffset, length ->
                chunk.copyInto(bytes, offset, chunkOffset, chunkOffset + length)
                offset += length
            },
        ).getOrThrow()
        check(offset == bytes.size) { "下载响应大小与附件声明不一致" }
        bytes
    }

    fun resolveUrl(attachment: Attachment): String = FileOps.resolveUrl(serverBaseUrl, attachment)

    /** 先使凭据失效，再断开每个活跃的 HTTP 请求。 */
    override fun close() {
        credentialGate.close()
        transport.close()
    }
}

/** 供平台传输层与其他附件 URL 消费者使用的纯共享规则。 */
object FileOps {
    private val uploadJson = Json { ignoreUnknownKeys = false }

    fun resolveUrl(serverUrl: String, path: String): String =
        "${canonicalHttpServerBase(serverUrl)}/api/v1/files/${AttachmentPolicy.canonicalPath(path)}"

    fun resolveUrl(serverUrl: String, attachment: Attachment): String = resolveUrl(serverUrl, attachment.path)

    fun parseUploadResult(body: String): UploadResult = runCatching {
        uploadJson.decodeFromString<UploadResult>(body)
    }.getOrElse {
        throw AppError.Business(-1, "上传服务返回了无效响应")
    }
}

/** HTTP 失败绝不会携带不可信的响应体或 bearer 材料。 */
internal fun fileUploadHttpFailure(status: Int): AppError = when (status) {
    401 -> AppError.AuthExpired
    else -> AppError.Business(status, "上传失败（HTTP $status）")
}

internal fun fileDownloadHttpFailure(status: Int): AppError = when (status) {
    401 -> AppError.AuthExpired
    else -> AppError.Business(status, "下载失败（HTTP $status）")
}

internal class SupersededFileCredentialException :
    IllegalStateException("文件请求使用的认证凭据已经轮换")

internal class FileCredentialGate(
    private val ownerUid: String,
    private val credentialsProvider: () -> SessionHttpCredentials,
) {
    private val lock = Any()
    private var closed = false
    private val ownerIdentityEpoch: Long

    init {
        require(ownerUid.isNotBlank()) { "文件仓库 owner uid 不能为空" }
        val initial = credentialsProvider()
        check(initial.uid == ownerUid) { "文件仓库初始认证身份不匹配" }
        ownerIdentityEpoch = initial.identityEpoch
    }

    fun requireCredentials(): RequiredFileCredentials = synchronized(lock) {
        check(!closed) { "文件仓库已经关闭" }
        val snapshot = credentialsProvider()
        requireCurrentOwner(snapshot)
        val token = snapshot.accessToken?.takeIf(String::isNotBlank)
            ?: error("认证会话缺少文件访问凭据")
        require(token.all { it.code in 0x21..0x7e }) { "文件访问凭据包含非法字符" }
        RequiredFileCredentials(token)
    }

    fun ensureCurrentOwner() = synchronized(lock) {
        check(!closed) { "文件仓库已经关闭" }
        requireCurrentOwner(credentialsProvider())
    }

    fun ensureCurrentCredentials(credentials: RequiredFileCredentials) = synchronized(lock) {
        check(!closed) { "文件仓库已经关闭" }
        requireCurrentCredentials(credentials)
    }

    /**
     * 服务器在读取请求体之前就已认证了这次上传。同一资源 owner 稍后的访问轮换
     * 不会使成功的回执失效；资源图替换与 close 才会。
     */
    fun <T> publishUploadResponse(block: () -> T): T = synchronized(lock) {
        check(!closed) { "文件仓库已经关闭" }
        requireCurrentOwner(credentialsProvider())
        block()
    }

    /** 将响应发布与 close、身份替换以及访问轮换串行化。 */
    fun <T> publishResponse(credentials: RequiredFileCredentials, block: () -> T): T = synchronized(lock) {
        check(!closed) { "文件仓库已经关闭" }
        requireCurrentCredentials(credentials)
        block()
    }

    /** 来自已被取代 bearer 的 401 不能使当前的持久会话退场。 */
    fun authoritativeFailure(
        credentials: RequiredFileCredentials,
        failure: Exception,
    ): Exception = synchronized(lock) {
        check(!closed) { "文件仓库已经关闭" }
        try {
            requireCurrentCredentials(credentials)
            failure
        } catch (_: SupersededFileCredentialException) {
            SupersededFileCredentialException()
        }
    }

    /** 在生命周期边界内执行回调，排除并发的 [close]。 */
    fun publishProgress(block: () -> Unit) = synchronized(lock) {
        check(!closed) { "文件仓库已经关闭" }
        requireCurrentOwner(credentialsProvider())
        block()
    }

    fun close(): Boolean = synchronized(lock) {
        if (closed) false else {
            closed = true
            true
        }
    }

    private fun requireCurrentOwner(snapshot: SessionHttpCredentials) {
        check(snapshot.uid == ownerUid && snapshot.identityEpoch == ownerIdentityEpoch) {
            "文件仓库所属认证会话已经变更"
        }
    }

    private fun requireCurrentCredentials(credentials: RequiredFileCredentials) {
        val snapshot = credentialsProvider()
        requireCurrentOwner(snapshot)
        if (snapshot.accessToken != credentials.accessToken) {
            throw SupersededFileCredentialException()
        }
    }
}

internal data class RequiredFileCredentials(val accessToken: String)

/** 校验源契约，并为两个平台传输层持有进度语义。 */
private class ExactLengthUploadSource(
    private val delegate: UploadSource,
    private val declaredLength: Long,
    private val credentialGate: FileCredentialGate,
    private val onProgress: (Float) -> Unit,
) : UploadSource {
    override val contentLength: Long = declaredLength

    override suspend fun writeTo(sink: UploadSink) {
        var written = 0L
        delegate.writeTo(UploadSink { bytes, offset, length ->
            currentCoroutineContext().ensureActive()
            require(offset >= 0 && length > 0 && offset <= bytes.size && length <= bytes.size - offset) {
                "UploadSource emitted an invalid byte range"
            }
            check(written <= contentLength - length) {
                "UploadSource emitted more bytes than its declared contentLength"
            }
            credentialGate.ensureCurrentOwner()
            sink.write(bytes, offset, length)
            written += length
            currentCoroutineContext().ensureActive()
            credentialGate.publishProgress {
                val progress = if (contentLength == 0L) 1f else written.toFloat() / contentLength
                onProgress(progress.coerceIn(0f, 1f))
            }
        })
        check(written == contentLength) {
            "UploadSource emitted $written bytes but declared $contentLength"
        }
        if (contentLength == 0L) {
            currentCoroutineContext().ensureActive()
            credentialGate.publishProgress { onProgress(1f) }
        }
    }
}

/** 校验传输层输出，并持有下载进度发布的语义。 */
private class ExactLengthDownloadSink(
    private val delegate: DownloadSink,
    private val expectedLength: Long,
    private val credentials: RequiredFileCredentials,
    private val credentialGate: FileCredentialGate,
    private val onProgress: (Float) -> Unit,
) : DownloadSink {
    private var written = 0L

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        currentCoroutineContext().ensureActive()
        require(offset >= 0 && length > 0 && offset <= bytes.size && length <= bytes.size - offset) {
            "Download transport emitted an invalid byte range"
        }
        if (written > expectedLength - length) throw downloadLengthMismatch()
        credentialGate.ensureCurrentCredentials(credentials)
        delegate.write(bytes, offset, length)
        currentCoroutineContext().ensureActive()
        credentialGate.publishResponse(credentials) {
            written += length
            if (written < expectedLength) {
                onProgress(
                    minOf(
                        (written.toDouble() / expectedLength.toDouble()).toFloat(),
                        MAX_INCOMPLETE_DOWNLOAD_PROGRESS,
                    ),
                )
            }
        }
    }

    fun finish() {
        if (written != expectedLength) throw downloadLengthMismatch()
        onProgress(1f)
    }

    private fun downloadLengthMismatch(): AppError.Business =
        AppError.Business(-1, "下载响应大小与附件声明不一致")
}

/** 仍能明确表示"未完成"的最大 Float 进度值。 */
private const val MAX_INCOMPLETE_DOWNLOAD_PROGRESS = 0.99999994f

internal data class MultipartUploadPlan(
    val boundary: String,
    val prefix: ByteArray,
    val suffix: ByteArray,
    val contentLength: Long,
) {
    companion object {
        fun create(fileName: String, contentType: String, fileLength: Long): MultipartUploadPlan {
            require(fileLength >= 0L) { "fileLength must not be negative" }
            val boundary = buildString {
                append("TeamTalk-")
                repeat(24) { append(HEX[Random.nextInt(HEX.length)]) }
            }
            check(boundary.all { it.isLetterOrDigit() || it == '-' })
            val safeName = sanitizeMultipartFileName(fileName)
            val safeType = sanitizeMultipartContentType(contentType)
            val prefix = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                append(safeName).append("\"\r\n")
                append("Content-Type: ").append(safeType).append("\r\n")
                append("Content-Length: ").append(fileLength).append("\r\n\r\n")
            }.encodeToByteArray()
            val suffix = "\r\n--$boundary--\r\n".encodeToByteArray()
            val requestLength = checkedLengthSum(prefix.size.toLong(), fileLength, suffix.size.toLong())
            return MultipartUploadPlan(boundary, prefix, suffix, requestLength)
        }

        private const val HEX = "0123456789abcdef"
    }
}

internal fun sanitizeMultipartFileName(value: String): String {
    val sanitized = value
        .trim()
        .map { char ->
            if (char == '\r' || char == '\n' || char == '"' || char == '\\' || char.code < 0x20 || char.code == 0x7f) {
                '_'
            } else {
                char
            }
        }
        .joinToString("")
        .take(255)
    return sanitized.ifBlank { "attachment" }
}

internal fun sanitizeMultipartContentType(value: String): String = value
    .trim()
    .takeIf { candidate ->
        candidate.length <= AttachmentPolicy.MAX_CONTENT_TYPE_LENGTH &&
            candidate.count { it == '/' } == 1 &&
            candidate.all { it.isLetterOrDigit() || it in "!#$&^_.+-/" }
    }
    ?: "application/octet-stream"

private fun checkedLengthSum(vararg values: Long): Long {
    var result = 0L
    for (value in values) {
        check(value >= 0L && result <= Long.MAX_VALUE - value) { "multipart request length overflow" }
        result += value
    }
    return result
}

internal interface PlatformFileTransport {
    suspend fun upload(
        url: String,
        bearerToken: String,
        identity: AttachmentUploadIdentity,
        plan: MultipartUploadPlan,
        source: UploadSource,
    ): String

    /** 恰好流式传输 [expectedBytes] 个字节，或者失败且不上报成功完成。 */
    suspend fun downloadTo(
        url: String,
        bearerToken: String,
        expectedBytes: Long,
        sink: DownloadSink,
    )

    fun close()
}

internal expect fun createPlatformFileTransport(): PlatformFileTransport

internal expect fun canonicalHttpServerBase(serverUrl: String): String

/** 唯一允许明文 HTTP 的例外是显式的本地开发端点。 */
internal fun String.isExplicitLoopbackHost(): Boolean =
    equals("localhost", ignoreCase = true) ||
        this == "127.0.0.1" ||
        this == "::1" ||
        // 在两个 JVM 目标上，java.net.URI 都会在 IPv6 authority 的 host 中保留方括号。
        this == "[::1]"

private fun createAttachmentUploadIdentity(): AttachmentUploadIdentity = AttachmentUploadIdentity(
    uploadId = UUID.randomUUID().toString(),
    issuedAt = System.currentTimeMillis(),
)

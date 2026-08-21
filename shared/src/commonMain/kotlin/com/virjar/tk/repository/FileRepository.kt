package com.virjar.tk.repository

import com.virjar.tk.AppError
import com.virjar.tk.Outcome
import com.virjar.tk.body.AttachmentPolicy
import com.virjar.tk.client.SessionHttpCredentials
import com.virjar.tk.http.UploadResult
import com.virjar.tk.model.Attachment
import com.virjar.tk.outcome
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Largest payload accepted by the explicit in-memory upload convenience API. */
const val MAX_SMALL_UPLOAD_BYTES: Int = 1024 * 1024

/** Largest download which may intentionally be materialized as one [ByteArray]. */
const val MAX_SMALL_DOWNLOAD_BYTES: Long = 8L * 1024 * 1024

/** Default chunk size used by repeatable in-memory and platform file sources. */
const val DEFAULT_UPLOAD_CHUNK_BYTES: Int = 64 * 1024

/**
 * Synchronous-consumption sink used by [UploadSource]. The source may reuse [bytes] after this
 * call returns, so an implementation must not retain the buffer.
 */
fun interface UploadSink {
    suspend fun write(bytes: ByteArray, offset: Int, length: Int)
}

/**
 * Repeatable streaming upload body.
 *
 * [contentLength] is fixed before HTTP starts and every [writeTo] call must emit exactly that many
 * bytes. Implementations must open a fresh underlying source for each call. The contract stays in
 * common code and deliberately exposes no `java.io` type; JVM/Android add `File.asUploadSource()`.
 */
interface UploadSource {
    val contentLength: Long

    suspend fun writeTo(sink: UploadSink)
}

/**
 * Explicit convenience for genuinely small payloads. A defensive copy makes the returned source
 * repeatable even when the caller later mutates its original array.
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
 * Session-owned HTTP file repository.
 *
 * The owner uid and server identity are fixed for this object's lifetime. The credential provider
 * is evaluated once per request, so a reconnect can rotate the token for the same uid while a
 * reused [com.virjar.tk.client.UserSession] carrying another uid fails closed. [close] is
 * idempotent and aborts active platform connections.
 */
class FileRepository internal constructor(
    serverUrl: String,
    ownerUid: String,
    credentialsProvider: () -> SessionHttpCredentials,
    private val transport: PlatformFileTransport,
) : AutoCloseable {
    constructor(
        serverUrl: String,
        ownerUid: String,
        credentialsProvider: () -> SessionHttpCredentials,
    ) : this(serverUrl, ownerUid, credentialsProvider, createPlatformFileTransport())

    private val serverBaseUrl = canonicalHttpServerBase(serverUrl)
    private val credentialGate = FileCredentialGate(ownerUid, credentialsProvider)

    /** Upload a repeatable source and return the server-authoritative attachment descriptor. */
    suspend fun upload(
        source: UploadSource,
        fileName: String,
        contentType: String,
    ): Outcome<Attachment> = uploadWithMeta(source, fileName, contentType).map(UploadResult::file)

    /** Upload a repeatable source and return server-derived media metadata. */
    suspend fun uploadWithMeta(
        source: UploadSource,
        fileName: String,
        contentType: String,
    ): Outcome<UploadResult> = uploadWithMeta(source, fileName, contentType) { }

    /** Upload with progress emitted only while this owner and coroutine are still active. */
    suspend fun uploadWithMeta(
        source: UploadSource,
        fileName: String,
        contentType: String,
        onProgress: (Float) -> Unit,
    ): Outcome<UploadResult> = outcome {
        // A hostile/custom source may expose a dynamic getter. Capture this contract exactly once
        // so HTTP Content-Length and the validating wrapper can never observe different values.
        val sourceLength = source.contentLength
        require(sourceLength >= 0L) { "上传长度不能为负数" }
        val credentials = credentialGate.requireCredentials()
        val plan = MultipartUploadPlan.create(
            fileName = fileName,
            contentType = contentType,
            fileLength = sourceLength,
        )
        val checkedSource = ExactLengthUploadSource(
            delegate = source,
            declaredLength = sourceLength,
            credentialGate = credentialGate,
            onProgress = onProgress,
        )
        val response = transport.upload(
            url = "$serverBaseUrl/api/v1/files/upload",
            bearerToken = credentials.accessToken,
            plan = plan,
            source = checkedSource,
        )
        credentialGate.ensureCurrentOwner()
        FileOps.parseUploadResult(response)
    }

    /** Explicit ByteArray convenience; payloads above [MAX_SMALL_UPLOAD_BYTES] are rejected. */
    suspend fun uploadSmallBytes(
        bytes: ByteArray,
        fileName: String,
        contentType: String,
    ): Outcome<Attachment> = outcome {
        upload(bytes.asSmallUploadSource(), fileName, contentType).getOrThrow()
    }

    /** Explicit ByteArray convenience including server-derived media metadata. */
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
     * Small-object bridge retained for deterministic tests and metadata payloads. Large attachment
     * downloads belong to platform cache/transfer controllers and are never materialized here.
     */
    suspend fun downloadSmall(attachment: Attachment): Outcome<ByteArray> = outcome {
        require(attachment.size in 0..MAX_SMALL_DOWNLOAD_BYTES) {
            "附件超过内存下载上限，必须使用平台流式下载入口"
        }
        val credentials = credentialGate.requireCredentials()
        val bytes = transport.downloadSmall(
            url = resolveUrl(attachment),
            bearerToken = credentials.accessToken,
            maxBytes = MAX_SMALL_DOWNLOAD_BYTES,
        )
        credentialGate.ensureCurrentOwner()
        bytes
    }

    fun resolveUrl(attachment: Attachment): String = FileOps.resolveUrl(serverBaseUrl, attachment)

    /** Invalidates credentials first, then disconnects every active HTTP request. */
    override fun close() {
        credentialGate.close()
        transport.close()
    }
}

/** Pure shared rules used by platform transports and other attachment URL consumers. */
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

    /** Executes callbacks inside the lifecycle boundary, excluding a concurrent [close]. */
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
}

internal data class RequiredFileCredentials(val accessToken: String)

/** Validates the source contract and owns progress semantics for both platform transports. */
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
                append("Content-Type: ").append(safeType).append("\r\n\r\n")
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
        plan: MultipartUploadPlan,
        source: UploadSource,
    ): String

    suspend fun downloadSmall(url: String, bearerToken: String, maxBytes: Long): ByteArray

    fun close()
}

internal expect fun createPlatformFileTransport(): PlatformFileTransport

internal expect fun canonicalHttpServerBase(serverUrl: String): String

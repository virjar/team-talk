@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.virjar.tk.shared.repository

import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ID_HEADER
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ISSUED_AT_HEADER
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.http.*
import com.virjar.tk.shared.platform.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.posix.*

/** Reopen a stable local file for each reliable retry; never materialize an attachment in NSData. */
fun PlatformFile.asUploadSource(): UploadSource {
    val file = canonicalFile
    require(file.isFile && !file.isSymbolicLink()) { "待上传文件不存在" }
    val size = file.length()
    val modified = file.lastModified()
    return object : UploadSource {
        override val contentLength: Long = size
        override suspend fun writeTo(sink: UploadSink) = foundationHttpIo {
            check(file.length() == size && file.lastModified() == modified) { "待上传文件已发生变化" }
            val input = open(file.path, O_RDONLY or O_NOFOLLOW)
            check(input >= 0) { "无法读取待上传文件" }
            try {
                val bytes = ByteArray(DEFAULT_UPLOAD_CHUNK_BYTES)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = bytes.usePinned { pinned ->
                        read(input, pinned.addressOf(0), bytes.size.toULong())
                    }
                    if (count < 0 && errno == EINTR) continue
                    check(count >= 0) { "读取上传文件失败" }
                    if (count == 0L) break
                    check(total <= size - count) { "待上传文件已发生变化" }
                    sink.write(bytes, 0, count.toInt())
                    total += count
                }
                check(total == size && file.length() == size && file.lastModified() == modified) { "待上传文件已发生变化" }
            } finally { platform.posix.close(input) }
        }
    }
}

internal actual fun canonicalHttpServerBase(serverUrl: String): String {
    val components = requireNotNull(NSURLComponents.componentsWithString(serverUrl.trim())) { "文件服务器地址非法" }
    val scheme = components.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "文件服务器必须使用 HTTP(S)" }
    require(!components.host.isNullOrBlank()) { "文件服务器地址缺少主机" }
    require(components.user == null && components.password == null) { "文件服务器地址不能包含凭据" }
    require(components.query == null && components.fragment == null) { "文件服务器地址不能包含 query 或 fragment" }
    val port = components.port?.intValue
    require(port == null || port in 1..65535) { "文件服务器端口非法" }
    components.scheme = scheme
    components.host = components.host?.lowercase()
    if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) components.port = null
    components.path = components.path.orEmpty().trimEnd('/')
    return requireNotNull(components.string).trimEnd('/')
}

internal actual fun createPlatformFileTransport(): PlatformFileTransport = FoundationFileTransport()

private class FoundationFileTransport : PlatformFileTransport {
    private val http = FoundationHttpClient()

    override suspend fun upload(
        url: String,
        bearerToken: String,
        identity: AttachmentUploadIdentity,
        plan: MultipartUploadPlan,
        source: UploadSource,
    ): String = http.operation {
        foundationHttpIo {
            val temporary = PlatformFile(NSTemporaryDirectory(), "teamtalk-upload-${platformRandomUuid()}.body")
            val output = open(temporary.path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW, 384)
            check(output >= 0) { "无法创建上传临时文件" }
            try {
                try {
                    check(NSFileManager.defaultManager.setAttributes(
                        mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication), temporary.path, null,
                    )) { "无法保护上传临时文件" }
                    writeFully(output, plan.prefix, 0, plan.prefix.size)
                    var written = plan.prefix.size.toLong()
                    source.writeTo(UploadSink { bytes, offset, length ->
                        ensureActive()
                        require(offset >= 0 && length > 0 && offset <= bytes.size && length <= bytes.size - offset)
                        check(written <= plan.contentLength - plan.suffix.size - length) { "上传内容超过声明长度" }
                        writeFully(output, bytes, offset, length)
                        written += length
                    })
                    check(written == plan.contentLength - plan.suffix.size) { "上传内容少于声明长度" }
                    writeFully(output, plan.suffix, 0, plan.suffix.size)
                } finally { platform.posix.close(output) }
                ensureActive()
                val request = foundationHttpRequest(url, "POST", mapOf(
                    "Authorization" to "Bearer $bearerToken",
                    "Content-Type" to "multipart/form-data; boundary=${plan.boundary}",
                    "Content-Length" to plan.contentLength.toString(),
                    ATTACHMENT_UPLOAD_ID_HEADER to identity.uploadId,
                    ATTACHMENT_UPLOAD_ISSUED_AT_HEADER to identity.issuedAt.toString(),
                ), timeoutSeconds = 120.0)
                readBounded(request, 1024 * 1024, NSURL.fileURLWithPath(temporary.path)) { headers ->
                    if (headers.status !in 200..299) throw fileUploadHttpFailure(headers.status)
                }.second
            } finally {
                // URLSession reads from a descriptor; cancelling the task before unlinking is safe on POSIX.
                task?.cancel()
                temporary.delete()
            }
        }
    }

    override suspend fun downloadTo(url: String, bearerToken: String, expectedBytes: Long, sink: DownloadSink) {
        require(expectedBytes >= 0)
        http.operation {
            val request = foundationHttpRequest(url, "GET", mapOf(
                "Authorization" to "Bearer $bearerToken", "Accept-Encoding" to "identity",
            ), timeoutSeconds = 120.0)
            var received = 0L
            stream(request, checkResponse = { headers ->
                if (headers.status != 200) throw fileDownloadHttpFailure(headers.status)
                if (headers.contentLength >= 0 && headers.contentLength != expectedBytes) throw lengthMismatch()
            }) { bytes ->
                if (received > expectedBytes - bytes.size) throw lengthMismatch()
                sink.write(bytes, 0, bytes.size)
                received += bytes.size
            }
            if (received != expectedBytes) throw lengthMismatch()
        }
    }

    override fun close() = http.close()

    private fun lengthMismatch() = AppError.Business(-1, "下载响应大小与附件声明不一致")
}

private suspend fun writeFully(fd: Int, bytes: ByteArray, offset: Int, length: Int) {
    var cursor = offset
    while (cursor < offset + length) {
        currentCoroutineContext().ensureActive()
        val count = bytes.usePinned { pinned -> write(fd, pinned.addressOf(cursor), (offset + length - cursor).toULong()) }
        if (count < 0 && errno == EINTR) continue
        check(count > 0) { "写入上传临时文件失败" }
        cursor += count.toInt()
    }
}

package com.virjar.tk.server.api

import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.FileStoreCapacityExceededException
import com.virjar.tk.server.infra.storage.FileStoreUploadExpiredException
import com.virjar.tk.server.infra.storage.FileStoreUploadConflictException
import com.virjar.tk.server.infra.storage.FileStoreUploadInProgressException
import com.virjar.tk.server.infra.storage.FileStoreUploadStaleAttemptException
import com.virjar.tk.server.infra.storage.BeginFileStoreUploadResult
import com.virjar.tk.server.infra.storage.ReadRange
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.server.domain.attachment.AttachmentAccess
import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ID_HEADER
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ISSUED_AT_HEADER
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.http.UploadResult
import com.virjar.tk.protocol.http.parseAttachmentUploadIdentityHeaders
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private val responseJson = Json { encodeDefaults = true }
private val SAFE_INLINE_ATTACHMENT_CONTENT_TYPES = setOf(
    "image/avif",
    "image/gif",
    "image/jpeg",
    "image/png",
    "image/webp",
)
private const val ATTACHMENT_UPLOAD_FINGERPRINT_VERSION = "teamtalk-attachment-upload-v1"
private const val ATTACHMENT_UPLOAD_IDENTITY_INVALID_MESSAGE = "Invalid attachment upload identity"
private const val ATTACHMENT_UPLOAD_IDENTITY_EXPIRED_MESSAGE = "Attachment upload identity has expired"
private const val ATTACHMENT_UPLOAD_IDENTITY_FUTURE_MESSAGE = "Attachment upload identity is too far in the future"
private const val ATTACHMENT_UPLOAD_IN_PROGRESS_MESSAGE = "Attachment upload is already in progress"
private const val ATTACHMENT_UPLOAD_CONFLICT_MESSAGE = "Attachment upload identity conflicts with its original request"
private const val UPLOAD_TRANSACTION_RETRY_AFTER_SECONDS = "1"

fun Route.fileRoutes(
    fileStore: FileStore,
    accessTokens: AccessTokenValidator,
    attachmentAccess: AttachmentAccess,
    uploadAdmission: AttachmentUploadAdmission,
    thumbnailService: com.virjar.tk.server.infra.media.ThumbnailService =
        com.virjar.tk.server.infra.media.ThumbnailService(
            fileStore.temporaryDirectory,
            retireTempFile = fileStore::retireTemporaryFile,
        ),
    maxUploadBytes: Long = AttachmentPolicy.MAX_UPLOAD_BYTES,
    uploadStagingTimeoutMillis: Long = DEFAULT_UPLOAD_STAGING_TIMEOUT_MILLIS,
    clock: () -> Long = System::currentTimeMillis,
    beforeUploadResponseDelivery: (encodedReceipt: String) -> Unit = {},
) {
    require(maxUploadBytes in 1..AttachmentPolicy.MAX_UPLOAD_BYTES) {
        "maxUploadBytes must be within the attachment policy bound"
    }
    require(uploadStagingTimeoutMillis > 0L) { "uploadStagingTimeoutMillis must be positive" }
    route("/api/v1/files") {
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") ?: return@get call.respond(HttpStatusCode.NotFound)
            val token = call.bearerAuthorizationToken()
            val info = token?.let { accessTokens.validateAccessToken(it) }
                ?: return@get call.respond(HttpStatusCode.Unauthorized, "invalid or missing token")
            val meta = attachmentAccess.readAuthorized(info.uid, path) { canonicalPath ->
                fileStore.getMeta(canonicalPath)
            } ?: return@get call.respond(HttpStatusCode.Forbidden, "attachment access denied")

            val requestedRange = resolveHttpByteRange(
                values = call.request.headers.getAll(HttpHeaders.Range),
                objectSize = meta.size,
            )
            // 让两个存储层共用同一条流式响应路径，这样响应头和权威元数据
            // 就不会因为 respondFile 的推断而产生分歧。HTTP 缓存不得在群成员资格
            // 或文档授权被撤销后继续放行访问；TeamTalk 的账户级媒体缓存
            // 仍是明确的离线所有者。
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
            call.response.headers.append(HttpHeaders.Vary, HttpHeaders.Authorization)
            call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
            if (requestedRange === HttpByteRangeResolution.Unsatisfiable) {
                call.response.headers.append(HttpHeaders.ContentRange, "bytes */${meta.size}")
                return@get call.respond(object : OutgoingContent.NoContent() {
                    override val status = HttpStatusCode.RequestedRangeNotSatisfiable
                    override val contentLength = 0L
                })
            }
            val disposition = if (meta.contentType in SAFE_INLINE_ATTACHMENT_CONTENT_TYPES) {
                ContentDisposition.Inline
            } else {
                ContentDisposition.Attachment
            }.withParameter(ContentDisposition.Parameters.FileName, meta.originalName)
            call.response.headers.append(HttpHeaders.ContentDisposition, disposition.toString())
            val partial = requestedRange as? HttpByteRangeResolution.Partial
            if (partial != null) {
                call.response.headers.append(
                    HttpHeaders.ContentRange,
                    "bytes ${partial.range.start}-${partial.range.end}/${meta.size}",
                )
            }
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val status = if (partial == null) HttpStatusCode.OK else HttpStatusCode.PartialContent
                override val contentType = ContentType.parse(meta.contentType)
                override val contentLength = partial?.contentLength ?: meta.size
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    fileStore.streamTo(meta, channel, partial?.range)
                }
            })
        }

        post("/upload") {
            // 鉴权：Bearer accessToken（TCP 认证时下发，PG epoch 校验）。上传必须已认证。
            val token = call.bearerAuthorizationToken()
            val info = token?.let { accessTokens.validateAccessToken(it) }
            if (info == null) return@post call.respond(HttpStatusCode.Unauthorized, "invalid or missing token")
            val uid = info.uid
            val requestBody = call.request.receiveChannel()
            val identity = try {
                parseAttachmentUploadIdentityHeaders(
                    call.request.headers.singleValueOrNull(ATTACHMENT_UPLOAD_ID_HEADER),
                    call.request.headers.singleValueOrNull(ATTACHMENT_UPLOAD_ISSUED_AT_HEADER),
                )
            } catch (_: IllegalArgumentException) {
                requestBody.cancelRejectedUpload()
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ATTACHMENT_UPLOAD_IDENTITY_INVALID_MESSAGE,
                )
            }
            val identityCheckedAt = clock()
            try {
                identity.requireActiveAt(identityCheckedAt)
            } catch (_: IllegalArgumentException) {
                requestBody.cancelRejectedUpload()
                val status = if (identity.issuedAt > identityCheckedAt) {
                    HttpStatusCode.BadRequest
                } else {
                    HttpStatusCode.Gone
                }
                val message = if (status == HttpStatusCode.Gone) {
                    ATTACHMENT_UPLOAD_IDENTITY_EXPIRED_MESSAGE
                } else {
                    ATTACHMENT_UPLOAD_IDENTITY_FUTURE_MESSAGE
                }
                return@post call.respond(status, message)
            }
            val envelope = try {
                strictMultipartEnvelope(call.request.headers, maxUploadBytes)
            } catch (rejection: UploadBodyRejection) {
                requestBody.cancelRejectedUpload()
                return@post call.respond(rejection.status, rejection.responseMessage)
            }

            val uploadLease = uploadAdmission.tryAcquire(uid)
            if (uploadLease == null) {
                requestBody.cancelRejectedUpload(ATTACHMENT_UPLOAD_SATURATED_MESSAGE)
                call.response.headers.append(
                    HttpHeaders.RetryAfter,
                    ATTACHMENT_UPLOAD_RETRY_AFTER_SECONDS,
                )
                return@post call.respondText(
                    ATTACHMENT_UPLOAD_SATURATED_MESSAGE,
                    ContentType.Text.Plain,
                    HttpStatusCode.ServiceUnavailable,
                )
            }

            val upload = AttachmentUploadRequest(fileStore, uploadLease)
            try {
                val staged = try {
                    withTimeout(uploadStagingTimeoutMillis) {
                        requestBody.stageStrictSingleFileMultipart(
                            envelope,
                            maxUploadBytes,
                        ) { multipartFile ->
                            val begin = upload.begin(uid, identity, multipartFile.payloadLength)
                            val tempFile = upload.createStagingFile()
                            StrictMultipartStagingTarget(tempFile, begin)
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    requestBody.cancelRejectedUpload(UPLOAD_STAGING_TIMEOUT_MESSAGE)
                    return@post call.respond(HttpStatusCode.RequestTimeout, UPLOAD_STAGING_TIMEOUT_MESSAGE)
                } catch (rejection: UploadBodyRejection) {
                    requestBody.cancelRejectedUpload()
                    return@post call.respond(rejection.status, rejection.responseMessage)
                } catch (capacity: FileStoreCapacityExceededException) {
                    requestBody.cancelRejectedUpload()
                    return@post call.respond(
                        HttpStatusCode.InsufficientStorage,
                        capacity.scope.responseMessage,
                    )
                } catch (_: FileStoreUploadExpiredException) {
                    requestBody.cancelRejectedUpload()
                    return@post call.respond(
                        HttpStatusCode.Gone,
                        ATTACHMENT_UPLOAD_IDENTITY_EXPIRED_MESSAGE,
                    )
                } catch (_: FileStoreUploadInProgressException) {
                    requestBody.cancelRejectedUpload()
                    call.response.headers.append(HttpHeaders.RetryAfter, UPLOAD_TRANSACTION_RETRY_AFTER_SECONDS)
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        ATTACHMENT_UPLOAD_IN_PROGRESS_MESSAGE,
                    )
                } catch (failure: Throwable) {
                    requestBody.cancelRejectedUpload()
                    throw failure
                }

                val requestFingerprint = attachmentUploadFingerprint(uid, identity, staged)
                val transaction = when (val begin = staged.owner) {
                    is BeginFileStoreUploadResult.ReplayCandidate -> {
                        val receipt = try {
                            begin.candidate.requireSameFingerprint(requestFingerprint)
                        } catch (_: FileStoreUploadConflictException) {
                            return@post call.respond(
                                HttpStatusCode.Conflict,
                                ATTACHMENT_UPLOAD_CONFLICT_MESSAGE,
                            )
                        } catch (_: FileStoreUploadExpiredException) {
                            return@post call.respond(
                                HttpStatusCode.Gone,
                                ATTACHMENT_UPLOAD_IDENTITY_EXPIRED_MESSAGE,
                            )
                        } catch (_: FileStoreUploadStaleAttemptException) {
                            call.response.headers.append(
                                HttpHeaders.RetryAfter,
                                UPLOAD_TRANSACTION_RETRY_AFTER_SECONDS,
                            )
                            return@post call.respond(
                                HttpStatusCode.Conflict,
                                ATTACHMENT_UPLOAD_IN_PROGRESS_MESSAGE,
                            )
                        }
                        beforeUploadResponseDelivery(receipt.encodedReceipt)
                        return@post call.respondText(receipt.encodedReceipt, ContentType.Application.Json)
                    }

                    is BeginFileStoreUploadResult.Started -> begin.transaction
                }

                try {
                    transaction.bindFingerprint(requestFingerprint)
                } catch (_: FileStoreUploadConflictException) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        ATTACHMENT_UPLOAD_CONFLICT_MESSAGE,
                    )
                }

                val isImage = staged.contentType.startsWith("image/")
                val isVideo = staged.contentType.startsWith("video/")
                val mediaInfo = when {
                    isImage -> thumbnailService.processImage(staged.file)
                    isVideo -> thumbnailService.processVideo(staged.file)
                    else -> null
                }
                mediaInfo?.thumbFile?.let(upload::ownTemporaryFile)

                val encodedResponse = try {
                    val thumbnail = mediaInfo?.thumbFile
                    if (thumbnail != null) transaction.reserveObject(thumbnail.length())
                    val storedPath = transaction.storeReserved(
                        staged.originalName,
                        staged.contentType,
                        staged.file,
                    )
                    val thumbPath = thumbnail?.let { file ->
                        transaction.storeReserved(
                            "thumb_${staged.originalName}.jpg",
                            "image/jpeg",
                            file,
                        )
                    }
                    responseJson.encodeToString(
                        UploadResult(
                            file = fileStore.getAttachment(storedPath)
                                ?: error("Stored attachment metadata missing"),
                            thumbnail = thumbPath?.let { path ->
                                fileStore.getAttachment(path)
                                    ?: error("Stored thumbnail metadata missing")
                            },
                            width = mediaInfo?.width ?: 0,
                            height = mediaInfo?.height ?: 0,
                            durationSec = mediaInfo?.durationSec,
                        ),
                    )
                } catch (capacity: FileStoreCapacityExceededException) {
                    return@post call.respond(
                        HttpStatusCode.InsufficientStorage,
                        capacity.scope.responseMessage,
                    )
                }

                // 临时文件回收是成功发布边界的一部分：如果无法确认回收完成，
                // 仍处于打开状态的事务会回滚其对象，而不是为
                // 一个操作上不完整的请求保存回执。
                upload.retireTemporaryFiles()
                // 在尝试 HTTP 投递之前先持久化精确的响应。断开的调用方
                // 可以重放相同的请求体并收到这份字节完全一致的回执。
                try {
                    upload.complete(encodedResponse)
                } catch (_: FileStoreUploadExpiredException) {
                    return@post call.respond(
                        HttpStatusCode.Gone,
                        ATTACHMENT_UPLOAD_IDENTITY_EXPIRED_MESSAGE,
                    )
                }
                // 为“提交到投递”的边界收窄出一个确定性的故障缝隙。生产环境
                // 使用空操作默认实现；测试可以在此处注入失败而不会削弱回执所有权。
                beforeUploadResponseDelivery(encodedResponse)
                call.respondText(encodedResponse, ContentType.Application.Json)
            } catch (failure: Throwable) {
                upload.recordFailure(failure)
                throw failure
            } finally {
                upload.close()
            }
        }
    }
}

private sealed interface HttpByteRangeResolution {
    data object Full : HttpByteRangeResolution

    data class Partial(
        val range: ReadRange,
        val contentLength: Long,
    ) : HttpByteRangeResolution

    data object Unsatisfiable : HttpByteRangeResolution
}

/**
 * 针对不可变附件元数据解析一个 RFC 7233 bytes 区间。
 *
 * 解析刻意只发生在鉴权和在线附件 ACL 都成功之后。这个顺序可以防止畸形探测
 * 得知某路径是否存在或对象有多大。不支持多区间，因为该路由只有一条流式响应体路径。
 */
private fun resolveHttpByteRange(
    values: List<String>?,
    objectSize: Long,
): HttpByteRangeResolution {
    require(objectSize >= 0L) { "Stored object size must not be negative" }
    if (values == null) return HttpByteRangeResolution.Full
    if (values.size != 1) return HttpByteRangeResolution.Unsatisfiable

    val value = values.single().trim()
    if (!value.startsWith("bytes=", ignoreCase = true) || value.indexOf(',') >= 0) {
        return HttpByteRangeResolution.Unsatisfiable
    }
    val specification = value.substring("bytes=".length)
    if (specification.isEmpty() || specification.count { it == '-' } != 1) {
        return HttpByteRangeResolution.Unsatisfiable
    }
    val dash = specification.indexOf('-')
    val firstText = specification.substring(0, dash)
    val lastText = specification.substring(dash + 1)

    if (firstText.isEmpty()) {
        val suffixLength = lastText.toUnsignedLongOrNull()
            ?.takeIf { it > 0L }
            ?: return HttpByteRangeResolution.Unsatisfiable
        if (objectSize == 0L) return HttpByteRangeResolution.Unsatisfiable
        val start = maxOf(0L, objectSize - minOf(suffixLength, objectSize))
        return HttpByteRangeResolution.Partial(
            range = ReadRange(start, objectSize - 1L),
            contentLength = objectSize - start,
        )
    }

    val first = firstText.toUnsignedLongOrNull()
        ?: return HttpByteRangeResolution.Unsatisfiable
    if (objectSize == 0L || first >= objectSize) return HttpByteRangeResolution.Unsatisfiable

    val requestedLast = if (lastText.isEmpty()) {
        objectSize - 1L
    } else {
        lastText.toUnsignedLongOrNull()
            ?: return HttpByteRangeResolution.Unsatisfiable
    }
    if (requestedLast < first) return HttpByteRangeResolution.Unsatisfiable
    // RFC 7233 规定：只要第一个字节存在，区间即可满足，并将超大的末尾字节
    // 截断到所选表示，而不是把一个有用的请求变成 416。
    val last = minOf(requestedLast, objectSize - 1L)
    return HttpByteRangeResolution.Partial(
        range = ReadRange(first, last),
        contentLength = last - first + 1L,
    )
}

private fun String.toUnsignedLongOrNull(): Long? =
    takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()

private fun Headers.singleValueOrNull(name: String): String? = getAll(name)?.singleOrNull()

private fun attachmentUploadFingerprint(
    uid: String,
    identity: AttachmentUploadIdentity,
    staged: StrictStagedFile<*>,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(
        ATTACHMENT_UPLOAD_FINGERPRINT_VERSION,
        uid,
        identity.uploadId,
        identity.issuedAt.toString(),
        staged.originalName,
        staged.contentType,
        staged.payloadLength.toString(),
        staged.payloadSha256,
    ).forEach(digest::updateLengthPrefixedUtf8)
    return digest.digest().joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun MessageDigest.updateLengthPrefixedUtf8(value: String) {
    val bytes = value.encodeToByteArray()
    update((bytes.size ushr 24).toByte())
    update((bytes.size ushr 16).toByte())
    update((bytes.size ushr 8).toByte())
    update(bytes.size.toByte())
    update(bytes)
}

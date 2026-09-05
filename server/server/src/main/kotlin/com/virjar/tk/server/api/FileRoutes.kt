package com.virjar.tk.server.api

import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.FileStoreCapacityExceededException
import com.virjar.tk.server.infra.storage.FileStoreUploadExpiredException
import com.virjar.tk.server.infra.storage.FileStoreUploadConflictException
import com.virjar.tk.server.infra.storage.FileStoreUploadDeliveryLease
import com.virjar.tk.server.infra.storage.FileStoreUploadInProgressException
import com.virjar.tk.server.infra.storage.FileStoreUploadReplayCandidate
import com.virjar.tk.server.infra.storage.FileStoreUploadStaleAttemptException
import com.virjar.tk.server.infra.storage.FileStoreUploadTransaction
import com.virjar.tk.server.infra.storage.BeginFileStoreUploadResult
import com.virjar.tk.server.infra.storage.ManagedTempResidueException
import com.virjar.tk.server.infra.storage.ReadRange
import com.virjar.tk.server.infra.storage.STAGING_TEMP_SUFFIX
import com.virjar.tk.server.infra.storage.UPLOAD_STAGING_TEMP_PREFIX
import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.server.domain.attachment.AttachmentAccess
import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ID_HEADER
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ISSUED_AT_HEADER
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.http.UploadResult
import com.virjar.tk.protocol.http.parseAttachmentUploadIdentityHeaders
import com.virjar.tk.protocol.ReliableCommandContract
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
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
            val token = call.bearerToken()
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
            val token = call.bearerToken()
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

            var uploadTransaction: FileStoreUploadTransaction? = null
            var uploadDeliveryLease: FileStoreUploadDeliveryLease? = null
            var uploadReplayCandidate: FileStoreUploadReplayCandidate? = null
            var stagedFile: File? = null
            var mediaInfo: com.virjar.tk.server.infra.media.ThumbnailService.MediaInfo? = null
            var terminalFailure: Throwable? = null
            val retirementCandidates = LinkedHashSet<File>()
            var hasUnlocatedResidue = false
            try {
                val staged = try {
                    withTimeout(uploadStagingTimeoutMillis) {
                        requestBody.stageStrictSingleFileMultipart(
                            envelope,
                            maxUploadBytes,
                        ) { multipartFile ->
                            val begin = fileStore.beginUploadTransaction(
                                uid = uid,
                                uploadId = identity.uploadId,
                                payloadLength = multipartFile.payloadLength,
                                receiptLeaseExpiresAt = ReliableCommandContract.lastActiveAt(identity.issuedAt),
                            )
                            if (begin is BeginFileStoreUploadResult.Started) {
                                uploadTransaction = begin.transaction
                            } else if (begin is BeginFileStoreUploadResult.ReplayCandidate) {
                                // 从 begin 起就持有持久回执及其底层对象，直到
                                // 重放响应完成投递或投递失败。
                                uploadReplayCandidate = begin.candidate
                            }
                            val tempFile = fileStore.createTemporaryFile(
                                UPLOAD_STAGING_TEMP_PREFIX,
                                STAGING_TEMP_SUFFIX,
                            )
                            stagedFile = tempFile
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
                when (val begin = staged.owner) {
                    is BeginFileStoreUploadResult.ReplayCandidate -> {
                        val candidate = checkNotNull(uploadReplayCandidate)
                        check(candidate === begin.candidate) {
                            "Multipart replay owner changed during staging"
                        }
                        val receipt = try {
                            candidate.requireSameFingerprint(requestFingerprint)
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

                    is BeginFileStoreUploadResult.Started -> Unit
                }

                val transaction = checkNotNull(uploadTransaction)
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
                if (isImage || isVideo) {
                    mediaInfo = if (isImage) thumbnailService.processImage(staged.file)
                    else thumbnailService.processVideo(staged.file)
                }

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
                    val mi = mediaInfo
                    responseJson.encodeToString(
                        UploadResult(
                            file = fileStore.getAttachment(storedPath)
                                ?: error("Stored attachment metadata missing"),
                            thumbnail = thumbPath?.let { path ->
                                fileStore.getAttachment(path)
                                    ?: error("Stored thumbnail metadata missing")
                            },
                            width = mi?.width ?: 0,
                            height = mi?.height ?: 0,
                            durationSec = mi?.durationSec,
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
                retireUploadTemporaryFiles(
                    fileStore,
                    listOfNotNull(staged.file, mediaInfo?.thumbFile),
                )
                // 在尝试 HTTP 投递之前先持久化精确的响应。断开的调用方
                // 可以重放相同的请求体并收到这份字节完全一致的回执。
                try {
                    val completion = transaction.complete(encodedResponse)
                    // complete() 在写入持久回执的同时原子地激活这个钉住。一直保留它
                    // 直到 respondText 完成或失败，这样第一份投递出的回执
                    // 永远不会引用在其自身响应窗口期内被回收的底层对象。
                    uploadDeliveryLease = completion.deliveryLease
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
                terminalFailure = failure
                hasUnlocatedResidue = failure.collectManagedTempResidues(retirementCandidates) ||
                    hasUnlocatedResidue
                throw failure
            } finally {
                stagedFile?.let(retirementCandidates::add)
                mediaInfo?.thumbFile?.let(retirementCandidates::add)
                var transactionCleanupConfirmed = false
                var cleanupFailure: Throwable? = null
                try {
                    uploadTransaction?.close()
                    transactionCleanupConfirmed = true
                } catch (transactionFailure: Throwable) {
                    cleanupFailure = transactionFailure
                    hasUnlocatedResidue = transactionFailure.collectManagedTempResidues(retirementCandidates) ||
                        hasUnlocatedResidue
                }
                var deliveryLeaseCleanupConfirmed = false
                try {
                    uploadDeliveryLease?.close()
                    deliveryLeaseCleanupConfirmed = true
                } catch (deliveryLeaseFailure: Throwable) {
                    val firstCleanup = cleanupFailure
                    if (firstCleanup == null) cleanupFailure = deliveryLeaseFailure
                    else if (firstCleanup !== deliveryLeaseFailure) firstCleanup.addSuppressed(deliveryLeaseFailure)
                    hasUnlocatedResidue = deliveryLeaseFailure.collectManagedTempResidues(retirementCandidates) ||
                        hasUnlocatedResidue
                }
                var replayCandidateCleanupConfirmed = false
                try {
                    uploadReplayCandidate?.close()
                    replayCandidateCleanupConfirmed = true
                } catch (candidateFailure: Throwable) {
                    val firstCleanup = cleanupFailure
                    if (firstCleanup == null) cleanupFailure = candidateFailure
                    else if (firstCleanup !== candidateFailure) firstCleanup.addSuppressed(candidateFailure)
                    hasUnlocatedResidue = candidateFailure.collectManagedTempResidues(retirementCandidates) ||
                        hasUnlocatedResidue
                }
                var finalRetirementConfirmed = false
                try {
                    retireUploadTemporaryFiles(fileStore, retirementCandidates)
                    finalRetirementConfirmed = true
                } catch (retirementFailure: Throwable) {
                    val firstCleanup = cleanupFailure
                    if (firstCleanup == null) cleanupFailure = retirementFailure
                    else if (firstCleanup !== retirementFailure) firstCleanup.addSuppressed(retirementFailure)
                    hasUnlocatedResidue = retirementFailure.collectManagedTempResidues(retirementCandidates) ||
                        hasUnlocatedResidue
                } finally {
                    if (
                        transactionCleanupConfirmed &&
                        deliveryLeaseCleanupConfirmed &&
                        replayCandidateCleanupConfirmed &&
                        finalRetirementConfirmed &&
                        !hasUnlocatedResidue
                    ) {
                        uploadLease.close()
                    }
                }
                cleanupFailure?.let { cleanup ->
                    val first = terminalFailure
                    if (first == null) throw cleanup
                    if (first !== cleanup) first.addSuppressed(cleanup)
                }
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

private fun io.ktor.server.application.ApplicationCall.bearerToken(): String? =
    request.header(HttpHeaders.Authorization)
        ?.removePrefix("Bearer ")
        ?.takeIf { it.isNotBlank() }

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

private fun retireUploadTemporaryFiles(fileStore: FileStore, files: Collection<File>) {
    var failure: Throwable? = null
    files.distinctBy { it.absoluteFile.normalize().path }.forEach { file ->
        try {
            fileStore.retireTemporaryFile(file)
        } catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}

private fun Throwable.collectManagedTempResidues(
    files: MutableSet<File>,
    seen: MutableSet<Throwable> = HashSet(),
): Boolean {
    if (!seen.add(this)) return false
    var hasUnlocatedResidue = false
    if (this is ManagedTempResidueException) {
        val residue = entry
        if (residue == null) hasUnlocatedResidue = true else files += residue.toFile()
    }
    cause?.let { cause ->
        hasUnlocatedResidue = cause.collectManagedTempResidues(files, seen) || hasUnlocatedResidue
    }
    suppressed.forEach { suppressedFailure ->
        hasUnlocatedResidue = suppressedFailure.collectManagedTempResidues(files, seen) || hasUnlocatedResidue
    }
    return hasUnlocatedResidue
}

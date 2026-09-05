package com.virjar.tk.server.api

import com.virjar.tk.server.domain.attachment.AttachmentAccess
import com.virjar.tk.server.domain.auth.AccessTokenValidator
import com.virjar.tk.server.infra.media.ThumbnailService
import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.FileStoreNativeResourceCloser
import com.virjar.tk.server.infra.storage.BeginFileStoreUploadResult
import com.virjar.tk.server.infra.storage.FileStoreUploadInProgressException
import com.virjar.tk.server.infra.storage.closeFileStoreNativeResource
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ID_HEADER
import com.virjar.tk.protocol.http.ATTACHMENT_UPLOAD_ISSUED_AT_HEADER
import com.virjar.tk.server.media.FaultBehavior
import com.virjar.tk.server.media.FaultProcessLauncher
import com.virjar.tk.protocol.ReliableCommandContract
import com.virjar.tk.shared.repository.FileOps
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.request.forms.*
import io.ktor.http.content.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 上传接口鉴权：Bearer accessToken（权威凭据校验），X-Uid 伪造通道已封死。
 */
class FileUploadAuthTest {

    private fun testFileStore(
        maxTotalBytes: Long = 10L * 1024 * 1024 * 1024,
        largeFileThreshold: Long = 32L * 1024 * 1024,
        clock: () -> Long = System::currentTimeMillis,
        managedTempFileDeleter: (Path) -> Unit = { path -> Files.delete(path) },
    ): TestFileStoreFixture {
        val root = Files.createTempDirectory("tk-upload-auth-").toFile()
        return try {
            val store = FileStore(
                dbPath = File(root, "rocksdb").absolutePath,
                fsRoot = File(root, "files").absolutePath,
                largeFileThreshold = largeFileThreshold,
                maxFileSize = com.virjar.tk.protocol.body.AttachmentPolicy.MAX_UPLOAD_BYTES,
                nativeResourceCloser = FileStoreNativeResourceCloser(::closeFileStoreNativeResource),
                tmpRoot = File(root, "tmp"),
                maxTotalBytes = maxTotalBytes,
                managedTempFileDeleter = managedTempFileDeleter,
                clock = clock,
            ).also { it.init() }
            TestFileStoreFixture(root, store)
        } catch (failure: Throwable) {
            runCatching {
                check(root.deleteRecursively() || !root.exists()) {
                    "Failed to delete FileUploadAuthTest root after initialization failure: $root"
                }
            }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun Application.installTestFileRoutes(
        files: TestFileStoreFixture,
        accessTokens: AccessTokenValidator,
        access: AttachmentAccess = AttachmentAccess { _, _ -> true },
        maxUploadBytes: Long = com.virjar.tk.protocol.body.AttachmentPolicy.MAX_UPLOAD_BYTES,
        uploadAdmission: AttachmentUploadAdmission = AttachmentUploadAdmission(),
        uploadStagingTimeoutMillis: Long = DEFAULT_UPLOAD_STAGING_TIMEOUT_MILLIS,
        clock: () -> Long = System::currentTimeMillis,
        beforeUploadResponseDelivery: (encodedReceipt: String) -> Unit = {},
        thumbnailService: ThumbnailService = ThumbnailService(
            files.store.temporaryDirectory,
            retireTempFile = files.store::retireTemporaryFile,
        ),
    ) {
        monitor.subscribe(ApplicationStopped) {
            files.closeAndDelete()
        }
        routing {
            fileRoutes(
                files.store,
                accessTokens,
                access,
                maxUploadBytes = maxUploadBytes,
                uploadAdmission = uploadAdmission,
                uploadStagingTimeoutMillis = uploadStagingTimeoutMillis,
                clock = clock,
                beforeUploadResponseDelivery = beforeUploadResponseDelivery,
                thumbnailService = thumbnailService,
            )
        }
    }

    @Test
    fun `无 token 上传被拒 401`() = testApplication {
        val files = testFileStore()
        application { installTestFileRoutes(files, TestAccessTokenValidator()) }
        val resp = client.post("/api/v1/files/upload")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(resp.bodyAsText().contains("token"))
    }

    @Test
    fun `伪造 X-Uid 不再被接受`() = testApplication {
        val files = testFileStore()
        application { installTestFileRoutes(files, TestAccessTokenValidator()) }
        val resp = client.post("/api/v1/files/upload") {
            header("X-Uid", "victim-uid")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status, "X-Uid 通道必须已封死")
    }

    @Test
    fun `有效 accessToken 可上传`() = testApplication {
        val access = "valid-upload-token"
        val accessTokens = TestAccessTokenValidator.single(access, "real-uid", "dev-1")
        val files = testFileStore()
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        application { installTestFileRoutes(files, accessTokens, uploadAdmission = admission) }
        val resp = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity()
            setBody(MultiPartFormDataContent(formData {
                append("file", byteArrayOf(1, 2, 3), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"t.bin\"")
                })
            }))
        }
        val responseBody = resp.bodyAsText()
        assertEquals(HttpStatusCode.OK, resp.status, "有效 token 上传应成功: $responseBody")
        val result = FileOps.parseUploadResult(responseBody)
        assertEquals("t.bin", result.file.name)
        assertEquals("application/octet-stream", result.file.contentType)
        assertEquals(3, result.file.size)
        assertTrue(result.file.path.startsWith("real-uid/"), "响应含当前用户相对 path: $responseBody")
        assertEquals(0, admission.activeUploadCount)
    }

    @Test
    fun `缩略图 helper 超时仍发布原附件并释放上传 lease`() = testApplication {
        val access = "thumbnail-timeout-token"
        val accessTokens = TestAccessTokenValidator.single(access, "real-uid", "dev-1")
        val files = testFileStore()
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        val launcher = FaultProcessLauncher(FaultBehavior.HANG)
        val thumbnailService = ThumbnailService(
            tempDirectory = files.store.temporaryDirectory,
            maxConcurrentHelpers = 1,
            helperTimeoutMillis = 150,
            terminationGraceMillis = 200,
            retireTempFile = files.store::retireTemporaryFile,
            processLauncher = launcher,
        )
        application {
            installTestFileRoutes(
                files,
                accessTokens,
                uploadAdmission = admission,
                thumbnailService = thumbnailService,
            )
        }

        val response = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity()
            setBody(MultiPartFormDataContent(formData {
                append("file", testPngBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"timeout.png\"")
                })
            }))
        }

        val responseBody = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, responseBody)
        val result = FileOps.parseUploadResult(responseBody)
        assertEquals("timeout.png", result.file.name)
        assertNull(result.thumbnail)
        assertEquals(0, result.width)
        assertEquals(0, result.height)
        assertEquals(0, admission.activeUploadCount)
        assertTrue(launcher.lastProcess?.isAlive == false)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `有效图片事务预留并发布原图与实际缩略图`() = testApplication {
        val access = "thumbnail-transaction-token"
        val uid = "thumbnail-transaction-uid"
        val files = testFileStore()
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, uid, "dev-1"),
            )
        }

        val response = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity()
            setBody(multipartUploadBody(testPngBytes(), "thumbnail.png", "image/png"))
        }

        val responseBody = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, responseBody)
        val result = FileOps.parseUploadResult(responseBody)
        val thumbnail = assertNotNull(result.thumbnail)
        assertEquals(16, result.width)
        assertEquals(16, result.height)
        assertEquals("thumbnail.png", result.file.name)
        assertEquals("image/jpeg", thumbnail.contentType)
        assertTrue(thumbnail.size > 0L)
        assertEquals(2, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `上传 admission 饱和稳定返回 503 且不创建暂存文件`() = testApplication {
        val access = "saturated-upload-token"
        val files = testFileStore()
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        val heldLease = checkNotNull(admission.tryAcquire("held-uid"))
        try {
            application {
                installTestFileRoutes(
                    files,
                    TestAccessTokenValidator.single(access, "real-uid", "dev-1"),
                    uploadAdmission = admission,
                )
            }

            val response = client.post("/api/v1/files/upload") {
                header(HttpHeaders.Authorization, "Bearer $access")
                attachmentUploadIdentity()
                setBody(MultiPartFormDataContent(formData {
                    append("file", byteArrayOf(1, 2, 3), Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "filename=\"must-not-stage.bin\"")
                    })
                }))
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("1", response.headers[HttpHeaders.RetryAfter])
            assertEquals(ATTACHMENT_UPLOAD_SATURATED_MESSAGE, response.bodyAsText())
            assertTrue(File(files.root, "tmp").listFiles().orEmpty().isEmpty())
            assertEquals(1, admission.activeUploadCount, "the rejected request must not own a lease")
        } finally {
            heldLease.close()
        }
        assertEquals(0, admission.activeUploadCount)
    }

    @Test
    fun `上传 admission 饱和会取消精确请求 channel`() = testApplication {
        val access = "saturated-channel-token"
        val files = testFileStore()
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        val heldLease = checkNotNull(admission.tryAcquire("held-uid"))
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "request-uid", "dev-1"),
                uploadAdmission = admission,
            )
        }

        try {
            coroutineScope {
                val requestBody = ByteChannel()
                val producerTerminal = CompletableDeferred<Exception?>()
                val prefix = slowMultipartPrefix(SLOW_MULTIPART_PAYLOAD_BYTES)
                val declaredLength = prefix.size + SLOW_MULTIPART_PAYLOAD_BYTES +
                    "\r\n--$SLOW_MULTIPART_BOUNDARY--\r\n".encodeToByteArray().size
                val producer = launch {
                    var terminal: Exception? = null
                    try {
                        requestBody.writeFully(prefix)
                        while (true) requestBody.writeFully(ByteArray(64 * 1024))
                    } catch (failure: Exception) {
                        terminal = failure
                    } finally {
                        producerTerminal.complete(terminal)
                    }
                }
                val requestJob = launch {
                    client.post("/api/v1/files/upload") {
                        header(HttpHeaders.Authorization, "Bearer $access")
                        attachmentUploadIdentity()
                        setBody(fixedMultipartContent(requestBody, declaredLength.toLong()))
                    }
                }
                try {
                    val terminal = withTimeout(5_000) { producerTerminal.await() }
                    assertTrue(terminal is kotlinx.coroutines.CancellationException)
                    assertEquals(ATTACHMENT_UPLOAD_SATURATED_MESSAGE, terminal.message)
                } finally {
                    requestBody.cancel()
                    requestJob.cancelAndJoin()
                    producer.cancelAndJoin()
                }
            }
            assertTrue(File(files.root, "tmp").listFiles().orEmpty().isEmpty())
            assertEquals(1, admission.activeUploadCount)
        } finally {
            heldLease.close()
        }
    }

    @Test
    fun `无效 token 被拒 401`() = testApplication {
        val files = testFileStore()
        application { installTestFileRoutes(files, TestAccessTokenValidator()) }
        val resp = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer forged-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `已认证上传缺失重复或畸形 identity headers 返回 400 且不暂存`() = testApplication {
        val access = "identity-header-token"
        val files = testFileStore()
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "identity-uid", "dev-1"),
            )
        }

        val missing = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            setBody(multipartUploadBody(byteArrayOf(1)))
        }
        assertEquals(HttpStatusCode.BadRequest, missing.status)

        val duplicate = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            headers.append(ATTACHMENT_UPLOAD_ID_HEADER, "01234567-89ab-cdef-0123-456789abcdef")
            headers.append(ATTACHMENT_UPLOAD_ID_HEADER, "11234567-89ab-cdef-0123-456789abcdef")
            header(ATTACHMENT_UPLOAD_ISSUED_AT_HEADER, System.currentTimeMillis().toString())
            setBody(multipartUploadBody(byteArrayOf(1)))
        }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status)

        val malformedId = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(uploadId = "01234567-89AB-CDEF-0123-456789ABCDEF")
            setBody(multipartUploadBody(byteArrayOf(1)))
        }
        assertEquals(HttpStatusCode.BadRequest, malformedId.status)

        val malformedIssuedAt = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            header(ATTACHMENT_UPLOAD_ID_HEADER, "21234567-89ab-cdef-0123-456789abcdef")
            header(ATTACHMENT_UPLOAD_ISSUED_AT_HEADER, "01")
            setBody(multipartUploadBody(byteArrayOf(1)))
        }
        assertEquals(HttpStatusCode.BadRequest, malformedIssuedAt.status)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
        assertEquals(0, files.store.accountedPendingFiles)
    }

    @Test
    fun `未来 identity 返回 400 而过期 identity 返回 410`() = testApplication {
        val access = "identity-window-token"
        val now = 1_800_000_000_000L
        val files = testFileStore()
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "identity-window-uid", "dev-1"),
                clock = { now },
            )
        }

        val future = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(
                uploadId = "31234567-89ab-cdef-0123-456789abcdef",
                issuedAt = now + ReliableCommandContract.MAX_FUTURE_CLOCK_SKEW_MILLIS + 1L,
            )
            setBody(multipartUploadBody(byteArrayOf(1)))
        }
        assertEquals(HttpStatusCode.BadRequest, future.status)

        val expired = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(
                uploadId = "41234567-89ab-cdef-0123-456789abcdef",
                issuedAt = now - ReliableCommandContract.RETRY_HORIZON_MILLIS - 1L,
            )
            setBody(multipartUploadBody(byteArrayOf(1)))
        }
        assertEquals(HttpStatusCode.Gone, expired.status)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
        assertEquals(0, files.store.accountedPendingFiles)
    }

    @Test
    fun `identity 重试窗口 exact boundary 仍可提交`() = testApplication {
        val access = "identity-boundary-token"
        val uid = "identity-boundary-uid"
        val now = 1_800_000_000_000L
        val issuedAt = now - ReliableCommandContract.RETRY_HORIZON_MILLIS
        val files = testFileStore(clock = { now })
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, uid, "dev-1"),
                clock = { now },
            )
        }

        val response = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(
                uploadId = "81234567-89ab-cdef-0123-456789abcdef",
                issuedAt = issuedAt,
            )
            setBody(multipartUploadBody(byteArrayOf(1, 2, 3), "boundary.bin"))
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(1, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `identity 在入口检查后到预留前过期返回 410 且不创建 temp`() = testApplication {
        val access = "identity-begin-expiry-token"
        val uid = "identity-begin-expiry-uid"
        val routeNow = 1_800_000_000_000L
        val storageNow = routeNow + 1L
        val issuedAt = routeNow - ReliableCommandContract.RETRY_HORIZON_MILLIS
        var tempRetirements = 0
        val files = testFileStore(
            managedTempFileDeleter = { path ->
                tempRetirements += 1
                Files.delete(path)
            },
            clock = { storageNow },
        )
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, uid, "dev-1"),
                clock = { routeNow },
            )
        }

        val response = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(
                uploadId = "a1234567-89ab-cdef-0123-456789abcdef",
                issuedAt = issuedAt,
            )
            setBody(multipartUploadBody(byteArrayOf(1), "begin-expiry.bin"))
        }

        assertEquals(HttpStatusCode.Gone, response.status, response.bodyAsText())
        assertEquals(0, tempRetirements)
        assertEquals(0, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `identity 在上传处理期间跨过重试窗口返回 410 并回滚`() = testApplication {
        val access = "identity-cross-boundary-token"
        val uid = "identity-cross-boundary-uid"
        var now = 1_800_000_000_000L
        val issuedAt = now - ReliableCommandContract.RETRY_HORIZON_MILLIS
        var tempRetirements = 0
        val files = testFileStore(
            managedTempFileDeleter = { path ->
                tempRetirements += 1
                now += 1L
                Files.delete(path)
            },
            clock = { now },
        )
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, uid, "dev-1"),
                clock = { now },
            )
        }

        val response = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(
                uploadId = "91234567-89ab-cdef-0123-456789abcdef",
                issuedAt = issuedAt,
            )
            setBody(multipartUploadBody(byteArrayOf(4, 5, 6), "cross-boundary.bin"))
        }

        assertEquals(HttpStatusCode.Gone, response.status, response.bodyAsText())
        assertEquals(1, tempRetirements)
        assertEquals(0, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertEquals(0L, files.store.accountedPendingBytes)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `同 identity 完整重放返回原字节且异 payload 冲突`() = testApplication {
        val access = "receipt-replay-token"
        val uid = "receipt-replay-uid"
        val uploadId = "51234567-89ab-cdef-0123-456789abcdef"
        val issuedAt = System.currentTimeMillis()
        val payload = testPngBytes()
        val files = testFileStore()
        val launcher = FaultProcessLauncher(FaultBehavior.MALFORMED_RESULT)
        val thumbnailService = ThumbnailService(
            tempDirectory = files.store.temporaryDirectory,
            maxConcurrentHelpers = 1,
            helperTimeoutMillis = 5_000,
            terminationGraceMillis = 200,
            retireTempFile = files.store::retireTemporaryFile,
            processLauncher = launcher,
        )
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, uid, "dev-1"),
                thumbnailService = thumbnailService,
            )
        }

        suspend fun upload(bytes: ByteArray) = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(uploadId, issuedAt)
            setBody(multipartUploadBody(bytes, "receipt.png", "image/png"))
        }

        val first = upload(payload)
        val firstReceipt = first.readRawBytes()
        assertEquals(HttpStatusCode.OK, first.status, firstReceipt.decodeToString())
        assertNull(FileOps.parseUploadResult(firstReceipt.decodeToString()).thumbnail)
        assertEquals(1, launcher.startCount.get())
        assertEquals(1, files.store.accountedStoredFiles)

        val replay = upload(payload)
        assertEquals(HttpStatusCode.OK, replay.status)
        assertContentEquals(firstReceipt, replay.readRawBytes())
        assertEquals(1, launcher.startCount.get(), "exact replay must not rerun media processing")
        assertEquals(1, files.store.accountedStoredFiles, "exact replay must not create another object")

        val changed = payload.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        val conflict = upload(changed)
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertEquals(1, launcher.startCount.get(), "conflicting replay must stop before media processing")
        assertEquals(1, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())

        val afterConflict = upload(payload)
        assertEquals(HttpStatusCode.OK, afterConflict.status)
        assertContentEquals(firstReceipt, afterConflict.readRawBytes())
        assertEquals(1, launcher.startCount.get(), "conflict cleanup must release the replay candidate")
    }

    @Test
    fun `完整重放在stage期间跨过receipt lease返回410并释放候选`() = testApplication {
        val access = "replay-stage-expiry-token"
        val uid = "replay-stage-expiry-uid"
        val uploadId = "b1234567-89ab-cdef-0123-456789abcdef"
        val issuedAt = 1_800_000_000_000L
        val expiresAt = ReliableCommandContract.lastActiveAt(issuedAt)
        val payload = byteArrayOf(1, 2, 3, 4)
        var routeNow = issuedAt
        var replayPhase = false
        var replayClockCalls = 0
        val files = testFileStore(
            clock = {
                if (!replayPhase) {
                    issuedAt
                } else if (replayClockCalls++ == 0) {
                    expiresAt
                } else {
                    expiresAt + 1L
                }
            },
        )
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, uid, "dev-1"),
                clock = { routeNow },
            )
        }

        suspend fun upload() = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(uploadId, issuedAt)
            setBody(multipartUploadBody(payload, "stage-expiry.bin"))
        }

        val first = upload()
        val firstBody = first.bodyAsText()
        assertEquals(HttpStatusCode.OK, first.status, firstBody)
        val originalPath = FileOps.parseUploadResult(firstBody).file.path
        assertNotNull(files.store.getAttachment(originalPath))

        routeNow = expiresAt
        replayPhase = true
        val replay = upload()

        assertEquals(HttpStatusCode.Gone, replay.status, replay.bodyAsText())
        assertEquals(2, replayClockCalls, "begin must precede full staging and resolve must recheck afterwards")
        assertNotNull(files.store.getAttachment(originalPath))
        assertEquals(1, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())

        val replacement = assertIs<BeginFileStoreUploadResult.Started>(
            files.store.beginUploadTransaction(
                uid = uid,
                uploadId = uploadId,
                payloadLength = payload.size.toLong(),
                receiptLeaseExpiresAt = Long.MAX_VALUE,
            ),
        ).transaction
        assertNotNull(
            files.store.getAttachment(originalPath),
            "a fresh begin detaches the elapsed receipt without deleting the independently retained object",
        )
        replacement.close()
        assertEquals(0, files.store.accountedPendingFiles)
    }

    @Test
    fun `首次交付期间pin阻止同ID begin和GC且响应结束后释放可回收`() = testApplication {
        val access = "first-delivery-pin-token"
        val uid = "first-delivery-pin-uid"
        val uploadId = "d1234567-89ab-cdef-0123-456789abcdef"
        val issuedAt = 1_800_000_000_000L
        val expiresAt = ReliableCommandContract.lastActiveAt(issuedAt)
        val payload = byteArrayOf(8, 9, 10, 11)
        var now = issuedAt
        var deliveredPath: String? = null
        val files = testFileStore(clock = { now })
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, uid, "dev-1"),
                clock = { now },
                beforeUploadResponseDelivery = { encodedReceipt ->
                    val path = FileOps.parseUploadResult(encodedReceipt).file.path
                    deliveredPath = path
                    now = expiresAt + 1L
                    val overlappingBegin = runCatching {
                        files.store.beginUploadTransaction(
                            uid = uid,
                            uploadId = uploadId,
                            payloadLength = payload.size.toLong(),
                            receiptLeaseExpiresAt = Long.MAX_VALUE,
                        )
                    }.exceptionOrNull()
                    assertIs<FileStoreUploadInProgressException>(overlappingBegin)
                    assertTrue(
                        files.store.scanRetirementCandidates(Long.MAX_VALUE, null, 10)
                            .candidates.none { candidate -> candidate.path == path },
                        "GC must not observe backing objects while the first response is in delivery",
                    )
                    assertNotNull(files.store.getAttachment(path))
                },
            )
        }

        val response = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(uploadId, issuedAt)
            setBody(multipartUploadBody(payload, "first-delivery-pin.bin"))
        }
        val encodedReceipt = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, encodedReceipt)
        val path = assertNotNull(deliveredPath)
        assertEquals(path, FileOps.parseUploadResult(encodedReceipt).file.path)
        assertNotNull(files.store.getAttachment(path))
        assertEquals(1, files.store.accountedStoredFiles)

        val retirement = files.store.scanRetirementCandidates(Long.MAX_VALUE, null, 10)
            .candidates.single { candidate -> candidate.path == path }
        assertTrue(files.store.retireIfExpiredAndUnchanged(retirement, Long.MAX_VALUE))
        assertNull(files.store.getAttachment(path), "response completion must release the first-delivery pin")

        val replacement = assertIs<BeginFileStoreUploadResult.Started>(
            files.store.beginUploadTransaction(
                uid = uid,
                uploadId = uploadId,
                payloadLength = payload.size.toLong(),
                receiptLeaseExpiresAt = Long.MAX_VALUE,
            ),
        ).transaction
        replacement.close()
        assertEquals(0, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `replay交付期间pin阻止同ID begin和GC且响应结束后释放可回收`() = testApplication {
        val access = "replay-delivery-pin-token"
        val uid = "replay-delivery-pin-uid"
        val uploadId = "c1234567-89ab-cdef-0123-456789abcdef"
        val issuedAt = 1_800_000_000_000L
        val expiresAt = ReliableCommandContract.lastActiveAt(issuedAt)
        val payload = byteArrayOf(4, 5, 6, 7)
        var now = issuedAt
        var deliveryAttempts = 0
        var originalPath: String? = null
        val files = testFileStore(clock = { now })
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, uid, "dev-1"),
                clock = { now },
                beforeUploadResponseDelivery = { encodedReceipt ->
                    deliveryAttempts += 1
                    val path = FileOps.parseUploadResult(encodedReceipt).file.path
                    if (deliveryAttempts == 1) {
                        originalPath = path
                    } else {
                        assertEquals(originalPath, path)
                        now = expiresAt + 1L
                        val overlappingBegin = runCatching {
                            files.store.beginUploadTransaction(
                                uid = uid,
                                uploadId = uploadId,
                                payloadLength = payload.size.toLong(),
                                receiptLeaseExpiresAt = Long.MAX_VALUE,
                            )
                        }.exceptionOrNull()
                        assertIs<FileStoreUploadInProgressException>(overlappingBegin)
                        assertTrue(
                            files.store.scanRetirementCandidates(Long.MAX_VALUE, null, 10)
                                .candidates.none { candidate -> candidate.path == path },
                            "GC must not observe backing objects while the replay response is in delivery",
                        )
                        assertNotNull(files.store.getAttachment(path))
                    }
                },
            )
        }

        suspend fun upload() = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(uploadId, issuedAt)
            setBody(multipartUploadBody(payload, "delivery-pin.bin"))
        }

        val first = upload()
        val originalReceipt = first.readRawBytes()
        assertEquals(HttpStatusCode.OK, first.status, originalReceipt.decodeToString())
        val path = assertNotNull(originalPath)

        now = expiresAt
        val replay = upload()
        val replayReceipt = replay.readRawBytes()
        assertEquals(HttpStatusCode.OK, replay.status, replayReceipt.decodeToString())
        assertContentEquals(originalReceipt, replayReceipt)
        assertEquals(2, deliveryAttempts)
        assertNotNull(files.store.getAttachment(path))
        assertEquals(1, files.store.accountedStoredFiles)

        val retirement = files.store.scanRetirementCandidates(Long.MAX_VALUE, null, 10)
            .candidates.single { candidate -> candidate.path == path }
        assertTrue(files.store.retireIfExpiredAndUnchanged(retirement, Long.MAX_VALUE))
        assertNull(files.store.getAttachment(path), "response completion must release the transient replay pin")

        val replacement = assertIs<BeginFileStoreUploadResult.Started>(
            files.store.beginUploadTransaction(
                uid = uid,
                uploadId = uploadId,
                payloadLength = payload.size.toLong(),
                receiptLeaseExpiresAt = Long.MAX_VALUE,
            ),
        ).transaction
        replacement.close()
        assertEquals(0, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `receipt提交后response delivery失败并重启仍原字节重放`() = testApplication {
        val access = "committed-delivery-failure-token"
        val uid = "committed-delivery-failure-uid"
        val uploadId = "71234567-89ab-cdef-0123-456789abcdef"
        val issuedAt = System.currentTimeMillis()
        val payload = testPngBytes()
        val files = testFileStore()
        val launcher = FaultProcessLauncher(FaultBehavior.MALFORMED_RESULT)
        val thumbnailService = ThumbnailService(
            tempDirectory = files.store.temporaryDirectory,
            maxConcurrentHelpers = 1,
            helperTimeoutMillis = 5_000,
            terminationGraceMillis = 200,
            retireTempFile = files.store::retireTemporaryFile,
            processLauncher = launcher,
        )
        var committedReceipt: String? = null
        var deliveryAttempts = 0
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, uid, "dev-1"),
                thumbnailService = thumbnailService,
                beforeUploadResponseDelivery = { encodedReceipt ->
                    deliveryAttempts += 1
                    committedReceipt = encodedReceipt
                    if (deliveryAttempts == 1) throw InjectedResponseDeliveryFailure()
                },
            )
        }

        suspend fun upload() = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity(uploadId, issuedAt)
            setBody(multipartUploadBody(payload, "committed.png", "image/png"))
        }

        val firstAttempt = runCatching { upload() }
        assertTrue(
            firstAttempt.isFailure || firstAttempt.getOrThrow().status == HttpStatusCode.InternalServerError,
            "the injected delivery failure must not report upload success",
        )
        val expectedReceipt = assertNotNull(committedReceipt).encodeToByteArray()
        assertEquals(1, deliveryAttempts)
        assertEquals(1, launcher.startCount.get())
        assertEquals(1, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())

        files.store.close()
        files.store.init()
        assertEquals(1, files.store.accountedStoredFiles)

        val replay = upload()
        assertEquals(HttpStatusCode.OK, replay.status)
        assertContentEquals(expectedReceipt, replay.readRawBytes())
        assertEquals(2, deliveryAttempts, "the replay receipt must remain pinned through response delivery")
        assertEquals(1, launcher.startCount.get(), "restart replay must not rerun media processing")
        assertEquals(1, files.store.accountedStoredFiles)
        assertEquals(0, files.store.accountedPendingFiles)
        assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `进行中的 upload identity 返回 409 retry-after 且不创建 request temp`() = testApplication {
        val access = "in-progress-upload-token"
        val uid = "in-progress-uid"
        val uploadId = "61234567-89ab-cdef-0123-456789abcdef"
        val issuedAt = System.currentTimeMillis()
        var tempDeleteCalls = 0
        val files = testFileStore { path ->
            tempDeleteCalls += 1
            Files.delete(path)
        }
        val begun = files.store.beginUploadTransaction(
            uid = uid,
            uploadId = uploadId,
            payloadLength = 3L,
            receiptLeaseExpiresAt = ReliableCommandContract.lastActiveAt(issuedAt),
        )
        val held = (begun as BeginFileStoreUploadResult.Started).transaction
        try {
            application {
                installTestFileRoutes(
                    files,
                    TestAccessTokenValidator.single(access, uid, "dev-1"),
                )
            }
            val response = client.post("/api/v1/files/upload") {
                header(HttpHeaders.Authorization, "Bearer $access")
                attachmentUploadIdentity(uploadId, issuedAt)
                setBody(multipartUploadBody(byteArrayOf(1, 2, 3)))
            }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("1", response.headers[HttpHeaders.RetryAfter])
            assertEquals(0, tempDeleteCalls, "request temp must not be created before begin succeeds")
            assertTrue(files.store.temporaryDirectory.listFiles().orEmpty().isEmpty())
            assertEquals(1, files.store.accountedPendingFiles)
        } finally {
            held.close()
        }
        assertEquals(0, files.store.accountedPendingFiles)
    }

    @Test
    fun `上传超过服务端字节上限返回 413`() = testApplication {
        val access = "bounded-upload-token"
        val files = testFileStore()
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "real-uid", "dev-1"),
                maxUploadBytes = 4,
                uploadAdmission = admission,
            )
        }
        val response = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity()
            setBody(MultiPartFormDataContent(formData {
                append("file", ByteArray(5), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"too-large.bin\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(File(files.root, "tmp").listFiles().orEmpty().isEmpty())
        assertEquals(0, admission.activeUploadCount)
    }

    @Test
    fun `持久容量耗尽返回 507 且清理上传暂存`() = testApplication {
        val access = "capacity-upload-token"
        var tempDeleteCalls = 0
        val files = testFileStore(
            maxTotalBytes = 4,
            managedTempFileDeleter = { path ->
                tempDeleteCalls += 1
                Files.delete(path)
            },
        )
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        files.store.store(
            "existing-user",
            "existing.bin",
            "application/octet-stream",
            File(files.root, "existing.bin").apply { writeBytes(ByteArray(4)) },
        )
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "real-uid", "dev-1"),
                uploadAdmission = admission,
            )
        }

        val response = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity()
            setBody(MultiPartFormDataContent(formData {
                append("file", byteArrayOf(1), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"over-capacity.bin\"")
                })
            }))
        }

        assertEquals(HttpStatusCode.InsufficientStorage, response.status)
        assertEquals("File storage global capacity is exhausted", response.bodyAsText())
        assertEquals(0, tempDeleteCalls, "capacity must be reserved before request temp creation")
        assertTrue(File(files.root, "tmp").listFiles().orEmpty().isEmpty())
        assertEquals(0, files.store.accountedPendingFiles)
        assertEquals(0, admission.activeUploadCount)
    }

    @Test
    fun `上传接口拒绝多个文件 part`() = testApplication {
        val access = "single-part-token"
        val files = testFileStore()
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "real-uid", "dev-1"),
            )
        }
        val response = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity()
            setBody(MultiPartFormDataContent(formData {
                repeat(2) { index ->
                    append("file", byteArrayOf(index.toByte()), Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "filename=\"$index.bin\"")
                    })
                }
            }))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `缺少 content length 会取消精确请求 channel 且不会挂起 producer`() = testApplication {
        val access = "cancel-extra-parts-token"
        val files = testFileStore()
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "real-uid", "dev-1"),
                uploadAdmission = admission,
            )
        }

        val nextResponse = coroutineScope {
            val requestBody = ByteChannel()
            val producerTerminal = CompletableDeferred<Exception?>()
            val producer = launch {
                writeEndlessMultipartAfterSecondFile(requestBody, producerTerminal)
            }
            val requestJob = launch {
                client.post("/api/v1/files/upload") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                    attachmentUploadIdentity()
                    setBody(endlessMultipartContent(requestBody))
                }
            }
            try {
                // Ktor 的 TestHttpClientEngine 即使在 route 已经发送响应后仍会等待请求 EOF。
                // 因此这个独立持有的 writer 所观察到的服务端取消信号，
                // 就是故意构造的无尽头正文的权威存活信号。
                val terminal = withTimeout(5_000) { producerTerminal.await() }
                if (terminal !is kotlinx.coroutines.CancellationException) {
                    throw AssertionError("Expected server request-body cancellation, got $terminal")
                }
                assertEquals(UPLOAD_BODY_REJECTION_MESSAGE, terminal.message)
                producer.join()

                requestJob.cancelAndJoin()
                withTimeout(5_000) {
                    client.post("/api/v1/files/upload") {
                        header(HttpHeaders.Authorization, "Bearer $access")
                        attachmentUploadIdentity()
                        setBody(MultiPartFormDataContent(formData {
                            append("file", byteArrayOf(7), Headers.build {
                                append(HttpHeaders.ContentType, "application/octet-stream")
                                append(HttpHeaders.ContentDisposition, "filename=\"next.bin\"")
                            })
                        }))
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Multipart producer did not observe server cancellation", timeout)
            } finally {
                requestBody.cancel()
                requestJob.cancelAndJoin()
                producer.cancelAndJoin()
            }
        }
        assertEquals(HttpStatusCode.OK, nextResponse.status)
        assertEquals(0, admission.activeUploadCount)
    }

    @Test
    fun `慢请求超过总 deadline 会取消正文并释放 uid 与全局槽位`() = testApplication {
        val access = "slow-upload-token"
        val files = testFileStore()
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "slow-uid", "dev-1"),
                uploadAdmission = admission,
                uploadStagingTimeoutMillis = 250,
            )
        }

        coroutineScope {
            val requestBody = ByteChannel()
            val producerTerminal = CompletableDeferred<Exception?>()
            val prefix = slowMultipartPrefix(SLOW_MULTIPART_PAYLOAD_BYTES)
            val declaredLength = prefix.size + SLOW_MULTIPART_PAYLOAD_BYTES +
                "\r\n--$SLOW_MULTIPART_BOUNDARY--\r\n".encodeToByteArray().size
            val producer = launch {
                var terminal: Exception? = null
                try {
                    requestBody.writeFully(prefix)
                    requestBody.flush()
                    while (true) {
                        delay(10)
                        requestBody.writeByte(1)
                        requestBody.flush()
                    }
                } catch (failure: Exception) {
                    terminal = failure
                } finally {
                    producerTerminal.complete(terminal)
                }
            }
            val requestJob = launch {
                client.post("/api/v1/files/upload") {
                    header(HttpHeaders.Authorization, "Bearer $access")
                    attachmentUploadIdentity()
                    setBody(fixedMultipartContent(requestBody, declaredLength.toLong()))
                }
            }
            try {
                val terminal = withTimeout(5_000) { producerTerminal.await() }
                assertTrue(terminal is kotlinx.coroutines.CancellationException)
                assertEquals(UPLOAD_STAGING_TIMEOUT_MESSAGE, terminal.message)
                withTimeout(5_000) {
                    while (admission.activeUploadCount != 0) delay(10)
                }
                assertEquals(0, admission.activeUidCount)
                assertEquals(0, files.store.accountedPendingFiles)
                assertEquals(0L, files.store.accountedPendingBytes)
            } finally {
                requestBody.cancel()
                requestJob.cancelAndJoin()
                producer.cancelAndJoin()
            }
        }

        val next = client.post("/api/v1/files/upload") {
            header(HttpHeaders.Authorization, "Bearer $access")
            attachmentUploadIdentity()
            setBody(MultiPartFormDataContent(formData {
                append("file", byteArrayOf(9), Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"after-timeout.bin\"")
                })
            }))
        }
        assertEquals(HttpStatusCode.OK, next.status)
    }

    @Test
    fun `暂存删除无法确认时 admission fail closed 且不会迟到或双重释放`() = testApplication {
        val access = "retirement-failure-token"
        var rejectDeletion = true
        var rejectedDeleteCalls = 0
        val files = testFileStore { path ->
            if (rejectDeletion && path.fileName.toString().startsWith("teamtalk-upload-")) {
                rejectedDeleteCalls += 1
                throw InjectedRuntimeRetirementFailure()
            }
            Files.delete(path)
        }
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "residue-uid", "dev-1"),
                uploadAdmission = admission,
            )
        }

        val malformed = malformedTerminalMultipartBody()
        runCatching {
            client.post("/api/v1/files/upload") {
                header(HttpHeaders.Authorization, "Bearer $access")
                attachmentUploadIdentity()
                setBody(byteArrayMultipartContent(malformed, RETIREMENT_MULTIPART_BOUNDARY))
            }
        }

        assertEquals(1, rejectedDeleteCalls)
        assertEquals(0, files.store.accountedPendingFiles)
        assertEquals(0L, files.store.accountedPendingBytes)
        assertEquals(1, admission.activeUploadCount)
        assertEquals(1, admission.activeUidCount)
        assertNull(admission.tryAcquire("another-uid"))
        val residue = checkNotNull(File(files.root, "tmp").listFiles().orEmpty().singleOrNull())

        rejectDeletion = false
        files.store.retireTemporaryFile(residue)
        assertEquals(1, admission.activeUploadCount, "later cleanup must not release a retained lease")
        assertNull(admission.tryAcquire("another-uid"), "retained capacity cannot be manufactured twice")
    }

    @Test
    fun `同请求最终重试确认全部临时文件退休后会释放 admission`() = testApplication {
        val access = "retirement-retry-token"
        var failedUploadRetirement = false
        var uploadDeleteCalls = 0
        val files = testFileStore { path ->
            if (path.fileName.toString().startsWith("teamtalk-upload-")) {
                uploadDeleteCalls += 1
                if (!failedUploadRetirement) {
                    failedUploadRetirement = true
                    throw InjectedRuntimeRetirementFailure()
                }
            }
            Files.delete(path)
        }
        val admission = AttachmentUploadAdmission(maxConcurrentUploads = 1)
        application {
            installTestFileRoutes(
                files,
                TestAccessTokenValidator.single(access, "retry-uid", "dev-1"),
                uploadAdmission = admission,
            )
        }

        runCatching {
            client.post("/api/v1/files/upload") {
                header(HttpHeaders.Authorization, "Bearer $access")
                attachmentUploadIdentity()
                setBody(MultiPartFormDataContent(formData {
                    append("file", byteArrayOf(1, 2, 3), Headers.build {
                        append(HttpHeaders.ContentType, "application/octet-stream")
                        append(HttpHeaders.ContentDisposition, "filename=\"retry.bin\"")
                    })
                }))
            }
        }

        assertEquals(2, uploadDeleteCalls)
        assertTrue(File(files.root, "tmp").listFiles().orEmpty().isEmpty())
        assertEquals(0, admission.activeUploadCount)
        assertEquals(0, admission.activeUidCount)
        assertNotNull(admission.tryAcquire("next-uid")).close()
    }

    @Test
    fun `下载必须携带有效 token 且通过附件授权`() = testApplication {
        val accessToken = "valid-download-token"
        val accessTokens = TestAccessTokenValidator.single(accessToken, "reader", "dev-1")
        val files = testFileStore()
        val source = File(files.root, "download-auth.txt").apply { writeText("secret") }
        val path = files.store.store("owner", "secret.txt", "text/plain", source)
        application {
            installTestFileRoutes(
                files,
                accessTokens,
                AttachmentAccess { uid, requestedPath -> uid == "reader" && requestedPath == path },
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/files/$path").status)
        val authorized = client.get("/api/v1/files/$path") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, authorized.status)
        assertEquals("secret", authorized.bodyAsText())
        assertEquals("bytes", authorized.headers[HttpHeaders.AcceptRanges])
        assertEquals("6", authorized.headers[HttpHeaders.ContentLength])
        assertEquals("nosniff", authorized.headers["X-Content-Type-Options"])
        assertEquals("private, no-store", authorized.headers[HttpHeaders.CacheControl])
        assertEquals(HttpHeaders.Authorization, authorized.headers[HttpHeaders.Vary])
        assertTrue(authorized.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment"))
        assertTrue(authorized.headers[HttpHeaders.ContentDisposition].orEmpty().contains("secret.txt"))
    }

    @Test
    fun `已认证但无附件权限返回 403`() = testApplication {
        val accessToken = "valid-denied-token"
        val accessTokens = TestAccessTokenValidator.single(accessToken, "reader", "dev-1")
        val files = testFileStore()
        val source = File(files.root, "download-denied.txt").apply { writeText("secret") }
        val path = files.store.store("owner", "secret.txt", "text/plain", source)
        application { installTestFileRoutes(files, accessTokens, AttachmentAccess { _, _ -> false }) }

        val response = client.get("/api/v1/files/$path") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `单段 bytes range 在小对象和大对象 tier 精确流式返回`() = testApplication {
        val accessToken = "valid-range-token"
        val accessTokens = TestAccessTokenValidator.single(accessToken, "reader", "dev-1")
        val files = testFileStore(largeFileThreshold = 8L)
        val smallBytes = "012345".encodeToByteArray()
        val largeBytes = "abcdefghij".encodeToByteArray()
        val smallPath = files.store.store(
            "owner",
            "small.bin",
            "application/octet-stream",
            File(files.root, "small-source.bin").apply { writeBytes(smallBytes) },
        )
        val largePath = files.store.store(
            "owner",
            "large.bin",
            "application/octet-stream",
            File(files.root, "large-source.bin").apply { writeBytes(largeBytes) },
        )
        assertEquals(com.virjar.tk.server.infra.storage.StorageTier.ROCKSDB, files.store.getMeta(smallPath)?.tier)
        assertEquals(com.virjar.tk.server.infra.storage.StorageTier.FILESYSTEM, files.store.getMeta(largePath)?.tier)
        application { installTestFileRoutes(files, accessTokens) }

        data class RangeCase(
            val path: String,
            val range: String,
            val contentRange: String,
            val expected: ByteArray,
        )

        val cases = listOf(
            RangeCase(smallPath, "bytes=1-3", "bytes 1-3/6", "123".encodeToByteArray()),
            RangeCase(smallPath, "BYTES=0-0", "bytes 0-0/6", "0".encodeToByteArray()),
            RangeCase(smallPath, "bytes=3-", "bytes 3-5/6", "345".encodeToByteArray()),
            RangeCase(smallPath, "bytes=-2", "bytes 4-5/6", "45".encodeToByteArray()),
            RangeCase(largePath, "bytes=2-6", "bytes 2-6/10", "cdefg".encodeToByteArray()),
            RangeCase(largePath, "bytes=7-", "bytes 7-9/10", "hij".encodeToByteArray()),
            RangeCase(largePath, "bytes=-4", "bytes 6-9/10", "ghij".encodeToByteArray()),
            // 按照 RFC 7233，第一个字节可满足时，会将超界的最后一个字节收敛到末尾。
            RangeCase(largePath, "bytes=8-99", "bytes 8-9/10", "ij".encodeToByteArray()),
        )
        cases.forEach { case ->
            val response = client.get("/api/v1/files/${case.path}") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.Range, case.range)
            }
            assertEquals(HttpStatusCode.PartialContent, response.status, case.range)
            assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges], case.range)
            assertEquals(case.contentRange, response.headers[HttpHeaders.ContentRange], case.range)
            assertEquals(case.expected.size.toString(), response.headers[HttpHeaders.ContentLength], case.range)
            assertEquals(case.expected.toList(), response.readRawBytes().toList(), case.range)
        }
    }

    @Test
    fun `零字节对象支持完整下载且任何 range 都返回 416`() = testApplication {
        val accessToken = "valid-empty-range-token"
        val accessTokens = TestAccessTokenValidator.single(accessToken, "reader", "dev-1")
        val files = testFileStore()
        val path = files.store.store(
            "owner",
            "empty.bin",
            "application/octet-stream",
            File(files.root, "empty-source.bin").apply { writeBytes(byteArrayOf()) },
        )
        application { installTestFileRoutes(files, accessTokens) }

        val complete = client.get("/api/v1/files/$path") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, complete.status)
        assertEquals("bytes", complete.headers[HttpHeaders.AcceptRanges])
        assertEquals("0", complete.headers[HttpHeaders.ContentLength])
        assertTrue(complete.readRawBytes().isEmpty())

        val partial = client.get("/api/v1/files/$path") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.Range, "bytes=0-")
        }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, partial.status)
        assertEquals("bytes", partial.headers[HttpHeaders.AcceptRanges])
        assertEquals("bytes */0", partial.headers[HttpHeaders.ContentRange])
        assertEquals("0", partial.headers[HttpHeaders.ContentLength])
        assertTrue(partial.readRawBytes().isEmpty())
    }

    @Test
    fun `畸形多段和无交集 range 返回 416`() = testApplication {
        val accessToken = "valid-invalid-range-token"
        val accessTokens = TestAccessTokenValidator.single(accessToken, "reader", "dev-1")
        val files = testFileStore()
        val path = files.store.store(
            "owner",
            "range.bin",
            "application/octet-stream",
            File(files.root, "invalid-range-source.bin").apply { writeText("012345") },
        )
        application { installTestFileRoutes(files, accessTokens) }

        listOf(
            "items=0-1",
            "bytes=",
            "bytes=1",
            "bytes=4-2",
            "bytes=-0",
            "bytes=0-1,3-4",
            "bytes=6-",
            "bytes=999999999999999999999999-",
        ).forEach { range ->
            val response = client.get("/api/v1/files/$path") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header(HttpHeaders.Range, range)
            }
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status, range)
            assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges], range)
            assertEquals("bytes */6", response.headers[HttpHeaders.ContentRange], range)
            assertEquals("0", response.headers[HttpHeaders.ContentLength], range)
            assertTrue(response.readRawBytes().isEmpty(), range)
        }
    }

    @Test
    fun `range 在权威 ACL 前不解析且撤权后的新请求失败关闭`() = testApplication {
        val accessToken = "valid-revoked-range-token"
        val accessTokens = TestAccessTokenValidator.single(accessToken, "reader", "dev-1")
        val files = testFileStore()
        val path = files.store.store(
            "owner",
            "revoked.bin",
            "application/octet-stream",
            File(files.root, "revoked-range-source.bin").apply { writeText("secret") },
        )
        var allowed = true
        application {
            installTestFileRoutes(
                files,
                accessTokens,
                AttachmentAccess { uid, requestedPath ->
                    allowed && uid == "reader" && requestedPath == path
                },
            )
        }

        val beforeRevocation = client.get("/api/v1/files/$path") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.Range, "bytes=1-3")
        }
        assertEquals(HttpStatusCode.PartialContent, beforeRevocation.status)
        assertEquals("ecr", beforeRevocation.bodyAsText())

        allowed = false
        val afterRevocation = client.get("/api/v1/files/$path") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header(HttpHeaders.Range, "bytes=1-3")
        }
        assertEquals(HttpStatusCode.Forbidden, afterRevocation.status)
        assertNull(afterRevocation.headers[HttpHeaders.AcceptRanges])
        assertNull(afterRevocation.headers[HttpHeaders.ContentRange])

        val unauthenticatedProbe = client.get("/api/v1/files/$path") {
            header(HttpHeaders.Range, "bytes=0-1,3-4")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthenticatedProbe.status)
        assertNull(unauthenticatedProbe.headers[HttpHeaders.AcceptRanges])
        assertNull(unauthenticatedProbe.headers[HttpHeaders.ContentRange])
    }
}

private fun endlessMultipartContent(
    requestBody: ByteReadChannel,
): OutgoingContent.ReadChannelContent {
    val requestContentType = ContentType.MultiPart.FormData.withParameter(
        "boundary",
        ENDLESS_MULTIPART_BOUNDARY,
    )
    return object : OutgoingContent.ReadChannelContent() {
        override val contentType: ContentType = requestContentType

        override fun readFrom(): ByteReadChannel = requestBody
    }
}

private fun fixedMultipartContent(
    requestBody: ByteReadChannel,
    length: Long,
): OutgoingContent.ReadChannelContent {
    val requestContentType = ContentType.MultiPart.FormData.withParameter(
        "boundary",
        SLOW_MULTIPART_BOUNDARY,
    )
    return object : OutgoingContent.ReadChannelContent() {
        override val contentType: ContentType = requestContentType
        override val contentLength: Long = length
        override fun readFrom(): ByteReadChannel = requestBody
    }
}

private fun slowMultipartPrefix(payloadBytes: Int): ByteArray = buildString {
    append("--$SLOW_MULTIPART_BOUNDARY\r\n")
    append("Content-Disposition: form-data; name=file; filename=\"slow.bin\"\r\n")
    append("Content-Type: application/octet-stream\r\n")
    append("Content-Length: $payloadBytes\r\n\r\n")
}.encodeToByteArray()

private fun malformedTerminalMultipartBody(): ByteArray {
    val prefix = buildString {
        append("--$RETIREMENT_MULTIPART_BOUNDARY\r\n")
        append("Content-Disposition: form-data; name=file; filename=\"residue.bin\"\r\n")
        append("Content-Type: application/octet-stream\r\n")
        append("Content-Length: 1\r\n\r\n")
    }.encodeToByteArray()
    val terminal = "\r\n--$RETIREMENT_MULTIPART_BOUNDARY--\r\n".encodeToByteArray()
    terminal[terminal.lastIndex - 2] = 'x'.code.toByte()
    return prefix + byteArrayOf(1) + terminal
}

private fun byteArrayMultipartContent(
    body: ByteArray,
    boundary: String,
): OutgoingContent.ByteArrayContent = object : OutgoingContent.ByteArrayContent() {
    override val contentType: ContentType = ContentType.MultiPart.FormData.withParameter("boundary", boundary)
    override val contentLength: Long = body.size.toLong()
    override fun bytes(): ByteArray = body
}

private fun HttpRequestBuilder.attachmentUploadIdentity(
    uploadId: String = UUID.randomUUID().toString(),
    issuedAt: Long = System.currentTimeMillis(),
) {
    header(ATTACHMENT_UPLOAD_ID_HEADER, uploadId)
    header(ATTACHMENT_UPLOAD_ISSUED_AT_HEADER, issuedAt.toString())
}

private fun multipartUploadBody(
    payload: ByteArray,
    fileName: String = "file.bin",
    contentType: String = "application/octet-stream",
): MultiPartFormDataContent = MultiPartFormDataContent(formData {
    append("file", payload, Headers.build {
        append(HttpHeaders.ContentType, contentType)
        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
    })
})

private suspend fun writeEndlessMultipartAfterSecondFile(
    channel: ByteWriteChannel,
    producerTerminal: CompletableDeferred<Exception?>,
) {
    var terminal: Exception? = null
    try {
        channel.writeFully(
            multipartFilePrefix(ENDLESS_MULTIPART_BOUNDARY, "first.bin") +
                byteArrayOf(1) +
                multipartFilePrefix(ENDLESS_MULTIPART_BOUNDARY, "second.bin", leadingBoundary = true) +
                byteArrayOf(2) +
                multipartFilePrefix(ENDLESS_MULTIPART_BOUNDARY, "third.bin", leadingBoundary = true),
        )
        channel.flush()

        val endlessTail = ByteArray(64 * 1024) { 2 }
        while (true) {
            channel.writeFully(endlessTail)
            channel.flush()
        }
    } catch (failure: Exception) {
        terminal = failure
    } finally {
        producerTerminal.complete(terminal)
    }
}

private fun testPngBytes(): ByteArray = ByteArrayOutputStream().use { output ->
    check(ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", output))
    output.toByteArray()
}

private const val ENDLESS_MULTIPART_BOUNDARY = "teamtalk-rejected-upload-boundary"
private const val SLOW_MULTIPART_BOUNDARY = "teamtalk-slow-upload-boundary"
private const val SLOW_MULTIPART_PAYLOAD_BYTES = 1024 * 1024
private const val RETIREMENT_MULTIPART_BOUNDARY = "teamtalk-retirement-test-boundary"

private class InjectedRuntimeRetirementFailure : RuntimeException()

private class InjectedResponseDeliveryFailure : RuntimeException()

private fun multipartFilePrefix(
    boundary: String,
    fileName: String,
    leadingBoundary: Boolean = false,
): ByteArray = buildString {
    if (leadingBoundary) append("\r\n")
    append("--")
    append(boundary)
    append("\r\n")
    append("Content-Disposition: form-data; name=\"file\"; filename=\"")
    append(fileName)
    append("\"\r\n")
    append("Content-Type: application/octet-stream\r\n\r\n")
}.encodeToByteArray()

private class TestFileStoreFixture(
    val root: File,
    val store: FileStore,
) {
    fun closeAndDelete() {
        var failure: Throwable? = null
        try {
            store.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            check(root.deleteRecursively() || !root.exists()) {
                "Failed to delete FileUploadAuthTest root: $root"
            }
        } catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}

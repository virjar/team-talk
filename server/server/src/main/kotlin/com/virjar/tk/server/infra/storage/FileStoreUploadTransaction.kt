package com.virjar.tk.server.infra.storage

import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

internal const val MAX_ENCODED_UPLOAD_RECEIPT_BYTES = 256 * 1_024

@Serializable
internal enum class FileStoreUploadTransactionState { STARTED, COMPLETED }

/** 在回执日志中重复描述符事实，使启动能拒绝一次被撕裂的完成。 */
@Serializable
internal data class FileStoreUploadObjectReceipt(
    val path: String,
    val name: String,
    val contentType: String,
    val size: Long,
)

/**
 * 持久幂等记录。[encodedReceipt] 是 HTTP 层产生的精确响应载荷；
 * 存储刻意将其视为不透明，同时保留描述符用于对账。
 */
@Serializable
internal data class FileStoreUploadTransactionRecord(
    val uid: String,
    val uploadId: String,
    val attemptToken: String,
    val requestFingerprint: String? = null,
    val state: FileStoreUploadTransactionState,
    val startedAt: Long,
    val reservedObjectSizes: List<Long>,
    val materializedObjectPaths: List<String> = emptyList(),
    val objects: List<FileStoreUploadObjectReceipt> = emptyList(),
    val encodedReceipt: String? = null,
    val completedAt: Long? = null,
    val receiptLeaseExpiresAt: Long? = null,
)

internal data class FileStoreUploadReceipt(
    val uid: String,
    val uploadId: String,
    val requestFingerprint: String,
    val objects: List<FileStoreUploadObjectReceipt>,
    val encodedReceipt: String,
    val completedAt: Long,
    val receiptLeaseExpiresAt: Long,
)

/** 从持久完成/重放开始一直到 HTTP 投递期间持有的精确进程内钉住。 */
internal class FileStoreUploadDeliveryLease internal constructor(
    internal val fileStore: FileStore,
    internal val transactionKey: String,
    internal val attemptToken: String,
    internal val lifecycleToken: String,
    internal val leaseToken: String,
) : AutoCloseable {
    internal var closed = false

    override fun close() {
        fileStore.releaseUploadDeliveryLease(this)
    }
}

internal data class FileStoreUploadCompletion(
    val receipt: FileStoreUploadReceipt,
    val deliveryLease: FileStoreUploadDeliveryLease,
)

/**
 * 重放在调用方暂存并哈希重试载荷之前是临时的。拥有者还会
 * 钉住持久回执直到 HTTP 投递完成，因此回收无法使
 * 已经重新校验过的结果失效。关闭是幂等的，且在每个路由出口都是强制的。
 */
internal class FileStoreUploadReplayCandidate internal constructor(
    internal val deliveryLease: FileStoreUploadDeliveryLease,
    val uploadId: String,
) : AutoCloseable {
    internal val fileStore: FileStore get() = deliveryLease.fileStore
    internal val transactionKey: String get() = deliveryLease.transactionKey
    internal val attemptToken: String get() = deliveryLease.attemptToken
    internal val lifecycleToken: String get() = deliveryLease.lifecycleToken
    internal val leaseToken: String get() = deliveryLease.leaseToken
    internal val closed: Boolean get() = deliveryLease.closed

    fun requireSameFingerprint(requestFingerprint: String): FileStoreUploadReceipt =
        fileStore.resolveUploadReplayCandidate(this, requestFingerprint)

    override fun close() {
        deliveryLease.close()
    }
}

internal data class FileStoreUploadReceiptLease(
    val uploadId: String,
    val expiresAt: Long,
)

internal sealed interface BeginFileStoreUploadResult {
    data class Started(val transaction: FileStoreUploadTransaction) : BeginFileStoreUploadResult
    data class ReplayCandidate(
        val candidate: FileStoreUploadReplayCandidate,
    ) : BeginFileStoreUploadResult
}

internal class FileStoreUploadConflictException(
    val uploadId: String,
) : IllegalStateException("Upload id was already used with a different payload")

internal class FileStoreUploadInProgressException(
    val uploadId: String,
) : IllegalStateException("Upload id is already in progress")

internal class FileStoreUploadExpiredException(
    val uploadId: String,
    val receiptLeaseExpiresAt: Long,
) : IllegalStateException("Upload identity is expired")

internal class FileStoreUploadStaleAttemptException(
    val uploadId: String,
) : IllegalStateException("Upload attempt is no longer active")

/**
 * 一次已开始上传的唯一拥有者。第一份预留由 begin() 占用；调用方可以在
 * 存储它之前添加一份派生的缩略图预留。关闭不完整的拥有者会中止它。
 */
internal class FileStoreUploadTransaction internal constructor(
    internal val fileStore: FileStore,
    internal val transactionKey: String,
    internal val attemptToken: String,
    val uid: String,
    val uploadId: String,
    internal val remainingReservationSizes: MutableList<Long>,
) : AutoCloseable {
    internal var requestFingerprint: String? = null
    internal var materializationUncertain = false
    internal var completed = false

    fun bindFingerprint(requestFingerprint: String) {
        fileStore.bindUploadTransactionFingerprint(this, requestFingerprint)
    }

    fun reserveObject(payloadLength: Long) {
        fileStore.reserveUploadTransactionObject(this, payloadLength)
    }

    fun storeReserved(fileName: String, contentType: String, source: File): String =
        fileStore.storeUploadTransactionObject(this, fileName, contentType, source)

    fun complete(encodedReceipt: String): FileStoreUploadCompletion =
        fileStore.completeUploadTransaction(this, encodedReceipt)

    fun abort() {
        fileStore.abortUploadTransaction(this)
    }

    override fun close() {
        abort()
    }
}

internal fun canonicalFileStoreUploadId(value: String): String {
    require(value.length in 1..64) { "Upload id is invalid" }
    val canonical = try {
        UUID.fromString(value).toString()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Upload id is invalid")
    }
    require(value == canonical) { "Upload id is not canonical" }
    return canonical
}

internal fun requireCanonicalUploadFingerprint(fingerprint: String) {
    require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Upload request fingerprint must be a lowercase SHA-256 value"
    }
}

internal fun fileStoreUploadTransactionKey(uid: String, canonicalUploadId: String): String =
    "${uid.length}:$uid:$canonicalUploadId"

internal fun FileStoreUploadTransactionRecord.toReceipt(): FileStoreUploadReceipt {
    check(state == FileStoreUploadTransactionState.COMPLETED) {
        "Upload transaction is not complete"
    }
    return FileStoreUploadReceipt(
        uid = uid,
        uploadId = uploadId,
        requestFingerprint = checkNotNull(requestFingerprint) {
            "Completed upload fingerprint is missing"
        },
        objects = objects,
        encodedReceipt = checkNotNull(encodedReceipt) { "Completed upload receipt is missing" },
        completedAt = checkNotNull(completedAt) { "Completed upload timestamp is missing" },
        receiptLeaseExpiresAt = checkNotNull(receiptLeaseExpiresAt) {
            "Completed upload receipt lease is missing"
        },
    )
}

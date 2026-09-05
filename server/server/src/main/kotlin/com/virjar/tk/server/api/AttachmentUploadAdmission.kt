package com.virjar.tk.server.api

import java.util.concurrent.atomic.AtomicBoolean

internal const val MAX_CONCURRENT_ATTACHMENT_UPLOADS = 4
internal const val DEFAULT_MAX_CONCURRENT_ATTACHMENT_UPLOADS_PER_UID = 2
internal const val ATTACHMENT_UPLOAD_RETRY_AFTER_SECONDS = "1"
internal const val ATTACHMENT_UPLOAD_SATURATED_MESSAGE =
    "Attachment upload capacity is temporarily unavailable"

/**
 * 请求体暂存的全进程准入边界。
 *
 * 每个租约在 Ktor 读取 multipart 请求体之前就预留了最坏情况的上传大小，
 * 因此生产容量将并发暂存上界限制为四个 512 MiB 的上传（合计 2 GiB）。
 * 获取操作刻意设计为非阻塞：调用方要么立即拥有预留名额，
 * 要么直接拒绝请求，而不会把请求体滞留到应用队列中。
 */
class AttachmentUploadAdmission(
    val maxConcurrentUploads: Int = MAX_CONCURRENT_ATTACHMENT_UPLOADS,
    val maxConcurrentUploadsPerUid: Int = minOf(
        DEFAULT_MAX_CONCURRENT_ATTACHMENT_UPLOADS_PER_UID,
        maxConcurrentUploads,
    ),
) {
    init {
        require(maxConcurrentUploads in 1..MAX_CONCURRENT_ATTACHMENT_UPLOADS) {
            "Attachment upload concurrency must be between 1 and $MAX_CONCURRENT_ATTACHMENT_UPLOADS"
        }
        require(maxConcurrentUploadsPerUid in 1..maxConcurrentUploads) {
            "Per-uid attachment upload concurrency must be between 1 and maxConcurrentUploads"
        }
    }

    private val lock = Any()
    private val activeUploadsByUid = HashMap<String, Int>()
    private var activeUploads = 0

    internal val activeUploadCount: Int
        get() = synchronized(lock) { activeUploads }

    internal val activeUidCount: Int
        get() = synchronized(lock) { activeUploadsByUid.size }

    internal fun tryAcquire(uid: String): AttachmentUploadLease? {
        require(uid.isNotBlank()) { "Attachment upload uid must not be blank" }
        synchronized(lock) {
            val activeForUid = activeUploadsByUid[uid] ?: 0
            if (activeUploads >= maxConcurrentUploads || activeForUid >= maxConcurrentUploadsPerUid) return null
            activeUploadsByUid[uid] = activeForUid + 1
            activeUploads += 1
        }
        return AttachmentUploadLease { release(uid) }
    }

    private fun release(uid: String) {
        synchronized(lock) {
            val activeForUid = activeUploadsByUid[uid]
                ?: error("Attachment upload admission uid accounting underflow")
            check(activeForUid > 0) { "Attachment upload admission uid accounting underflow" }
            if (activeForUid == 1) activeUploadsByUid.remove(uid) else activeUploadsByUid[uid] = activeForUid - 1
            check(activeUploads > 0) { "Attachment upload admission accounting underflow" }
            activeUploads -= 1
        }
    }
}

/** 一个预留名额：即使清理路径汇合或竞态，其释放仍然是安全的。 */
internal class AttachmentUploadLease(
    private val releasePermit: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) releasePermit()
    }
}

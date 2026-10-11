package com.virjar.tk.server.api

import com.virjar.tk.protocol.ReliableCommandContract
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.server.infra.storage.BeginFileStoreUploadResult
import com.virjar.tk.server.infra.storage.FileStore
import com.virjar.tk.server.infra.storage.FileStoreUploadDeliveryLease
import com.virjar.tk.server.infra.storage.ManagedTempResidueException
import com.virjar.tk.server.infra.storage.STAGING_TEMP_SUFFIX
import com.virjar.tk.server.infra.storage.UPLOAD_STAGING_TEMP_PREFIX
import java.io.File

/** 单次 HTTP 上传在投递完成或失败之前，独占其暂存文件、上传尝试与响应占位。 */
internal class AttachmentUploadRequest(
    private val fileStore: FileStore,
    private val admission: AttachmentUploadLease,
) : AutoCloseable {
    private var attempt: BeginFileStoreUploadResult? = null
    private var deliveryLease: FileStoreUploadDeliveryLease? = null
    private val temporaryFiles = LinkedHashSet<File>()
    private var terminalFailure: Throwable? = null
    private var closed = false

    fun begin(uid: String, identity: AttachmentUploadIdentity, payloadLength: Long): BeginFileStoreUploadResult {
        check(attempt == null) { "Upload request already owns an attempt" }
        return fileStore.beginUploadTransaction(
            uid = uid,
            uploadId = identity.uploadId,
            payloadLength = payloadLength,
            receiptLeaseExpiresAt = ReliableCommandContract.lastActiveAt(identity.issuedAt),
        ).also { attempt = it }
    }

    fun createStagingFile(): File = fileStore.createTemporaryFile(UPLOAD_STAGING_TEMP_PREFIX, STAGING_TEMP_SUFFIX)
        .also(::ownTemporaryFile)

    fun ownTemporaryFile(file: File) { temporaryFiles += file }

    /** 退役是发布的一部分：失败时上传尝试仍保持打开以便回滚。 */
    fun retireTemporaryFiles() = retireUploadTemporaryFiles(fileStore, temporaryFiles)

    fun complete(encodedReceipt: String) {
        val started = checkNotNull(attempt as? BeginFileStoreUploadResult.Started) {
            "Only a started upload can publish a receipt"
        }
        // FileStore 以原子方式持久完成并激活该占位。将其保持到 respondText 结束。
        deliveryLease = started.transaction.complete(encodedReceipt).deliveryLease
    }

    fun recordFailure(failure: Throwable) { terminalFailure = failure }

    override fun close() {
        // 本请求只有一个清理终态；之后的 close 无法释放已保留的准入。
        if (closed) return
        closed = true
        val retirementCandidates = LinkedHashSet<File>()
        var hasUnlocatedResidue = terminalFailure?.collectManagedTempResidues(retirementCandidates) ?: false
        retirementCandidates.addAll(temporaryFiles)
        var cleanupFailure: Throwable? = null
        fun clean(action: () -> Unit): Boolean = try {
            action()
            true
        } catch (failure: Throwable) {
            val first = cleanupFailure
            if (first == null) cleanupFailure = failure else if (first !== failure) first.addSuppressed(failure)
            hasUnlocatedResidue = failure.collectManagedTempResidues(retirementCandidates) || hasUnlocatedResidue
            false
        }

        val attemptClosed = clean {
            when (val current = attempt) {
                is BeginFileStoreUploadResult.Started -> current.transaction.close()
                is BeginFileStoreUploadResult.ReplayCandidate -> current.candidate.close()
                null -> Unit
            }
        }
        val deliveryClosed = clean { deliveryLease?.close() }
        val retired = clean { retireUploadTemporaryFiles(fileStore, retirementCandidates) }
        // 未知或未确认的清理会保留该准入槽位；后续 GC 无法将其释放。
        if (attemptClosed && deliveryClosed && retired && !hasUnlocatedResidue) admission.close()
        cleanupFailure?.let { cleanup ->
            val first = terminalFailure
            if (first == null) throw cleanup
            if (first !== cleanup) first.addSuppressed(cleanup)
        }
    }
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

package com.virjar.tk.server.infra.storage

import com.virjar.tk.server.runtime.mergeRuntimeFailure
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class FileStoreUploadResources(
    val db: RocksDB,
    val metaCf: ColumnFamilyHandle,
    val uploadCf: ColumnFamilyHandle,
)

internal interface FileStoreUploadHost {
    val owner: FileStore
    val consistencyFailureOrNull: Throwable?

    fun resources(): FileStoreUploadResources?
    fun storeReservedObject(
        uid: String,
        fileName: String,
        contentType: String,
        source: File,
        uploadTransactionKey: String,
        uploadAttemptToken: String,
        uploadObjectIndex: Int,
        onDurableCommit: () -> Unit,
    ): String

    fun getDurableMetadata(path: String): FileMetadata?
    fun hasBackingData(metadata: FileMetadata): Boolean
    fun rollbackUnpublished(paths: List<String>)
    fun recordConsistencyFailure(failure: Throwable)
}

private data class ActiveUploadDeliveryLease(
    val attemptToken: String,
    val lifecycleToken: String,
    val leaseToken: String,
)

/** 上传状态机。FileStore 仍是监视器与原生资源拥有者。 */
internal class FileStoreUploadCoordinator(
    private val monitor: Any,
    private val persistentState: FileStorePersistentState,
    private val capacityLedger: FileStoreCapacityLedger,
    private val maxFileSize: Long,
    private val clock: () -> Long,
    private val mutationFaultInjector: FileStoreMutationFaultInjector,
    private val beforeUploadAttemptDiscovery: () -> Unit,
    private val host: FileStoreUploadHost,
) {
    private val activeUploadDeliveryLeases = mutableMapOf<String, ActiveUploadDeliveryLease>()
    private var lifecycleToken: String? = null

    internal fun openLifecycle(token: String) {
        requireMonitor()
        activeUploadDeliveryLeases.clear()
        lifecycleToken = token
    }

    internal fun closeLifecycle() {
        requireMonitor()
        activeUploadDeliveryLeases.clear()
        lifecycleToken = null
    }

    /** 在请求体暂存开始之前，先占用字节与一个对象名额。 */
    internal fun beginUploadTransaction(
        uid: String,
        uploadId: String,
        payloadLength: Long,
        receiptLeaseExpiresAt: Long,
    ): BeginFileStoreUploadResult {
        requireMonitor()
        host.consistencyFailureOrNull?.let { throw it }
        val resources = requireResources()
        requireValidFileStoreOwnerUid(uid)
        require(payloadLength in 0L..maxFileSize) {
            "Attachment exceeds the $maxFileSize byte storage limit"
        }
        val canonicalUploadId = canonicalFileStoreUploadId(uploadId)
        val startedAt = clock()
        check(startedAt >= 0L) { "FileStore clock returned a negative epoch timestamp" }
        if (receiptLeaseExpiresAt < startedAt) {
            throw FileStoreUploadExpiredException(canonicalUploadId, receiptLeaseExpiresAt)
        }
        val transactionKey = fileStoreUploadTransactionKey(uid, canonicalUploadId)
        persistentState.getUploadTransaction(
            resources.db,
            resources.uploadCf,
            transactionKey,
        )?.let { existing ->
            check(existing.uid == uid && existing.uploadId == canonicalUploadId) {
                "FileStore upload transaction identity is inconsistent"
            }
            when (existing.state) {
                FileStoreUploadTransactionState.STARTED ->
                    throw FileStoreUploadInProgressException(canonicalUploadId)

                FileStoreUploadTransactionState.COMPLETED -> {
                    activeUploadDeliveryLeases[transactionKey]?.let { activeLease ->
                        check(activeLease.attemptToken == existing.attemptToken) {
                            "FileStore replay lease does not match its receipt"
                        }
                        throw FileStoreUploadInProgressException(canonicalUploadId)
                    }
                    if (checkNotNull(existing.receiptLeaseExpiresAt) >= startedAt) {
                        verifyCompletedUploadBacking(transactionKey, existing)
                        val deliveryLease = createUploadDeliveryLease(
                            transactionKey = transactionKey,
                            attemptToken = existing.attemptToken,
                        )
                        val replayCandidate = FileStoreUploadReplayCandidate(
                            deliveryLease = deliveryLease,
                            uploadId = existing.uploadId,
                        )
                        val beginResult = BeginFileStoreUploadResult.ReplayCandidate(replayCandidate)
                        activateUploadDeliveryLease(deliveryLease)
                        return beginResult
                    }
                    persistentState.expireUploadTransaction(
                        resources.db,
                        resources.metaCf,
                        resources.uploadCf,
                        transactionKey,
                    )
                }
            }
        }

        val attemptToken = UUID.randomUUID().toString()
        capacityLedger.reservePending(uid, payloadLength)
        val started = FileStoreUploadTransactionRecord(
            uid = uid,
            uploadId = canonicalUploadId,
            attemptToken = attemptToken,
            state = FileStoreUploadTransactionState.STARTED,
            startedAt = startedAt,
            reservedObjectSizes = listOf(payloadLength),
            receiptLeaseExpiresAt = receiptLeaseExpiresAt,
        )
        try {
            persistentState.putUploadTransaction(
                resources.db,
                resources.uploadCf,
                transactionKey,
                started,
            )
        } catch (failure: Throwable) {
            capacityLedger.releasePending(uid, payloadLength)
            throw failure
        }
        return BeginFileStoreUploadResult.Started(
            FileStoreUploadTransaction(
                fileStore = host.owner,
                transactionKey = transactionKey,
                attemptToken = attemptToken,
                uid = uid,
                uploadId = canonicalUploadId,
                remainingReservationSizes = mutableListOf(payloadLength),
            ),
        )
    }

    internal fun bindUploadTransactionFingerprint(
        transaction: FileStoreUploadTransaction,
        requestFingerprint: String,
    ) {
        requireMonitor()
        try {
            requireOpenUploadTransaction(transaction)
            requireCanonicalUploadFingerprint(requestFingerprint)
            val alreadyBound = transaction.requestFingerprint
            if (alreadyBound != null) {
                if (alreadyBound != requestFingerprint) {
                    throw FileStoreUploadConflictException(transaction.uploadId)
                }
                return
            }
            val resources = requireResources()
            val current = requireStartedUploadRecord(transaction, resources)
            val durableFingerprint = current.requestFingerprint
            if (durableFingerprint != null && durableFingerprint != requestFingerprint) {
                throw FileStoreUploadConflictException(transaction.uploadId)
            }
            if (durableFingerprint == null) {
                persistentState.putUploadTransaction(
                    resources.db,
                    resources.uploadCf,
                    transaction.transactionKey,
                    current.copy(requestFingerprint = requestFingerprint),
                )
            }
            transaction.requestFingerprint = requestFingerprint
        } catch (failure: Throwable) {
            abortAfterFailure(transaction, failure)
        }
    }

    internal fun reserveUploadTransactionObject(
        transaction: FileStoreUploadTransaction,
        payloadLength: Long,
    ) {
        requireMonitor()
        try {
            requireOpenUploadTransaction(transaction)
            require(payloadLength in 0L..maxFileSize) {
                "Attachment exceeds the $maxFileSize byte storage limit"
            }
            val resources = requireResources()
            val current = requireStartedUploadRecord(transaction, resources)
            check(current.reservedObjectSizes.size < MAX_UPLOAD_TRANSACTION_OBJECTS) {
                "Upload transaction already reserved its main file and optional thumbnail"
            }

            capacityLedger.reservePending(transaction.uid, payloadLength)
            try {
                persistentState.putUploadTransaction(
                    resources.db,
                    resources.uploadCf,
                    transaction.transactionKey,
                    current.copy(reservedObjectSizes = current.reservedObjectSizes + payloadLength),
                )
            } catch (failure: Throwable) {
                capacityLedger.releasePending(transaction.uid, payloadLength)
                throw failure
            }
            transaction.remainingReservationSizes += payloadLength
        } catch (failure: Throwable) {
            abortAfterFailure(transaction, failure)
        }
    }

    internal fun storeUploadTransactionObject(
        transaction: FileStoreUploadTransaction,
        fileName: String,
        contentType: String,
        source: File,
    ): String {
        requireMonitor()
        try {
            requireOpenUploadTransaction(transaction)
            checkNotNull(transaction.requestFingerprint) {
                "Upload transaction fingerprint must be bound before storing objects"
            }
            val resources = requireResources()
            val current = requireStartedUploadRecord(transaction, resources)
            val objectIndex = current.reservedObjectSizes.size -
                transaction.remainingReservationSizes.size
            check(objectIndex in current.reservedObjectSizes.indices) {
                "Upload transaction object position is inconsistent"
            }
            val expectedSize = transaction.remainingReservationSizes.firstOrNull()
                ?: error("Upload transaction has no remaining object reservation")
            check(expectedSize == current.reservedObjectSizes[objectIndex]) {
                "Upload transaction reservation no longer matches its owner"
            }
            require(source.length() == expectedSize) {
                "Attachment payload length does not match its upload reservation"
            }
            val path = host.storeReservedObject(
                uid = transaction.uid,
                fileName = fileName,
                contentType = contentType,
                source = source,
                uploadTransactionKey = transaction.transactionKey,
                uploadAttemptToken = transaction.attemptToken,
                uploadObjectIndex = objectIndex,
                onDurableCommit = { transaction.materializationUncertain = true },
            )
            val durableMetadata = checkNotNull(host.getDurableMetadata(path)) {
                "Upload transaction object metadata disappeared after commit"
            }
            mutationFaultInjector.before(
                FileStoreMutationPoint.AFTER_TRANSACTION_OBJECT_DURABLE,
                durableMetadata,
            )
            check(current.materializedObjectPaths.size == objectIndex) {
                "Upload transaction durable journal is out of order"
            }
            persistentState.putUploadTransaction(
                resources.db,
                resources.uploadCf,
                transaction.transactionKey,
                current.copy(materializedObjectPaths = current.materializedObjectPaths + path),
            )
            transaction.materializationUncertain = false
            transaction.remainingReservationSizes.removeAt(0)
            return path
        } catch (failure: Throwable) {
            abortAfterFailure(transaction, failure)
        }
    }

    internal fun completeUploadTransaction(
        transaction: FileStoreUploadTransaction,
        encodedReceipt: String,
    ): FileStoreUploadCompletion {
        requireMonitor()
        try {
            requireOpenUploadTransaction(transaction)
            val requestFingerprint = checkNotNull(transaction.requestFingerprint) {
                "Upload transaction fingerprint must be bound before completion"
            }
            check(transaction.remainingReservationSizes.isEmpty()) {
                "Upload transaction still has unmaterialized object reservations"
            }
            val receiptBytes = encodedReceipt.toByteArray(StandardCharsets.UTF_8)
            require(encodedReceipt.isNotEmpty() && receiptBytes.size <= MAX_ENCODED_UPLOAD_RECEIPT_BYTES) {
                "Encoded upload receipt is empty or too large"
            }
            val resources = requireResources()
            val started = requireStartedUploadRecord(transaction, resources)
            check(started.materializedObjectPaths.size == started.reservedObjectSizes.size) {
                "Upload transaction has incomplete durable objects"
            }
            val objects = started.materializedObjectPaths.mapIndexed { index, path ->
                val metadata = checkNotNull(host.getDurableMetadata(path)) {
                    "Upload transaction object metadata is missing"
                }
                check(
                    metadata.lifecycle == FileMetadataLifecycle.ACTIVE &&
                        metadata.uid == transaction.uid &&
                        metadata.uploadTransactionKey == transaction.transactionKey &&
                        metadata.uploadAttemptToken == transaction.attemptToken &&
                        metadata.uploadObjectIndex == index &&
                        host.hasBackingData(metadata)
                ) { "Upload transaction object is not durably available" }
                FileStoreUploadObjectReceipt(
                    path = metadata.path,
                    name = metadata.originalName,
                    contentType = metadata.contentType,
                    size = metadata.size,
                )
            }
            check(objects.map(FileStoreUploadObjectReceipt::size) == started.reservedObjectSizes) {
                "Upload transaction objects do not match their reservations"
            }
            val completedAt = maxOf(clock(), started.startedAt)
            val leaseExpiresAt = checkNotNull(started.receiptLeaseExpiresAt)
            if (leaseExpiresAt < completedAt) {
                throw FileStoreUploadExpiredException(transaction.uploadId, leaseExpiresAt)
            }
            check(activeUploadDeliveryLeases[transaction.transactionKey] == null) {
                "Upload transaction already has an active delivery lease"
            }
            val deliveryLease = createUploadDeliveryLease(
                transactionKey = transaction.transactionKey,
                attemptToken = transaction.attemptToken,
            )
            val completed = started.copy(
                state = FileStoreUploadTransactionState.COMPLETED,
                requestFingerprint = requestFingerprint,
                objects = objects,
                encodedReceipt = encodedReceipt,
                completedAt = completedAt,
                receiptLeaseExpiresAt = leaseExpiresAt,
            )
            val completion = FileStoreUploadCompletion(
                receipt = completed.toReceipt(),
                deliveryLease = deliveryLease,
            )
            persistentState.putUploadTransaction(
                resources.db,
                resources.uploadCf,
                transaction.transactionKey,
                completed,
            )
            activateUploadDeliveryLease(deliveryLease)
            transaction.completed = true
            return completion
        } catch (failure: Throwable) {
            abortAfterFailure(transaction, failure)
        }
    }

    internal fun abortUploadTransaction(transaction: FileStoreUploadTransaction) {
        requireMonitor()
        if (transaction.completed) return
        check(transactionBelongsToThisStore(transaction)) {
            "Upload transaction belongs to a different FileStore"
        }
        val resources = host.resources()
        if (resources == null) {
            finishUploadHandle(transaction)
            return
        }
        val current = persistentState.getUploadTransaction(
            resources.db,
            resources.uploadCf,
            transaction.transactionKey,
        )
        if (
            current == null ||
            current.state != FileStoreUploadTransactionState.STARTED ||
            current.attemptToken != transaction.attemptToken
        ) {
            finishUploadHandle(transaction)
            return
        }

        val durableObjects = if (transaction.materializationUncertain) {
            resolveUncertainUploadAttemptObjects(current, transaction, resources)
        } else {
            resolveJournaledUploadAttemptObjects(current, transaction)
        }
        val materializedIndices = durableObjects.mapTo(mutableSetOf()) { metadata ->
            checkNotNull(metadata.uploadObjectIndex) {
                "Upload transaction object position is missing"
            }
        }

        // 在每一个已物化对象被确认不存在之前，STARTED 一直保持持久。
        host.rollbackUnpublished(durableObjects.map(FileMetadata::path))
        persistentState.deleteUploadTransaction(
            resources.db,
            resources.uploadCf,
            transaction.transactionKey,
        )
        current.reservedObjectSizes.forEachIndexed { index, size ->
            if (index !in materializedIndices) capacityLedger.releasePending(transaction.uid, size)
        }
        finishUploadHandle(transaction)
    }

    internal fun resolveUploadReplayCandidate(
        candidate: FileStoreUploadReplayCandidate,
        requestFingerprint: String,
    ): FileStoreUploadReceipt {
        requireMonitor()
        requireCanonicalUploadFingerprint(requestFingerprint)
        host.consistencyFailureOrNull?.let { throw it }
        if (candidate.fileStore !== host.owner || candidate.closed) {
            throw FileStoreUploadStaleAttemptException(candidate.uploadId)
        }
        val activeLifecycleToken = lifecycleToken
        val activeLease = activeUploadDeliveryLeases[candidate.transactionKey]
        if (
            activeLifecycleToken == null ||
            candidate.lifecycleToken != activeLifecycleToken ||
            activeLease == null ||
            activeLease.attemptToken != candidate.attemptToken ||
            activeLease.lifecycleToken != candidate.lifecycleToken ||
            activeLease.leaseToken != candidate.leaseToken
        ) {
            throw FileStoreUploadStaleAttemptException(candidate.uploadId)
        }

        val resources = host.resources()
            ?: throw FileStoreUploadStaleAttemptException(candidate.uploadId)
        val record = try {
            checkNotNull(
                persistentState.getUploadTransaction(
                    resources.db,
                    resources.uploadCf,
                    candidate.transactionKey,
                ),
            ) { "Pinned FileStore upload receipt is missing" }.also { current ->
                check(
                    current.state == FileStoreUploadTransactionState.COMPLETED &&
                        current.uploadId == candidate.uploadId &&
                        current.attemptToken == candidate.attemptToken
                ) { "Pinned FileStore upload receipt was replaced" }
            }
        } catch (failure: Throwable) {
            host.recordConsistencyFailure(failure)
            throw failure
        }
        val now = clock()
        check(now >= 0L) { "FileStore clock returned a negative epoch timestamp" }
        val receiptLeaseExpiresAt = checkNotNull(record.receiptLeaseExpiresAt)
        if (receiptLeaseExpiresAt < now) {
            throw FileStoreUploadExpiredException(candidate.uploadId, receiptLeaseExpiresAt)
        }
        try {
            verifyCompletedUploadBacking(candidate.transactionKey, record)
        } catch (failure: Throwable) {
            host.recordConsistencyFailure(failure)
            throw failure
        }
        if (checkNotNull(record.requestFingerprint) != requestFingerprint) {
            throw FileStoreUploadConflictException(candidate.uploadId)
        }
        return record.toReceipt()
    }

    internal fun releaseUploadDeliveryLease(deliveryLease: FileStoreUploadDeliveryLease) {
        requireMonitor()
        if (deliveryLease.closed) return
        if (deliveryLease.fileStore === host.owner && deliveryLease.lifecycleToken == lifecycleToken) {
            val activeLease = activeUploadDeliveryLeases[deliveryLease.transactionKey]
            if (
                activeLease != null &&
                activeLease.attemptToken == deliveryLease.attemptToken &&
                activeLease.lifecycleToken == deliveryLease.lifecycleToken &&
                activeLease.leaseToken == deliveryLease.leaseToken
            ) {
                activeUploadDeliveryLeases.remove(deliveryLease.transactionKey)
            }
        }
        deliveryLease.closed = true
    }

    internal fun uploadReceiptLease(
        path: String,
        now: Long = clock(),
    ): FileStoreUploadReceiptLease? {
        requireMonitor()
        val metadata = host.getDurableMetadata(path) ?: return null
        val transactionKey = metadata.uploadTransactionKey ?: return null
        val resources = requireResources()
        val record = persistentState.getUploadTransaction(
            resources.db,
            resources.uploadCf,
            transactionKey,
        ) ?: return null
        if (
            record.state != FileStoreUploadTransactionState.COMPLETED ||
            record.objects.none { it.path == path }
        ) {
            return null
        }
        val expiresAt = checkNotNull(record.receiptLeaseExpiresAt)
        return if (
            expiresAt >= now ||
            hasActiveUploadDeliveryLease(transactionKey, record.attemptToken)
        ) {
            FileStoreUploadReceiptLease(record.uploadId, expiresAt)
        } else {
            null
        }
    }

    internal fun hasActiveUploadReceiptLease(metadata: FileMetadata, now: Long): Boolean {
        requireMonitor()
        val transactionKey = metadata.uploadTransactionKey ?: return false
        val resources = requireResources()
        val record = persistentState.getUploadTransaction(
            resources.db,
            resources.uploadCf,
            transactionKey,
        ) ?: return false
        return record.state == FileStoreUploadTransactionState.COMPLETED &&
            record.objects.any { it.path == metadata.path } &&
            (
                checkNotNull(record.receiptLeaseExpiresAt) >= now ||
                    hasActiveUploadDeliveryLease(transactionKey, record.attemptToken)
            )
    }

    /** 受保护期间返回 true；已过期的回执在回收前被原子地解除关联。 */
    internal fun detachExpiredUploadReceiptOrReportActive(
        metadata: FileMetadata,
        now: Long,
    ): Boolean {
        requireMonitor()
        val transactionKey = metadata.uploadTransactionKey ?: return false
        val resources = requireResources()
        val record = persistentState.getUploadTransaction(
            resources.db,
            resources.uploadCf,
            transactionKey,
        ) ?: return false
        if (
            record.state != FileStoreUploadTransactionState.COMPLETED ||
            record.objects.none { it.path == metadata.path }
        ) {
            return false
        }
        if (
            checkNotNull(record.receiptLeaseExpiresAt) >= now ||
            hasActiveUploadDeliveryLease(transactionKey, record.attemptToken)
        ) {
            return true
        }
        persistentState.expireUploadTransaction(
            resources.db,
            resources.metaCf,
            resources.uploadCf,
            transactionKey,
        )
        return false
    }

    private fun transactionBelongsToThisStore(transaction: FileStoreUploadTransaction): Boolean =
        transaction.fileStore === host.owner

    private fun finishUploadHandle(transaction: FileStoreUploadTransaction) {
        transaction.remainingReservationSizes.clear()
        transaction.materializationUncertain = false
        transaction.completed = true
    }

    private fun resolveJournaledUploadAttemptObjects(
        record: FileStoreUploadTransactionRecord,
        transaction: FileStoreUploadTransaction,
    ): List<FileMetadata> = try {
        record.materializedObjectPaths.mapIndexed { index, path ->
            val metadata = checkNotNull(host.getDurableMetadata(path)) {
                "Upload transaction journal backing object is missing"
            }
            check(
                metadata.lifecycle == FileMetadataLifecycle.ACTIVE &&
                    metadata.uid == transaction.uid &&
                    metadata.uploadTransactionKey == transaction.transactionKey &&
                    metadata.uploadAttemptToken == transaction.attemptToken &&
                    metadata.uploadObjectIndex == index &&
                    host.hasBackingData(metadata)
            ) { "Upload transaction journal backing object is inconsistent" }
            metadata
        }
    } catch (failure: Throwable) {
        host.recordConsistencyFailure(failure)
        throw failure
    }

    /** 解析对象提交与日志更新之间可能存在的那个持久对象。 */
    private fun resolveUncertainUploadAttemptObjects(
        record: FileStoreUploadTransactionRecord,
        transaction: FileStoreUploadTransaction,
        resources: FileStoreUploadResources,
    ): List<FileMetadata> = try {
        beforeUploadAttemptDiscovery()
        val durableObjects = persistentState.findUploadAttemptObjects(
            dbInst = resources.db,
            mCf = resources.metaCf,
            transactionKey = transaction.transactionKey,
            attemptToken = transaction.attemptToken,
        )
        val inFlightIndex = record.reservedObjectSizes.size -
            transaction.remainingReservationSizes.size
        check(inFlightIndex in record.reservedObjectSizes.indices) {
            "Upload transaction in-flight object position is inconsistent"
        }
        check(
            record.materializedObjectPaths.size == inFlightIndex ||
                record.materializedObjectPaths.size == inFlightIndex + 1
        ) { "Upload transaction journal advanced beyond its in-flight object" }
        val objectsByIndex = durableObjects.associateBy { metadata ->
            checkNotNull(metadata.uploadObjectIndex) {
                "Upload transaction object position is missing"
            }
        }
        check(objectsByIndex.size == durableObjects.size) {
            "Upload transaction contains duplicate object positions"
        }
        check(objectsByIndex.keys == (0..inFlightIndex).toSet()) {
            "Upload transaction durable objects do not cover its journal and in-flight object"
        }
        durableObjects.forEach { metadata ->
            val index = checkNotNull(metadata.uploadObjectIndex)
            check(
                metadata.lifecycle == FileMetadataLifecycle.ACTIVE &&
                    metadata.uid == transaction.uid &&
                    metadata.uploadTransactionKey == transaction.transactionKey &&
                    metadata.uploadAttemptToken == transaction.attemptToken &&
                    metadata.size == record.reservedObjectSizes[index] &&
                    host.hasBackingData(metadata)
            ) { "Upload transaction recovery object is inconsistent" }
        }
        record.materializedObjectPaths.forEachIndexed { index, path ->
            check(objectsByIndex[index]?.path == path) {
                "Upload transaction journal backing object is inconsistent"
            }
        }
        durableObjects
    } catch (failure: Throwable) {
        host.recordConsistencyFailure(failure)
        throw failure
    }

    private fun requireOpenUploadTransaction(transaction: FileStoreUploadTransaction) {
        host.consistencyFailureOrNull?.let { throw it }
        check(transactionBelongsToThisStore(transaction)) {
            "Upload transaction belongs to a different FileStore"
        }
        check(!transaction.completed) { "Upload transaction is already complete" }
        check(host.resources() != null) { "FileStore not initialized" }
    }

    private fun requireStartedUploadRecord(
        transaction: FileStoreUploadTransaction,
        resources: FileStoreUploadResources,
    ): FileStoreUploadTransactionRecord {
        val current = persistentState.getUploadTransaction(
            resources.db,
            resources.uploadCf,
            transaction.transactionKey,
        ) ?: throw FileStoreUploadStaleAttemptException(transaction.uploadId)
        if (
            current.state != FileStoreUploadTransactionState.STARTED ||
            current.uid != transaction.uid ||
            current.uploadId != transaction.uploadId ||
            current.attemptToken != transaction.attemptToken
        ) {
            throw FileStoreUploadStaleAttemptException(transaction.uploadId)
        }
        check(current.requestFingerprint == transaction.requestFingerprint) {
            "Upload transaction fingerprint no longer matches its owner"
        }
        return current
    }

    private fun verifyCompletedUploadBacking(
        transactionKey: String,
        record: FileStoreUploadTransactionRecord,
    ) {
        val observed = record.objects.mapIndexed { index, descriptor ->
            val metadata = host.getDurableMetadata(descriptor.path)
            checkNotNull(metadata) { "Upload receipt backing object is missing" }
            check(
                metadata.lifecycle == FileMetadataLifecycle.ACTIVE &&
                    metadata.uid == record.uid &&
                    metadata.uploadTransactionKey == transactionKey &&
                    metadata.uploadAttemptToken == record.attemptToken &&
                    metadata.uploadObjectIndex == index &&
                    metadata.originalName == descriptor.name &&
                    metadata.contentType == descriptor.contentType &&
                    metadata.size == descriptor.size &&
                    host.hasBackingData(metadata)
            ) { "Upload receipt descriptor does not match its backing object" }
            metadata.path
        }
        check(observed.distinct().size == observed.size) {
            "Upload receipt contains duplicate backing objects"
        }
    }

    /** 必须在 COMPLETED 写入之前创建，使提交之后不再有任何可能失败的 token。 */
    private fun createUploadDeliveryLease(
        transactionKey: String,
        attemptToken: String,
    ): FileStoreUploadDeliveryLease = FileStoreUploadDeliveryLease(
        fileStore = host.owner,
        transactionKey = transactionKey,
        attemptToken = attemptToken,
        lifecycleToken = checkNotNull(lifecycleToken) {
            "FileStore lifecycle token is unavailable"
        },
        leaseToken = UUID.randomUUID().toString(),
    )

    private fun activateUploadDeliveryLease(deliveryLease: FileStoreUploadDeliveryLease) {
        check(
            deliveryLease.fileStore === host.owner &&
                deliveryLease.lifecycleToken == lifecycleToken &&
                activeUploadDeliveryLeases[deliveryLease.transactionKey] == null
        ) { "FileStore upload delivery lease cannot be activated" }
        activeUploadDeliveryLeases[deliveryLease.transactionKey] = ActiveUploadDeliveryLease(
            attemptToken = deliveryLease.attemptToken,
            lifecycleToken = deliveryLease.lifecycleToken,
            leaseToken = deliveryLease.leaseToken,
        )
    }

    private fun hasActiveUploadDeliveryLease(transactionKey: String, attemptToken: String): Boolean {
        val activeLease = activeUploadDeliveryLeases[transactionKey] ?: return false
        return activeLease.attemptToken == attemptToken &&
            activeLease.lifecycleToken == lifecycleToken
    }

    private fun abortAfterFailure(
        transaction: FileStoreUploadTransaction,
        failure: Throwable,
    ): Nothing {
        var terminalFailure = failure
        try {
            abortUploadTransaction(transaction)
        } catch (abortFailure: Throwable) {
            terminalFailure = mergeRuntimeFailure(terminalFailure, abortFailure)
        }
        throw terminalFailure
    }

    private fun requireResources(): FileStoreUploadResources =
        host.resources() ?: error("FileStore not initialized")

    private fun requireMonitor() {
        check(Thread.holdsLock(monitor)) {
            "FileStore upload coordinator requires the FileStore mutation monitor"
        }
    }

    private companion object {
        const val MAX_UPLOAD_TRANSACTION_OBJECTS = 2
    }
}

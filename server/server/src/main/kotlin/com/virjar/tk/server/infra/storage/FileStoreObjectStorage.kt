package com.virjar.tk.server.infra.storage

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.server.runtime.mergeRuntimeFailure
import kotlinx.serialization.json.Json
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * FileStore 打开的原生资源的一次调用作用域快照。
 *
 * 拥有者在持有其生命周期监视器时为每个操作提供新快照；
 * 此辅助类绝不跨调用保留 RocksDB 句柄，也不拥有其生命周期。
 */
internal data class FileStoreObjectStorageResources(
    val db: RocksDB,
    val metaCf: ColumnFamilyHandle,
    val rocksDbTier: RocksDbTier,
    val fsTier: FileSystemTier,
)

/**
 * 直接与事务上传共用的持久对象创建与回滚机制。
 *
 * FileStore 仍是唯一的同步拥有者。每个可调用入口都校验其调用方
 * 已经持有 [monitor]；此辅助类刻意既不引入锁，也不引入镜像状态。
 */
internal class FileStoreObjectStorage(
    private val monitor: Any,
    private val largeFileThreshold: Long,
    private val maxFileSize: Long,
    private val capacityLedger: FileStoreCapacityLedger,
    private val persistentState: FileStorePersistentState,
    private val mutationFaultInjector: FileStoreMutationFaultInjector,
    private val clock: () -> Long,
    private val json: Json,
    private val resources: () -> FileStoreObjectStorageResources,
    private val recordConsistencyFailure: (Throwable) -> Unit,
) {
    private val logger = LoggerFactory.getLogger("FileStore")

    /**
     * 存储一个对象，同时保留 FileStore 精确的持久提交通知缝隙。
     */
    fun store(
        uid: String,
        fileName: String,
        contentType: String,
        tempFile: File,
        uploadTransactionKey: String?,
        uploadAttemptToken: String?,
        uploadObjectIndex: Int?,
        capacityAlreadyPending: Boolean,
        onDurableCommit: () -> Unit = {},
    ): String {
        requireMonitorHeld()
        val open = resources()
        val size = tempFile.length()
        requireValidFileStoreOwnerUid(uid)
        require(size in 0L..maxFileSize) {
            "Attachment exceeds the $maxFileSize byte storage limit"
        }
        if (!capacityAlreadyPending) capacityLedger.requireAvailable(uid, size)
        val safeName = sanitizeOriginalName(fileName)
        val safeContentType = sanitizeContentType(contentType)
        val ext = safeName.substringAfterLast('.', "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(12)
        val suffix = if (ext.isBlank()) "" else ".$ext"
        val path = "$uid/${UUID.randomUUID().toString().replace("-", "")}$suffix"
        val storageKey = UUID.randomUUID().toString().replace("-", "")
        val tier = if (size > largeFileThreshold) StorageTier.FILESYSTEM else StorageTier.ROCKSDB

        val metadata = FileMetadata(
            path = path,
            originalName = safeName,
            contentType = safeContentType,
            size = size,
            tier = tier,
            storageKey = storageKey,
            uploadedAt = clock(),
            uid = uid,
            lifecycle = FileMetadataLifecycle.ACTIVE,
            uploadTransactionKey = uploadTransactionKey,
            uploadAttemptToken = uploadAttemptToken,
            uploadObjectIndex = uploadObjectIndex,
        )
        writeMetaAndData(
            open = open,
            path = path,
            metadata = metadata,
            tempFile = tempFile,
            capacityAlreadyPending = capacityAlreadyPending,
        )
        // 让此调用紧跟持久写之后、甚至先于诊断日志：
        // abort 只在这个提交到日志的小窗口内需要恢复查找。
        onDurableCommit()
        logger.debug("File stored: {} ({} bytes, {})", path, size, tier)
        return path
    }

    fun rollbackOneUnpublished(path: String) {
        requireMonitorHeld()
        val open = resources()
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        val encodedMeta = open.db.get(open.metaCf, pathBytes) ?: return
        val metadata = json.decodeFromString(
            FileMetadata.serializer(),
            String(encodedMeta, StandardCharsets.UTF_8),
        )

        when (metadata.tier) {
            StorageTier.ROCKSDB -> rollbackRocksObject(open, pathBytes, metadata)
            StorageTier.FILESYSTEM -> rollbackFilesystemObject(open, pathBytes, metadata)
        }
    }

    fun hasBackingData(metadata: FileMetadata): Boolean {
        requireMonitorHeld()
        val open = resources()
        return when (metadata.tier) {
            StorageTier.ROCKSDB ->
                metadata.lifecycle == FileMetadataLifecycle.ACTIVE &&
                    open.rocksDbTier.contains(metadata.path)

            StorageTier.FILESYSTEM ->
                metadata.lifecycle == FileMetadataLifecycle.ACTIVE &&
                    open.fsTier.storedSize(metadata.storageKey) == metadata.size
        }
    }

    private fun writeMetaAndData(
        open: FileStoreObjectStorageResources,
        path: String,
        metadata: FileMetadata,
        tempFile: File,
        capacityAlreadyPending: Boolean,
    ) {
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        val encodedMetadata = json.encodeToString(FileMetadata.serializer(), metadata)
            .toByteArray(StandardCharsets.UTF_8)

        if (metadata.tier == StorageTier.FILESYSTEM) {
            writeFilesystemObject(
                open = open,
                pathBytes = pathBytes,
                metadata = metadata,
                tempFile = tempFile,
                capacityAlreadyPending = capacityAlreadyPending,
            )
        } else {
            writeRocksObject(
                open = open,
                pathBytes = pathBytes,
                encodedMetadata = encodedMetadata,
                metadata = metadata,
                tempFile = tempFile,
                capacityAlreadyPending = capacityAlreadyPending,
            )
        }
    }

    private fun writeFilesystemObject(
        open: FileStoreObjectStorageResources,
        pathBytes: ByteArray,
        metadata: FileMetadata,
        tempFile: File,
        capacityAlreadyPending: Boolean,
    ) {
        val pending = metadata.copy(lifecycle = FileMetadataLifecycle.PENDING_CREATE)
        if (!capacityAlreadyPending) capacityLedger.reserve(metadata.uid, metadata.size)
        try {
            persistentState.putMetadata(open.db, open.metaCf, pathBytes, pending)
            persistentState.resolveFilesystemEntry(metadata.storageKey)
            open.fsTier.moveFrom(metadata.storageKey, tempFile)
            check(open.fsTier.storedSize(metadata.storageKey) == metadata.size) {
                "Attachment changed while entering filesystem storage"
            }
            mutationFaultInjector.before(
                FileStoreMutationPoint.ACTIVATE_FILESYSTEM_METADATA,
                metadata,
            )
            persistentState.putMetadata(open.db, open.metaCf, pathBytes, metadata)
            if (capacityAlreadyPending) capacityLedger.commitPending(metadata.uid, metadata.size)
        } catch (failure: Throwable) {
            var terminalFailure = failure
            try {
                persistentState.deleteFilesystemEntity(metadata)
                mutationFaultInjector.before(
                    FileStoreMutationPoint.FINALIZE_FILESYSTEM_METADATA_DELETE,
                    pending,
                )
                persistentState.deleteMetadata(open.db, open.metaCf, pathBytes)
                if (!capacityAlreadyPending) capacityLedger.retire(metadata.uid, metadata.size)
            } catch (rollbackFailure: Throwable) {
                recordConsistencyFailure(rollbackFailure)
                terminalFailure = mergeRuntimeFailure(terminalFailure, rollbackFailure)
            }
            throw terminalFailure
        }
    }

    private fun writeRocksObject(
        open: FileStoreObjectStorageResources,
        pathBytes: ByteArray,
        encodedMetadata: ByteArray,
        metadata: FileMetadata,
        tempFile: File,
        capacityAlreadyPending: Boolean,
    ) {
        val data = readExactRocksDbPayload(tempFile, metadata.size)
        if (!capacityAlreadyPending) capacityLedger.reserve(metadata.uid, metadata.size)
        try {
            WriteBatch().use { batch ->
                batch.put(open.metaCf, pathBytes, encodedMetadata)
                open.rocksDbTier.addToBatch(batch, metadata, data)
                mutationFaultInjector.before(FileStoreMutationPoint.COMMIT_ROCKS_CREATE, metadata)
                authoritativeRocksWriteOptions().use { options -> open.db.write(options, batch) }
            }
            if (capacityAlreadyPending) capacityLedger.commitPending(metadata.uid, metadata.size)
        } catch (failure: Throwable) {
            var terminalFailure = failure
            try {
                WriteBatch().use { batch ->
                    batch.delete(open.metaCf, pathBytes)
                    open.rocksDbTier.deleteFromBatch(batch, metadata.path)
                    mutationFaultInjector.before(FileStoreMutationPoint.COMMIT_ROCKS_DELETE, metadata)
                    authoritativeRocksWriteOptions().use { options -> open.db.write(options, batch) }
                }
                open.rocksDbTier.forgetDeleted(metadata.path)
                if (!capacityAlreadyPending) capacityLedger.retire(metadata.uid, metadata.size)
            } catch (rollbackFailure: Throwable) {
                recordConsistencyFailure(rollbackFailure)
                terminalFailure = mergeRuntimeFailure(terminalFailure, rollbackFailure)
            }
            throw terminalFailure
        }
    }

    /** RocksDB 的 Java API 需要一个值数组；把该层的堆预算与精确读取显式化。 */
    private fun readExactRocksDbPayload(tempFile: File, expectedSize: Long): ByteArray {
        require(expectedSize in 0L..minOf(largeFileThreshold, maxFileSize, Int.MAX_VALUE.toLong())) {
            "RocksDB attachment payload exceeds its explicit heap budget"
        }
        val result = ByteArray(expectedSize.toInt())
        tempFile.inputStream().buffered(DEFAULT_COPY_BUFFER_BYTES).use { input ->
            var offset = 0
            while (offset < result.size) {
                val read = input.read(result, offset, result.size - offset)
                require(read > 0) { "Attachment changed while entering RocksDB storage" }
                offset += read
            }
            require(input.read() == -1) { "Attachment changed while entering RocksDB storage" }
        }
        return result
    }

    private fun rollbackRocksObject(
        open: FileStoreObjectStorageResources,
        pathBytes: ByteArray,
        metadata: FileMetadata,
    ) {
        try {
            WriteBatch().use { batch ->
                batch.delete(open.metaCf, pathBytes)
                open.rocksDbTier.deleteFromBatch(batch, metadata.path)
                mutationFaultInjector.before(FileStoreMutationPoint.COMMIT_ROCKS_DELETE, metadata)
                authoritativeRocksWriteOptions().use { options -> open.db.write(options, batch) }
            }
            open.rocksDbTier.forgetDeleted(metadata.path)
            capacityLedger.retire(metadata.uid, metadata.size)
        } catch (failure: Throwable) {
            recordConsistencyFailure(failure)
            throw failure
        }
    }

    private fun rollbackFilesystemObject(
        open: FileStoreObjectStorageResources,
        pathBytes: ByteArray,
        metadata: FileMetadata,
    ) {
        try {
            val pendingDelete = metadata.copy(lifecycle = FileMetadataLifecycle.PENDING_DELETE)
            if (metadata.lifecycle != FileMetadataLifecycle.PENDING_DELETE) {
                mutationFaultInjector.before(
                    FileStoreMutationPoint.MARK_FILESYSTEM_DELETE_PENDING,
                    pendingDelete,
                )
                persistentState.putMetadata(open.db, open.metaCf, pathBytes, pendingDelete)
            }
            persistentState.deleteFilesystemEntity(pendingDelete)
            mutationFaultInjector.before(
                FileStoreMutationPoint.FINALIZE_FILESYSTEM_METADATA_DELETE,
                pendingDelete,
            )
            persistentState.deleteMetadata(open.db, open.metaCf, pathBytes)
            capacityLedger.retire(metadata.uid, metadata.size)
        } catch (failure: Throwable) {
            recordConsistencyFailure(failure)
            throw failure
        }
    }

    private fun requireMonitorHeld() {
        check(Thread.holdsLock(monitor)) {
            "FileStore object storage requires its owner monitor"
        }
    }

    private fun sanitizeOriginalName(fileName: String): String = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001F]"), "_")
        .trim()
        .take(255)
        .ifBlank { "attachment" }

    private fun sanitizeContentType(contentType: String): String = contentType
        .trim()
        .takeIf { candidate ->
            candidate.isNotEmpty() &&
                candidate.length <= AttachmentPolicy.MAX_CONTENT_TYPE_LENGTH &&
                candidate.none { it.code < 0x20 || it.code == 0x7f }
        }
        ?: "application/octet-stream"

    private companion object {
        const val DEFAULT_COPY_BUFFER_BYTES = 64 * 1024
    }
}

package com.virjar.tk.server.infra.storage

import kotlinx.serialization.json.Json
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 拥有 FileStore 的持久生命周期日志与启动对账协议。
 *
 * 文件系统对象无法参与 RocksDB 批次。因此其元数据生命周期
 * 就是持久事务记录：创建在 ACTIVE 之前是临时的，删除保持 pending
 * 直到对象被确认不存在，而启动会在存储
 * 可发布为健康之前解析每个中间状态。
 */
internal class FileStorePersistentState(
    private val filesystemRoot: File,
    private val maxFileSize: Long,
    private val quotaLimits: FileStoreQuotaLimits,
    private val json: Json,
    private val mutationFaultInjector: FileStoreMutationFaultInjector,
) {
    private val logger = LoggerFactory.getLogger("FileStore")

    /**
     * 在 FileStore 实例发布之前，解析每个持久中间状态。
     * 元数据是核算权威：文件系统行只在其对象被确认
     * 不存在之后删除。反之，没有活跃行的物理对象会在启动
     * 完成之前删除，因此未核算的字节绝不会进入运行中的实例。
     */
    fun reconcile(
        dbInst: RocksDB,
        mCf: ColumnFamilyHandle,
        dCf: ColumnFamilyHandle,
        uCf: ColumnFamilyHandle,
        now: Long,
    ): FileStorePersistentUsage {
        val uploadRecords = readUploadTransactions(
            dbInst = dbInst,
            uCf = uCf,
        )
        val transactionObjects = mutableMapOf<String, MutableList<FileMetadata>>()
        val activeFilesystemObjects = mutableSetOf<Path>()
        val activeFilesystemStorageKeys = mutableSetOf<String>()
        val activeRocksObjects = mutableSetOf<String>()
        var durableRecordCount = 0
        var totalUsage = FileStoreUsage.EMPTY
        val ownerUsages = mutableMapOf<String, FileStoreUsage>()
        var repairedRecords = 0

        dbInst.newIterator(mCf).use { iterator ->
            iterator.seekToFirst()
            while (iterator.isValid) {
                check(durableRecordCount < quotaLimits.maxTotalFiles) {
                    "FileStore global persistent capacity is exceeded"
                }
                durableRecordCount += 1
                val metadataKey = iterator.key()
                val metadata = json.decodeFromString(
                    FileMetadata.serializer(),
                    String(iterator.value(), StandardCharsets.UTF_8),
                )
                validatePersistentMetadata(metadataKey, metadata)
                val uploadRecord = metadata.uploadTransactionKey?.let { transactionKey ->
                    checkNotNull(uploadRecords[transactionKey]) {
                        "FileStore metadata references a missing upload transaction"
                    }.also { record ->
                        check(record.uid == metadata.uid) {
                            "FileStore upload transaction owner does not match its object"
                        }
                        check(record.attemptToken == metadata.uploadAttemptToken) {
                            "FileStore upload attempt does not match its object"
                        }
                    }
                }
                val remainsActive = if (
                    uploadRecord?.state == FileStoreUploadTransactionState.STARTED
                ) {
                    reconcileStartedUploadObject(dbInst, mCf, dCf, metadataKey, metadata)
                    false
                } else when (metadata.tier) {
                    StorageTier.FILESYSTEM -> {
                        check(activeFilesystemStorageKeys.add(metadata.storageKey)) {
                            "FileStore metadata contains duplicate filesystem object ownership"
                        }
                        val target = resolveFilesystemEntry(metadata.storageKey).toPath()
                            .toAbsolutePath()
                            .normalize()
                        val active = reconcileFilesystemRecord(dbInst, mCf, metadataKey, metadata, target)
                        if (active) activeFilesystemObjects.add(target)
                        active
                    }

                    StorageTier.ROCKSDB -> {
                        val active = reconcileRocksRecord(dbInst, mCf, dCf, metadataKey, metadata)
                        if (active) activeRocksObjects.add(metadata.path)
                        active
                    }
                }
                if (remainsActive) {
                    metadata.uploadTransactionKey?.let { transactionKey ->
                        transactionObjects.getOrPut(transactionKey, ::mutableListOf).add(metadata)
                    }
                    val ownerUsage = ownerUsages[metadata.uid] ?: FileStoreUsage.EMPTY
                    val exceededScope = quotaLimits.exceededScope(
                        total = totalUsage,
                        owner = ownerUsage,
                        additionalBytes = metadata.size,
                    )
                    check(exceededScope == null) {
                        when (exceededScope) {
                            FileStoreCapacityScope.OWNER -> "FileStore owner persistent capacity is exceeded"
                            FileStoreCapacityScope.GLOBAL -> "FileStore global persistent capacity is exceeded"
                            null -> "FileStore persistent capacity is invalid"
                        }
                    }
                    totalUsage = totalUsage.addPersistentObject(metadata.size)
                    ownerUsages[metadata.uid] = ownerUsage.addPersistentObject(metadata.size)
                } else {
                    repairedRecords += 1
                }
                iterator.next()
            }
            iterator.status()
        }

        reconcileUploadTransactions(
            dbInst = dbInst,
            mCf = mCf,
            uCf = uCf,
            now = now,
            uploadRecords = uploadRecords,
            transactionObjects = transactionObjects,
        )

        repairedRecords += deleteFilesystemOrphans(activeFilesystemObjects)
        repairedRecords += deleteRocksOrphans(dbInst, dCf, activeRocksObjects)
        if (repairedRecords > 0) {
            logger.warn("FileStore startup reconciled {} incomplete or orphaned persistent objects", repairedRecords)
        }
        return FileStorePersistentUsage(
            totalUsage = totalUsage,
            ownerUsages = ownerUsages.toMap(),
        )
    }

    fun resolveFilesystemEntry(storageKey: String): File {
        require(isValidStorageKey(storageKey)) { "Invalid FileStore storage key" }
        val normalizedRoot = filesystemRoot.toPath().toAbsolutePath().normalize()
        check(Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalizedRoot)) {
            "FileStore filesystem root must be a real directory"
        }
        val firstLevel = normalizedRoot.resolve(storageKey.substring(0, 2))
        val secondLevel = firstLevel.resolve(storageKey.substring(2, 4))
        listOf(firstLevel, secondLevel).forEach { directory ->
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                check(
                    Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(directory)
                ) {
                    "FileStore object path must not traverse a symbolic link"
                }
            }
        }
        val target = secondLevel
            .resolve("$storageKey.dat")
            .normalize()
        check(target.startsWith(normalizedRoot) && target != normalizedRoot) {
            "FileStore object must remain below its storage root"
        }
        return target.toFile()
    }

    fun deleteFilesystemEntity(metadata: FileMetadata) {
        mutationFaultInjector.before(FileStoreMutationPoint.DELETE_FILESYSTEM_ENTITY, metadata)
        val target = resolveFilesystemEntry(metadata.storageKey).toPath()
            .toAbsolutePath()
            .normalize()
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        check(target.toFile().delete()) { "Failed to retire filesystem attachment" }
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "FileStore could not confirm filesystem attachment retirement"
        }
    }

    fun putMetadata(
        dbInst: RocksDB,
        mCf: ColumnFamilyHandle,
        pathBytes: ByteArray,
        metadata: FileMetadata,
    ) {
        val encoded = json.encodeToString(FileMetadata.serializer(), metadata)
            .toByteArray(StandardCharsets.UTF_8)
        authoritativeRocksWriteOptions().use { options -> dbInst.put(mCf, options, pathBytes, encoded) }
    }

    fun deleteMetadata(
        dbInst: RocksDB,
        mCf: ColumnFamilyHandle,
        pathBytes: ByteArray,
    ) {
        authoritativeRocksWriteOptions().use { options -> dbInst.delete(mCf, options, pathBytes) }
    }

    fun putUploadTransaction(
        dbInst: RocksDB,
        uCf: ColumnFamilyHandle,
        transactionKey: String,
        record: FileStoreUploadTransactionRecord,
    ) {
        val encoded = json.encodeToString(FileStoreUploadTransactionRecord.serializer(), record)
            .toByteArray(StandardCharsets.UTF_8)
        authoritativeRocksWriteOptions().use { options ->
            dbInst.put(
                uCf,
                options,
                transactionKey.toByteArray(StandardCharsets.UTF_8),
                encoded,
            )
        }
    }

    fun deleteUploadTransaction(
        dbInst: RocksDB,
        uCf: ColumnFamilyHandle,
        transactionKey: String,
    ) {
        authoritativeRocksWriteOptions().use { options ->
            dbInst.delete(uCf, options, transactionKey.toByteArray(StandardCharsets.UTF_8))
        }
    }

    fun getUploadTransaction(
        dbInst: RocksDB,
        uCf: ColumnFamilyHandle,
        transactionKey: String,
    ): FileStoreUploadTransactionRecord? {
        val encoded = dbInst.get(uCf, transactionKey.toByteArray(StandardCharsets.UTF_8)) ?: return null
        return json.decodeFromString(
            FileStoreUploadTransactionRecord.serializer(),
            String(encoded, StandardCharsets.UTF_8),
        )
    }

    /** 当失败发生在句柄记录其路径之前时使用的持久恢复查找。 */
    fun findUploadAttemptObjects(
        dbInst: RocksDB,
        mCf: ColumnFamilyHandle,
        transactionKey: String,
        attemptToken: String,
    ): List<FileMetadata> {
        val result = ArrayList<FileMetadata>(MAX_UPLOAD_TRANSACTION_OBJECTS)
        dbInst.newIterator(mCf).use { iterator ->
            iterator.seekToFirst()
            while (iterator.isValid) {
                val metadata = json.decodeFromString(
                    FileMetadata.serializer(),
                    String(iterator.value(), StandardCharsets.UTF_8),
                )
                if (
                    metadata.uploadTransactionKey == transactionKey &&
                    metadata.uploadAttemptToken == attemptToken
                ) {
                    result += metadata
                }
                iterator.next()
            }
            iterator.status()
        }
        check(result.size <= MAX_UPLOAD_TRANSACTION_OBJECTS) {
            "FileStore upload attempt contains too many durable objects"
        }
        return result.sortedBy { metadata ->
            checkNotNull(metadata.uploadObjectIndex) {
                "FileStore upload attempt object position is missing"
            }
        }.also { sorted ->
            check(sorted.map(FileMetadata::uploadObjectIndex).distinct().size == sorted.size) {
                "FileStore upload attempt contains duplicate object positions"
            }
        }
    }

    /** 原子地回收一个回执身份，并解除其保护的每个对象。 */
    fun expireUploadTransaction(
        dbInst: RocksDB,
        mCf: ColumnFamilyHandle,
        uCf: ColumnFamilyHandle,
        transactionKey: String,
    ) {
        val record = getUploadTransaction(dbInst, uCf, transactionKey) ?: return
        check(record.state == FileStoreUploadTransactionState.COMPLETED) {
            "Only a completed upload receipt can expire"
        }
        WriteBatch().use { batch ->
            record.objects.forEachIndexed { index, descriptor ->
                val pathBytes = descriptor.path.toByteArray(StandardCharsets.UTF_8)
                val encodedMetadata = dbInst.get(mCf, pathBytes)
                if (encodedMetadata != null) {
                    val metadata = json.decodeFromString(
                        FileMetadata.serializer(),
                        String(encodedMetadata, StandardCharsets.UTF_8),
                    )
                    check(
                        metadata.uploadTransactionKey == transactionKey &&
                            metadata.uploadAttemptToken == record.attemptToken &&
                            metadata.uploadObjectIndex == index &&
                            metadata.path == descriptor.path &&
                            metadata.originalName == descriptor.name &&
                            metadata.contentType == descriptor.contentType &&
                            metadata.size == descriptor.size
                    ) { "FileStore upload receipt object is inconsistent during expiry" }
                    batch.put(
                        mCf,
                        pathBytes,
                        json.encodeToString(
                            FileMetadata.serializer(),
                            metadata.copy(
                                uploadTransactionKey = null,
                                uploadAttemptToken = null,
                                uploadObjectIndex = null,
                            ),
                        ).toByteArray(StandardCharsets.UTF_8),
                    )
                }
            }
            batch.delete(uCf, transactionKey.toByteArray(StandardCharsets.UTF_8))
            authoritativeRocksWriteOptions().use { options -> dbInst.write(options, batch) }
        }
    }

    private fun readUploadTransactions(
        dbInst: RocksDB,
        uCf: ColumnFamilyHandle,
    ): Map<String, FileStoreUploadTransactionRecord> {
        val records = mutableMapOf<String, FileStoreUploadTransactionRecord>()
        dbInst.newIterator(uCf).use { iterator ->
            iterator.seekToFirst()
            while (iterator.isValid) {
                val keyBytes = iterator.key()
                val transactionKey = String(keyBytes, StandardCharsets.UTF_8)
                check(keyBytes.contentEquals(transactionKey.toByteArray(StandardCharsets.UTF_8))) {
                    "FileStore upload transaction key is not canonical UTF-8"
                }
                val record = json.decodeFromString(
                    FileStoreUploadTransactionRecord.serializer(),
                    String(iterator.value(), StandardCharsets.UTF_8),
                )
                validateUploadTransaction(
                    transactionKey = transactionKey,
                    record = record,
                )
                check(records.put(transactionKey, record) == null) {
                    "FileStore upload transaction key is duplicated"
                }
                iterator.next()
            }
            iterator.status()
        }
        return records
    }

    private fun validateUploadTransaction(
        transactionKey: String,
        record: FileStoreUploadTransactionRecord,
    ) {
        check(isValidFileStoreOwnerUid(record.uid)) {
            "FileStore upload transaction owner is invalid"
        }
        val canonicalUploadId = runCatching { canonicalFileStoreUploadId(record.uploadId) }
            .getOrElse { throw IllegalStateException("FileStore upload transaction id is invalid", it) }
        check(record.uploadId == canonicalUploadId) {
            "FileStore upload transaction id is not canonical"
        }
        check(transactionKey == fileStoreUploadTransactionKey(record.uid, canonicalUploadId)) {
            "FileStore upload transaction key does not match its record"
        }
        val canonicalAttemptToken = runCatching { canonicalFileStoreUploadId(record.attemptToken) }
            .getOrElse { throw IllegalStateException("FileStore upload attempt token is invalid", it) }
        check(record.attemptToken == canonicalAttemptToken) {
            "FileStore upload attempt token is not canonical"
        }
        record.requestFingerprint?.let { fingerprint ->
            check(
                fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' }
            ) { "FileStore upload transaction fingerprint is invalid" }
        }
        check(record.startedAt >= 0L) { "FileStore upload transaction timestamp is invalid" }
        val receiptLeaseExpiresAt = checkNotNull(record.receiptLeaseExpiresAt) {
            "FileStore upload transaction receipt lease is missing"
        }
        check(receiptLeaseExpiresAt >= record.startedAt) {
            "FileStore upload transaction receipt lease is invalid"
        }
        check(record.reservedObjectSizes.size in 1..MAX_UPLOAD_TRANSACTION_OBJECTS) {
            "FileStore upload transaction object count is invalid"
        }
        check(record.reservedObjectSizes.all { it in 0L..maxFileSize }) {
            "FileStore upload transaction reservation is invalid"
        }
        check(
            record.materializedObjectPaths.size <= record.reservedObjectSizes.size &&
                record.materializedObjectPaths.distinct().size == record.materializedObjectPaths.size
        ) { "FileStore upload transaction materialized objects are invalid" }
        when (record.state) {
            FileStoreUploadTransactionState.STARTED -> check(
                record.objects.isEmpty() &&
                    record.encodedReceipt == null &&
                    record.completedAt == null
            ) { "Started FileStore upload transaction contains completion facts" }

            FileStoreUploadTransactionState.COMPLETED -> {
                check(record.requestFingerprint != null) {
                    "Completed FileStore upload transaction fingerprint is missing"
                }
                check(record.objects.size == record.reservedObjectSizes.size) {
                    "Completed FileStore upload transaction object count is invalid"
                }
                check(record.materializedObjectPaths == record.objects.map(FileStoreUploadObjectReceipt::path)) {
                    "Completed FileStore upload transaction journal does not match its receipt"
                }
                check(record.objects.map(FileStoreUploadObjectReceipt::size) == record.reservedObjectSizes) {
                    "Completed FileStore upload transaction reservation does not match its objects"
                }
                check(record.objects.map(FileStoreUploadObjectReceipt::path).distinct().size == record.objects.size) {
                    "Completed FileStore upload transaction contains duplicate objects"
                }
                val encodedReceipt = checkNotNull(record.encodedReceipt) {
                    "Completed FileStore upload transaction receipt is missing"
                }
                check(
                    encodedReceipt.isNotEmpty() &&
                        encodedReceipt.toByteArray(StandardCharsets.UTF_8).size <=
                        MAX_ENCODED_UPLOAD_RECEIPT_BYTES
                ) { "Completed FileStore upload transaction receipt is invalid" }
                val completedAt = checkNotNull(record.completedAt) {
                    "Completed FileStore upload transaction timestamp is missing"
                }
                check(completedAt >= record.startedAt) {
                    "Completed FileStore upload transaction timestamp is invalid"
                }
                check(receiptLeaseExpiresAt >= completedAt) {
                    "Completed FileStore upload receipt lease is invalid"
                }
            }
        }
    }

    private fun reconcileStartedUploadObject(
        dbInst: RocksDB,
        mCf: ColumnFamilyHandle,
        dCf: ColumnFamilyHandle,
        metadataKey: ByteArray,
        metadata: FileMetadata,
    ) {
        when (metadata.tier) {
            StorageTier.ROCKSDB -> WriteBatch().use { batch ->
                batch.delete(mCf, metadataKey)
                batch.delete(dCf, metadata.path.toByteArray(StandardCharsets.UTF_8))
                authoritativeRocksWriteOptions().use { options -> dbInst.write(options, batch) }
            }

            StorageTier.FILESYSTEM -> {
                val target = resolveFilesystemEntry(metadata.storageKey).toPath()
                    .toAbsolutePath()
                    .normalize()
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) deleteFilesystemEntity(metadata)
                check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    "FileStore could not retire an incomplete upload object"
                }
                deleteMetadata(dbInst, mCf, metadataKey)
            }
        }
    }

    private fun reconcileUploadTransactions(
        dbInst: RocksDB,
        mCf: ColumnFamilyHandle,
        uCf: ColumnFamilyHandle,
        now: Long,
        uploadRecords: Map<String, FileStoreUploadTransactionRecord>,
        transactionObjects: Map<String, List<FileMetadata>>,
    ) {
        uploadRecords.forEach { (transactionKey, record) ->
            when (record.state) {
                FileStoreUploadTransactionState.STARTED ->
                    deleteUploadTransaction(dbInst, uCf, transactionKey)

                FileStoreUploadTransactionState.COMPLETED -> {
                    val leaseExpiresAt = checkNotNull(record.receiptLeaseExpiresAt)
                    if (leaseExpiresAt < now) {
                        expireUploadTransaction(dbInst, mCf, uCf, transactionKey)
                        return@forEach
                    }
                    val metadata = transactionObjects[transactionKey].orEmpty()
                    val metadataByPath = metadata.associateBy(FileMetadata::path)
                    check(metadataByPath.size == metadata.size) {
                        "FileStore upload receipt has duplicate backing metadata"
                    }
                    check(metadataByPath.keys == record.objects.mapTo(mutableSetOf(), FileStoreUploadObjectReceipt::path)) {
                        "FileStore upload receipt backing objects are incomplete"
                    }
                    record.objects.forEachIndexed { index, descriptor ->
                        val stored = checkNotNull(metadataByPath[descriptor.path]) {
                            "FileStore upload receipt backing object is missing"
                        }
                        check(
                            stored.lifecycle == FileMetadataLifecycle.ACTIVE &&
                                stored.uid == record.uid &&
                                stored.originalName == descriptor.name &&
                                stored.contentType == descriptor.contentType &&
                                stored.size == descriptor.size &&
                                stored.uploadTransactionKey == transactionKey &&
                                stored.uploadAttemptToken == record.attemptToken &&
                                stored.uploadObjectIndex == index
                        ) { "FileStore upload receipt descriptor does not match its backing object" }
                    }
                }
            }
        }
    }

    private fun reconcileFilesystemRecord(
        dbInst: RocksDB,
        mCf: ColumnFamilyHandle,
        metadataKey: ByteArray,
        metadata: FileMetadata,
        target: Path,
    ): Boolean {
        val exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
        val isValidActiveObject = metadata.lifecycle == FileMetadataLifecycle.ACTIVE &&
            exists &&
            Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
            Files.size(target) == metadata.size
        if (isValidActiveObject) return true

        if (exists) deleteFilesystemEntity(metadata)
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "FileStore could not confirm filesystem object retirement"
        }
        deleteMetadata(dbInst, mCf, metadataKey)
        return false
    }

    private fun reconcileRocksRecord(
        dbInst: RocksDB,
        mCf: ColumnFamilyHandle,
        dCf: ColumnFamilyHandle,
        metadataKey: ByteArray,
        metadata: FileMetadata,
    ): Boolean {
        val pathBytes = metadata.path.toByteArray(StandardCharsets.UTF_8)
        val data = dbInst.get(dCf, pathBytes)
        if (
            metadata.lifecycle == FileMetadataLifecycle.ACTIVE &&
            data != null &&
            data.size.toLong() == metadata.size
        ) {
            return true
        }

        WriteBatch().use { batch ->
            batch.delete(mCf, metadataKey)
            batch.delete(dCf, pathBytes)
            authoritativeRocksWriteOptions().use { options -> dbInst.write(options, batch) }
        }
        return false
    }

    private fun deleteFilesystemOrphans(activeObjects: Set<Path>): Int {
        val root = filesystemRoot.toPath().toAbsolutePath().normalize()
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "FileStore filesystem root must be a real directory"
        }
        var deleted = 0
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next().toAbsolutePath().normalize()
                if (candidate == root || Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) continue
                if (candidate in activeObjects) continue
                check(candidate.startsWith(root)) { "FileStore filesystem scan escaped its storage root" }
                check(candidate.toFile().delete()) { "Failed to retire orphaned filesystem attachment" }
                check(!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    "FileStore could not confirm orphaned filesystem attachment retirement"
                }
                deleted += 1
            }
        }
        return deleted
    }

    private fun deleteRocksOrphans(
        dbInst: RocksDB,
        dCf: ColumnFamilyHandle,
        activeObjects: Set<String>,
    ): Int {
        val orphanKeys = ArrayList<ByteArray>(RECONCILE_DELETE_BATCH_SIZE)
        var deleted = 0
        fun flushDeletes() {
            if (orphanKeys.isEmpty()) return
            WriteBatch().use { batch ->
                orphanKeys.forEach { key -> batch.delete(dCf, key) }
                authoritativeRocksWriteOptions().use { options -> dbInst.write(options, batch) }
            }
            deleted += orphanKeys.size
            orphanKeys.clear()
        }
        dbInst.newIterator(dCf).use { iterator ->
            iterator.seekToFirst()
            while (iterator.isValid) {
                val rawKey = iterator.key()
                val decodedKey = String(rawKey, StandardCharsets.UTF_8)
                val isCanonicalUtf8 = rawKey.contentEquals(decodedKey.toByteArray(StandardCharsets.UTF_8))
                if (!isCanonicalUtf8 || decodedKey !in activeObjects) {
                    orphanKeys.add(rawKey)
                    if (orphanKeys.size == RECONCILE_DELETE_BATCH_SIZE) flushDeletes()
                }
                iterator.next()
            }
            iterator.status()
        }
        flushDeletes()
        return deleted
    }

    private fun validatePersistentMetadata(key: ByteArray, metadata: FileMetadata) {
        val decodedKey = String(key, StandardCharsets.UTF_8)
        check(key.contentEquals(decodedKey.toByteArray(StandardCharsets.UTF_8)) && decodedKey == metadata.path) {
            "FileStore metadata key does not match its record"
        }
        check(metadata.size in 0L..maxFileSize) { "FileStore persistent usage metadata is invalid" }
        check(metadata.uploadedAt >= 0L) { "FileStore persistent upload timestamp is invalid" }
        check(metadata.businessBoundAt == null || metadata.businessBoundAt >= metadata.uploadedAt) {
            "FileStore persistent business-bound timestamp is invalid"
        }
        check(isValidFileStoreOwnerUid(metadata.uid)) { "FileStore persistent owner metadata is invalid" }
        check(
            metadata.path.startsWith("${metadata.uid}/") &&
                metadata.path.length > metadata.uid.length + 1 &&
                '/' !in metadata.path.substring(metadata.uid.length + 1) &&
                '\\' !in metadata.path
        ) { "FileStore persistent owner metadata is invalid" }
        check(isValidStorageKey(metadata.storageKey)) { "FileStore metadata contains an invalid storage key" }
        if (metadata.uploadTransactionKey == null) {
            check(metadata.uploadAttemptToken == null && metadata.uploadObjectIndex == null) {
                "FileStore direct metadata contains partial upload transaction identity"
            }
        } else {
            val attemptToken = checkNotNull(metadata.uploadAttemptToken) {
                "FileStore upload metadata attempt token is missing"
            }
            check(runCatching { canonicalFileStoreUploadId(attemptToken) }.getOrNull() == attemptToken) {
                "FileStore upload metadata attempt token is invalid"
            }
            val objectIndex = checkNotNull(metadata.uploadObjectIndex) {
                "FileStore upload metadata object position is missing"
            }
            check(objectIndex in 0 until MAX_UPLOAD_TRANSACTION_OBJECTS) {
                "FileStore upload metadata object position is invalid"
            }
        }
    }

    private fun isValidStorageKey(storageKey: String): Boolean =
        storageKey.length == STORAGE_KEY_HEX_LENGTH && storageKey.all { it in '0'..'9' || it in 'a'..'f' }

    private companion object {
        const val STORAGE_KEY_HEX_LENGTH = 32
        const val RECONCILE_DELETE_BATCH_SIZE = 512
        const val MAX_UPLOAD_TRANSACTION_OBJECTS = 2
    }
}

internal data class FileStorePersistentUsage(
    val totalUsage: FileStoreUsage,
    val ownerUsages: Map<String, FileStoreUsage>,
) {
    companion object {
        val EMPTY = FileStorePersistentUsage(
            totalUsage = FileStoreUsage.EMPTY,
            ownerUsages = emptyMap(),
        )
    }
}

private fun FileStoreUsage.addPersistentObject(size: Long): FileStoreUsage {
    check(size >= 0L && storedBytes <= Long.MAX_VALUE - size) {
        "FileStore persistent usage metadata is invalid"
    }
    check(storedFiles < Int.MAX_VALUE) { "FileStore persistent usage metadata is invalid" }
    return FileStoreUsage(
        storedBytes = storedBytes + size,
        storedFiles = storedFiles + 1,
    )
}

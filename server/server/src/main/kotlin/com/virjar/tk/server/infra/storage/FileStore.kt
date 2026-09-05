package com.virjar.tk.server.infra.storage

import com.virjar.tk.protocol.body.AttachmentPolicy
import com.virjar.tk.server.domain.attachment.AttachmentCatalog
import com.virjar.tk.server.domain.attachment.AttachmentRetirementCandidate
import com.virjar.tk.server.domain.attachment.AttachmentRetirementScanPage
import com.virjar.tk.server.domain.attachment.AttachmentRetirementStore
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.server.runtime.RuntimeFailureCollector
import com.virjar.tk.server.runtime.mergeRuntimeFailure
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.serialization.json.Json
import org.rocksdb.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal fun interface FileStoreNativeResourceCloser {
    fun close(resource: AutoCloseable)
}

internal enum class FileStoreMutationPoint {
    ACTIVATE_FILESYSTEM_METADATA,
    MARK_FILESYSTEM_DELETE_PENDING,
    COMMIT_ROCKS_CREATE,
    COMMIT_ROCKS_DELETE,
    DELETE_FILESYSTEM_ENTITY,
    FINALIZE_FILESYSTEM_METADATA_DELETE,
    AFTER_TRANSACTION_OBJECT_DURABLE,
}

internal fun interface FileStoreMutationFaultInjector {
    fun before(point: FileStoreMutationPoint, metadata: FileMetadata)
}

private val noFileStoreMutationFaults = FileStoreMutationFaultInjector { _, _ -> }

internal fun closeFileStoreNativeResource(resource: AutoCloseable) {
    // RocksDB.close() 刻意吞掉 RocksDBException；生命周期拥有者需要 closeE()，
    // 这样失败的数据库关闭不会被上报为成功。
    if (resource is RocksDB) resource.closeE() else resource.close()
}

private val directFileStoreNativeResourceCloser = FileStoreNativeResourceCloser { resource ->
    closeFileStoreNativeResource(resource)
}

/**
 * 多级文件存储：小文件（<=32MB）存 RocksDB，大文件存文件系统。
 * 元数据统一存在 RocksDB meta column family。
 */
class FileStore : AttachmentCatalog, AttachmentRetirementStore {
    private val dbPath: String
    private val fsRoot: String
    private val tmpRoot: File
    private val largeFileThreshold: Long
    private val maxFileSize: Long
    private val quotaLimits: FileStoreQuotaLimits
    private val capacityLedger: FileStoreCapacityLedger
    private val nativeResourceCloser: FileStoreNativeResourceCloser
    private val managedTempFileDeleter: (Path) -> Unit
    private val afterNativeOpen: () -> Unit
    private val mutationFaultInjector: FileStoreMutationFaultInjector
    private val beforeUploadAttemptDiscovery: () -> Unit
    private val clock: () -> Long
    private val persistentState: FileStorePersistentState
    private val objectStorage: FileStoreObjectStorage
    private val uploadCoordinator: FileStoreUploadCoordinator
    private val logger = LoggerFactory.getLogger("FileStore")
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Volatile
    private var db: RocksDB? = null
    private var defaultCf: ColumnFamilyHandle? = null
    private var metaCf: ColumnFamilyHandle? = null
    private var dataCf: ColumnFamilyHandle? = null
    private var uploadCf: ColumnFamilyHandle? = null
    private var rocksDbTier: RocksDbTier? = null
    private var fsTier: FileSystemTier? = null
    private var terminalLifecycleFailure: Throwable? = null
    @Volatile
    private var consistencyFailure: Throwable? = null

    constructor(
        dbPath: String,
        fsRoot: String,
        largeFileThreshold: Long = 32L * 1024 * 1024,
        maxFileSize: Long = AttachmentPolicy.MAX_UPLOAD_BYTES,
        tmpRoot: File = File(File(fsRoot).absoluteFile.parentFile, "tmp"),
        maxTotalBytes: Long = DEFAULT_FILE_STORE_MAX_TOTAL_BYTES,
        maxTotalFiles: Int = DEFAULT_FILE_STORE_MAX_TOTAL_FILES,
        maxOwnerBytes: Long = DEFAULT_FILE_STORE_MAX_OWNER_BYTES,
        maxOwnerFiles: Int = DEFAULT_FILE_STORE_MAX_OWNER_FILES,
    ) : this(
        dbPath = dbPath,
        fsRoot = fsRoot,
        largeFileThreshold = largeFileThreshold,
        maxFileSize = maxFileSize,
        nativeResourceCloser = directFileStoreNativeResourceCloser,
        afterNativeOpen = {},
        mutationFaultInjector = noFileStoreMutationFaults,
        tmpRoot = tmpRoot,
        maxTotalBytes = maxTotalBytes,
        maxTotalFiles = maxTotalFiles,
        maxOwnerBytes = maxOwnerBytes,
        maxOwnerFiles = maxOwnerFiles,
    )

    internal constructor(
        dbPath: String,
        fsRoot: String,
        largeFileThreshold: Long,
        maxFileSize: Long,
        nativeResourceCloser: FileStoreNativeResourceCloser,
        afterNativeOpen: () -> Unit = {},
        tmpRoot: File = File(File(fsRoot).absoluteFile.parentFile, "tmp"),
        maxTotalBytes: Long = DEFAULT_FILE_STORE_MAX_TOTAL_BYTES,
        mutationFaultInjector: FileStoreMutationFaultInjector = noFileStoreMutationFaults,
        maxTotalFiles: Int = DEFAULT_FILE_STORE_MAX_TOTAL_FILES,
        maxOwnerBytes: Long = DEFAULT_FILE_STORE_MAX_OWNER_BYTES,
        maxOwnerFiles: Int = DEFAULT_FILE_STORE_MAX_OWNER_FILES,
        managedTempFileDeleter: (Path) -> Unit = { path -> Files.delete(path) },
        beforeUploadAttemptDiscovery: () -> Unit = {},
        clock: () -> Long = System::currentTimeMillis,
    ) {
        this.dbPath = dbPath
        this.fsRoot = fsRoot
        this.tmpRoot = tmpRoot.absoluteFile.normalize()
        this.largeFileThreshold = largeFileThreshold
        this.maxFileSize = maxFileSize
        this.quotaLimits = FileStoreQuotaLimits(
            maxTotalBytes = maxTotalBytes,
            maxTotalFiles = maxTotalFiles,
            maxOwnerBytes = maxOwnerBytes,
            maxOwnerFiles = maxOwnerFiles,
        )
        this.capacityLedger = FileStoreCapacityLedger(quotaLimits)
        this.nativeResourceCloser = nativeResourceCloser
        this.managedTempFileDeleter = managedTempFileDeleter
        this.afterNativeOpen = afterNativeOpen
        this.mutationFaultInjector = mutationFaultInjector
        this.beforeUploadAttemptDiscovery = beforeUploadAttemptDiscovery
        this.clock = clock
        this.persistentState = FileStorePersistentState(
            filesystemRoot = File(fsRoot),
            maxFileSize = maxFileSize,
            quotaLimits = quotaLimits,
            json = json,
            mutationFaultInjector = mutationFaultInjector,
        )
        this.objectStorage = FileStoreObjectStorage(
            monitor = this,
            largeFileThreshold = largeFileThreshold,
            maxFileSize = maxFileSize,
            capacityLedger = capacityLedger,
            persistentState = persistentState,
            mutationFaultInjector = mutationFaultInjector,
            clock = clock,
            json = json,
            resources = {
                FileStoreObjectStorageResources(
                    db = db ?: error("FileStore not initialized"),
                    metaCf = metaCf ?: error("FileStore not initialized"),
                    rocksDbTier = rocksDbTier ?: error("FileStore RocksDB tier is not initialized"),
                    fsTier = fsTier ?: error("FileStore filesystem tier is not initialized"),
                )
            },
            recordConsistencyFailure = ::recordConsistencyFailure,
        )
        this.uploadCoordinator = FileStoreUploadCoordinator(
            monitor = this,
            persistentState = persistentState,
            capacityLedger = capacityLedger,
            maxFileSize = maxFileSize,
            clock = clock,
            mutationFaultInjector = mutationFaultInjector,
            beforeUploadAttemptDiscovery = beforeUploadAttemptDiscovery,
            host = object : FileStoreUploadHost {
                override val owner: FileStore get() = this@FileStore
                override val consistencyFailureOrNull: Throwable? get() = consistencyFailure
                override fun resources(): FileStoreUploadResources? {
                    val database = db ?: return null
                    val metadata = metaCf ?: return null
                    val uploads = uploadCf ?: return null
                    return FileStoreUploadResources(database, metadata, uploads)
                }
                override fun storeReservedObject(
                    uid: String, fileName: String, contentType: String, source: File,
                    uploadTransactionKey: String, uploadAttemptToken: String, uploadObjectIndex: Int,
                    onDurableCommit: () -> Unit,
                ): String = storeInternal(
                    uid = uid, fileName = fileName, contentType = contentType, tempFile = source,
                    uploadTransactionKey = uploadTransactionKey, uploadAttemptToken = uploadAttemptToken,
                    uploadObjectIndex = uploadObjectIndex, capacityAlreadyPending = true,
                    onDurableCommit = onDurableCommit,
                )
                override fun getDurableMetadata(path: String): FileMetadata? = getDurableMeta(path)
                override fun hasBackingData(metadata: FileMetadata): Boolean =
                    this@FileStore.hasBackingData(metadata)
                override fun rollbackUnpublished(paths: List<String>) =
                    this@FileStore.rollbackUnpublished(paths)
                override fun recordConsistencyFailure(failure: Throwable) =
                    this@FileStore.recordConsistencyFailure(failure)
            },
        )
        require(maxFileSize > 0L) { "maxFileSize must be positive" }
        require(largeFileThreshold in 1L..maxFileSize) {
            "largeFileThreshold must be positive and no larger than maxFileSize"
        }
        require(largeFileThreshold <= Int.MAX_VALUE.toLong()) {
            "largeFileThreshold must fit the RocksDB Java value-array budget"
        }
    }

    val isHealthy: Boolean get() = db != null && consistencyFailure == null
    val isRunning: Boolean get() = db != null

    internal val accountedStoredBytes: Long
        get() = synchronized(this) { capacityLedger.totalUsage.storedBytes }

    internal val accountedStoredFiles: Int
        get() = synchronized(this) { capacityLedger.totalUsage.storedFiles }

    internal fun accountedOwnerUsage(uid: String): FileStoreUsage = synchronized(this) {
        capacityLedger.ownerUsage(uid)
    }

    internal val accountedPendingBytes: Long
        get() = synchronized(this) { capacityLedger.totalCapacityUsage.pending.storedBytes }

    internal val accountedPendingFiles: Int
        get() = synchronized(this) { capacityLedger.totalCapacityUsage.pending.storedFiles }

    internal fun accountedOwnerCapacityUsage(uid: String): FileStoreCapacityUsage = synchronized(this) {
        capacityLedger.ownerCapacityUsage(uid)
    }

    @Synchronized
    fun init() {
        terminalLifecycleFailure?.let { throw it }
        if (db != null) return
        RocksDB.loadLibrary()
        val dbDirectory = File(dbPath)
        check((dbDirectory.isDirectory || dbDirectory.mkdirs()) && dbDirectory.isDirectory) {
            "Cannot create FileStore RocksDB directory: $dbPath"
        }
        val fsDirectory = File(fsRoot)
        check((fsDirectory.isDirectory || fsDirectory.mkdirs()) && fsDirectory.isDirectory) {
            "Cannot create FileStore filesystem directory: $fsRoot"
        }
        ManagedTempFiles.ensureDirectory(tmpRoot)

        val nativeOptions = mutableListOf<AutoCloseable>()
        val cfHandles = mutableListOf<ColumnFamilyHandle>()
        var openedDb: RocksDB? = null
        var openedRocksTier: RocksDbTier? = null
        var openedFsTier: FileSystemTier? = null
        var openedUsage = FileStorePersistentUsage.EMPTY
        var openedLifecycleToken: String? = null
        var startupFailure: Throwable? = null
        try {
            val metaOptions = ColumnFamilyOptions().also(nativeOptions::add)
            metaOptions.setWriteBufferSize(64 * 1024 * 1024)

            val dataOptions = ColumnFamilyOptions().also(nativeOptions::add)
            dataOptions
                .setWriteBufferSize(64 * 1024 * 1024)
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
                .setEnableBlobFiles(true)
                .setMinBlobSize(4 * 1024)
                .setBlobFileSize(4 * 1024 * 1024)
                .setBlobCompressionType(CompressionType.LZ4_COMPRESSION)
                .setEnableBlobGarbageCollection(true)
                .setBlobGarbageCollectionAgeCutoff(0.25)
                .setBlobGarbageCollectionForceThreshold(0.5)
                .setBlobCompactionReadaheadSize(1 * 1024 * 1024)
                .setPrepopulateBlobCache(PrepopulateBlobCache.PREPOPULATE_BLOB_FLUSH_ONLY)

            val uploadOptions = ColumnFamilyOptions().also(nativeOptions::add)
            uploadOptions.setWriteBufferSize(16 * 1024 * 1024)

            val dbOptions = DBOptions().also(nativeOptions::add)
            dbOptions
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setIncreaseParallelism(Runtime.getRuntime().availableProcessors())
                .setMaxOpenFiles(1000)
            val defaultOptions = ColumnFamilyOptions().also(nativeOptions::add)

            val cfDescriptors = listOf(
                ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, defaultOptions),
                ColumnFamilyDescriptor("meta".toByteArray(), metaOptions),
                ColumnFamilyDescriptor("data".toByteArray(), dataOptions),
                ColumnFamilyDescriptor("uploads".toByteArray(), uploadOptions),
            )
            val database = RocksDB.open(dbOptions, dbPath, cfDescriptors, cfHandles)
            openedDb = database
            check(cfHandles.size == EXPECTED_COLUMN_FAMILY_COUNT) {
                "FileStore column-family initialization was incomplete"
            }
            val retiredCrashResidueCount = ManagedTempFiles.cleanupCrashResidue(
                tmpRoot,
                managedTempFileDeleter,
            )
            if (retiredCrashResidueCount > 0) {
                logger.warn(
                    "FileStore startup retired {} managed temporary crash residues",
                    retiredCrashResidueCount,
                )
            }
            val openedDataCf = cfHandles[DATA_COLUMN_FAMILY_INDEX]
            openedRocksTier = RocksDbTier(database, openedDataCf)
            openedFsTier = FileSystemTier(database, openedDataCf, fsDirectory)
            openedUsage = persistentState.reconcile(
                dbInst = database,
                mCf = cfHandles[META_COLUMN_FAMILY_INDEX],
                dCf = openedDataCf,
                uCf = cfHandles[UPLOAD_COLUMN_FAMILY_INDEX],
                now = clock(),
            )
            capacityLedger.restore(openedUsage)
            openedLifecycleToken = UUID.randomUUID().toString()
            afterNativeOpen()
        } catch (error: Throwable) {
            startupFailure = error
        }

        // RocksDB 在打开后不再需要这些选项对象。其关闭属于启动的一部分：
        // 关闭失败会使存储保持未发布状态，并回滚每个已打开的原生句柄。
        val optionCleanupFailures = RuntimeFailureCollector()
        nativeOptions.asReversed().forEach { option ->
            optionCleanupFailures.capture { nativeResourceCloser.close(option) }
        }

        if (startupFailure == null && optionCleanupFailures.failureOrNull() == null) {
            try {
                checkNotNull(openedDb) { "FileStore database was not opened" }
                check(cfHandles.size == EXPECTED_COLUMN_FAMILY_COUNT) {
                    "FileStore column-family initialization was incomplete"
                }
                checkNotNull(openedRocksTier) { "FileStore RocksDB tier was not opened" }
                checkNotNull(openedFsTier) { "FileStore filesystem tier was not opened" }
                logger.info("FileStore opened at: {} (fs: {})", dbPath, fsRoot)
            } catch (error: Throwable) {
                startupFailure = error
            }
        }

        if (startupFailure != null || optionCleanupFailures.failureOrNull() != null) {
            val rollbackFailures = RuntimeFailureCollector()
            capacityLedger.clear()
            rollbackFailures.capture { openedRocksTier?.clearCache() }
            cfHandles.asReversed().forEach { handle ->
                rollbackFailures.capture { nativeResourceCloser.close(handle) }
            }
            rollbackFailures.capture { openedDb?.let { nativeResourceCloser.close(it) } }

            var failure = startupFailure
            optionCleanupFailures.failureOrNull()?.let { cleanupFailure ->
                failure = mergeRuntimeFailure(failure, cleanupFailure)
            }
            rollbackFailures.failureOrNull()?.let { cleanupFailure ->
                failure = mergeRuntimeFailure(failure, cleanupFailure)
            }
            val observedFailure = checkNotNull(failure) { "FileStore startup failed without a cause" }
            if (optionCleanupFailures.failureOrNull() != null || rollbackFailures.failureOrNull() != null) {
                terminalLifecycleFailure = observedFailure
            }
            throw observedFailure
        }

        val database = checkNotNull(openedDb) { "FileStore database was not opened" }
        check(cfHandles.size == EXPECTED_COLUMN_FAMILY_COUNT) {
            "FileStore column-family initialization was incomplete"
        }
        val openedRocks = checkNotNull(openedRocksTier) { "FileStore RocksDB tier was not opened" }
        val openedFilesystem = checkNotNull(openedFsTier) { "FileStore filesystem tier was not opened" }
        val initializedLifecycleToken = checkNotNull(openedLifecycleToken) {
            "FileStore lifecycle token was not initialized"
        }

        // 只在配置资源成功关闭之后发布。db 是 volatile
        // 生命周期标志，且被刻意最后写入，因此观察者绝不会看到部分存储。
        defaultCf = cfHandles[DEFAULT_COLUMN_FAMILY_INDEX]
        metaCf = cfHandles[META_COLUMN_FAMILY_INDEX]
        dataCf = cfHandles[DATA_COLUMN_FAMILY_INDEX]
        uploadCf = cfHandles[UPLOAD_COLUMN_FAMILY_INDEX]
        rocksDbTier = openedRocks
        fsTier = openedFilesystem
        uploadCoordinator.openLifecycle(initializedLifecycleToken)
        consistencyFailure = null
        db = database
    }

    @Synchronized
    internal fun beginUploadTransaction(
        uid: String,
        uploadId: String,
        payloadLength: Long,
        receiptLeaseExpiresAt: Long,
    ): BeginFileStoreUploadResult = uploadCoordinator.beginUploadTransaction(
        uid = uid,
        uploadId = uploadId,
        payloadLength = payloadLength,
        receiptLeaseExpiresAt = receiptLeaseExpiresAt,
    )

    @Synchronized
    internal fun bindUploadTransactionFingerprint(
        transaction: FileStoreUploadTransaction,
        requestFingerprint: String,
    ) = uploadCoordinator.bindUploadTransactionFingerprint(transaction, requestFingerprint)

    @Synchronized
    internal fun reserveUploadTransactionObject(
        transaction: FileStoreUploadTransaction,
        payloadLength: Long,
    ) = uploadCoordinator.reserveUploadTransactionObject(transaction, payloadLength)

    @Synchronized
    internal fun storeUploadTransactionObject(
        transaction: FileStoreUploadTransaction,
        fileName: String,
        contentType: String,
        source: File,
    ): String = uploadCoordinator.storeUploadTransactionObject(
        transaction = transaction,
        fileName = fileName,
        contentType = contentType,
        source = source,
    )

    @Synchronized
    internal fun completeUploadTransaction(
        transaction: FileStoreUploadTransaction,
        encodedReceipt: String,
    ): FileStoreUploadCompletion =
        uploadCoordinator.completeUploadTransaction(transaction, encodedReceipt)

    @Synchronized
    internal fun abortUploadTransaction(transaction: FileStoreUploadTransaction) =
        uploadCoordinator.abortUploadTransaction(transaction)

    @Synchronized
    internal fun resolveUploadReplayCandidate(
        candidate: FileStoreUploadReplayCandidate,
        requestFingerprint: String,
    ): FileStoreUploadReceipt =
        uploadCoordinator.resolveUploadReplayCandidate(candidate, requestFingerprint)

    @Synchronized
    internal fun releaseUploadDeliveryLease(deliveryLease: FileStoreUploadDeliveryLease) =
        uploadCoordinator.releaseUploadDeliveryLease(deliveryLease)

    @Synchronized
    internal fun uploadReceiptLease(
        path: String,
        now: Long = clock(),
    ): FileStoreUploadReceiptLease? = uploadCoordinator.uploadReceiptLease(path, now)

    @Synchronized
    fun store(uid: String, fileName: String, contentType: String, tempFile: File): String =
        storeInternal(
            uid = uid,
            fileName = fileName,
            contentType = contentType,
            tempFile = tempFile,
            uploadTransactionKey = null,
            uploadAttemptToken = null,
            uploadObjectIndex = null,
            capacityAlreadyPending = false,
        )

    private fun storeInternal(
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
        consistencyFailure?.let { throw it }
        return objectStorage.store(
            uid = uid,
            fileName = fileName,
            contentType = contentType,
            tempFile = tempFile,
            uploadTransactionKey = uploadTransactionKey,
            uploadAttemptToken = uploadAttemptToken,
            uploadObjectIndex = uploadObjectIndex,
            capacityAlreadyPending = capacityAlreadyPending,
            onDurableCommit = onDurableCommit,
        )
    }

    /**
     * 从 InputStream 存储文件（写入临时文件后调用 store）。
     */
    fun store(uid: String, fileName: String, contentType: String, inputStream: InputStream): String {
        val tmpFile = createTemporaryFile(FILE_STORE_TEMP_PREFIX, STAGING_TEMP_SUFFIX)
        var operationFailure: Throwable? = null
        var storedPath: String? = null
        try {
            tmpFile.outputStream().buffered().use { out ->
                val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    if (copied > maxFileSize - read) {
                        throw IllegalArgumentException("Attachment exceeds the $maxFileSize byte storage limit")
                    }
                    out.write(buffer, 0, read)
                    copied += read
                }
            }
            val path = store(uid, fileName, contentType, tmpFile)
            storedPath = path
            return path
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            try {
                retireTemporaryFile(tmpFile)
            } catch (retirementFailure: Throwable) {
                val first = operationFailure
                if (first == null) {
                    var terminalFailure = retirementFailure
                    storedPath?.let { path ->
                        try {
                            rollbackUnpublished(listOf(path))
                        } catch (rollbackFailure: Throwable) {
                            terminalFailure = mergeRuntimeFailure(terminalFailure, rollbackFailure)
                        }
                    }
                    throw terminalFailure
                }
                if (first !== retirementFailure) first.addSuppressed(retirementFailure)
            }
        }
    }

    /** 在 FileStore 显式受管的临时根下创建一个私有请求作用域文件。 */
    internal fun createTemporaryFile(prefix: String, suffix: String): File {
        check(db != null) { "FileStore not initialized" }
        return ManagedTempFiles.create(tmpRoot, prefix, suffix)
    }

    /** 证明一个请求作用域的受管临时文件已不存在，否则抛出带类型的残留失败。 */
    internal fun retireTemporaryFile(file: File) {
        ManagedTempFiles.retire(tmpRoot, file, managedTempFileDeleter)
    }

    internal val temporaryDirectory: File
        get() {
            check(db != null) { "FileStore not initialized" }
            return tmpRoot
        }

    /**
     * 移除由一个上传请求持久化、但从未发布给其调用方的对象。
     * RocksDB 元数据与载荷在一个批次中删除。文件系统载荷删除无法
     * 与 RocksDB 保持原子，因此两侧都会尝试，每个失败都保留用于诊断。
     */
    @Synchronized
    internal fun rollbackUnpublished(paths: List<String>) {
        val failures = RuntimeFailureCollector()
        paths.asReversed().distinct().forEach { path ->
            failures.capture { rollbackOneUnpublished(path) }
        }
        failures.throwIfAny()
    }

    /**
     * 按持久键顺序在 [afterPath] 之后扫描至多 [limit] 行元数据，并返回
     * 符合条件且 ACTIVE 的子集。统计扫描行数而非候选数，可防止大部分活跃的
     * 存储把每小时维护 pass 变成全键空间遍历。
     * 调用方在不持有 FileStore 原生资源监视器的情况下做引用 I/O，然后
     * 把不可变 token 交回 [retireIfExpiredAndUnchanged]。
     */
    @Synchronized
    override fun scanRetirementCandidates(
        uploadedAtOrBefore: Long,
        afterPath: String?,
        limit: Int,
    ): AttachmentRetirementScanPage {
        consistencyFailure?.let { throw it }
        require(uploadedAtOrBefore >= 0L) { "attachment retirement cutoff must not be negative" }
        require(limit in 1..MAX_RETIREMENT_SCAN_PAGE_SIZE) {
            "attachment retirement page size is out of range"
        }
        val dbInst = db ?: error("FileStore not initialized")
        val mCf = metaCf ?: error("FileStore not initialized")
        val result = ArrayList<AttachmentRetirementCandidate>(limit)
        val now = clock()
        var scanned = 0
        var lastScannedPath: String? = null
        var hasMore = false
        dbInst.newIterator(mCf).use { iterator ->
            if (afterPath == null) {
                iterator.seekToFirst()
            } else {
                val afterBytes = afterPath.toByteArray(StandardCharsets.UTF_8)
                iterator.seek(afterBytes)
                if (iterator.isValid && iterator.key().contentEquals(afterBytes)) iterator.next()
            }
            while (iterator.isValid && scanned < limit) {
                val metadata = json.decodeFromString(
                    FileMetadata.serializer(),
                    String(iterator.value(), StandardCharsets.UTF_8),
                )
                lastScannedPath = metadata.path
                scanned += 1
                if (
                    metadata.lifecycle == FileMetadataLifecycle.ACTIVE &&
                    metadata.uploadedAt <= uploadedAtOrBefore &&
                    !uploadCoordinator.hasActiveUploadReceiptLease(metadata, now)
                ) {
                    result += AttachmentRetirementCandidate(metadata.path, metadata.uploadedAt)
                }
                iterator.next()
            }
            hasMore = iterator.isValid
            iterator.status()
        }
        return AttachmentRetirementScanPage(
            candidates = result,
            lastScannedPath = lastScannedPath,
            hasMore = hasMore,
        )
    }

    /** 比较并回收，防止过期的扫描 token 删除另一行不同的持久数据。 */
    @Synchronized
    override fun retireIfExpiredAndUnchanged(
        candidate: AttachmentRetirementCandidate,
        uploadedAtOrBefore: Long,
    ): Boolean {
        consistencyFailure?.let { throw it }
        val current = getDurableMeta(candidate.path) ?: return false
        if (
            current.lifecycle != FileMetadataLifecycle.ACTIVE ||
            current.uploadedAt != candidate.uploadedAt ||
            current.uploadedAt > uploadedAtOrBefore
        ) {
            return false
        }
        if (uploadCoordinator.detachExpiredUploadReceiptOrReportActive(current, clock())) return false
        rollbackOneUnpublished(candidate.path)
        return true
    }

    // ── 读取 ──

    @Synchronized
    fun getMeta(path: String): FileMetadata? = getDurableMeta(path)
        ?.takeIf { it.lifecycle == FileMetadataLifecycle.ACTIVE }

    @Synchronized
    internal fun getDurableMeta(path: String): FileMetadata? {
        val dbInst = db ?: return null
        val mCf = metaCf ?: return null
        val bytes = dbInst.get(mCf, path.toByteArray(StandardCharsets.UTF_8)) ?: return null
        return json.decodeFromString(FileMetadata.serializer(), String(bytes, StandardCharsets.UTF_8))
    }

    /**
     * 向领域层暴露可用的公开附件描述符，隐藏 tier/storageKey 等存储实现细节。
     * RocksDB tier 的 meta/data 在同一 WriteBatch 中原子提交；文件系统 tier 还需
     * 核对实体文件及长度，避免只剩孤儿元数据的附件被消息服务判定为可发送。
     */
    override fun getAttachment(path: String): Attachment? = getMeta(path)?.let { meta ->
        if (meta.tier == StorageTier.FILESYSTEM) {
            val file = getFile(meta)
            if (file == null || file.length() != meta.size) return null
        }
        Attachment(
            path = meta.path,
            name = meta.originalName,
            contentType = meta.contentType,
            size = meta.size,
        )
    }

    override fun getOwnerUid(path: String): String? = getMeta(path)?.uid

    override fun isStaging(path: String): Boolean =
        getMeta(path)?.let { it.businessBoundAt == null } == true

    @Synchronized
    override fun markBusinessBound(paths: Collection<String>) {
        consistencyFailure?.let { throw it }
        if (paths.isEmpty()) return
        val dbInst = db ?: error("FileStore not initialized")
        val mCf = metaCf ?: error("FileStore not initialized")
        val now = clock()
        paths.distinct().sorted().forEach { path ->
            // 幂等修复可能在一个不活跃的历史引用已被回收之后到达。
            // 对现有对象而言发布是单调的；缺失已经是终结状态。
            val current = getDurableMeta(path) ?: return@forEach
            require(current.lifecycle == FileMetadataLifecycle.ACTIVE) {
                "Attachment is not active: $path"
            }
            if (current.businessBoundAt == null) {
                persistentState.putMetadata(
                    dbInst,
                    mCf,
                    path.toByteArray(StandardCharsets.UTF_8),
                    current.copy(businessBoundAt = maxOf(now, current.uploadedAt)),
                )
            }
        }
    }

    suspend fun streamTo(meta: FileMetadata, channel: ByteWriteChannel, range: ReadRange? = null) {
        when (meta.tier) {
            StorageTier.ROCKSDB -> rocksDbTier!!.streamTo(meta, channel, range)
            StorageTier.FILESYSTEM -> fsTier!!.streamTo(meta, channel, range)
        }
    }

    /**
     * 获取文件系统存储的实际 File 对象（仅 FILESYSTEM tier）。
     */
    fun getFile(meta: FileMetadata): File? {
        if (meta.tier != StorageTier.FILESYSTEM) return null
        val file = persistentState.resolveFilesystemEntry(meta.storageKey)
        return if (file.exists()) file else null
    }

    @Synchronized
    fun close() {
        terminalLifecycleFailure?.let { throw it }
        val openedDb = db ?: return
        val openedRocksTier = rocksDbTier
        val openedDefaultCf = defaultCf
        val openedMetaCf = metaCf
        val openedDataCf = dataCf
        val openedUploadCf = uploadCf
        db = null
        defaultCf = null
        metaCf = null
        dataCf = null
        uploadCf = null
        rocksDbTier = null
        fsTier = null
        uploadCoordinator.closeLifecycle()
        capacityLedger.clear()

        val failures = RuntimeFailureCollector()
        failures.capture { openedRocksTier?.clearCache() }
        failures.capture { openedUploadCf?.let { nativeResourceCloser.close(it) } }
        failures.capture { openedDataCf?.let { nativeResourceCloser.close(it) } }
        failures.capture { openedMetaCf?.let { nativeResourceCloser.close(it) } }
        failures.capture { openedDefaultCf?.let { nativeResourceCloser.close(it) } }
        failures.capture { nativeResourceCloser.close(openedDb) }
        if (failures.failureOrNull() == null) {
            failures.capture { logger.info("FileStore closed") }
        }
        failures.failureOrNull()?.let { failure ->
            terminalLifecycleFailure = failure
            throw failure
        }
    }

    private fun rollbackOneUnpublished(path: String) = objectStorage.rollbackOneUnpublished(path)

    @Synchronized
    internal fun hasBackingData(meta: FileMetadata): Boolean =
        db != null && objectStorage.hasBackingData(meta)

    private fun recordConsistencyFailure(failure: Throwable) {
        consistencyFailure = mergeRuntimeFailure(consistencyFailure, failure)
    }

    private companion object {
        const val DEFAULT_COPY_BUFFER_BYTES = 64 * 1024
        const val EXPECTED_COLUMN_FAMILY_COUNT = 4
        const val DEFAULT_COLUMN_FAMILY_INDEX = 0
        const val META_COLUMN_FAMILY_INDEX = 1
        const val DATA_COLUMN_FAMILY_INDEX = 2
        const val UPLOAD_COLUMN_FAMILY_INDEX = 3
        const val MAX_RETIREMENT_SCAN_PAGE_SIZE = 4_096
    }
}

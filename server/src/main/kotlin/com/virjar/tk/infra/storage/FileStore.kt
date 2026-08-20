package com.virjar.tk.infra.storage

import com.virjar.tk.model.Attachment
import com.virjar.tk.domain.attachment.AttachmentCatalog
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.rocksdb.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
data class FileMetadata(
    val path: String,
    val originalName: String,
    val contentType: String,
    val size: Long,
    val tier: StorageTier,
    val storageKey: String,
    val uploadedAt: Long,
    val uid: String,
)

enum class StorageTier { ROCKSDB, FILESYSTEM }

data class ReadRange(val start: Long, val end: Long)

/**
 * 多级文件存储：小文件（<=32MB）存 RocksDB，大文件存文件系统。
 * 元数据统一存在 RocksDB meta column family。
 */
class FileStore(
    private val dbPath: String,
    private val fsRoot: String,
    private val largeFileThreshold: Long = 32L * 1024 * 1024,
) : AttachmentCatalog {
    private val logger = LoggerFactory.getLogger("FileStore")
    private val json = Json { ignoreUnknownKeys = true }

    private var db: RocksDB? = null
    private var defaultCf: ColumnFamilyHandle? = null
    private var metaCf: ColumnFamilyHandle? = null
    private var dataCf: ColumnFamilyHandle? = null
    private var rocksDbTier: RocksDbTier? = null
    private var fsTier: FileSystemTier? = null

    val isHealthy: Boolean get() = db != null
    val isRunning: Boolean get() = db != null

    @Synchronized
    fun init() {
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

        val nativeOptions = mutableListOf<AutoCloseable>()
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
            )
            val cfHandles = mutableListOf<ColumnFamilyHandle>()
            var openedDb: RocksDB? = null
            try {
                val database = RocksDB.open(dbOptions, dbPath, cfDescriptors, cfHandles)
                openedDb = database
                check(cfHandles.size == 3) { "FileStore column-family initialization was incomplete" }
                val openedDefaultCf = cfHandles[0]
                val openedMetaCf = cfHandles[1]
                val openedDataCf = cfHandles[2]
                val openedRocksTier = RocksDbTier(database, openedDataCf)
                val openedFsTier = FileSystemTier(database, openedDataCf, fsDirectory)

                defaultCf = openedDefaultCf
                metaCf = openedMetaCf
                dataCf = openedDataCf
                rocksDbTier = openedRocksTier
                fsTier = openedFsTier
                db = database
            } catch (error: Throwable) {
                db = null
                defaultCf = null
                metaCf = null
                dataCf = null
                rocksDbTier = null
                fsTier = null
                cfHandles.asReversed().forEach { handle -> runCatching { handle.close() } }
                runCatching { openedDb?.close() }
                throw error
            }
        } finally {
            nativeOptions.asReversed().forEach { option -> runCatching { option.close() } }
        }

        logger.info("FileStore opened at: {} (fs: {})", dbPath, fsRoot)
    }

    // ── 写入 ──

    fun store(uid: String, fileName: String, contentType: String, tempFile: File): String {
        val dbInst = db ?: error("FileStore not initialized")
        val mCf = metaCf ?: error("FileStore not initialized")
        val size = tempFile.length()
        val safeName = sanitizeOriginalName(fileName)
        val ext = safeName.substringAfterLast('.', "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(12)
        val suffix = if (ext.isBlank()) "" else ".$ext"
        val path = "$uid/${UUID.randomUUID().toString().replace("-", "")}$suffix"
        val storageKey = UUID.randomUUID().toString().replace("-", "")
        val tier = if (size > largeFileThreshold) StorageTier.FILESYSTEM else StorageTier.ROCKSDB

        val meta = FileMetadata(
            path = path, originalName = safeName, contentType = contentType,
            size = size, tier = tier, storageKey = storageKey,
            uploadedAt = System.currentTimeMillis(), uid = uid,
        )
        writeMetaAndData(dbInst, mCf, path, meta, tempFile)
        logger.debug("File stored: {} ({} bytes, {})", path, size, tier)
        return path
    }

    /**
     * 从 InputStream 存储文件（写入临时文件后调用 store）。
     */
    fun store(uid: String, fileName: String, contentType: String, inputStream: InputStream): String {
        val tmpFile = File.createTempFile("tk-upload-", ".tmp")
        tmpFile.deleteOnExit()
        try {
            tmpFile.outputStream().buffered().use { out -> inputStream.copyTo(out) }
            return store(uid, fileName, contentType, tmpFile)
        } finally {
            tmpFile.delete()
        }
    }

    // ── 读取 ──

    fun getMeta(path: String): FileMetadata? {
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
        // 通过 FileSystemTier 的 resolveFile 逻辑重建路径
        val storageKey = meta.storageKey
        val level1 = if (storageKey.length >= 2) storageKey.substring(0, 2) else "00"
        val level2 = if (storageKey.length >= 4) storageKey.substring(2, 4) else "00"
        val file = File(File(File(fsRoot, level1), level2), "$storageKey.dat")
        return if (file.exists()) file else null
    }

    @Synchronized
    fun close() {
        val openedDb = db ?: return
        val openedRocksTier = rocksDbTier
        val openedDefaultCf = defaultCf
        val openedMetaCf = metaCf
        val openedDataCf = dataCf
        db = null
        defaultCf = null
        metaCf = null
        dataCf = null
        rocksDbTier = null
        fsTier = null

        var failure: Throwable? = null
        fun closePart(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
        }
        closePart { openedRocksTier?.clearCache() }
        closePart { openedDataCf?.close() }
        closePart { openedMetaCf?.close() }
        closePart { openedDefaultCf?.close() }
        closePart { openedDb.close() }
        logger.info("FileStore closed")
        failure?.let { throw it }
    }

    // ── 内部方法 ──

    private fun writeMetaAndData(
        dbInst: RocksDB, mCf: ColumnFamilyHandle,
        path: String, meta: FileMetadata, tempFile: File
    ) {
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        val metaJson = json.encodeToString(FileMetadata.serializer(), meta).toByteArray(StandardCharsets.UTF_8)

        if (meta.tier == StorageTier.FILESYSTEM) {
            fsTier!!.moveFrom(meta.storageKey, tempFile)
            dbInst.put(mCf, pathBytes, metaJson)
        } else {
            val data = tempFile.readBytes()
            WriteBatch().use { batch ->
                batch.put(mCf, pathBytes, metaJson)
                rocksDbTier!!.addToBatch(batch, meta, data)
                WriteOptions().use { options -> dbInst.write(options, batch) }
            }
            tempFile.delete()
        }
    }

    private fun sanitizeOriginalName(fileName: String): String = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001F]"), "_")
        .trim()
        .take(255)
        .ifBlank { "attachment" }
}

package com.virjar.tk.infra.storage

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
) {
    private val logger = LoggerFactory.getLogger("FileStore")
    private val json = Json { ignoreUnknownKeys = true }

    private val largeFileThreshold = 32L * 1024 * 1024 // 32MB

    private var db: RocksDB? = null
    private var metaCf: ColumnFamilyHandle? = null
    private var dataCf: ColumnFamilyHandle? = null
    private var rocksDbTier: RocksDbTier? = null
    private var fsTier: FileSystemTier? = null

    val isHealthy: Boolean get() = db != null
    val isRunning: Boolean get() = db != null

    fun init() {
        val metaOptions = ColumnFamilyOptions()
            .setWriteBufferSize(64 * 1024 * 1024)

        val dataOptions = ColumnFamilyOptions()
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

        val dbOptions = DBOptions()
            .setCreateIfMissing(true)
            .setCreateMissingColumnFamilies(true)
            .setIncreaseParallelism(Runtime.getRuntime().availableProcessors())
            .setMaxOpenFiles(1000)

        val cfDescriptors = listOf(
            ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, ColumnFamilyOptions()),
            ColumnFamilyDescriptor("meta".toByteArray(), metaOptions),
            ColumnFamilyDescriptor("data".toByteArray(), dataOptions),
        )
        val cfHandles = mutableListOf<ColumnFamilyHandle>()
        File(dbPath).mkdirs()
        db = RocksDB.open(dbOptions, dbPath, cfDescriptors, cfHandles)
        metaCf = cfHandles[1]
        dataCf = cfHandles[2]

        rocksDbTier = RocksDbTier(db!!, dataCf!!)
        fsTier = FileSystemTier(db!!, dataCf!!, File(fsRoot))

        logger.info("FileStore opened at: {} (fs: {})", dbPath, fsRoot)
    }

    // ── 写入 ──

    fun store(uid: String, fileName: String, contentType: String, tempFile: File): String {
        val dbInst = db ?: error("FileStore not initialized")
        val mCf = metaCf ?: error("FileStore not initialized")
        val size = tempFile.length()
        val ext = fileName.substringAfterLast('.', "")
        val path = "$uid/${UUID.randomUUID().toString().replace("-", "")}.$ext"
        val storageKey = UUID.randomUUID().toString().replace("-", "")
        val tier = if (size > largeFileThreshold) StorageTier.FILESYSTEM else StorageTier.ROCKSDB

        val meta = FileMetadata(
            path = path, originalName = fileName, contentType = contentType,
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

    fun resolveUrl(path: String): String = "/api/v1/files/$path"

    fun close() {
        rocksDbTier?.clearCache()
        metaCf?.close()
        dataCf?.close()
        db?.close()
        db = null
        metaCf = null
        dataCf = null
        rocksDbTier = null
        fsTier = null
        logger.info("FileStore closed")
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
                dbInst.write(WriteOptions(), batch)
            }
            tempFile.delete()
        }
    }
}

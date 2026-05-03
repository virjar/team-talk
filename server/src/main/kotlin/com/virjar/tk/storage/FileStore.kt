package com.virjar.tk.storage

import com.virjar.tk.env.Environment
import com.virjar.tk.env.ThreadIOGuard
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.rocksdb.*
import org.slf4j.LoggerFactory
import java.io.File
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
    val thumbnailPath: String? = null,
    val expireAt: Long? = null,
    val archived: Boolean = false,
)

enum class StorageTier { ROCKSDB, FILESYSTEM }

data class ReadRange(val start: Long, val end: Long)

object FileStore {

    private val logger = LoggerFactory.getLogger(FileStore::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private const val LARGE_FILE_THRESHOLD = 32L * 1024 * 1024 // 32MB

    private var db: RocksDB? = null
    private var metaCf: ColumnFamilyHandle? = null
    private var dataCf: ColumnFamilyHandle? = null
    private var rocksDbTier: RocksDbTier? = null
    private var fsTier: FileSystemTier? = null

    fun start(dbPath: String = Environment.fileStoreRocksdbDir.absolutePath, fsRoot: String = Environment.fileStoreFsDir.absolutePath) {
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

        logger.info("FileStore opened at: {}", dbPath)
    }

    // ── 写入 ──

    /**
     * 统一写入入口：接收临时文件，按实际大小自动路由到 RocksDB 或文件系统。
     * 调用后临时文件由本方法负责处理（小文件删除，大文件 rename）。
     */
    fun store(uid: String, fileName: String, contentType: String, tempFile: File): String {
        ThreadIOGuard.check("FileStore")
        val size = tempFile.length()
        val ext = fileName.substringAfterLast('.', "")
        val path = "${uid}/${UUID.randomUUID().toString().replace("-", "")}.${ext}"
        val storageKey = UUID.randomUUID().toString().replace("-", "")
        val tier = if (size > LARGE_FILE_THRESHOLD) StorageTier.FILESYSTEM else StorageTier.ROCKSDB

        val meta = FileMetadata(
            path = path, originalName = fileName, contentType = contentType,
            size = size, tier = tier, storageKey = storageKey,
            uploadedAt = System.currentTimeMillis(), uid = uid,
        )
        writeMetaAndData(path, meta, tempFile)
        logger.debug("File stored: {} ({} bytes, {})", path, size, tier)
        return path
    }

    /** 小数据直接写入（缩略图等） */
    fun storeBytes(uid: String, fileName: String, contentType: String, data: ByteArray): String {
        ThreadIOGuard.check("FileStore")
        val ext = fileName.substringAfterLast('.', "")
        val path = "${uid}/${UUID.randomUUID().toString().replace("-", "")}.${ext}"
        val storageKey = UUID.randomUUID().toString().replace("-", "")
        val meta = FileMetadata(
            path = path, originalName = fileName, contentType = contentType,
            size = data.size.toLong(), tier = StorageTier.ROCKSDB, storageKey = storageKey,
            uploadedAt = System.currentTimeMillis(), uid = uid,
        )
        val dbInst = db ?: error("FileStore not started")
        val mCf = metaCf ?: error("FileStore not started")
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        WriteBatch().use { batch ->
            batch.put(mCf, pathBytes, json.encodeToString(FileMetadata.serializer(), meta).toByteArray(StandardCharsets.UTF_8))
            rocksDbTier!!.addToBatch(batch, meta, data)
            dbInst.write(WriteOptions(), batch)
        }
        return path
    }

    // ── 读取 ──

    fun getMeta(path: String): FileMetadata? {
        ThreadIOGuard.check("FileStore")
        val dbInst = db ?: return null
        val mCf = metaCf ?: return null
        val bytes = dbInst.get(mCf, path.toByteArray(StandardCharsets.UTF_8)) ?: return null
        return json.decodeFromString(FileMetadata.serializer(), String(bytes, StandardCharsets.UTF_8))
    }

    /**
     * 流式写入到 channel。meta 存在则文件必须存在，否则抛 IllegalStateException（5xx）。
     */
    suspend fun streamTo(meta: FileMetadata, channel: ByteWriteChannel, range: ReadRange? = null) {
        ThreadIOGuard.check("FileStore")
        when (meta.tier) {
            StorageTier.ROCKSDB -> rocksDbTier!!.streamTo(meta, channel, range)
            StorageTier.FILESYSTEM -> fsTier!!.streamTo(meta, channel, range)
        }
    }

    // ── 删除 / 生命周期 ──

    fun delete(path: String): Boolean {
        ThreadIOGuard.check("FileStore")
        val dbInst = db ?: return false
        val mCf = metaCf ?: return false
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        val metaBytes = dbInst.get(mCf, pathBytes) ?: return false
        val meta = json.decodeFromString(FileMetadata.serializer(), String(metaBytes, StandardCharsets.UTF_8))

        when (meta.tier) {
            StorageTier.ROCKSDB -> {
                WriteBatch().use { batch ->
                    batch.delete(mCf, pathBytes)
                    rocksDbTier!!.addDeleteToBatch(batch, meta)
                    dbInst.write(WriteOptions(), batch)
                }
            }
            StorageTier.FILESYSTEM -> {
                fsTier!!.deleteData(meta)
                dbInst.delete(mCf, pathBytes)
            }
        }
        return true
    }

    fun markArchived(path: String) {
        ThreadIOGuard.check("FileStore")
        val dbInst = db ?: return
        val mCf = metaCf ?: return
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        val metaBytes = dbInst.get(mCf, pathBytes) ?: return
        val meta = json.decodeFromString(FileMetadata.serializer(), String(metaBytes, StandardCharsets.UTF_8))
        dbInst.put(mCf, pathBytes, json.encodeToString(FileMetadata.serializer(), meta.copy(archived = true)).toByteArray(StandardCharsets.UTF_8))
    }

    fun findArchivable(beforeEpoch: Long, limit: Int): List<FileMetadata> {
        ThreadIOGuard.check("FileStore")
        val dbInst = db ?: return emptyList()
        val mCf = metaCf ?: return emptyList()
        val result = mutableListOf<FileMetadata>()
        val iter = dbInst.newIterator(mCf)
        iter.seekToFirst()
        while (iter.isValid && result.size < limit) {
            val meta = json.decodeFromString(FileMetadata.serializer(), String(iter.value(), StandardCharsets.UTF_8))
            if (!meta.archived && meta.uploadedAt < beforeEpoch) result.add(meta)
            iter.next()
        }
        iter.close()
        return result
    }

    fun findDeletable(beforeEpoch: Long, limit: Int): List<FileMetadata> {
        ThreadIOGuard.check("FileStore")
        val dbInst = db ?: return emptyList()
        val mCf = metaCf ?: return emptyList()
        val result = mutableListOf<FileMetadata>()
        val iter = dbInst.newIterator(mCf)
        iter.seekToFirst()
        while (iter.isValid && result.size < limit) {
            val meta = json.decodeFromString(FileMetadata.serializer(), String(iter.value(), StandardCharsets.UTF_8))
            if (meta.archived || (meta.expireAt != null && meta.expireAt < beforeEpoch)) result.add(meta)
            iter.next()
        }
        iter.close()
        return result
    }

    val isHealthy: Boolean get() = db != null

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

    private fun writeMetaAndData(path: String, meta: FileMetadata, tempFile: File) {
        val dbInst = db ?: error("FileStore not started")
        val mCf = metaCf ?: error("FileStore not started")
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

    private fun readMeta(path: String): FileMetadata? {
        val dbInst = db ?: return null
        val mCf = metaCf ?: return null
        val bytes = dbInst.get(mCf, path.toByteArray(StandardCharsets.UTF_8)) ?: return null
        return json.decodeFromString(FileMetadata.serializer(), String(bytes, StandardCharsets.UTF_8))
    }
}

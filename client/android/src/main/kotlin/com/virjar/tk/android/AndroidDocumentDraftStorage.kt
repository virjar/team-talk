package com.virjar.tk.android

import android.content.Context
import android.util.AtomicFile
import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.navigation.feature.document.DocumentDraftPayload
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadRetryableException
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadStatus
import com.virjar.tk.app.navigation.feature.document.DocumentDraftRecordSource
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_MANIFEST_BYTES
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_RECORD_BYTES
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_RECORDS
import com.virjar.tk.app.navigation.feature.document.MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** 同步磁盘边界；[AndroidDocumentDraftPersistence] 会串行化每一次调用。 */
internal interface DocumentDraftStorage {
    fun read(
        ownerKey: DocumentDraftOwnerKey,
        consume: (DocumentDraftRecordSource) -> Unit,
    ): DocumentDraftReadStatus

    fun write(ownerKey: DocumentDraftOwnerKey, payload: DocumentDraftPayload): Boolean
    fun tombstone(ownerKey: DocumentDraftOwnerKey, recoveryKeys: Set<String>): Boolean
    fun delete(ownerKey: DocumentDraftOwnerKey): Boolean
    /** 崩溃安全的 owner 封存标记，在异步的显式账户删除操作返回之前写入。 */
    fun sealDeletion(ownerKey: DocumentDraftOwnerKey): Boolean
    fun clearAll(): Boolean
}

/** 供索引解码与顺序编码共用；返回 null 表示该代数据不可接受。 */
internal fun nextDocumentDraftRecordTotal(current: Long, nextRecordBytes: Int): Long? {
    if (current !in 0L..MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES ||
        nextRecordBytes !in 0..MAX_DOCUMENT_DRAFT_RECORD_BYTES
    ) return null
    val next = current + nextRecordBytes
    return next.takeIf { it <= MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES }
}

internal fun androidDocumentDraftStorage(context: Context): DocumentDraftStorage =
    AtomicFileDocumentDraftStorage(context.applicationContext)

/**
 * 崩溃安全的、有界的记录存储。
 *
 * 每个 tab/create 命令都会被独立编码并安装。小型的索引清单最后发布，因此即使较新的一代写入失败，
 * 完整的前一代仍然可读，且无需在内存中构造一个聚合体。显式取消是单独维护的单调账本，
 * 因此即使后续的清单写入失败或丢失，取消信息也能保留下来。
 */
private class AtomicFileDocumentDraftStorage(
    context: Context,
) : DocumentDraftStorage {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val ownerPreferences = context.getSharedPreferences(OWNER_PREFERENCES, Context.MODE_PRIVATE)
    private val ownerLock = Any()
    private var selectedOwnerHash: String? = null

    override fun read(
        ownerKey: DocumentDraftOwnerKey,
        consume: (DocumentDraftRecordSource) -> Unit,
    ): DocumentDraftReadStatus {
        if (!selectOwner(ownerKey)) return DocumentDraftReadStatus.RETRYABLE
        val ownerHash = ownerHash(ownerKey)
        val manifestBytes = when (val read = readAtomic(manifestFile(ownerHash), MAX_INDEX_BYTES)) {
            AtomicRead.Absent -> return DocumentDraftReadStatus.ABSENT
            is AtomicRead.Available -> read.bytes
            AtomicRead.Retryable -> return DocumentDraftReadStatus.RETRYABLE
            AtomicRead.Corrupt -> return retireCorruptOwner(ownerKey, ownerHash)
        }
        val index = try {
            decodeIndex(manifestBytes)
        } catch (_: Exception) {
            return retireCorruptOwner(ownerKey, ownerHash)
        }
        val tombstones = when (val ledger = readTombstones(ownerHash)) {
            is TombstoneLedgerRead.Available -> ledger.keys
            TombstoneLedgerRead.Retryable -> return DocumentDraftReadStatus.RETRYABLE
            TombstoneLedgerRead.Corrupt -> return retireCorruptOwner(ownerKey, ownerHash)
        }
        consume(object : DocumentDraftRecordSource {
            override val manifest: String = index.commonManifest
            override val tombstones: Set<String> = tombstones

            override fun recordByteCount(key: String): Long? =
                index.records[key]?.byteCount?.toLong()

            override fun readRecord(key: String): String? {
                val descriptor = index.records[key] ?: return null
                return readRecord(ownerHash, descriptor)
            }
        })
        return DocumentDraftReadStatus.AVAILABLE
    }

    override fun write(ownerKey: DocumentDraftOwnerKey, payload: DocumentDraftPayload): Boolean {
        if (!selectOwner(ownerKey)) return false
        val commonManifest = payload.manifest.toByteArray(Charsets.UTF_8)
        if (commonManifest.size > MAX_COMMON_MANIFEST_BYTES ||
            payload.records.size > MAX_RECORD_COUNT ||
            payload.activeRecoveryKeys.size > MAX_RECORD_COUNT
        ) return false
        val ownerHash = ownerHash(ownerKey)
        val oldTombstones = when (val ledger = readTombstones(ownerHash)) {
            is TombstoneLedgerRead.Available -> ledger.keys
            TombstoneLedgerRead.Retryable -> return false
            TombstoneLedgerRead.Corrupt -> {
                // 当前载荷是一份完整的替换代数据。在清理残留之前先对损坏的 owner 标记设防，
                // 然后重建全新的账本和索引。
                if (!reinitializeCorruptOwner(ownerKey, ownerHash)) return false
                emptySet()
            }
        }
        val oldIndex = readIndexOrNull(ownerHash)
        val previousDigests = oldIndex?.records?.values?.mapTo(mutableSetOf(), RecordDescriptor::digest)
            .orEmpty()
        val installedFiles = mutableListOf<File>()
        val descriptors = linkedMapOf<String, RecordDescriptor>()
        var totalRecordBytes = 0L
        for (record in payload.records) {
            val bytes = try {
                record.payload().toByteArray(Charsets.UTF_8)
            } catch (_: Exception) {
                cleanupNewRecords(installedFiles)
                return false
            }
            totalRecordBytes = nextDocumentDraftRecordTotal(totalRecordBytes, bytes.size) ?: run {
                cleanupNewRecords(installedFiles)
                return false
            }
            val digest = sha256Hex(bytes)
            val descriptor = RecordDescriptor(record.key, digest, bytes.size)
            val file = recordFile(ownerHash, digest)
            val existedAndValid = readRecordBytes(file, descriptor) != null
            if (!existedAndValid && !writeAtomic(AtomicFile(file), bytes)) {
                cleanupNewRecords(installedFiles)
                return false
            }
            if (!existedAndValid && digest !in previousDigests) installedFiles += file
            descriptors[record.key] = descriptor
        }
        val index = StorageIndex(
            commonManifest = payload.manifest,
            records = descriptors,
        )
        val encodedIndex = try {
            encodeIndex(index)
        } catch (_: Exception) {
            cleanupNewRecords(installedFiles)
            return false
        }
        if (!writeAtomic(AtomicFile(manifestFile(ownerHash)), encodedIndex)) {
            cleanupNewRecords(installedFiles)
            return false
        }
        cleanupUnreferencedRecords(ownerHash, descriptors.values.mapTo(mutableSetOf(), RecordDescriptor::digest))

        // 发布已经成功。在崩溃或清理失败之后保留多余的取消身份是安全的，因为恢复 ID 永远不会被复用。
        writeTombstones(ownerHash, oldTombstones.intersect(payload.activeRecoveryKeys))
        return true
    }

    override fun tombstone(
        ownerKey: DocumentDraftOwnerKey,
        recoveryKeys: Set<String>,
    ): Boolean {
        if (recoveryKeys.isEmpty()) return true
        if (recoveryKeys.size > MAX_RECORD_COUNT || recoveryKeys.any { !it.matches(RECORD_KEY_PATTERN) }) {
            return false
        }
        if (!selectOwner(ownerKey)) return false
        val ownerHash = ownerHash(ownerKey)
        return when (val ledger = readTombstones(ownerHash)) {
            is TombstoneLedgerRead.Available ->
                writeTombstones(ownerHash, ledger.keys + recoveryKeys)
            TombstoneLedgerRead.Retryable,
            TombstoneLedgerRead.Corrupt -> false
        }
    }

    override fun delete(ownerKey: DocumentDraftOwnerKey): Boolean {
        val ownerHash = ownerHash(ownerKey)
        val filesDeleted = deleteOwnerFiles(ownerHash)
        val ownerDeleted = synchronized(ownerLock) {
            val removed = if (ownerPreferences.getString(ACTIVE_OWNER_KEY, null) == ownerHash) {
                ownerPreferences.edit().remove(ACTIVE_OWNER_KEY).commit()
            } else {
                true
            }
            if (selectedOwnerHash == ownerHash) selectedOwnerHash = null
            removed
        }
        return filesDeleted && ownerDeleted
    }

    override fun sealDeletion(ownerKey: DocumentDraftOwnerKey): Boolean = synchronized(ownerLock) {
        val ownerHash = ownerHash(ownerKey)
        val sealed = if (ownerPreferences.getString(ACTIVE_OWNER_KEY, null) == ownerHash) {
            ownerPreferences.edit().remove(ACTIVE_OWNER_KEY).commit()
        } else {
            true
        }
        if (sealed && selectedOwnerHash == ownerHash) selectedOwnerHash = null
        sealed
    }

    override fun clearAll(): Boolean {
        val filesDeleted = clearDraftFiles()
        val ownerDeleted = synchronized(ownerLock) {
            val removed = try {
                ownerPreferences.edit().remove(ACTIVE_OWNER_KEY).commit()
            } catch (_: Exception) {
                false
            }
            selectedOwnerHash = null
            removed
        }
        return filesDeleted && ownerDeleted
    }

    /** 一次安装最多只为当前已认证的 owner key 保留草稿。 */
    private fun selectOwner(ownerKey: DocumentDraftOwnerKey): Boolean = synchronized(ownerLock) {
        val requested = ownerHash(ownerKey)
        if (selectedOwnerHash == requested) return@synchronized true
        val current = ownerPreferences.getString(ACTIVE_OWNER_KEY, null)
        // 删除失败后，没有对应已提交 owner 标记的孤儿字节是不可信的。
        if (current != requested && !clearDraftFiles()) return@synchronized false
        if (current != requested &&
            !ownerPreferences.edit().putString(ACTIVE_OWNER_KEY, requested).commit()
        ) return@synchronized false
        selectedOwnerHash = requested
        true
    }

    /** 必须先废弃损坏的取消账本，然后才能观察到任何旧的清单。 */
    private fun retireCorruptOwner(
        ownerKey: DocumentDraftOwnerKey,
        ownerHash: String,
    ): DocumentDraftReadStatus {
        val sealed = try {
            sealDeletion(ownerKey)
        } catch (_: Exception) {
            false
        }
        if (!sealed) return DocumentDraftReadStatus.RETRYABLE
        // 一旦持久的 owner 标记不存在，残留的字节就只是不受信任的孤儿数据，
        // 之后的 selectOwner() 会在发布另一个 owner 标记之前清除它们。
        deleteOwnerFiles(ownerHash)
        return DocumentDraftReadStatus.ABSENT
    }

    private fun reinitializeCorruptOwner(
        ownerKey: DocumentDraftOwnerKey,
        ownerHash: String,
    ): Boolean {
        val sealed = try {
            sealDeletion(ownerKey)
        } catch (_: Exception) {
            false
        }
        if (!sealed) return false
        deleteOwnerFiles(ownerHash)
        return selectOwner(ownerKey)
    }

    private fun ownerHash(ownerKey: DocumentDraftOwnerKey): String =
        AndroidDocumentDraftPersistence.draftFileName(ownerKey).removeSuffix(".json")

    private fun manifestFile(ownerHash: String): File = File(directory, "$ownerHash.manifest")
    private fun tombstoneFile(ownerHash: String): File = File(directory, "$ownerHash.tombstones")
    private fun recordFile(ownerHash: String, digest: String): File =
        File(directory, "$ownerHash.$digest.record")

    private fun readRecord(ownerHash: String, descriptor: RecordDescriptor): String? {
        val file = recordFile(ownerHash, descriptor.digest)
        val bytes = try {
            readRecordBytes(file, descriptor)
        } catch (failure: IOException) {
            throw DocumentDraftReadRetryableException(failure)
        }
        if (bytes != null) return bytes.toString(Charsets.UTF_8)
        // 缺失或损坏的记录会被隔离处理；常规恢复只会丢弃这一条记录身份。
        try {
            AtomicFile(file).delete()
        } catch (_: Exception) {
            // 尽力而为的清理不会改变隔离记录的结果。
        }
        return null
    }

    private fun readRecordBytes(file: File, descriptor: RecordDescriptor): ByteArray? {
        if (atomicReadableCandidateTooLarge(file, MAX_RECORD_BYTES)) return null
        val bytes = try {
            AtomicFile(file).readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        if (bytes.size != descriptor.byteCount || bytes.size > MAX_RECORD_BYTES) return null
        return bytes.takeIf { sha256Hex(it) == descriptor.digest }
    }

    private fun readIndexOrNull(ownerHash: String): StorageIndex? =
        when (val read = readAtomic(manifestFile(ownerHash), MAX_INDEX_BYTES)) {
            is AtomicRead.Available -> try {
                decodeIndex(read.bytes)
            } catch (_: Exception) {
                null
            }
            else -> null
        }

    private fun readTombstones(ownerHash: String): TombstoneLedgerRead =
        when (val read = readAtomic(tombstoneFile(ownerHash), MAX_TOMBSTONE_BYTES)) {
            AtomicRead.Absent -> TombstoneLedgerRead.Available(emptySet())
            is AtomicRead.Available -> try {
                TombstoneLedgerRead.Available(decodeTombstones(read.bytes))
            } catch (_: Exception) {
                TombstoneLedgerRead.Corrupt
            }
            AtomicRead.Retryable -> TombstoneLedgerRead.Retryable
            AtomicRead.Corrupt -> TombstoneLedgerRead.Corrupt
        }

    private fun writeTombstones(ownerHash: String, keys: Set<String>): Boolean {
        if (keys.isEmpty()) {
            return try {
                AtomicFile(tombstoneFile(ownerHash)).delete()
                true
            } catch (_: Exception) {
                false
            }
        }
        return try {
            writeAtomic(AtomicFile(tombstoneFile(ownerHash)), encodeTombstones(keys))
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanupNewRecords(files: List<File>) {
        files.forEach { file ->
            try {
                AtomicFile(file).delete()
            } catch (_: Exception) {
                // 旧的清单不会引用这些不可变文件。
            }
        }
    }

    private fun cleanupUnreferencedRecords(ownerHash: String, retainedDigests: Set<String>) {
        val prefix = "$ownerHash."
        directory.listFiles().orEmpty().forEach { file ->
            val baseName = file.name.removeSuffix(".bak").removeSuffix(".new")
            if (!baseName.startsWith(prefix) || !baseName.endsWith(".record")) return@forEach
            val digest = baseName.removePrefix(prefix).removeSuffix(".record")
            if (digest.matches(SHA256_PATTERN) && digest !in retainedDigests) {
                try {
                    AtomicFile(File(directory, baseName)).delete()
                } catch (_: Exception) {
                    // 未被引用的不可变记录可以安全地在之后的某次写入时回收。
                }
            }
        }
    }

    private fun deleteOwnerFiles(ownerHash: String): Boolean {
        if (!directory.exists()) return true
        var deleted = true
        directory.listFiles().orEmpty().forEach { file ->
            if (file.name.startsWith(ownerHash)) {
                try {
                    if (!file.delete() && file.exists()) deleted = false
                } catch (_: Exception) {
                    deleted = false
                }
            }
        }
        if (directory.exists() && directory.listFiles().isNullOrEmpty()) directory.delete()
        return deleted
    }

    private fun clearDraftFiles(): Boolean {
        if (!directory.exists()) return true
        var deleted = true
        directory.listFiles().orEmpty().forEach { file ->
            try {
                if (!file.delete() && file.exists()) deleted = false
            } catch (_: Exception) {
                deleted = false
            }
        }
        if (directory.exists() && !directory.delete()) deleted = false
        return deleted && !directory.exists()
    }

    private fun readAtomic(file: File, maxBytes: Int): AtomicRead = try {
        // 不要预先检查 baseFile：必须让 AtomicFile 有机会恢复存活的 .bak 文件。
        if (atomicReadableCandidateTooLarge(file, maxBytes)) {
            AtomicRead.Corrupt
        } else {
            val bytes = AtomicFile(file).readFully()
            if (bytes.size > maxBytes) AtomicRead.Corrupt else AtomicRead.Available(bytes)
        }
    } catch (_: FileNotFoundException) {
        AtomicRead.Absent
    } catch (_: Exception) {
        AtomicRead.Retryable
    }

    private fun atomicReadableCandidateTooLarge(file: File, maxBytes: Int): Boolean {
        // android.util.AtomicFile 会在打开 baseFile 之前恢复旧版 .bak 文件。检查那个确切的首选
        // 候选文件，以保留崩溃恢复能力，同时避免分配无上限的字节。
        val backup = File("${file.path}.bak")
        val candidate = backup.takeIf(File::exists) ?: file
        return candidate.exists() && candidate.length() > maxBytes
    }

    private fun writeAtomic(atomicFile: AtomicFile, bytes: ByteArray): Boolean {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return false
        var stream: FileOutputStream? = null
        return try {
            val output = atomicFile.startWrite()
            stream = output
            output.write(bytes)
            atomicFile.finishWrite(output)
            true
        } catch (_: Exception) {
            stream?.let { output ->
                try {
                    atomicFile.failWrite(output)
                } catch (_: Exception) {
                    // 原始失败由变更协调器负责上报。
                }
            }
            false
        }
    }
}

private sealed interface AtomicRead {
    data object Absent : AtomicRead
    data class Available(val bytes: ByteArray) : AtomicRead
    data object Retryable : AtomicRead
    data object Corrupt : AtomicRead
}

private sealed interface TombstoneLedgerRead {
    data class Available(val keys: Set<String>) : TombstoneLedgerRead
    data object Retryable : TombstoneLedgerRead
    data object Corrupt : TombstoneLedgerRead
}

private data class StorageIndex(
    val commonManifest: String,
    val records: Map<String, RecordDescriptor>,
)

private data class RecordDescriptor(
    val key: String,
    val digest: String,
    val byteCount: Int,
)

private fun encodeIndex(index: StorageIndex): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output ->
        val manifestBytes = index.commonManifest.toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= MAX_COMMON_MANIFEST_BYTES)
        require(index.records.size <= MAX_RECORD_COUNT)
        output.writeInt(INDEX_MAGIC)
        output.writeInt(INDEX_VERSION)
        output.writeInt(manifestBytes.size)
        output.write(manifestBytes)
        output.writeInt(index.records.size)
        index.records.values.forEach { descriptor ->
            output.writeBoundedKey(descriptor.key)
            output.write(hexToBytes(descriptor.digest))
            output.writeInt(descriptor.byteCount)
        }
    }
    bytes.toByteArray().also { require(it.size <= MAX_INDEX_BYTES) }
}

private fun decodeIndex(bytes: ByteArray): StorageIndex =
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == INDEX_MAGIC) { "Invalid document draft index" }
        require(input.readInt() == INDEX_VERSION) { "Unsupported document draft index" }
        val manifestSize = input.readInt()
        require(manifestSize in 0..MAX_COMMON_MANIFEST_BYTES) { "Invalid common manifest size" }
        val manifestBytes = ByteArray(manifestSize).also(input::readFully)
        val recordCount = input.readInt()
        require(recordCount in 0..MAX_RECORD_COUNT) { "Invalid draft record count" }
        val records = linkedMapOf<String, RecordDescriptor>()
        var totalRecordBytes = 0L
        repeat(recordCount) {
            val key = input.readBoundedKey()
            val digest = ByteArray(SHA256_BYTES).also(input::readFully).toHex()
            val byteCount = input.readInt()
            require(byteCount in 0..MAX_RECORD_BYTES) { "Invalid draft record size" }
            totalRecordBytes = requireNotNull(
                nextDocumentDraftRecordTotal(totalRecordBytes, byteCount),
            ) { "Document draft records exceed aggregate size limit" }
            require(records.put(key, RecordDescriptor(key, digest, byteCount)) == null) {
                "Duplicate draft record key"
            }
        }
        require(input.read() == -1) { "Trailing document draft index bytes" }
        StorageIndex(manifestBytes.toString(Charsets.UTF_8), records)
    }

private fun encodeTombstones(keys: Set<String>): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output ->
        require(keys.size <= MAX_RECORD_COUNT)
        output.writeInt(TOMBSTONE_MAGIC)
        output.writeInt(TOMBSTONE_VERSION)
        output.writeInt(keys.size)
        keys.sorted().forEach(output::writeBoundedKey)
    }
    bytes.toByteArray().also { require(it.size <= MAX_TOMBSTONE_BYTES) }
}

private fun decodeTombstones(bytes: ByteArray): Set<String> =
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == TOMBSTONE_MAGIC) { "Invalid draft tombstones" }
        require(input.readInt() == TOMBSTONE_VERSION) { "Unsupported draft tombstones" }
        val count = input.readInt()
        require(count in 0..MAX_RECORD_COUNT) { "Invalid draft tombstone count" }
        buildSet {
            repeat(count) { require(add(input.readBoundedKey())) { "Duplicate draft tombstone" } }
            require(input.read() == -1) { "Trailing draft tombstone bytes" }
        }
    }

private fun DataOutputStream.writeBoundedKey(key: String) {
    require(key.matches(RECORD_KEY_PATTERN)) { "Invalid draft record key" }
    val bytes = key.toByteArray(Charsets.US_ASCII)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readBoundedKey(): String {
    val size = readInt()
    require(size in 1..MAX_RECORD_KEY_BYTES) { "Invalid draft record key size" }
    return ByteArray(size).also(::readFully).toString(Charsets.US_ASCII).also { key ->
        require(key.matches(RECORD_KEY_PATTERN)) { "Invalid draft record key" }
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    return buildString(size * 2) {
        this@toHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private fun hexToBytes(value: String): ByteArray {
    require(value.matches(SHA256_PATTERN)) { "Invalid SHA-256 digest" }
    return ByteArray(SHA256_BYTES) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private const val DIRECTORY_NAME = "document-drafts-v2"
private const val OWNER_PREFERENCES = "teamtalk_document_drafts"
private const val ACTIVE_OWNER_KEY = "active_owner_hash"
private const val INDEX_MAGIC = 0x54544433
private const val INDEX_VERSION = 1
private const val TOMBSTONE_MAGIC = 0x54544331
private const val TOMBSTONE_VERSION = 1
private const val SHA256_BYTES = 32
private const val MAX_COMMON_MANIFEST_BYTES = MAX_DOCUMENT_DRAFT_MANIFEST_BYTES
private const val MAX_RECORD_BYTES = MAX_DOCUMENT_DRAFT_RECORD_BYTES
private const val MAX_RECORD_COUNT = MAX_DOCUMENT_DRAFT_RECORDS
private const val MAX_RECORD_KEY_BYTES = 128
private const val MAX_INDEX_BYTES =
    4 + 4 + 4 + MAX_COMMON_MANIFEST_BYTES + 4 +
        MAX_RECORD_COUNT * (4 + MAX_RECORD_KEY_BYTES + SHA256_BYTES + 4)
private const val MAX_TOMBSTONE_BYTES =
    4 + 4 + 4 + MAX_RECORD_COUNT * (4 + MAX_RECORD_KEY_BYTES)
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val RECORD_KEY_PATTERN = Regex("[a-z0-9-]{1,128}")

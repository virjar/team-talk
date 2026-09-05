package com.virjar.tk.desktop

import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.navigation.feature.document.DocumentDraftPayload
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_MANIFEST_BYTES
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_RECORD_BYTES
import com.virjar.tk.app.navigation.feature.document.MAX_DOCUMENT_DRAFT_RECORDS
import com.virjar.tk.app.navigation.feature.document.MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.util.Base64

internal fun desktopDocumentDraftStorage(
    dataDir: File,
    ownerKey: DocumentDraftOwnerKey,
): DesktopDocumentDraftStorage {
    val directory = JvmPrivateDataDirectory.openExisting(dataDir)
    val namespace = listOf(
        DesktopDocumentDraftPersistence.DRAFTS_DIRECTORY,
        DesktopDocumentDraftPersistence.STORAGE_VERSION_DIRECTORY,
        DesktopDocumentDraftPersistence.DEPLOYMENTS_DIRECTORY,
        ownerKey.deploymentFingerprint,
        DesktopDocumentDraftPersistence.OWNERS_DIRECTORY,
        desktopDocumentDraftOwnerNamespace(ownerKey),
    )
    val identity = namespace.fold(directory.root) { path, component -> path.resolve(component) }
        .normalize()
        .toString()
    return RecordDesktopDocumentDraftStorage(directory, namespace, identity)
}

/**
 * 每条记录不可变的按记录存储，外加两个小型原子控制文件。
 *
 * 发布顺序是刻意的：每条被引用的记录都要先于活跃 manifest 持久化；
 * 墓碑压缩只发生在该 manifest 之后。删除时先持久发布 DELETED manifest，然后才移除敏感的记录文件。
 */
private class RecordDesktopDocumentDraftStorage(
    private val dataDirectory: JvmPrivateDataDirectory,
    private val namespace: List<String>,
    override val coordinationIdentity: Any,
) : DesktopDocumentDraftStorage {
    private val manifestFile = dataDirectory.atomicTextFile(
        privateDirectories = namespace,
        fileName = DesktopDocumentDraftPersistence.DRAFT_FILE_NAME,
    )
    private val tombstoneFile = dataDirectory.atomicTextFile(
        privateDirectories = namespace,
        fileName = DesktopDocumentDraftPersistence.TOMBSTONE_FILE_NAME,
    )

    override fun read(
        limits: DesktopDocumentDraftLimits,
        consume: (DesktopDocumentDraftStoredRecordSource) -> Unit,
    ): DesktopDocumentDraftStorageReadStatus {
        val encoded = manifestFile.readText(MAX_STORAGE_MANIFEST_BYTES)
        if (encoded == null) {
            cleanupAbsentNamespace()
            return DesktopDocumentDraftStorageReadStatus.ABSENT
        }
        return when (val manifest = decodeStorageManifest(encoded, limits)) {
            DesktopStorageManifest.Deleted -> {
                retryDeletedRecordCleanup()
                DesktopDocumentDraftStorageReadStatus.ABSENT
            }
            is DesktopStorageManifest.Active -> {
                val tombstones = readTombstones(limits)
                consume(StoredRecordSource(manifest, tombstones, limits))
                retryActiveOrphanCleanup(manifest.records)
                DesktopDocumentDraftStorageReadStatus.AVAILABLE
            }
        }
    }

    override fun replace(payload: DocumentDraftPayload, limits: DesktopDocumentDraftLimits) {
        validatePayloadIdentities(payload, limits)
        val oldTombstones = readTombstones(limits)
        val originalFileNames = dataDirectory.listPrivateFileNames(namespace)
        val newlyInstalledRecordNames = linkedSetOf<String>()
        val descriptors = ArrayList<DesktopRecordDescriptor>(payload.records.size)
        var totalBytes = 0L
        var manifestPublished = false
        try {
            payload.records.forEach { record ->
                val content = try {
                    record.payload()
                } catch (rejected: IllegalArgumentException) {
                    throw DesktopDocumentDraftRejectedException(
                        "Document draft record payload was rejected before storage",
                        rejected,
                    )
                }
                val descriptor = desktopRecordDescriptor(
                    key = record.key,
                    content = content,
                    maxRecordBytes = limits.maxRecordBytes,
                )
                requireDesktopDraftPayload(
                    totalBytes <= limits.maxTotalRecordBytes - descriptor.byteCount.toLong(),
                ) {
                    "Document draft records exceed the total size limit"
                }
                totalBytes += descriptor.byteCount.toLong()
                val recordName = recordFileName(descriptor)
                if (recordName !in originalFileNames) newlyInstalledRecordNames += recordName
                installRecord(descriptor, content, limits)
                descriptors += descriptor
            }

            val manifest = DesktopStorageManifest.Active(payload.manifest, descriptors)
            manifestFile.replaceText(encodeStorageManifest(manifest, limits), MAX_STORAGE_MANIFEST_BYTES)
            manifestPublished = true

            // 在 manifest 之后、这次替换之前崩溃，只会多保留一些墓碑，这是失败关闭的。
            // 若在 manifest 发布前清除它们，可能复活已取消的工作。
            writeTombstones(oldTombstones.intersect(payload.activeRecoveryKeys), limits)
            cleanupUnreferencedRecords(
                retainedFileNames = descriptors.mapTo(hashSetOf(), ::recordFileName) + setOf(
                    DesktopDocumentDraftPersistence.DRAFT_FILE_NAME,
                    DesktopDocumentDraftPersistence.TOMBSTONE_FILE_NAME,
                ),
            )
        } catch (failure: Throwable) {
            if (!manifestPublished) cleanupRejectedRecordAttempt(newlyInstalledRecordNames, failure)
            throw failure
        }
    }

    override fun tombstone(recoveryKeys: Set<String>, limits: DesktopDocumentDraftLimits) {
        require(recoveryKeys.all { it.matches(DOCUMENT_DRAFT_RECORD_KEY) }) {
            "Invalid document draft tombstone"
        }
        val merged = readTombstones(limits) + recoveryKeys
        writeTombstones(merged, limits)
    }

    override fun delete(limits: DesktopDocumentDraftLimits) {
        manifestFile.replaceText(
            encodeStorageManifest(DesktopStorageManifest.Deleted, limits),
            MAX_STORAGE_MANIFEST_BYTES,
        )
        var cleanupFailure: Throwable? = null
        val fileNames = try {
            dataDirectory.listPrivateFileNames(namespace)
        } catch (failure: Throwable) {
            cleanupFailure = failure
            emptySet()
        }
        fileNames.asSequence()
            .filterNot { it == DesktopDocumentDraftPersistence.DRAFT_FILE_NAME }
            .forEach { fileName ->
                try {
                    dataDirectory.atomicTextFile(namespace, fileName).delete()
                } catch (failure: Throwable) {
                    val primary = cleanupFailure
                    if (primary == null) cleanupFailure = failure else primary.addSuppressed(failure)
                }
            }
        cleanupFailure?.let { throw it }
    }

    /** 逻辑删除之后的崩溃可能残留字节；此后每次打开都会重试物理擦除。 */
    private fun retryDeletedRecordCleanup() {
        val fileNames = try {
            dataDirectory.listPrivateFileNames(namespace)
        } catch (_: Exception) {
            return
        }
        fileNames.asSequence()
            .filterNot { it == DesktopDocumentDraftPersistence.DRAFT_FILE_NAME }
            .forEach { fileName ->
                try {
                    dataDirectory.atomicTextFile(namespace, fileName).delete()
                } catch (_: Exception) {
                    // DELETED 发布仍然权威；下次打开时重试。
                }
            }
    }

    /** 没有 manifest 就没有任何记录具备权威性；移除发布前崩溃留下的孤儿记录。 */
    private fun cleanupAbsentNamespace() {
        dataDirectory.listPrivateFileNames(namespace).forEach { fileName ->
            dataDirectory.atomicTextFile(namespace, fileName).delete()
        }
    }

    private fun retryActiveOrphanCleanup(records: List<DesktopRecordDescriptor>) {
        val retained = records.mapTo(hashSetOf(), ::recordFileName) + setOf(
            DesktopDocumentDraftPersistence.DRAFT_FILE_NAME,
            DesktopDocumentDraftPersistence.TOMBSTONE_FILE_NAME,
        )
        val fileNames = try {
            dataDirectory.listPrivateFileNames(namespace)
        } catch (_: Exception) {
            return
        }
        fileNames.forEach { fileName ->
            if (fileName !in retained) {
                try {
                    dataDirectory.atomicTextFile(namespace, fileName).delete()
                } catch (_: Exception) {
                    // 恢复仍可用；严格清理由下一次写入/删除重试。
                }
            }
        }
    }

    private fun installRecord(
        descriptor: DesktopRecordDescriptor,
        content: String,
        limits: DesktopDocumentDraftLimits,
    ) {
        val file = recordFile(descriptor)
        val validExisting = try {
            file.readText(limits.maxRecordBytes)?.let { existing ->
                val bytes = existing.toByteArray(StandardCharsets.UTF_8)
                bytes.size == descriptor.byteCount && desktopSha256Hex(bytes) == descriptor.digest
            } == true
        } catch (_: Exception) {
            false
        }
        if (!validExisting) file.replaceText(content, limits.maxRecordBytes)
    }

    /**
     * 账本损坏时有意采取失败关闭：把它当作空账本可能复活一个被显式取消的恢复身份。
     * 读取与替换保持可重试/被阻塞；显式删除无需读取账本，始终可以发布权威的 DELETED 封条。
     */
    private fun readTombstones(limits: DesktopDocumentDraftLimits): Set<String> =
        tombstoneFile.readText(MAX_TOMBSTONE_FILE_BYTES)
            ?.let { decodeTombstones(it, limits) }
            .orEmpty()

    private fun writeTombstones(keys: Set<String>, limits: DesktopDocumentDraftLimits) {
        require(keys.size <= limits.maxTombstones) { "Too many document draft tombstones" }
        require(keys.all { it.matches(DOCUMENT_DRAFT_RECORD_KEY) }) {
            "Invalid document draft tombstone"
        }
        tombstoneFile.replaceText(encodeTombstones(keys), MAX_TOMBSTONE_FILE_BYTES)
    }

    private fun cleanupUnreferencedRecords(
        retainedFileNames: Set<String>,
    ) {
        // 该 v3 namespace 由记录存储独占。移除每个未被引用的私有文件，
        // 同时也会回收进程崩溃留下的不可变或原子临时孤儿文件。
        dataDirectory.listPrivateFileNames(namespace).forEach { fileName ->
            if (fileName !in retainedFileNames) {
                dataDirectory.atomicTextFile(namespace, fileName).delete()
            }
        }
    }

    /** 被拒绝的发布前 payload 不能让不可变孤儿记录无限累积。 */
    private fun cleanupRejectedRecordAttempt(fileNames: Set<String>, primaryFailure: Throwable) {
        fileNames.forEach { fileName ->
            try {
                dataDirectory.atomicTextFile(namespace, fileName).delete()
            } catch (cleanupFailure: Throwable) {
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun recordFile(descriptor: DesktopRecordDescriptor) = dataDirectory.atomicTextFile(
        privateDirectories = namespace,
        fileName = recordFileName(descriptor),
    )

    private inner class StoredRecordSource(
        private val storedManifest: DesktopStorageManifest.Active,
        override val tombstones: Set<String>,
        private val limits: DesktopDocumentDraftLimits,
    ) : DesktopDocumentDraftStoredRecordSource {
        private val records = storedManifest.records.associateBy(DesktopRecordDescriptor::key)

        override val manifest: String = storedManifest.manifest

        override fun recordByteCount(key: String): Long? {
            if (!key.matches(DOCUMENT_DRAFT_RECORD_KEY)) return null
            return records[key]?.byteCount?.toLong()
        }

        override fun readRecord(key: String): String? {
            if (!key.matches(DOCUMENT_DRAFT_RECORD_KEY)) return null
            val descriptor = records[key] ?: return null
            val recordName = recordFileName(descriptor)
            val persistedPath = try {
                dataDirectory.requirePrivateFile(namespace, recordName).toPath()
            } catch (_: NoSuchFileException) {
                return null
            }
            // 永久超限/损坏的记录与摘要不匹配一样被隔离。路径归属、链接与权限失败
            // 仍会作为可重试的存储读取向上传播。
            if (Files.size(persistedPath) > limits.maxRecordBytes) return null
            val content = recordFile(descriptor).readText(limits.maxRecordBytes)
                ?: return null
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            return content.takeIf {
                bytes.size == descriptor.byteCount && desktopSha256Hex(bytes) == descriptor.digest
            }
        }
    }
}

private sealed class DesktopStorageManifest {
    data object Deleted : DesktopStorageManifest()

    data class Active(
        val manifest: String,
        val records: List<DesktopRecordDescriptor>,
    ) : DesktopStorageManifest()
}

private data class DesktopRecordDescriptor(
    val key: String,
    val byteCount: Int,
    val digest: String,
)

private data class DesktopUtf8Metadata(
    val byteCount: Int,
    val digest: String,
)

/** 用于内容寻址记录名的精确、分配受限的 UTF-8 元数据。 */
private fun desktopRecordDescriptor(
    key: String,
    content: String,
    maxRecordBytes: Long,
): DesktopRecordDescriptor = desktopUtf8Metadata(content, maxRecordBytes).let { metadata ->
    DesktopRecordDescriptor(
        key = key,
        byteCount = metadata.byteCount,
        digest = metadata.digest,
    )
}

private fun desktopUtf8Metadata(
    content: String,
    maxBytes: Long,
): DesktopUtf8Metadata {
    val digest = MessageDigest.getInstance("SHA-256")
    val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val input = CharBuffer.wrap(content)
    val output = ByteBuffer.allocate(UTF8_DIGEST_BUFFER_BYTES)
    var byteCount = 0L

    fun consumeOutput() {
        output.flip()
        val chunkBytes = output.remaining().toLong()
        requireDesktopDraftPayload(byteCount <= maxBytes - chunkBytes) {
            "Document draft text is too large"
        }
        byteCount += chunkBytes
        digest.update(output)
        output.clear()
    }

    while (true) {
        val result = encoder.encode(input, output, true)
        consumeOutput()
        if (result.isUnderflow) break
        if (result.isError) result.throwException()
        check(result.isOverflow) { "Unexpected UTF-8 encoder state" }
    }
    while (true) {
        val result = encoder.flush(output)
        consumeOutput()
        if (result.isUnderflow) break
        if (result.isError) result.throwException()
        check(result.isOverflow) { "Unexpected UTF-8 encoder state" }
    }
    return DesktopUtf8Metadata(
        byteCount = byteCount.toInt(),
        digest = desktopHex(digest.digest()),
    )
}

internal fun validatedDesktopDocumentDraftLimits(
    maxManifestBytes: Long,
    maxRecordBytes: Long,
    maxTotalRecordBytes: Long,
    maxRecords: Int,
    maxTombstones: Int,
): DesktopDocumentDraftLimits {
    require(maxManifestBytes in 1L..MAX_DOCUMENT_DRAFT_MANIFEST_BYTES.toLong()) {
        "Invalid document draft manifest limit"
    }
    require(maxRecordBytes in 1L..MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong()) {
        "Invalid document draft record limit"
    }
    require(
        maxTotalRecordBytes >= maxRecordBytes &&
            maxTotalRecordBytes <= MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES,
    ) {
        "Invalid document draft total record limit"
    }
    require(
        maxRecords == MAX_DOCUMENT_DRAFT_RECORDS && maxTombstones >= maxRecords,
    ) {
        "Invalid document draft identity limits"
    }
    return DesktopDocumentDraftLimits(
        maxManifestBytes = maxManifestBytes,
        maxRecordBytes = maxRecordBytes,
        maxTotalRecordBytes = maxTotalRecordBytes,
        maxRecords = maxRecords,
        maxTombstones = maxTombstones,
    )
}

private fun validatePayloadIdentities(
    payload: DocumentDraftPayload,
    limits: DesktopDocumentDraftLimits,
) {
    requireDesktopDraftPayload(payload.records.size <= limits.maxRecords) {
        "Too many document draft records"
    }
    requireDesktopDraftPayload(payload.activeRecoveryKeys.size <= limits.maxRecords) {
        "Too many active document draft recovery identities"
    }
    requireDesktopDraftPayload(
        payload.activeRecoveryKeys.all { it.matches(DOCUMENT_DRAFT_RECORD_KEY) },
    ) {
        "Invalid active document draft recovery identity"
    }
    requireDesktopDraftPayload(
        payload.records.map { it.key }.distinct().size == payload.records.size,
    ) {
        "Duplicate document draft record identity"
    }
    requireDesktopDraftPayload(payload.records.all { it.key in payload.activeRecoveryKeys }) {
        "Document draft records must belong to active recovery identities"
    }
}

private inline fun requireDesktopDraftPayload(
    condition: Boolean,
    lazyMessage: () -> String,
) {
    if (!condition) throw DesktopDocumentDraftRejectedException(lazyMessage())
}

private fun encodeStorageManifest(
    manifest: DesktopStorageManifest,
    limits: DesktopDocumentDraftLimits,
): String = when (manifest) {
    DesktopStorageManifest.Deleted -> "$DESKTOP_MANIFEST_HEADER\n$DELETED_STATE\n"
    is DesktopStorageManifest.Active -> {
        val manifestMetadata = desktopUtf8Metadata(manifest.manifest, limits.maxManifestBytes)
        val manifestBytes = manifest.manifest.toByteArray(StandardCharsets.UTF_8)
        check(manifestBytes.size == manifestMetadata.byteCount) { "UTF-8 manifest size changed" }
        requireDesktopDraftPayload(manifest.records.size <= limits.maxRecords) {
            "Too many document draft records"
        }
        buildString {
            append(DESKTOP_MANIFEST_HEADER).append('\n')
            append(ACTIVE_STATE).append('\n')
            append(manifestBytes.size).append('\n')
            append(manifestMetadata.digest).append('\n')
            append(Base64.getEncoder().encodeToString(manifestBytes)).append('\n')
            append(manifest.records.size).append('\n')
            manifest.records.forEach { descriptor ->
                append(descriptor.key).append('\t')
                append(descriptor.byteCount).append('\t')
                append(descriptor.digest).append('\n')
            }
        }.also { encoded ->
            requireDesktopDraftPayload(
                encoded.toByteArray(StandardCharsets.UTF_8).size.toLong() <=
                    MAX_STORAGE_MANIFEST_BYTES,
            ) {
                "Encoded document draft manifest is too large"
            }
        }
    }
}

private fun decodeStorageManifest(
    content: String,
    limits: DesktopDocumentDraftLimits,
): DesktopStorageManifest {
    require(content.endsWith('\n')) { "Invalid document draft manifest" }
    val lines = content.dropLast(1).split('\n')
    require(lines.size >= 2 && lines[0] == DESKTOP_MANIFEST_HEADER) {
        "Invalid document draft manifest"
    }
    if (lines[1] == DELETED_STATE) {
        require(lines.size == 2) { "Invalid deleted document draft manifest" }
        return DesktopStorageManifest.Deleted
    }
    require(lines[1] == ACTIVE_STATE && lines.size >= 6) {
        "Invalid active document draft manifest"
    }
    val manifestByteCount = lines[2].toIntOrNull()
        ?.takeIf { it >= 0 && it.toLong() <= limits.maxManifestBytes }
        ?: error("Invalid document draft manifest size")
    val manifestDigest = lines[3].also {
        require(it.matches(DESKTOP_SHA256_PATTERN)) { "Invalid document draft manifest digest" }
    }
    val manifestBytes = Base64.getDecoder().decode(lines[4])
    require(
        manifestBytes.size == manifestByteCount && desktopSha256Hex(manifestBytes) == manifestDigest,
    ) { "Document draft manifest digest mismatch" }
    val decodedManifest = manifestBytes.toString(StandardCharsets.UTF_8)
    require(decodedManifest.toByteArray(StandardCharsets.UTF_8).contentEquals(manifestBytes)) {
        "Document draft manifest is not valid UTF-8"
    }
    val recordCount = lines[5].toIntOrNull()
        ?.takeIf { it in 0..limits.maxRecords }
        ?: error("Invalid document draft record count")
    require(lines.size == 6 + recordCount) { "Invalid document draft record manifest" }
    var totalBytes = 0L
    val records = lines.drop(6).map { line ->
        val fields = line.split('\t')
        require(fields.size == 3) { "Invalid document draft record descriptor" }
        val key = fields[0].also {
            require(it.matches(DOCUMENT_DRAFT_RECORD_KEY)) { "Invalid document draft record identity" }
        }
        val byteCount = fields[1].toIntOrNull()
            ?.takeIf { it >= 0 && it.toLong() <= limits.maxRecordBytes }
            ?: error("Invalid document draft record size")
        require(totalBytes <= limits.maxTotalRecordBytes - byteCount.toLong()) {
            "Document draft records exceed the total size limit"
        }
        totalBytes += byteCount.toLong()
        val digest = fields[2].also {
            require(it.matches(DESKTOP_SHA256_PATTERN)) { "Invalid document draft record digest" }
        }
        DesktopRecordDescriptor(key, byteCount, digest)
    }
    require(records.map(DesktopRecordDescriptor::key).distinct().size == records.size) {
        "Duplicate document draft record identity"
    }
    return DesktopStorageManifest.Active(decodedManifest, records)
}

private fun encodeTombstones(keys: Set<String>): String = buildString {
    append(DESKTOP_TOMBSTONE_HEADER).append('\n')
    append(keys.size).append('\n')
    keys.sorted().forEach { append(it).append('\n') }
}

private fun decodeTombstones(
    content: String,
    limits: DesktopDocumentDraftLimits,
): Set<String> {
    require(content.endsWith('\n')) { "Invalid document draft tombstones" }
    val lines = content.dropLast(1).split('\n')
    require(lines.size >= 2 && lines[0] == DESKTOP_TOMBSTONE_HEADER) {
        "Invalid document draft tombstones"
    }
    val count = lines[1].toIntOrNull()
        ?.takeIf { it in 0..limits.maxTombstones }
        ?: error("Invalid document draft tombstone count")
    require(lines.size == count + 2) { "Invalid document draft tombstones" }
    val keys = lines.drop(2)
    require(keys.distinct().size == keys.size && keys.all { it.matches(DOCUMENT_DRAFT_RECORD_KEY) }) {
        "Invalid document draft tombstone identity"
    }
    return keys.toSet()
}

private fun recordFileName(descriptor: DesktopRecordDescriptor): String {
    require(descriptor.key.matches(DOCUMENT_DRAFT_RECORD_KEY)) {
        "Invalid document draft record identity"
    }
    require(descriptor.digest.matches(DESKTOP_SHA256_PATTERN)) {
        "Invalid document draft record digest"
    }
    val keyDigest = desktopSha256Hex(descriptor.key.toByteArray(StandardCharsets.UTF_8))
    return "$RECORD_FILE_PREFIX$keyDigest-${descriptor.digest}$RECORD_FILE_SUFFIX"
}

private fun desktopSha256Hex(bytes: ByteArray): String =
    desktopHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun desktopHex(bytes: ByteArray): String {
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private const val DESKTOP_MANIFEST_HEADER = "TEAMTALK_DOCUMENT_DRAFT_RECORDS_V3"
private const val DESKTOP_TOMBSTONE_HEADER = "TEAMTALK_DOCUMENT_DRAFT_TOMBSTONES_V1"
private const val ACTIVE_STATE = "ACTIVE"
private const val DELETED_STATE = "DELETED"
private const val RECORD_FILE_PREFIX = "record-"
private const val RECORD_FILE_SUFFIX = ".json"
private const val MAX_STORAGE_MANIFEST_BYTES = 8L * 1024L * 1024L
private const val MAX_TOMBSTONE_FILE_BYTES = 2L * 1024L * 1024L
private const val UTF8_DIGEST_BUFFER_BYTES = 64 * 1024
internal val DOCUMENT_DRAFT_RECORD_KEY = Regex("[a-z0-9-]{1,128}")
private val DESKTOP_SHA256_PATTERN = Regex("[0-9a-f]{64}")

internal fun desktopDocumentDraftOwnerNamespace(ownerKey: DocumentDraftOwnerKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(
        buildString {
            append("teamtalk-desktop-document-draft-owner-v2\u0000")
            append(ownerKey.deploymentFingerprint)
            append('\u0000')
            append(ownerKey.datasetId)
            append('\u0000')
            append(ownerKey.uid)
        }
            .toByteArray(StandardCharsets.UTF_8),
    )
    val hex = "0123456789abcdef"
    return buildString(2 + digest.size * 2) {
        append("u-")
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

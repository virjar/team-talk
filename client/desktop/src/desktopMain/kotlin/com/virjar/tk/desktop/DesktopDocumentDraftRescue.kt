package com.virjar.tk.desktop

import com.virjar.tk.app.navigation.feature.document.*
import com.virjar.tk.shared.client.*
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

@Serializable
internal data class DesktopDocumentDraftRescuePreview(
    val root: String,
    val database: String,
    val owner: LocalCacheDiagnosticOwner,
    val recordKey: String,
    val documentId: String?,
    val spaceId: String,
    val baseRevision: Long?,
    val creating: Boolean,
    val markdownCharacters: Int,
    val attachmentCount: Int,
    val manifestSha256: String,
    val targetStateSha256: String,
    val consequence: String = "Restore one unsaved document tab with its original identity and revision. " +
        "Opening the client does not save it. Saving requires an explicit action and current server validation; " +
        "remote changes can cause a revision conflict. No pending document operation or unfinished attachment upload is restored. " +
        "The archive and original files remain unchanged",
)

@Serializable
internal data class DesktopDocumentDraftRescueRecords(
    val owner: LocalCacheDiagnosticOwner,
    val manifestSha256: String,
    val recordKeys: List<String>,
    val limitation: String = "Unretired tab identities only. Preview validates the selected record and the empty target; listing does not prove recoverability",
)

/** Reuses the product's record reader/writer and app codec without starting a UI or client session. */
internal object DesktopDocumentDraftRescue {
    fun listRecords(archive: File): DesktopDocumentDraftRescueRecords =
        readDocumentSource(archive) { source, owner, sha ->
            DesktopDocumentDraftRescueRecords(owner, sha, DocumentDraftRescue.recordKeys(source))
        }

    fun preview(root: File, database: String, archive: File, recordKey: String): DesktopDocumentDraftRescuePreview {
        val source = readSource(archive, recordKey)
        return LocalCacheDocumentDraftRescueAccess.withTarget(root, database, source.owner) { directory ->
            runCatching { source.preview(directory, database, emptyTargetState(directory, source.owner)) }
        }.getOrThrow()
    }

    fun importDraft(
        root: File, database: String, archive: File, recordKey: String,
        expectedManifestSha256: String, expectedTargetStateSha256: String,
    ): DesktopDocumentDraftRescuePreview {
        val source = readSource(archive, recordKey, expectedManifestSha256)
        return LocalCacheDocumentDraftRescueAccess.withTarget(root, database, source.owner) { directory ->
            runCatching {
                val state = emptyTargetState(directory, source.owner)
                checkRescue(state == expectedTargetStateSha256, "TARGET_DOCUMENT_DRAFTS_CHANGED")
                checkRescue(LocalCacheArchive.verify(archive).manifestSha256 == expectedManifestSha256, "ARCHIVE_CHANGED")
                // Only an empty namespace is admitted. The existing writer publishes immutable records
                // before its atomic manifest; no normal target read may clean up unreviewed files.
                desktopDocumentDraftStorage(directory.root.toFile(), source.owner.draftOwner())
                    .replace(source.prepared.payload, rescueLimits)
                source.preview(directory, database, state)
            }
        }.getOrThrow()
    }

    private fun readSource(archive: File, recordKey: String, expected: String? = null): Source =
        readDocumentSource(archive, expected) { source, owner, sha ->
            Source(owner, sha, DocumentDraftRescue.prepare(source, recordKey))
        }

    private fun <T> readDocumentSource(
        archive: File, expected: String? = null,
        read: (DocumentDraftRecordSource, LocalCacheDiagnosticOwner, String) -> T,
    ): T =
        LocalCacheDocumentDraftRescueAccess.withSourceCopy(archive, expected) { root, owner, sha ->
            runCatching {
                var result: Result<T>? = null
                val status = desktopDocumentDraftStorage(root, owner.draftOwner()).read(rescueLimits) { stored ->
                    val source = object : DocumentDraftRecordSource {
                        override val manifest = stored.manifest
                        override val tombstones = stored.tombstones
                        override fun recordByteCount(key: String) = stored.recordByteCount(key)
                        override fun readRecord(key: String) = stored.readRecord(key)
                    }
                    result = runCatching { read(source, owner, sha) }
                }
                checkRescue(status == DesktopDocumentDraftStorageReadStatus.AVAILABLE && result != null,
                    "SOURCE_DOCUMENT_DRAFT_UNAVAILABLE")
                checkNotNull(result).getOrThrow()
            }
        }.getOrThrow()

    private data class Source(
        val owner: LocalCacheDiagnosticOwner,
        val manifestSha256: String,
        val prepared: DocumentDraftRescuePrepared,
    ) {
        fun preview(directory: JvmPrivateDataDirectory, database: String, targetState: String): DesktopDocumentDraftRescuePreview {
            val metadata = prepared.metadata
            return DesktopDocumentDraftRescuePreview(directory.root.toString(), database, owner, metadata.recordKey,
                metadata.documentId, metadata.spaceId, metadata.baseRevision, metadata.creating,
                metadata.markdownCharacters, metadata.attachmentCount, manifestSha256, targetState)
        }
    }
}

private fun emptyTargetState(directory: JvmPrivateDataDirectory, owner: LocalCacheDiagnosticOwner): String {
    val namespace = DocumentDraftStoragePaths.jvmDirectories(owner.deploymentFingerprint, owner.datasetId, owner.uid)
    val names = directory.listPrivateFileNames(namespace)
    val state = when {
        names.isEmpty() -> "ABSENT"
        names == setOf(DesktopDocumentDraftPersistence.DRAFT_FILE_NAME) -> {
            val manifest = directory.atomicTextFile(namespace, DesktopDocumentDraftPersistence.DRAFT_FILE_NAME).readText(128)
            checkRescue(manifest == "TEAMTALK_DOCUMENT_DRAFT_RECORDS_V3\nDELETED\n", "TARGET_DOCUMENT_DRAFTS_NOT_EMPTY")
            "DELETED"
        }
        else -> throw DesktopDocumentDraftRescueFailure("TARGET_DOCUMENT_DRAFTS_NOT_EMPTY")
    }
    val bytes = "teamtalk-document-rescue-target-v1\u0000${namespace.joinToString("/")}\u0000$state".toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun LocalCacheDiagnosticOwner.draftOwner() = DocumentDraftOwnerKey(deploymentFingerprint, datasetId, uid)

internal class DesktopDocumentDraftRescueFailure(val code: String) : IllegalStateException(code)

private fun checkRescue(condition: Boolean, code: String) {
    if (!condition) throw DesktopDocumentDraftRescueFailure(code)
}

private val rescueLimits = DesktopDocumentDraftLimits(MAX_DOCUMENT_DRAFT_MANIFEST_BYTES.toLong(),
    MAX_DOCUMENT_DRAFT_RECORD_BYTES.toLong(), MAX_TOTAL_DOCUMENT_DRAFT_RECORD_BYTES, MAX_DOCUMENT_DRAFT_RECORDS, 8192)

package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class LocalCacheChatDraftRescuePreview(
    val root: String,
    val database: String,
    val owner: LocalCacheDiagnosticOwner,
    val chatId: String,
    val sourceDatabase: String,
    val manifestSha256: String,
    val composerRevision: Long,
    val markdownCharacters: Int,
    val attachmentCount: Int,
    val sourceBytes: Long,
    val consequence: String,
)

@Serializable
data class LocalCacheChatDraftRescueReport(
    val preview: LocalCacheChatDraftRescuePreview,
    val installedRevision: Long,
    val attachmentRetriesRequired: Int,
)

/** Offline import of one complete composer, never a replay of the archived command queues. */
object LocalCacheChatDraftRescue {
    fun preview(root: File, databasePath: String, archive: File, sourceDatabase: String, chatId: String): LocalCacheChatDraftRescuePreview =
        rescueGuarded {
            val candidate = readChatDraftRescueSource(archive, sourceDatabase, chatId)
            withCacheRescueTarget(root, databasePath, candidate.owner) { target ->
                val revision = target.checkEmptyComposer(candidate.snapshot.chatId, candidate.uploads)
                checkRescueSourceDestination(target.directory, candidate.sourceFiles())
                val prepared = prepareRescuedComposer(candidate, Math.addExact(revision, 1))
                target.checkCapacity(candidate, prepared.bytes.size, prepared.sync.encodeToByteArray().size)
                report(target, databasePath, candidate, revision)
            }
        }

    fun importDraft(
        root: File, databasePath: String, archive: File, sourceDatabase: String, chatId: String,
        expectedManifestSha256: String, expectedComposerRevision: Long,
    ): LocalCacheChatDraftRescueReport = rescueGuarded {
        if (expectedComposerRevision < 0) rescueFailure("TARGET_COMPOSER_CHANGED")
        val candidate = readChatDraftRescueSource(archive, sourceDatabase, chatId, expectedManifestSha256)
        withCacheRescueTarget(root, databasePath, candidate.owner) { target ->
            val revision = target.composerRevision()
            if (revision != expectedComposerRevision) rescueFailure("TARGET_COMPOSER_CHANGED")
            target.checkEmptyComposer(candidate.snapshot.chatId, candidate.uploads)
            checkRescueSourceDestination(target.directory, candidate.sourceFiles())
            val next = Math.addExact(revision, 1)
            val prepared = prepareRescuedComposer(candidate, next)
            target.checkCapacity(candidate, prepared.bytes.size, prepared.sync.encodeToByteArray().size)
            // Files precede SQL publication. Failure can leave only verified unreferenced sources;
            // a retry reuses identical blobs and never overwrites a different source identity.
            copyRescueSources(archiveRoot(archive), target.directory, candidate.sourceFiles())
            if (LocalCacheArchive.verify(archive).manifestSha256 != expectedManifestSha256)
                rescueFailure("ARCHIVE_CHANGED")
            target.install(prepared.snapshot, prepared.bytes, prepared.sync, prepared.jobs)
            target.commit()
            LocalCacheChatDraftRescueReport(report(target, databasePath, candidate, revision), next, prepared.jobs.size)
        }
    }

    private fun report(target: CacheRescueTarget, database: String, source: ChatDraftRescueSource, revision: Long) =
        LocalCacheChatDraftRescuePreview(target.directory.root.toString(), database, source.owner, source.snapshot.chatId,
            source.sourceDatabase, source.manifestSha256, revision, source.snapshot.markdown.length,
            MarkdownAssetPolicy.recoveryReferences(source.snapshot.markdown).mapNotNull { it.assetId }.distinct().size,
            source.sources.sumOf { it.bytes },
            "Restore only this chat draft as a conflict requiring a fresh server draft read; locally sourced attachments require explicit retry. " +
                "No message or archived command is sent. The archive, quarantines and independent document drafts remain unchanged")
}

private data class RescuedComposer(val snapshot: ChatDraftSnapshot, val bytes: ByteArray, val sync: String, val jobs: List<ChatAssetUpload>)

private fun prepareRescuedComposer(candidate: ChatDraftRescueSource, revision: Long): RescuedComposer {
    val uploadIds = candidate.uploads.mapTo(hashSetOf()) { it.assetId }
    val snapshot = candidate.snapshot.copy(
        revision = revision,
        // Non-null prevents hydration from replacing a restored plain-text composer with its legacy mirror.
        sharedRevision = 0,
        assets = candidate.snapshot.assets.filter { it.assetId !in uploadIds },
        pendingAssetIds = (candidate.snapshot.pendingAssetIds + uploadIds).distinct(),
    )
    val jobs = candidate.uploads.map { it.copy(state = ChatAssetUploadState.FAILED, asset = null,
        nextAttemptAt = 0, failure = "救援的附件待确认，请重试上传") }
    val json = Json { encodeDefaults = true }
    return RescuedComposer(snapshot, json.encodeToString(snapshot).encodeToByteArray(),
        json.encodeToString(StoredChatDraftSyncRecord(chatId = snapshot.chatId, localRevision = revision,
            dirty = true, conflict = true, stale = true)), jobs)
}

private fun ChatDraftRescueSource.sourceFiles() = CacheRescueSources(owner, uploads, sources)

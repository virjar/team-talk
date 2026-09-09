package com.virjar.tk.shared.client

import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.shared.repository.chatAssetSpoolDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.Path
import java.util.UUID

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

internal class LocalCacheChatDraftRescueFailure(reason: String) :
    IllegalStateException("Local cache draft rescue failed: $reason")
internal fun rescueFailure(reason: String): Nothing = throw LocalCacheChatDraftRescueFailure(reason)

/** Offline import of one complete composer, never a replay of the archived command queues. */
object LocalCacheChatDraftRescue {
    fun preview(root: File, databasePath: String, archive: File, sourceDatabase: String, chatId: String): LocalCacheChatDraftRescuePreview =
        rescueGuarded {
            val candidate = readChatDraftRescueSource(archive, sourceDatabase, chatId)
            withChatDraftRescueTarget(root, databasePath, candidate.owner) { target ->
                val revision = target.checkEmptyComposer(candidate)
                checkRescueSourceDestination(target.directory, candidate)
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
        withChatDraftRescueTarget(root, databasePath, candidate.owner) { target ->
            val revision = target.composerRevision()
            if (revision != expectedComposerRevision) rescueFailure("TARGET_COMPOSER_CHANGED")
            target.checkEmptyComposer(candidate)
            checkRescueSourceDestination(target.directory, candidate)
            val next = Math.addExact(revision, 1)
            val prepared = prepareRescuedComposer(candidate, next)
            target.checkCapacity(candidate, prepared.bytes.size, prepared.sync.encodeToByteArray().size)
            // Files precede SQL publication. Failure can leave only verified unreferenced sources;
            // a retry reuses identical blobs and never overwrites a different source identity.
            copyRescueSources(archiveRoot(archive), target.directory, candidate)
            if (LocalCacheArchive.verify(archive).manifestSha256 != expectedManifestSha256)
                rescueFailure("ARCHIVE_CHANGED")
            target.install(prepared.snapshot, prepared.bytes, prepared.sync, prepared.jobs)
            target.commit()
            LocalCacheChatDraftRescueReport(report(target, databasePath, candidate, revision), next, prepared.jobs.size)
        }
    }

    private fun report(target: ChatDraftRescueTarget, database: String, source: ChatDraftRescueSource, revision: Long) =
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

private fun rescueSpool(source: ChatDraftRescueSource) = chatAssetSpoolDirectories(
    AccountDataOwner(source.owner.deploymentFingerprint, source.owner.datasetId, source.owner.uid))

private fun checkRescueSourceDestination(directory: JvmPrivateDataDirectory, candidate: ChatDraftRescueSource) {
    if (candidate.sources.isEmpty()) return
    val components = rescueSpool(candidate)
    val relative = components.joinToString("/")
    val plan = LocalCacheArchivePlan(candidate.owner, listOf(ArchiveScopePlan(relative, "CHAT_SOURCES", ArchiveScopeKind.DIRECTORY)))
    val current = captureArchiveInventory(directory.root, plan)
    val incoming = candidate.sources.associateBy { it.path.substringAfterLast('/') }
    val expectedIds = candidate.uploads.mapTo(hashSetOf()) { it.sourceId }
    val sourceIds = mutableSetOf<String>()
    val id = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    val ready = Regex("($id)\\.[0-9a-f]{64}\\.blob")
    val partial = Regex("$id\\.partial")
    for ((path, state) in current.files) {
        val name = path.substringAfterLast('/')
        val match = ready.matchEntire(name)
        if (match == null && !partial.matches(name) || match != null && !sourceIds.add(match.groupValues[1]))
            rescueFailure("TARGET_SOURCE_STATE_INVALID")
        if (name.substringBefore('.') in expectedIds) {
            val expected = incoming[name] ?: rescueFailure("TARGET_SOURCE_ID_CONFLICT")
            if (state.bytes != expected.bytes || digestArchiveFile(directory.root, directory.root.resolve(path), state.bytes) != expected.sha256)
                rescueFailure("TARGET_SOURCE_ID_CONFLICT")
        }
    }
    val added = incoming.filterKeys { "$relative/$it" !in current.files }.values
    if (current.files.size + added.size > 128 || current.files.values.sumOf { it.bytes } + added.sumOf { it.bytes } > 512L * 1024 * 1024)
        rescueFailure("TARGET_CAPACITY_EXCEEDED")
    if (Files.getFileStore(directory.root).usableSpace < added.sumOf { it.bytes } + 16L * 1024 * 1024)
        rescueFailure("INSUFFICIENT_FREE_SPACE")
}

private fun copyRescueSources(archive: Path, destination: JvmPrivateDataDirectory, candidate: ChatDraftRescueSource) {
    val directories = rescueSpool(candidate)
    for (file in candidate.sources) {
        val name = file.path.substringAfterLast('/')
        val target = sourcePath(destination.root, (directories + name).joinToString("/"))
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            destination.requirePrivateFile(directories, name)
            if (digestArchiveFile(destination.root, target, file.bytes) != file.sha256) rescueFailure("TARGET_SOURCE_ID_CONFLICT")
            java.nio.channels.FileChannel.open(target, java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { it.force(true) }
            destination.security().forceDirectory(target.parent)
            continue
        }
        val temporaryName = "${UUID.randomUUID()}.partial"
        val temporary = destination.preparePrivateFile(directories, temporaryName).toPath()
        try {
            if (digestArchiveFile(archive, archive.resolve("payload").resolve(file.path), file.bytes, temporary) != file.sha256)
                rescueFailure("SOURCE_DIGEST_MISMATCH")
            // No replacement: all cooperating clients are excluded by the root lease.
            if (Files.exists(target, NOFOLLOW_LINKS)) rescueFailure("TARGET_SOURCE_ID_CONFLICT")
            Files.move(temporary, target, ATOMIC_MOVE)
            destination.security().forceDirectory(target.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

internal inline fun <T> rescueGuarded(action: () -> T): T = try { action() }
catch (failure: LocalCacheChatDraftRescueFailure) { throw failure }
catch (_: Exception) { rescueFailure("PATH_STATE_OR_IO_FAILURE") }

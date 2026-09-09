package com.virjar.tk.shared.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE

@Serializable
data class LocalCacheQuarantineDiscardReport(
    val root: String,
    val path: String,
    val owner: LocalCacheDiagnosticOwner,
    val layout: LocalCacheDiagnosticLayout,
    val archiveDirectory: String,
    val manifestSha256: String,
    val removedFiles: Int,
    val removedBytes: Long,
    val alreadyAbsent: Boolean,
    val consequence: String,
)

private class QuarantineDiscardFailure(reason: String) :
    IllegalStateException("Local cache quarantine discard failed: $reason")

/**
 * Explicit abandonment, never recovery: remove only the selected archived quarantine. The verified
 * archive is also the retry inventory; each remaining file must still match it. No account-ban
 * marker is used, because that lifecycle would erase the active database, shared files and credentials.
 */
object LocalCacheQuarantineDisposition {
    fun discard(
        root: File,
        quarantinePath: String,
        archive: File,
        expectedManifestSha256: String,
        layout: LocalCacheDiagnosticLayout = LocalCacheDiagnosticLayout.JVM,
    ): LocalCacheQuarantineDiscardReport = try {
        if (!expectedManifestSha256.matches(Regex("[0-9a-f]{64}"))) fail("ARCHIVE_CONFIRMATION_MISMATCH")
        val source = archiveRoot(root)
        val archived = archiveRoot(archive)
        if (archived.startsWith(source) || source.startsWith(archived)) fail("ARCHIVE_INSIDE_SOURCE")
        val manifest = verifiedManifest(archived, expectedManifestSha256)
        if (manifest.layout != layout || manifest.quarantinePath != quarantinePath) fail("ARCHIVE_SELECTION_MISMATCH")
        val plan = localCacheArchivePlan(layout, quarantinePath)
        val quarantine = plan.scopes.single { it.category == QUARANTINE }
        val deletionPlan = plan.copy(scopes = listOf(quarantine))
        val privateSource = if (layout == LocalCacheDiagnosticLayout.JVM) JvmPrivateDataDirectory.openExisting(source.toFile()) else null
        val lease = privateSource?.let {
            // Preservation and disposition must not initialize a missing installation lock.
            it.requirePrivateFile(emptyList(), ".lock")
            try { JvmClientDataLease.acquire(source.toFile()) }
            catch (_: Exception) { fail("CLIENT_LOCK_UNAVAILABLE") }
        }
        lease.use {
            // Current shared files may have legitimately changed since export. They are not deletion
            // targets and are never read or overwritten here; only the retained quarantine is frozen.
            val before = captureArchiveInventory(source, deletionPlan)
            requireMatchingSource(source, before, manifest)
            if (captureArchiveInventory(source, deletionPlan) != before) fail("SOURCE_DOES_NOT_MATCH_ARCHIVE")
            // Recheck the backup immediately before deletion. Neither the source nor archive may be
            // concurrently edited; Android accepts an offline app-data export, never a running device.
            verifiedManifest(archived, expectedManifestSha256)
            forceVerifiedArchive(archived, manifest)
            val remaining = before.files
            val security = privateSource?.security()
                ?: JvmPrivatePathSecurity.forPath(source, Files.getOwner(source, NOFOLLOW_LINKS))
            val archivedFiles = manifest.files.associateBy { it.path }
            val deletionOrder = remaining.keys.sortedWith(compareBy<String> {
                when {
                    it == quarantinePath -> 2
                    it.substringAfterLast('/').matches(Regex("cache_e[0-9]+\\.db")) -> 1
                    else -> 0
                }
            }.thenBy { it })
            for (relative in deletionOrder) {
                val file = archivedFiles.getValue(relative)
                if (digestArchiveFile(source, source.resolve(relative), file.bytes) != file.sha256)
                    fail("SOURCE_DOES_NOT_MATCH_ARCHIVE")
                Files.delete(source.resolve(relative))
                security.forceDirectory(source.resolve(relative).parent)
            }
            // Keep the JVM directory until every file is gone: its name prevents ordinary startup
            // from treating old attachment sources as orphans after a partially completed discard.
            if (quarantine.kind == ArchiveScopeKind.DIRECTORY && before.scopes.single { it.category == QUARANTINE }.present) {
                val directory = source.resolve(quarantine.path)
                requireRealDirectory(basicAttributes(directory), "Quarantine directory")
                Files.delete(directory) // Nonrecursive: an unexpected new file is never swept away.
                security.forceDirectory(directory.parent)
            }
            val after = captureArchiveInventory(source, deletionPlan)
            if (after.files.isNotEmpty() || after.scopes.any { it.present })
                fail("SOURCE_CHANGED_DURING_DISCARD")
            verifiedManifest(archived, expectedManifestSha256)
            LocalCacheQuarantineDiscardReport(source.toString(), quarantinePath, plan.owner, layout,
                archived.toString(), expectedManifestSha256, remaining.size, remaining.values.sumOf { it.bytes },
                !before.scopes.single { it.category == QUARANTINE }.present,
                "The replacement database, attachment sources and document drafts are unchanged; " +
                    "after the last quarantine is removed, normal client startup may reclaim sources referenced only by discarded data")
        }
    } catch (failure: QuarantineDiscardFailure) {
        throw failure
    } catch (_: Exception) {
        // Filesystem/SQLite exceptions may carry paths or stored text. Emit only fixed classifications.
        fail("PATH_STATE_OR_IO_FAILURE")
    }

    private fun verifiedManifest(directory: Path, expectedHash: String): LocalCacheArchiveManifest {
        val report = try { LocalCacheArchive.verify(directory.toFile()) }
        catch (_: Exception) { fail("ARCHIVE_INVALID_OR_CHANGED") }
        if (report.manifestSha256 != expectedHash) fail("ARCHIVE_CONFIRMATION_MISMATCH")
        val encoded = readArchiveManifest(directory.resolve("manifest.json"))
        if (archiveSha256(encoded.toByteArray(Charsets.UTF_8)) != expectedHash) fail("ARCHIVE_INVALID_OR_CHANGED")
        return Json.decodeFromString<LocalCacheArchiveManifest>(encoded)
    }

    private fun forceVerifiedArchive(root: Path, manifest: LocalCacheArchiveManifest) {
        try {
            val archive = JvmPrivateDataDirectory.openExisting(root.toFile())
            // A valid archive may have been copied or moved since export. Flush that new copy before
            // making deletion of its source durable; hashing alone only proves readable bytes.
            for (relative in manifest.files.map { "payload/${it.path}" } + "manifest.json") {
                val parts = relative.split('/')
                val file = archive.requirePrivateFile(parts.dropLast(1), parts.last()).toPath()
                FileChannel.open(file, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
            }
            forceArchiveDirectories(archive)
            archive.security().forceDirectory(root.parent)
        } catch (_: Exception) {
            fail("ARCHIVE_FLUSH_FAILED")
        }
    }

    private fun requireMatchingSource(root: Path, current: ArchiveInventory, manifest: LocalCacheArchiveManifest) {
        val archived = manifest.files.filter { it.category == QUARANTINE }.associateBy { it.path }
        for ((path, state) in current.files) {
            val file = archived[path] ?: fail("SOURCE_DOES_NOT_MATCH_ARCHIVE")
            if (file.category != state.category || file.bytes != state.bytes ||
                digestArchiveFile(root, root.resolve(path), state.bytes) != file.sha256)
                fail("SOURCE_DOES_NOT_MATCH_ARCHIVE")
        }
    }

    private const val QUARANTINE = "QUARANTINE"
    private fun fail(reason: String): Nothing = throw QuarantineDiscardFailure(reason)
}

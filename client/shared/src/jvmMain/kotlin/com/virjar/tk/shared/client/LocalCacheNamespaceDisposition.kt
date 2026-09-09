package com.virjar.tk.shared.client

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

@Serializable
data class LocalCacheNamespaceDiscardReport(
    val root: String,
    val owner: LocalCacheDiagnosticOwner,
    val layout: LocalCacheDiagnosticLayout,
    val archiveDirectory: String,
    val manifestSha256: String,
    val removedFiles: Int,
    val removedBytes: Long,
    val alreadyAbsent: Boolean,
    val consequence: String,
)

/** Explicit abandonment of one complete owner, never an automatic old-epoch or logout cleanup. */
object LocalCacheNamespaceDisposition {
    fun discard(
        root: File,
        owner: LocalCacheDiagnosticOwner,
        archive: File,
        expectedManifestSha256: String,
        layout: LocalCacheDiagnosticLayout = LocalCacheDiagnosticLayout.JVM,
    ): LocalCacheNamespaceDiscardReport = namespaceArchiveGuarded {
        val source = archiveRoot(root)
        val archived = archiveRoot(archive)
        if (archived.startsWith(source) || source.startsWith(archived)) archiveFailure("ARCHIVE_INSIDE_SOURCE")
        val manifest = LocalCacheNamespaceArchive.confirmedManifest(archived, expectedManifestSha256)
        if (manifest.owner != owner || manifest.layout != layout) archiveFailure("ARCHIVE_SELECTION_MISMATCH")
        localCacheArchiveLease(source, layout).use {
            requireUnreferencedLocalCacheNamespace(source, owner, layout)
            fun deletionPlan() = localCacheNamespacePlan(source, layout, owner).let { plan ->
                plan.copy(scopes = plan.scopes.filter { it.category != LocalCacheNamespaceArchive.DOCUMENT_OWNER_STATE })
            }
            val plan = deletionPlan()
            val before = captureArchiveInventory(source, plan)
            requireMatchingNamespaceSource(source, before, manifest)
            if (deletionPlan() != plan || captureArchiveInventory(source, plan) != before)
                archiveFailure("SOURCE_DOES_NOT_MATCH_ARCHIVE")
            LocalCacheNamespaceArchive.confirmedManifest(archived, expectedManifestSha256)
            forceVerifiedLocalCacheArchive(archived, manifest.files)
            requireUnreferencedLocalCacheNamespace(source, owner, layout)
            val security = JvmPrivatePathSecurity.forPath(source, Files.getOwner(source, NOFOLLOW_LINKS))
            val archivedFiles = manifest.files.associateBy { it.path }
            // Remove the abandoned databases before their shared sources. An interrupted disposal
            // must not leave a surviving queue whose original source files we have already removed.
            val order = before.files.keys.sortedWith(compareBy<String> {
                when {
                    before.files.getValue(it).category != "DATABASES" -> 2
                    it.endsWith(".db") || it.substringAfterLast('/').contains(".db.corrupt-") &&
                        LocalCacheDiagnostics.FAMILY_SUFFIXES.filter(String::isNotEmpty).none(it::endsWith) -> 1
                    else -> 0
                }
            }.thenBy { it })
            for (relative in order) {
                requireUnreferencedLocalCacheNamespace(source, owner, layout)
                val file = archivedFiles.getValue(relative)
                if (digestArchiveFile(source, source.resolve(relative), file.bytes) != file.sha256)
                    archiveFailure("SOURCE_DOES_NOT_MATCH_ARCHIVE")
                Files.delete(source.resolve(relative))
                security.forceDirectory(source.resolve(relative).parent)
            }
            for (scope in plan.scopes.filter { it.kind == ArchiveScopeKind.DIRECTORY }) {
                if (!before.scopes.single { it.path == scope.path && it.category == scope.category }.present) continue
                requireUnreferencedLocalCacheNamespace(source, owner, layout)
                val directory = sourcePath(source, scope.path)
                requireRealDirectory(basicAttributes(directory), "Namespace directory")
                Files.delete(directory) // Nonrecursive: an unexpected child is never swept away.
                security.forceDirectory(directory.parent)
            }
            val after = captureArchiveInventory(source, deletionPlan())
            if (after.files.isNotEmpty() || after.scopes.any { it.present }) archiveFailure("SOURCE_CHANGED_DURING_DISCARD")
            LocalCacheNamespaceArchive.confirmedManifest(archived, expectedManifestSha256)
            LocalCacheNamespaceDiscardReport(source.toString(), owner, layout, archived.toString(), expectedManifestSha256,
                before.files.size, before.files.values.sumOf { it.bytes }, before.scopes.none { it.present },
                "All known database epochs, quarantines, attachment sources and document drafts of this owner are abandoned; " +
                    "unsent messages and pending commands are no longer replayable from this installation. " +
                    "The archive, login credentials, Android shared preferences, media caches, unknown layouts and other owners remain unchanged")
        }
    }
}

private fun requireMatchingNamespaceSource(root: Path, current: ArchiveInventory, manifest: LocalCacheNamespaceArchiveManifest) {
    val scopes = manifest.scopes.associateBy { it.path to it.category }
    for (scope in current.scopes) {
        if (scope.present && scopes[scope.path to scope.category]?.present != true)
            archiveFailure("SOURCE_DOES_NOT_MATCH_ARCHIVE")
    }
    val archived = manifest.files.associateBy { it.path }
    for ((path, state) in current.files) {
        val file = archived[path] ?: archiveFailure("SOURCE_DOES_NOT_MATCH_ARCHIVE")
        if (file.category != state.category || file.bytes != state.bytes ||
            digestArchiveFile(root, root.resolve(path), state.bytes) != file.sha256)
            archiveFailure("SOURCE_DOES_NOT_MATCH_ARCHIVE")
    }
}

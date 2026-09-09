package com.virjar.tk.shared.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

@Serializable
data class LocalCacheArchiveReport(val directory: String, val fileCount: Int, val totalBytes: Long, val manifestSha256: String)

@Serializable
internal data class LocalCacheArchiveScope(val path: String, val category: String, val present: Boolean)

@Serializable
internal data class LocalCacheArchiveFile(val path: String, val category: String, val bytes: Long, val sha256: String)

@Serializable
internal data class LocalCacheArchiveManifest(
    val formatVersion: Int,
    val archiveId: String,
    val layout: LocalCacheDiagnosticLayout,
    val owner: LocalCacheDiagnosticOwner,
    val quarantinePath: String,
    val scopes: List<LocalCacheArchiveScope>,
    val files: List<LocalCacheArchiveFile>,
    val limitations: List<String>,
)

internal class LocalCacheArchiveFailure(reason: String) : IllegalStateException("Local cache archive failed: $reason")
internal fun archiveFailure(reason: String): Nothing = throw LocalCacheArchiveFailure(reason)

/** Raw preservation only. Never instantiate a cache, spool, document reader or credential store. */
object LocalCacheArchive {
    const val MAX_FILE_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_FILES = 4096
    internal const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    private val json = Json { encodeDefaults = true }
    private val limitations = listOf(
        "Raw preservation, not repaired data, replay permission or permission to delete the source",
        "A stable file capture is not proof of an online atomic snapshot or of a complete Android app-data export",
        "Attachment sources and document drafts are shared with the active account database and remain in place",
        "Only the current known owner layouts are covered; legacy layouts, login credentials, media caches and other owners are excluded",
        "Payload files may contain private message bodies, drafts and credentials in pending commands; keep this archive private",
        "Verification proves consistency with this manifest, not authenticity, recoverability or equality to the current source",
    )

    fun export(root: File, quarantinePath: String, destination: File,
               layout: LocalCacheDiagnosticLayout = LocalCacheDiagnosticLayout.JVM): LocalCacheArchiveReport = guarded {
        val source = archiveRoot(root)
        val plan = localCacheArchivePlan(layout, quarantinePath)
        val parent = destination.toPath().toAbsolutePath().normalize().parent ?: archiveFailure("INVALID_DESTINATION")
        val target = parent.toRealPath().resolve(destination.name)
        if (target.startsWith(source)) archiveFailure("DESTINATION_INSIDE_SOURCE")
        if (Files.exists(target, NOFOLLOW_LINKS)) archiveFailure("DESTINATION_EXISTS")
        val lease = if (layout == LocalCacheDiagnosticLayout.JVM) {
            val privateSource = JvmPrivateDataDirectory.openExisting(source.toFile())
            // Do not create a missing source lock or adopt/reset an installation during preservation.
            privateSource.requirePrivateFile(emptyList(), ".lock")
            try { JvmClientDataLease.acquire(source.toFile()) }
            catch (_: Exception) { archiveFailure("CLIENT_LOCK_UNAVAILABLE") }
        } else null
        lease.use {
            val before = captureArchiveInventory(source, plan)
            if (quarantinePath !in before.files) archiveFailure("QUARANTINE_MAIN_MISSING")
            val output = JvmPrivateDataDirectory.createNew(target.toFile(), parent.toRealPath().toFile())
            // The manifest is the sole completion record. On failure retain this private incomplete
            // output for explicit disposal; never overwrite or recursively remove a caller's directory.
            val files = before.files.map { (relative, state) ->
                val parts = relative.split('/')
                val copy = output.preparePrivateFile(listOf("payload") + parts.dropLast(1), parts.last()).toPath()
                LocalCacheArchiveFile(relative, state.category, state.bytes,
                    digestArchiveFile(source, source.resolve(relative), state.bytes, copy))
            }
            if (captureArchiveInventory(source, plan) != before || files.any { file ->
                    digestArchiveFile(source, source.resolve(file.path), file.bytes) != file.sha256
                } || captureArchiveInventory(source, plan) != before) archiveFailure("SOURCE_CHANGED_DURING_CAPTURE")
            // Flush the new payload tree before publishing its completion record.
            forceArchiveDirectories(output)
            if (files.any { file ->
                    digestArchiveFile(output.root, output.root.resolve("payload").resolve(file.path), file.bytes) != file.sha256
                }) archiveFailure("PAYLOAD_DIGEST_MISMATCH")
            val manifest = LocalCacheArchiveManifest(1, UUID.randomUUID().toString(), layout, plan.owner,
                quarantinePath, before.scopes, files, limitations)
            val encoded = json.encodeToString(manifest)
            if (encoded.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) archiveFailure("MANIFEST_SIZE_LIMIT")
            output.atomicTextFile(fileName = "manifest.json").replaceText(encoded, MAX_MANIFEST_BYTES)
            verify(target.toFile())
        }
    }

    fun verify(directory: File): LocalCacheArchiveReport = guarded {
        val root = archiveRoot(directory)
        val privateArchive = JvmPrivateDataDirectory.openExisting(root.toFile())
        val manifestFile = privateArchive.requirePrivateFile(emptyList(), "manifest.json").toPath()
        val encoded = readArchiveManifest(manifestFile)
        val manifest = json.decodeFromString<LocalCacheArchiveManifest>(encoded)
        if (manifest.formatVersion != 1) archiveFailure("UNSUPPORTED_ARCHIVE_FORMAT")
        if (runCatching { UUID.fromString(manifest.archiveId).toString() == manifest.archiveId }.getOrDefault(false).not())
            archiveFailure("INVALID_ARCHIVE_ID")
        val plan = localCacheArchivePlan(manifest.layout, manifest.quarantinePath)
        if (manifest.owner != plan.owner || manifest.limitations != limitations) archiveFailure("MANIFEST_SCOPE_MISMATCH")
        if (manifest.scopes.map { it.path to it.category } != plan.scopes.map { it.path to it.category })
            archiveFailure("MANIFEST_SCOPE_MISMATCH")
        if (manifest.files.isEmpty() || manifest.files.size > MAX_FILES ||
            manifest.files.map { it.path }.toSet().size != manifest.files.size) archiveFailure("INVALID_FILE_INVENTORY")
        var total = 0L
        for (file in manifest.files) {
            requireArchiveRelativePath(file.path)
            if (file.bytes !in 0..MAX_FILE_BYTES || file.bytes > MAX_TOTAL_BYTES - total ||
                !file.sha256.matches(Regex("[0-9a-f]{64}"))) archiveFailure("INVALID_FILE_INVENTORY")
            total += file.bytes
            val scopeIndex = plan.scopes.indexOfFirst { it.includes(file.path) && it.category == file.category }
            if (scopeIndex < 0 || !manifest.scopes[scopeIndex].present) archiveFailure("MANIFEST_SCOPE_MISMATCH")
        }
        for ((index, scope) in plan.scopes.withIndex()) {
            if (scope.kind != ArchiveScopeKind.DIRECTORY && manifest.scopes[index].present !=
                manifest.files.any { scope.includes(it.path) && it.category == scope.category })
                archiveFailure("MANIFEST_SCOPE_MISMATCH")
        }
        if (manifest.files.none { it.path == manifest.quarantinePath }) archiveFailure("QUARANTINE_MAIN_MISSING")
        val actual = archivePayloadFiles(root)
        if (actual.keys != manifest.files.map { it.path }.toSet()) archiveFailure("PAYLOAD_INVENTORY_MISMATCH")
        for (file in manifest.files) {
            if (actual.getValue(file.path).bytes != file.bytes ||
                digestArchiveFile(root, root.resolve("payload").resolve(file.path), file.bytes) != file.sha256)
                archiveFailure("PAYLOAD_DIGEST_MISMATCH")
        }
        if (archivePayloadFiles(root) != actual || readArchiveManifest(manifestFile) != encoded)
            archiveFailure("ARCHIVE_CHANGED_DURING_VERIFICATION")
        LocalCacheArchiveReport(root.toString(), manifest.files.size, total, archiveSha256(encoded.toByteArray(Charsets.UTF_8)))
    }

    private inline fun <T> guarded(action: () -> T): T = try { action() }
    catch (failure: LocalCacheArchiveFailure) { throw failure }
    catch (_: Exception) { archiveFailure("PATH_STATE_OR_IO_FAILURE") }
}

internal enum class ArchiveScopeKind { DIRECTORY, DATABASE_FAMILY, ANDROID_DOCUMENTS, OWNER_PREFERENCES }
internal data class ArchiveScopePlan(val path: String, val category: String, val kind: ArchiveScopeKind) {
    fun includes(candidate: String): Boolean = when (kind) {
        ArchiveScopeKind.DIRECTORY -> candidate.startsWith("$path/") && '/' !in candidate.removePrefix("$path/")
        ArchiveScopeKind.DATABASE_FAMILY -> candidate in LocalCacheDiagnostics.FAMILY_SUFFIXES.map { path + it }
        ArchiveScopeKind.ANDROID_DOCUMENTS -> candidate.startsWith("$path.") && '/' !in candidate.removePrefix("$path.")
        ArchiveScopeKind.OWNER_PREFERENCES -> candidate == path || candidate == "$path.bak"
    }
}
internal data class LocalCacheArchivePlan(val owner: LocalCacheDiagnosticOwner, val scopes: List<ArchiveScopePlan>)

internal fun localCacheArchivePlan(layout: LocalCacheDiagnosticLayout, quarantinePath: String): LocalCacheArchivePlan {
    requireArchiveRelativePath(quarantinePath)
    val parts = quarantinePath.split('/')
    val owner: LocalCacheDiagnosticOwner
    val databaseScopes: List<ArchiveScopePlan>
    if (layout == LocalCacheDiagnosticLayout.JVM) {
        if (parts.size != 7 || parts[0] != "deployments" || parts[2] != "datasets" || parts[4] != "users" ||
            !parts[6].matches(Regex("cache_e[0-9]+\\.db"))) archiveFailure("INVALID_QUARANTINE_PATH")
        val user = Regex("([\\p{L}\\p{N}_-]+)\\.corrupt-([A-Za-z0-9-]+)").matchEntire(parts[5])
            ?: archiveFailure("INVALID_QUARANTINE_PATH")
        owner = LocalCacheDiagnosticOwner(parts[1], parts[3], user.groupValues[1])
        databaseScopes = listOf(ArchiveScopePlan(parts.dropLast(1).joinToString("/"), "QUARANTINE", ArchiveScopeKind.DIRECTORY),
            ArchiveScopePlan((parts.take(5) + owner.uid).joinToString("/"), "ACTIVE_DATABASE", ArchiveScopeKind.DIRECTORY))
    } else {
        if (parts.size != 2 || parts[0] != "databases") archiveFailure("INVALID_QUARANTINE_PATH")
        val match = Regex("(cache_e[0-9]+_([0-9a-f]{64})_([0-9a-f-]{36})_([\\p{L}\\p{N}_-]+)\\.db)\\.corrupt-[A-Za-z0-9-]+")
            .matchEntire(parts[1]) ?: archiveFailure("INVALID_QUARANTINE_PATH")
        owner = LocalCacheDiagnosticOwner(match.groupValues[2], match.groupValues[3], match.groupValues[4])
        databaseScopes = listOf(ArchiveScopePlan(quarantinePath, "QUARANTINE", ArchiveScopeKind.DATABASE_FAMILY),
            ArchiveScopePlan("databases/${match.groupValues[1]}", "ACTIVE_DATABASE", ArchiveScopeKind.DATABASE_FAMILY))
    }
    validatedDeploymentFingerprint(owner.deploymentFingerprint)
    validatedLocalCacheDatasetId(owner.datasetId)
    validatedLocalCacheOwnerId(owner.uid)
    val spool = com.virjar.tk.shared.repository.chatAssetSpoolDirectories(
        AccountDataOwner(owner.deploymentFingerprint, owner.datasetId, owner.uid)).joinToString("/")
    val extra = if (layout == LocalCacheDiagnosticLayout.JVM) listOf(
        ArchiveScopePlan(spool, "CHAT_SOURCES", ArchiveScopeKind.DIRECTORY),
        ArchiveScopePlan(DocumentDraftStoragePaths.jvmDirectories(owner.deploymentFingerprint, owner.datasetId, owner.uid).joinToString("/"),
            "DOCUMENT_DRAFTS", ArchiveScopeKind.DIRECTORY),
    ) else listOf(
        ArchiveScopePlan("no_backup/$spool", "CHAT_SOURCES", ArchiveScopeKind.DIRECTORY),
        ArchiveScopePlan("no_backup/${DocumentDraftStoragePaths.ANDROID_DIRECTORY}/" +
            DocumentDraftStoragePaths.androidOwnerPrefix(owner.deploymentFingerprint, owner.datasetId, owner.uid),
            "DOCUMENT_DRAFTS", ArchiveScopeKind.ANDROID_DOCUMENTS),
        ArchiveScopePlan("shared_prefs/teamtalk_document_drafts.xml", "DOCUMENT_OWNER_STATE", ArchiveScopeKind.OWNER_PREFERENCES),
    )
    return LocalCacheArchivePlan(owner, databaseScopes + extra)
}

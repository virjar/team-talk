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
internal data class LocalCacheNamespaceArchiveManifest(
    val formatVersion: Int,
    val purpose: String,
    val archiveId: String,
    val layout: LocalCacheDiagnosticLayout,
    val owner: LocalCacheDiagnosticOwner,
    val scopes: List<LocalCacheArchiveScope>,
    val files: List<LocalCacheArchiveFile>,
    val limitations: List<String>,
)

/** Preserve a complete known owner, including every epoch and retained quarantine, without SQL. */
object LocalCacheNamespaceArchive {
    private val json = Json { encodeDefaults = true }
    private val limitations = listOf(
        "Raw preservation of one exact deployment, dataset and uid; not repaired data or permission to replay or delete it",
        "All known database epochs and quarantines, attachment sources and independent document drafts of this owner are included",
        "Android document owner preferences are installation-wide: preserved for context, never deleted by namespace disposition",
        "Login credentials, media caches, telemetry, unknown legacy layouts and all other owners are excluded and remain in place",
        "A stable capture does not prove an online atomic snapshot or a complete Android app-data export; stop the source application first",
        "Payload files may contain private messages, drafts and credentials in pending commands; keep the archive private",
        "Verification proves consistency with this manifest, not authenticity, recoverability or equality to the current source",
    )

    fun export(
        root: File,
        owner: LocalCacheDiagnosticOwner,
        destination: File,
        layout: LocalCacheDiagnosticLayout = LocalCacheDiagnosticLayout.JVM,
    ): LocalCacheArchiveReport = namespaceArchiveGuarded {
        writeLocalCacheArchive(root, destination, layout, { localCacheNamespacePlan(it, layout, owner) }, {
            if (it.files.values.none { file -> file.category != DOCUMENT_OWNER_STATE }) archiveFailure("EMPTY_NAMESPACE_SCOPE")
        }) { plan, before, files ->
            json.encodeToString(LocalCacheNamespaceArchiveManifest(2, "NAMESPACE", UUID.randomUUID().toString(),
                layout, plan.owner, before.scopes, files, limitations))
        }
    }

    internal fun verify(root: Path, encoded: String): LocalCacheArchiveReport {
        val manifest = json.decodeFromString<LocalCacheNamespaceArchiveManifest>(encoded)
        if (manifest.formatVersion != 2 || manifest.purpose != "NAMESPACE") archiveFailure("UNSUPPORTED_ARCHIVE_FORMAT")
        if (manifest.limitations != limitations) archiveFailure("MANIFEST_SCOPE_MISMATCH")
        val plan = validatedNamespaceManifestPlan(manifest)
        if (manifest.files.none { it.category != DOCUMENT_OWNER_STATE }) archiveFailure("EMPTY_NAMESPACE_SCOPE")
        return verifyLocalCacheArchivePayload(root, encoded, manifest.archiveId, plan, manifest.scopes, manifest.files)
    }

    internal fun confirmedManifest(root: Path, expectedHash: String): LocalCacheNamespaceArchiveManifest {
        if (!expectedHash.matches(Regex("[0-9a-f]{64}"))) archiveFailure("ARCHIVE_CONFIRMATION_MISMATCH")
        if (LocalCacheArchive.verify(root.toFile()).manifestSha256 != expectedHash) archiveFailure("ARCHIVE_CONFIRMATION_MISMATCH")
        val encoded = readArchiveManifest(root.resolve("manifest.json"))
        if (archiveSha256(encoded.toByteArray(Charsets.UTF_8)) != expectedHash) archiveFailure("ARCHIVE_INVALID_OR_CHANGED")
        val manifest = try { json.decodeFromString<LocalCacheNamespaceArchiveManifest>(encoded) }
        catch (_: Exception) { archiveFailure("ARCHIVE_SELECTION_MISMATCH") }
        if (manifest.formatVersion != 2 || manifest.purpose != "NAMESPACE") archiveFailure("ARCHIVE_SELECTION_MISMATCH")
        return manifest
    }

    internal const val DOCUMENT_OWNER_STATE = "DOCUMENT_OWNER_STATE"
}

/** Enumerate only the current known ownership grammar; a missing database does not hide local drafts. */
internal fun localCacheNamespacePlan(root: Path, layout: LocalCacheDiagnosticLayout, owner: LocalCacheDiagnosticOwner): LocalCacheArchivePlan {
    val extras = localCacheOwnerArchiveScopes(layout, owner) // Validates the complete owner first.
    val databaseScopes = if (layout == LocalCacheDiagnosticLayout.JVM) {
        val active = namespaceJvmDirectory(owner)
        val names = namespaceDirectoryNames(root, active.substringBeforeLast('/'))
        listOf(active) + names.filter { isNamespaceQuarantineDirectory(it, owner.uid) }
            .map { active.substringBeforeLast('/') + "/" + it }.sorted()
    } else {
        namespaceDirectoryNames(root, "databases").mapNotNull { name ->
            androidNamespaceFamily(name, owner)?.let { "databases/$it" }
        }.distinct().sorted()
    }
    val kind = if (layout == LocalCacheDiagnosticLayout.JVM) ArchiveScopeKind.DIRECTORY else ArchiveScopeKind.DATABASE_FAMILY
    return LocalCacheArchivePlan(owner, databaseScopes.map { ArchiveScopePlan(it, "DATABASES", kind) } + extras)
}

private fun validatedNamespaceManifestPlan(manifest: LocalCacheNamespaceArchiveManifest): LocalCacheArchivePlan {
    val extras = localCacheOwnerArchiveScopes(manifest.layout, manifest.owner)
    val databaseScopes = manifest.scopes.takeWhile { it.category == "DATABASES" }.map { it.path }
    if (databaseScopes.size > LocalCacheArchive.MAX_FILES || databaseScopes.distinct().size != databaseScopes.size)
        archiveFailure("MANIFEST_SCOPE_MISMATCH")
    val kind = if (manifest.layout == LocalCacheDiagnosticLayout.JVM) {
        val active = namespaceJvmDirectory(manifest.owner)
        if (databaseScopes.firstOrNull() != active || databaseScopes.drop(1) != databaseScopes.drop(1).sorted() ||
            databaseScopes.drop(1).any {
                it.substringBeforeLast('/') != active.substringBeforeLast('/') ||
                    !isNamespaceQuarantineDirectory(it.substringAfterLast('/'), manifest.owner.uid)
            }) archiveFailure("MANIFEST_SCOPE_MISMATCH")
        ArchiveScopeKind.DIRECTORY
    } else {
        if (databaseScopes != databaseScopes.sorted() || databaseScopes.any {
                it.substringBeforeLast('/') != "databases" ||
                    androidNamespaceFamily(it.substringAfterLast('/'), manifest.owner) != it.substringAfterLast('/')
            }) archiveFailure("MANIFEST_SCOPE_MISMATCH")
        ArchiveScopeKind.DATABASE_FAMILY
    }
    return LocalCacheArchivePlan(manifest.owner, databaseScopes.map { ArchiveScopePlan(it, "DATABASES", kind) } + extras)
}

private fun namespaceJvmDirectory(owner: LocalCacheDiagnosticOwner) =
    "deployments/${owner.deploymentFingerprint}/datasets/${owner.datasetId}/users/${owner.uid}"

private fun isNamespaceQuarantineDirectory(name: String, uid: String): Boolean =
    name.startsWith("$uid.corrupt-") && name.removePrefix("$uid.corrupt-").matches(Regex("[A-Za-z0-9-]+"))

private fun androidNamespaceFamily(name: String, owner: LocalCacheDiagnosticOwner): String? {
    val identity = Regex.escape("_${owner.deploymentFingerprint}_${owner.datasetId}_${owner.uid}.db")
    val family = Regex("cache_e[0-9]+$identity(?:\\.corrupt-[A-Za-z0-9-]+)?")
    // Longest suffix first so an open marker belonging to a quarantine remains in that family.
    return LocalCacheDiagnostics.FAMILY_SUFFIXES.sortedByDescending { it.length }.firstNotNullOfOrNull { suffix ->
        if (!name.endsWith(suffix)) null else name.removeSuffix(suffix).takeIf { family.matches(it) }
    }
}

private fun namespaceDirectoryNames(root: Path, relative: String): List<String> {
    val directory = sourcePath(root, relative)
    if (!Files.exists(directory, NOFOLLOW_LINKS)) return emptyList()
    requireRealDirectory(basicAttributes(directory), "Namespace directory")
    return Files.newDirectoryStream(directory).use { stream ->
        val names = stream.asSequence().take(LocalCacheArchive.MAX_FILES + 1).map { it.fileName.toString() }.toList()
        if (names.size > LocalCacheArchive.MAX_FILES) archiveFailure("SOURCE_INVENTORY_LIMIT_OR_OVERLAP")
        names
    }
}

internal inline fun <T> namespaceArchiveGuarded(action: () -> T): T = try { action() }
catch (failure: LocalCacheArchiveFailure) { throw failure }
catch (_: Exception) { archiveFailure("PATH_STATE_OR_IO_FAILURE") }

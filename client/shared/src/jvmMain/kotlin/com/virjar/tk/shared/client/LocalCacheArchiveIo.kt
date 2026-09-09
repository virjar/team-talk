package com.virjar.tk.shared.client

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Shared byte-preservation mechanics; each archive purpose supplies and validates its own scope. */
internal fun writeLocalCacheArchive(
    root: File,
    destination: File,
    layout: LocalCacheDiagnosticLayout,
    planFor: (Path) -> LocalCacheArchivePlan,
    checkInventory: (ArchiveInventory) -> Unit,
    manifest: (LocalCacheArchivePlan, ArchiveInventory, List<LocalCacheArchiveFile>) -> String,
): LocalCacheArchiveReport {
    val source = archiveRoot(root)
    val parent = destination.toPath().toAbsolutePath().normalize().parent ?: archiveFailure("INVALID_DESTINATION")
    val target = parent.toRealPath().resolve(destination.name)
    if (target.startsWith(source)) archiveFailure("DESTINATION_INSIDE_SOURCE")
    if (Files.exists(target, NOFOLLOW_LINKS)) archiveFailure("DESTINATION_EXISTS")
    localCacheArchiveLease(source, layout).use {
        val plan = planFor(source)
        val before = captureArchiveInventory(source, plan)
        checkInventory(before)
        val output = JvmPrivateDataDirectory.createNew(target.toFile(), parent.toRealPath().toFile())
        // Publish the manifest last. A failed private capture remains incomplete, never overwritten
        // or recursively removed; the caller can inspect it without losing the original bytes.
        val files = before.files.map { (relative, state) ->
            val parts = relative.split('/')
            val copy = output.preparePrivateFile(listOf("payload") + parts.dropLast(1), parts.last()).toPath()
            LocalCacheArchiveFile(relative, state.category, state.bytes,
                digestArchiveFile(source, source.resolve(relative), state.bytes, copy))
        }
        fun unchanged() = planFor(source) == plan && captureArchiveInventory(source, plan) == before
        if (!unchanged() || files.any {
                digestArchiveFile(source, source.resolve(it.path), it.bytes) != it.sha256
            } || !unchanged()) archiveFailure("SOURCE_CHANGED_DURING_CAPTURE")
        forceArchiveDirectories(output)
        if (files.any {
                digestArchiveFile(output.root, output.root.resolve("payload").resolve(it.path), it.bytes) != it.sha256
            }) archiveFailure("PAYLOAD_DIGEST_MISMATCH")
        val encoded = manifest(plan, before, files)
        if (encoded.toByteArray(Charsets.UTF_8).size > LocalCacheArchive.MAX_MANIFEST_BYTES)
            archiveFailure("MANIFEST_SIZE_LIMIT")
        output.atomicTextFile(fileName = "manifest.json").replaceText(encoded, LocalCacheArchive.MAX_MANIFEST_BYTES)
        return LocalCacheArchive.verify(target.toFile())
    }
}

internal fun localCacheArchiveLease(root: Path, layout: LocalCacheDiagnosticLayout): JvmClientDataLease? {
    if (layout != LocalCacheDiagnosticLayout.JVM) return null
    // Never create a missing installation lock or invoke version/reset/credential initialization.
    JvmPrivateDataDirectory.openExisting(root.toFile()).requirePrivateFile(emptyList(), ".lock")
    return try { JvmClientDataLease.acquire(root.toFile()) }
    catch (_: Exception) { archiveFailure("CLIENT_LOCK_UNAVAILABLE") }
}

internal fun verifyLocalCacheArchivePayload(
    root: Path,
    encoded: String,
    archiveId: String,
    plan: LocalCacheArchivePlan,
    scopes: List<LocalCacheArchiveScope>,
    files: List<LocalCacheArchiveFile>,
): LocalCacheArchiveReport {
    if (runCatching { UUID.fromString(archiveId).toString() == archiveId }.getOrDefault(false).not())
        archiveFailure("INVALID_ARCHIVE_ID")
    if (scopes.map { it.path to it.category } != plan.scopes.map { it.path to it.category })
        archiveFailure("MANIFEST_SCOPE_MISMATCH")
    if (files.isEmpty() || files.size > LocalCacheArchive.MAX_FILES || files.map { it.path }.toSet().size != files.size)
        archiveFailure("INVALID_FILE_INVENTORY")
    var total = 0L
    for (file in files) {
        requireArchiveRelativePath(file.path)
        if (file.bytes !in 0..LocalCacheArchive.MAX_FILE_BYTES || file.bytes > LocalCacheArchive.MAX_TOTAL_BYTES - total ||
            !file.sha256.matches(Regex("[0-9a-f]{64}"))) archiveFailure("INVALID_FILE_INVENTORY")
        total += file.bytes
        val index = plan.scopes.indexOfFirst { it.includes(file.path) && it.category == file.category }
        if (index < 0 || !scopes[index].present) archiveFailure("MANIFEST_SCOPE_MISMATCH")
    }
    for ((index, scope) in plan.scopes.withIndex()) {
        if (scope.kind != ArchiveScopeKind.DIRECTORY && scopes[index].present !=
            files.any { scope.includes(it.path) && it.category == scope.category }) archiveFailure("MANIFEST_SCOPE_MISMATCH")
    }
    val actual = archivePayloadFiles(root)
    if (actual.keys != files.map { it.path }.toSet()) archiveFailure("PAYLOAD_INVENTORY_MISMATCH")
    for (file in files) {
        if (actual.getValue(file.path).bytes != file.bytes ||
            digestArchiveFile(root, root.resolve("payload").resolve(file.path), file.bytes) != file.sha256)
            archiveFailure("PAYLOAD_DIGEST_MISMATCH")
    }
    if (archivePayloadFiles(root) != actual || readArchiveManifest(root.resolve("manifest.json")) != encoded)
        archiveFailure("ARCHIVE_CHANGED_DURING_VERIFICATION")
    return LocalCacheArchiveReport(root.toString(), files.size, total, archiveSha256(encoded.toByteArray(Charsets.UTF_8)))
}

internal fun forceVerifiedLocalCacheArchive(root: Path, files: List<LocalCacheArchiveFile>) {
    try {
        val archive = JvmPrivateDataDirectory.openExisting(root.toFile())
        // The archive may have moved since export. Flush the verified copy before source deletion.
        for (relative in files.map { "payload/${it.path}" } + "manifest.json") {
            val parts = relative.split('/')
            val file = archive.requirePrivateFile(parts.dropLast(1), parts.last()).toPath()
            FileChannel.open(file, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        }
        forceArchiveDirectories(archive)
        archive.security().forceDirectory(root.parent)
    } catch (_: Exception) { archiveFailure("ARCHIVE_FLUSH_FAILED") }
}

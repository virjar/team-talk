package com.virjar.tk.shared.client

import com.virjar.tk.shared.repository.chatAssetSpoolDirectories
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.Path
import java.util.UUID

internal data class CacheRescueSources(
    val owner: LocalCacheDiagnosticOwner,
    val uploads: List<ChatAssetUpload>,
    val sources: List<LocalCacheArchiveFile>,
)

private fun rescueSpool(source: CacheRescueSources) = chatAssetSpoolDirectories(
    AccountDataOwner(source.owner.deploymentFingerprint, source.owner.datasetId, source.owner.uid))

internal fun checkRescueSourceDestination(directory: JvmPrivateDataDirectory, candidate: CacheRescueSources) {
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

internal fun copyRescueSources(archive: Path, destination: JvmPrivateDataDirectory, candidate: CacheRescueSources) {
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

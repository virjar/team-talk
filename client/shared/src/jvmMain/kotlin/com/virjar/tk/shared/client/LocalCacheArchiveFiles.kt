package com.virjar.tk.shared.client

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

internal data class ArchiveFileState(val category: String, val bytes: Long, val modified: FileTime, val key: Any?)
internal data class ArchiveInventory(val scopes: List<LocalCacheArchiveScope>, val files: Map<String, ArchiveFileState>)

internal fun archiveRoot(file: File): Path {
    val root = file.toPath().toAbsolutePath().normalize()
    requireRealDirectory(basicAttributes(root), "Archive source or destination")
    return root.toRealPath()
}

internal fun requireArchiveRelativePath(path: String) {
    if (path.isEmpty() || path.length > 1024 || '\\' in path || '\u0000' in path ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." || ':' in it }) archiveFailure("INVALID_RELATIVE_PATH")
}

/** Source paths are read-only. Validate all existing parents without creating missing components. */
internal fun sourcePath(root: Path, relative: String): Path {
    requireArchiveRelativePath(relative)
    var current = root
    val parts = relative.split('/')
    for ((index, name) in parts.withIndex()) {
        current = current.resolve(name)
        if (Files.exists(current, NOFOLLOW_LINKS)) {
            val attrs = basicAttributes(current)
            if (attrs.isSymbolicLink || attrs.isOther) archiveFailure("SOURCE_LINK_OR_UNSUPPORTED_PATH")
            if (index != parts.lastIndex && !attrs.isDirectory) archiveFailure("SOURCE_PARENT_NOT_DIRECTORY")
        }
    }
    return current
}

internal fun captureArchiveInventory(root: Path, plan: LocalCacheArchivePlan): ArchiveInventory {
    val files = linkedMapOf<String, ArchiveFileState>()
    var entries = 0
    var total = 0L
    fun add(path: Path, category: String) {
        val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
        sourcePath(root, relative)
        val attributes = basicAttributes(path)
        requireRealFile(attributes, "Archive source file")
        if (attributes.size() > LocalCacheArchive.MAX_FILE_BYTES ||
            attributes.size() > LocalCacheArchive.MAX_TOTAL_BYTES - total) archiveFailure("SOURCE_SIZE_LIMIT")
        if (files.size >= LocalCacheArchive.MAX_FILES || relative in files) archiveFailure("SOURCE_INVENTORY_LIMIT_OR_OVERLAP")
        files[relative] = ArchiveFileState(category, attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        total += attributes.size()
    }
    fun children(directory: Path, select: (Path) -> Boolean, category: String) {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return
        sourcePath(root, root.relativize(directory).toString().replace(File.separatorChar, '/'))
        requireRealDirectory(basicAttributes(directory), "Archive source namespace")
        Files.newDirectoryStream(directory).use { stream ->
            for (entry in stream) {
                if (++entries > LocalCacheArchive.MAX_FILES) archiveFailure("SOURCE_INVENTORY_LIMIT_OR_OVERLAP")
                if (select(entry)) add(entry, category)
            }
        }
    }
    val scopes = plan.scopes.map { scope ->
        val path = sourcePath(root, scope.path)
        val present = when (scope.kind) {
            ArchiveScopeKind.DIRECTORY -> {
                val exists = Files.exists(path, NOFOLLOW_LINKS)
                children(path, { true }, scope.category)
                exists
            }
            ArchiveScopeKind.ANDROID_DOCUMENTS -> {
                children(path.parent, { it.fileName.toString().startsWith(path.fileName.toString() + ".") }, scope.category)
                files.values.any { it.category == scope.category }
            }
            ArchiveScopeKind.DATABASE_FAMILY, ArchiveScopeKind.OWNER_PREFERENCES -> {
                val suffixes = if (scope.kind == ArchiveScopeKind.DATABASE_FAMILY) LocalCacheDiagnostics.FAMILY_SUFFIXES else listOf("", ".bak")
                var exists = false
                for (suffix in suffixes) {
                    val file = sourcePath(root, scope.path + suffix)
                    if (Files.exists(file, NOFOLLOW_LINKS)) { add(file, scope.category); exists = true }
                }
                exists
            }
        }
        LocalCacheArchiveScope(scope.path, scope.category, present)
    }
    return ArchiveInventory(scopes, files.toSortedMap())
}

/** Hash fixed-size raw files, optionally copying them to a newly created private destination. */
internal fun digestArchiveFile(root: Path, source: Path, expectedBytes: Long, copy: Path? = null): String {
    if (expectedBytes !in 0..LocalCacheArchive.MAX_FILE_BYTES) archiveFailure("FILE_SIZE_LIMIT")
    sourcePath(root, root.relativize(source).toString().replace(File.separatorChar, '/'))
    requireRealFile(basicAttributes(source), "Archive input file")
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(source, READ, NOFOLLOW_LINKS).use { input ->
        val output = copy?.let { FileChannel.open(it, WRITE, NOFOLLOW_LINKS) }
        output.use { channel ->
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                if (bytes > expectedBytes) archiveFailure("FILE_CHANGED_DURING_READ")
                digest.update(buffer, 0, count)
                if (channel != null) {
                    val chunk = ByteBuffer.wrap(buffer, 0, count)
                    while (chunk.hasRemaining()) channel.write(chunk)
                }
            }
            if (bytes != expectedBytes) archiveFailure("FILE_CHANGED_DURING_READ")
            channel?.force(true)
        }
    }
    return digest.digest().archiveHex()
}

internal fun archivePayloadFiles(root: Path): Map<String, ArchiveFileState> {
    val privateArchive = JvmPrivateDataDirectory.openExisting(root.toFile())
    val security = privateArchive.security()
    val rootNames = Files.newDirectoryStream(root).use { stream -> stream.asSequence().take(3).map { it.fileName.toString() }.toSet() }
    if (rootNames != setOf("manifest.json", "payload")) archiveFailure("ARCHIVE_ROOT_INVENTORY_MISMATCH")
    val files = linkedMapOf<String, ArchiveFileState>()
    val directories = mutableSetOf<String>()
    var entries = 0
    fun visit(directory: Path, depth: Int) {
        if (depth > 16) archiveFailure("PAYLOAD_INVENTORY_LIMIT")
        security.requirePrivateDirectory(directory)
        Files.newDirectoryStream(directory).use { stream ->
            for (child in stream) {
                if (++entries > LocalCacheArchive.MAX_FILES + 128) archiveFailure("PAYLOAD_INVENTORY_LIMIT")
                val relative = root.resolve("payload").relativize(child).toString().replace(File.separatorChar, '/')
                requireArchiveRelativePath(relative)
                if (Files.isDirectory(child, NOFOLLOW_LINKS)) { directories += relative; visit(child, depth + 1) }
                else {
                    val attributes = security.requirePrivateFile(child)
                    if (files.size >= LocalCacheArchive.MAX_FILES) archiveFailure("PAYLOAD_INVENTORY_LIMIT")
                    files[relative] = ArchiveFileState("", attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
                }
            }
        }
    }
    visit(root.resolve("payload"), 0)
    val expectedDirectories = files.keys.flatMap { path ->
        val parts = path.split('/')
        (1 until parts.size).map { parts.take(it).joinToString("/") }
    }.toSet()
    if (directories != expectedDirectories) archiveFailure("PAYLOAD_INVENTORY_MISMATCH")
    return files.toSortedMap()
}

internal fun forceArchiveDirectories(output: JvmPrivateDataDirectory) {
    Files.walk(output.root).use { paths ->
        paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.sorted(Comparator.reverseOrder()).forEach { output.security().forceDirectory(it) }
    }
}

internal fun readArchiveManifest(path: Path): String {
    val length = Files.size(path)
    if (length !in 1..LocalCacheArchive.MAX_MANIFEST_BYTES) archiveFailure("MANIFEST_SIZE_LIMIT")
    val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { it.readNBytes(LocalCacheArchive.MAX_MANIFEST_BYTES.toInt() + 1) }
    if (bytes.size.toLong() != length) archiveFailure("MANIFEST_CHANGED_DURING_READ")
    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}

internal fun archiveSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).archiveHex()
private fun ByteArray.archiveHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

package com.virjar.tk.agent

import com.virjar.tk.repository.UploadSource
import com.virjar.tk.repository.UploadSink
import com.virjar.tk.repository.DEFAULT_UPLOAD_CHUNK_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal immutable snapshot contract used by the retry-safe file coordinator and its tests. */
internal interface AgentPreparedUpload : AutoCloseable {
    val originalFileName: String
    val source: UploadSource
    val contentSha256: ByteArray
}

/** Private immutable upload snapshot. Closing it removes the staging file. */
internal class AgentStagedUpload internal constructor(
    override val originalFileName: String,
    override val source: UploadSource,
    override val contentSha256: ByteArray,
    internal val stagingPath: Path,
    private val stagingRoot: Path,
) : AgentPreparedUpload {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Files.deleteIfExists(stagingPath)
        FileChannel.open(stagingRoot, StandardOpenOption.READ).use { it.force(true) }
    }
}

/**
 * Single filesystem boundary for both headless upload endpoints.
 *
 * The caller path is opened once with NOFOLLOW and copied into a unique private staging file.
 * Upload retries only reopen that private snapshot; the original path is never returned.
 */
class AgentFileAccessPolicy(
    dataDir: File,
    userHome: File? = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { File(it) },
) {
    private val dataDirectory = AgentDataDirectoryPolicy.openRuntime(dataDir, userHome)
    private val outgoingRoot = dataDirectory.ensurePrivateChild(OUTGOING_DIRECTORY)
    private val stagingRoot = dataDirectory.ensurePrivateChild(STAGING_DIRECTORY)

    init {
        cleanupOrphanedStagingFiles()
    }

    internal fun stageUpload(rawPath: String): AgentStagedUpload {
        val verifiedSource = requireSourcePath(rawPath)
        val sourcePath = verifiedSource.path
        val originalName = sourcePath.fileName.toString()
        val partial = Files.createTempFile(
            stagingRoot,
            STAGING_FILE_PREFIX,
            STAGING_PARTIAL_SUFFIX,
            FILE_ATTRIBUTE,
        )
        val ready = stagingRoot.resolve("${partial.fileName}.ready")
        val digest = MessageDigest.getInstance("SHA-256")
        var installed = false
        try {
            Files.setPosixFilePermissions(partial, FILE_PERMISSIONS)
            val readOptions: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            Files.newByteChannel(sourcePath, readOptions).use { input ->
                validateSourceIdentity(verifiedSource)
                FileChannel.open(
                    partial,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { output ->
                    val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
                    var copied = 0L
                    while (true) {
                        buffer.clear()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        buffer.flip()
                        digest.update(buffer.asReadOnlyBuffer())
                        while (buffer.hasRemaining()) output.write(buffer)
                    }
                    require(copied == verifiedSource.size) {
                        "Upload source changed while it was being staged"
                    }
                    output.force(true)
                }
            }
            validateSourceIdentity(verifiedSource)
            require(
                unixInt(partial, "nlink") == 1 &&
                    unixInt(partial, "uid") == dataDirectory.ownerUid &&
                    unixInt(partial, "gid") == dataDirectory.ownerGid &&
                    posixPermissions(partial) == FILE_PERMISSIONS
            ) {
                "Private upload staging file identity changed"
            }
            try {
                Files.move(partial, ready, StandardCopyOption.ATOMIC_MOVE)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Staging filesystem does not support atomic install", unsupported)
            }
            forceDirectory(stagingRoot)
            val staged = AgentStagedUpload(
                originalFileName = originalName,
                source = PrivateStagedUploadSource(ready),
                contentSha256 = digest.digest(),
                stagingPath = ready,
                stagingRoot = stagingRoot,
            )
            installed = true
            return staged
        } finally {
            Files.deleteIfExists(partial)
            if (!installed) Files.deleteIfExists(ready)
        }
    }

    private fun requireSourcePath(rawPath: String): VerifiedUploadInput {
        require(rawPath.isNotBlank() && rawPath.none(Char::isISOControl)) { "Invalid upload path" }
        val supplied = Path.of(rawPath)
        require(supplied.none { it.toString() == ".." }) { "Parent traversal is not allowed" }
        val candidate = if (supplied.isAbsolute) supplied else outgoingRoot.resolve(supplied)
        val normalized = candidate.toAbsolutePath().normalize()
        require(normalized.startsWith(outgoingRoot) && normalized != outgoingRoot) {
            "Upload path is outside the outgoing directory"
        }
        rejectSymlinkComponents(normalized)
        val canonical = normalized.toRealPath()
        require(canonical.startsWith(outgoingRoot) && canonical != outgoingRoot) {
            "Upload path escapes the outgoing directory"
        }
        val attributes = Files.readAttributes(
            canonical,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(!attributes.isSymbolicLink && attributes.isRegularFile) {
            "Upload path must be a real regular file"
        }
        require(canonical.fileName.toString().lowercase() !in RESERVED_SECRET_NAMES) {
            "Agent private files cannot be uploaded"
        }
        require((Files.getAttribute(canonical, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1) {
            "Hard-linked upload files are not allowed"
        }
        require(posixPermissions(canonical).none { it in FORBIDDEN_SOURCE_PERMISSIONS }) {
            "Upload source cannot be group/world writable"
        }
        require(
            unixInt(canonical, "uid") == dataDirectory.ownerUid &&
                unixInt(canonical, "gid") == dataDirectory.ownerGid
        ) {
            "Upload source must be owned by the agent identity"
        }
        return VerifiedUploadInput(
            path = canonical,
            fileKey = requireNotNull(attributes.fileKey()) {
                "Upload filesystem must expose a stable file identity"
            },
            size = attributes.size(),
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun rejectSymlinkComponents(candidate: Path) {
        var current = outgoingRoot
        val components = outgoingRoot.relativize(candidate).toList()
        for ((index, component) in components.withIndex()) {
            current = current.resolve(component)
            val attributes = Files.readAttributes(
                current,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            require(!attributes.isSymbolicLink) { "Symlink upload paths are not allowed" }
            if (index < components.lastIndex) {
                require(attributes.isDirectory) { "Upload parent components must be directories" }
                require(posixPermissions(current).none { it in FORBIDDEN_SOURCE_PERMISSIONS }) {
                    "Upload parent directories cannot be group/world writable"
                }
                require(
                    unixInt(current, "uid") == dataDirectory.ownerUid &&
                        unixInt(current, "gid") == dataDirectory.ownerGid
                ) {
                    "Upload parent directories must be owned by the agent identity"
                }
            }
        }
    }

    private fun validateSourceIdentity(expected: VerifiedUploadInput) {
        val attributes = Files.readAttributes(
            expected.path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(
            !attributes.isSymbolicLink && attributes.isRegularFile &&
                attributes.fileKey() == expected.fileKey &&
                attributes.size() == expected.size &&
                attributes.lastModifiedTime().toMillis() == expected.lastModifiedMillis &&
                unixInt(expected.path, "nlink") == 1 &&
                unixInt(expected.path, "uid") == dataDirectory.ownerUid &&
                unixInt(expected.path, "gid") == dataDirectory.ownerGid &&
                posixPermissions(expected.path).none { it in FORBIDDEN_SOURCE_PERMISSIONS }
        ) {
            "Upload source changed while it was being staged"
        }
    }

    /** Single-owner startup boundary: remove only this implementation's direct staging names. */
    private fun cleanupOrphanedStagingFiles() {
        var changed = false
        Files.newDirectoryStream(stagingRoot).use { entries ->
            entries.forEach { entry ->
                val name = entry.fileName.toString()
                if (!name.startsWith(STAGING_FILE_PREFIX) ||
                    !(name.endsWith(STAGING_PARTIAL_SUFFIX) || name.endsWith(STAGING_READY_SUFFIX))
                ) {
                    return@forEach
                }
                require(entry.parent == stagingRoot) { "Invalid private staging entry" }
                val attributes = Files.readAttributes(
                    entry,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                require(attributes.isRegularFile || attributes.isSymbolicLink) {
                    "Invalid private staging entry type"
                }
                changed = Files.deleteIfExists(entry) || changed
            }
        }
        if (changed) forceDirectory(stagingRoot)
    }

    private fun posixPermissions(path: Path): Set<PosixFilePermission> {
        val view = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: error("Agent uploads require a POSIX filesystem")
        return view.readAttributes().permissions()
    }

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun unixInt(path: Path, attribute: String): Int =
        (Files.getAttribute(path, "unix:$attribute", LinkOption.NOFOLLOW_LINKS) as Number).toInt()

    private data class VerifiedUploadInput(
        val path: Path,
        val fileKey: Any,
        val size: Long,
        val lastModifiedMillis: Long,
    )

    private companion object {
        const val OUTGOING_DIRECTORY = "outgoing"
        const val STAGING_DIRECTORY = ".staging"
        const val STAGING_FILE_PREFIX = ".upload-"
        const val STAGING_PARTIAL_SUFFIX = ".partial"
        const val STAGING_READY_SUFFIX = ".partial.ready"
        const val COPY_BUFFER_BYTES = 64 * 1024
        val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        val FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)
        val RESERVED_SECRET_NAMES = setOf(
            "credentials.properties",
            "api-token",
            ".tt-cli",
            "bootstrap.env",
            ".tt-agent-data",
        )
        val FORBIDDEN_SOURCE_PERMISSIONS = setOf(
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE,
        )
    }
}

/** Reopens only the private immutable staging inode and validates it on every retry. */
private class PrivateStagedUploadSource(
    private val path: Path,
) : UploadSource {
    private val expectedAttributes = attributes()
    private val expectedFileKey = requireNotNull(expectedAttributes.fileKey()) {
        "Staging filesystem must expose a stable file identity"
    }

    override val contentLength: Long = expectedAttributes.size()

    override suspend fun writeTo(sink: UploadSink) = withContext(Dispatchers.IO) {
        validate()
        val options: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        Files.newByteChannel(path, options).use { input ->
            validate()
            val bytes = ByteArray(DEFAULT_UPLOAD_CHUNK_BYTES)
            val buffer = ByteBuffer.wrap(bytes)
            var written = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                sink.write(bytes, 0, read)
                written += read
            }
            check(written == contentLength) { "Private upload staging file changed during transfer" }
        }
        validate()
    }

    private fun validate() {
        val current = attributes()
        check(
            !current.isSymbolicLink && current.isRegularFile &&
                current.fileKey() == expectedFileKey && current.size() == contentLength &&
                (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 &&
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) == PRIVATE_PERMISSIONS
        ) {
            "Private upload staging identity changed"
        }
    }

    private fun attributes(): BasicFileAttributes = Files.readAttributes(
        path,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS,
    )

    private companion object {
        val PRIVATE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
    }
}

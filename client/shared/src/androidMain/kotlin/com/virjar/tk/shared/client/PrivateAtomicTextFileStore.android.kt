package com.virjar.tk.shared.client

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

internal actual fun privateAtomicTextFileStore(
    dataDir: File,
    privateDirectories: List<String>,
    fileName: String,
    replacementTemporaryFileName: String?,
): PrivateAtomicTextFileStore = NioPrivateAtomicTextFileStore(
    dataDir,
    privateDirectories,
    fileName,
    replacementTemporaryFileName,
)

private class NioPrivateAtomicTextFileStore(
    dataDir: File,
    private val privateDirectories: List<String>,
    private val fileName: String,
    private val replacementTemporaryFileName: String?,
) : PrivateAtomicTextFileStore {
    private val dataRoot = dataDir.toPath().toAbsolutePath().normalize()

    init {
        require(privateDirectories.isNotEmpty()) { "Private namespace must not be empty" }
        privateDirectories.forEach(::requireSafeComponent)
        requireSafeComponent(fileName)
        replacementTemporaryFileName?.let { temporary ->
            requireSafeComponent(temporary)
            require(temporary != fileName) { "Replacement temporary file must differ from its target" }
        }
    }

    override fun existsNonEmpty(): Boolean {
        val directory = openNamespace(create = false) ?: return false
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        return requirePrivateFile(target, directory).size() > 0L
    }

    override fun readText(maxBytes: Long): String? {
        require(maxBytes in 1L..Int.MAX_VALUE.toLong()) { "Invalid private text size limit" }
        val directory = openNamespace(create = false) ?: return null
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        val expected = requirePrivateFile(target, directory)
        require(expected.size() <= maxBytes) { "Private text file is too large" }
        val bytes = useResourcePreservingFatalFailure(FileChannel.open(target, READ_OPTIONS)) { channel ->
            val size = channel.size()
            require(size == expected.size() && size <= maxBytes) {
                "Private text file changed before read"
            }
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            require(!buffer.hasRemaining()) { "Private text file changed during read" }
            buffer.array()
        }
        val after = requirePrivateFile(target, directory)
        require(
            sameNioFileSnapshotIdentity(expected, after) &&
                expected.size() == after.size() && expected.lastModifiedTime() == after.lastModifiedTime(),
        ) { "Private text file changed during read" }
        return bytes.decodeToString()
    }

    override fun replaceText(content: String, maxBytes: Long) {
        require(maxBytes in 1L..Int.MAX_VALUE.toLong()) { "Invalid private text size limit" }
        val bytes = content.encodeToByteArray()
        require(bytes.size.toLong() <= maxBytes) { "Private text content is too large" }
        val directory = checkNotNull(openNamespace(create = true))
        val target = directory.resolve(fileName)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requirePrivateFile(target, directory)
        val temporary = replacementTemporaryFileName?.let { temporaryFileName ->
            cleanupPendingReplacement()
            directory.resolve(temporaryFileName).also { pending ->
                Files.createFile(pending, FILE_ATTRIBUTE)
                requirePrivateFile(pending, directory)
                forceDirectory(directory)
            }
        } ?: Files.createTempFile(directory, ".$fileName-", ".tmp", FILE_ATTRIBUTE)
        var installed = false
        var operationFailure: Throwable? = null
        try {
            requirePrivateFile(temporary, directory)
            useResourcePreservingFatalFailure(FileChannel.open(temporary, WRITE_OPTIONS)) { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            requirePrivateFile(temporary, directory)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requirePrivateFile(target, directory)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Private store requires atomic replacement", unsupported)
            }
            requirePrivateFile(target, directory)
            forceDirectory(directory)
            installed = true
        } catch (failure: Throwable) {
            operationFailure = failure
            throw failure
        } finally {
            try {
                if (!installed || Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(temporary)
                }
            } catch (cleanupFailure: Throwable) {
                val terminal = mergeSessionLifecycleFailures(operationFailure, cleanupFailure)
                if (operationFailure == null || terminal !== operationFailure) throw terminal
            }
        }
    }

    override fun cleanupPendingReplacement(): Boolean {
        val temporaryFileName = replacementTemporaryFileName ?: return false
        val directory = openNamespace(create = false) ?: return false
        val pending = directory.resolve(temporaryFileName)
        if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) return false
        requirePrivateFile(pending, directory)
        Files.delete(pending)
        forceDirectory(directory)
        return true
    }

    override fun delete(): Boolean {
        val directory = openNamespace(create = false) ?: return false
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        requirePrivateFile(target, directory)
        Files.delete(target)
        forceDirectory(directory)
        return true
    }

    private fun openNamespace(create: Boolean): Path? {
        requireTrustedDataRoot()
        var directory = dataRoot
        privateDirectories.forEach { component ->
            val child = directory.resolve(component)
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return null
                try {
                    Files.createDirectory(child, DIRECTORY_ATTRIBUTE)
                    forceDirectory(directory)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // 在下方校验；一个竞争产生的不安全路径绝不会被采纳。
                }
            }
            requirePrivateDirectory(child, directory)
            directory = child
        }
        return directory
    }

    /** Android 的应用私有数据根目录是可信的，但这里从不修改其权限或重写它。 */
    private fun requireTrustedDataRoot() {
        val attributes = Files.readAttributes(
            dataRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "Private store data root must be a real directory"
        }
    }

    private fun requirePrivateDirectory(path: Path, parent: Path) {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "Private namespace component must be a real directory"
        }
        requireSameOwner(path, parent)
        require(posixPermissions(path) == DIRECTORY_PERMISSIONS) {
            "Existing private namespace permissions are not 0700"
        }
    }

    private fun requirePrivateFile(path: Path, directory: Path): BasicFileAttributes {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "Private payload must be a real regular file"
        }
        requireSameOwner(path, directory)
        require(posixPermissions(path) == FILE_PERMISSIONS) {
            "Existing private payload permissions are not 0600"
        }
        require(
            (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1,
        ) { "Hard-linked private payloads are not allowed" }
        return attributes
    }

    private fun requireSameOwner(path: Path, parent: Path) {
        require(
            Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) ==
                Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS),
        ) { "Private path owner does not match its parent" }
    }

    private fun posixPermissions(path: Path): Set<PosixFilePermission> {
        val view = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: error("Private store requires a POSIX filesystem")
        return view.readAttributes().permissions()
    }

    private fun forceDirectory(directory: Path) {
        useResourcePreservingFatalFailure(FileChannel.open(directory, StandardOpenOption.READ)) { channel ->
            channel.force(true)
        }
    }

    private fun requireSafeComponent(component: String) {
        require(
            component.isNotBlank() &&
                component != "." &&
                component != ".." &&
                component.none { it == '/' || it == '\\' || it.isISOControl() },
        ) { "Unsafe private-store path component" }
    }

    private companion object {
        val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        val DIRECTORY_ATTRIBUTE = PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS)
        val FILE_ATTRIBUTE = PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)
        val READ_OPTIONS: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        val WRITE_OPTIONS: Set<OpenOption> = setOf(
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        )
    }
}

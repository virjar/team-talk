package com.virjar.tk.client

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
): PrivateAtomicTextFileStore = NioPrivateAtomicTextFileStore(dataDir, privateDirectories, fileName)

private class NioPrivateAtomicTextFileStore(
    dataDir: File,
    private val privateDirectories: List<String>,
    private val fileName: String,
) : PrivateAtomicTextFileStore {
    private val dataRoot = dataDir.toPath().toAbsolutePath().normalize()

    init {
        require(privateDirectories.isNotEmpty()) { "Private namespace must not be empty" }
        privateDirectories.forEach(::requireSafeComponent)
        requireSafeComponent(fileName)
    }

    override fun existsNonEmpty(): Boolean {
        val directory = openNamespace(create = false) ?: return false
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        return requirePrivateFile(target, directory).size() > 0L
    }

    override fun readText(): String? {
        val directory = openNamespace(create = false) ?: return null
        val target = directory.resolve(fileName)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        requirePrivateFile(target, directory)
        val bytes = FileChannel.open(target, READ_OPTIONS).use { channel ->
            val size = channel.size()
            require(size <= Int.MAX_VALUE) { "Private text file is too large" }
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            require(!buffer.hasRemaining()) { "Private text file changed during read" }
            buffer.array()
        }
        return bytes.decodeToString()
    }

    override fun replaceText(content: String) {
        val directory = checkNotNull(openNamespace(create = true))
        val target = directory.resolve(fileName)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requirePrivateFile(target, directory)
        val temporary = Files.createTempFile(directory, ".$fileName-", ".tmp", FILE_ATTRIBUTE)
        var installed = false
        try {
            requirePrivateFile(temporary, directory)
            FileChannel.open(temporary, WRITE_OPTIONS).use { channel ->
                val buffer = ByteBuffer.wrap(content.encodeToByteArray())
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
        } finally {
            if (!installed || Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(temporary)
            }
        }
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
                    // Validate below; a competing unsafe path is never adopted.
                }
            }
            requirePrivateDirectory(child, directory)
            directory = child
        }
        return directory
    }

    /** Android's app-private data root is trusted but never chmodded or rewritten here. */
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
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
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

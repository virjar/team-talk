package com.virjar.tk.shared.repository

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

internal actual fun chatAssetSpoolFiles(dataDir: File, directories: List<String>): ChatAssetSpoolFiles {
    val root = dataDir.toPath().toAbsolutePath().normalize()
    val rootAttributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(rootAttributes.isDirectory && !rootAttributes.isSymbolicLink) { "Private attachment root is not a directory" }
    val owner = Files.getOwner(root, NOFOLLOW_LINKS)
    val directoryPermissions = PosixFilePermissions.fromString("rwx------")
    val filePermissions = PosixFilePermissions.fromString("rw-------")
    fun namespace(): Path {
        var current = root
        for (name in directories) {
            require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' })
            current = current.resolve(name)
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current, PosixFilePermissions.asFileAttribute(directoryPermissions))
                    FileChannel.open(current.parent, READ).use { it.force(true) }
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // A competing creator must still pass the exact owner/type/mode checks below.
                }
            }
            val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.isDirectory && !attributes.isSymbolicLink &&
                Files.getOwner(current, NOFOLLOW_LINKS) == owner &&
                Files.getPosixFilePermissions(current, NOFOLLOW_LINKS) == directoryPermissions) {
                "Invalid private attachment directory"
            }
        }
        return current
    }
    val path = namespace()
    return object : ChatAssetSpoolFiles {
        override val directory: Path = path

        override fun createFile(name: String): Path {
            namespace()
            return Files.createFile(path.resolve(name), PosixFilePermissions.asFileAttribute(filePermissions))
        }

        override fun requireFile(path: Path): BasicFileAttributes {
            namespace()
            require(path.parent == directory) { "Attachment source escaped its private namespace" }
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.isRegularFile && !attributes.isSymbolicLink &&
                Files.getOwner(path, NOFOLLOW_LINKS) == owner &&
                Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == filePermissions &&
                (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt() == 1) {
                "Invalid private attachment source"
            }
            return attributes
        }

        override fun forceDirectory() {
            FileChannel.open(directory, READ).use { it.force(true) }
        }
    }
}

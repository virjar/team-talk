package com.virjar.tk.shared.repository

import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal actual fun chatAssetSpoolFiles(dataDir: File, directories: List<String>): ChatAssetSpoolFiles {
    val root = JvmPrivateDataDirectory.openExisting(dataDir)
    val namespace = root.ensureDirectory(*directories.toTypedArray()).toPath()
    val security = root.security()
    return object : ChatAssetSpoolFiles {
        override val directory: Path = namespace

        override fun createFile(name: String): Path {
            root.ensureDirectory(*directories.toTypedArray())
            val target = namespace.resolve(name)
            check(!Files.exists(target, NOFOLLOW_LINKS)) { "Private attachment source already exists" }
            security.createEmptyFile(target)
            return target
        }

        override fun requireFile(path: Path): BasicFileAttributes {
            root.ensureDirectory(*directories.toTypedArray())
            require(path.parent == namespace) { "Attachment source escaped its private namespace" }
            return security.requirePrivateFile(path)
        }

        override fun forceDirectory() = security.forceDirectory(namespace)
    }
}

package com.virjar.tk.server.infra.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

internal const val UPLOAD_STAGING_TEMP_PREFIX = "teamtalk-upload-"
internal const val FILE_STORE_TEMP_PREFIX = "teamtalk-store-"
internal const val THUMBNAIL_TEMP_PREFIX = "teamtalk-thumb-"
internal const val THUMBNAIL_RESULT_TEMP_PREFIX = "teamtalk-thumb-result-"
internal const val STAGING_TEMP_SUFFIX = ".tmp"
internal const val THUMBNAIL_TEMP_SUFFIX = ".jpg"

/** 一个受管临时条目在其运行时回收点之后无法被证明不存在。 */
internal class ManagedTempResidueException(
    internal val entry: Path? = null,
    cause: Throwable? = null,
) :
    IllegalStateException("Managed temporary file retirement could not be confirmed", cause)

/** 上传暂存与生成的媒体缩略图共用的安全边界。 */
internal object ManagedTempFiles {
    private val crashResidueNames = listOf(
        ManagedTempName(UPLOAD_STAGING_TEMP_PREFIX, STAGING_TEMP_SUFFIX),
        ManagedTempName(FILE_STORE_TEMP_PREFIX, STAGING_TEMP_SUFFIX),
        ManagedTempName(THUMBNAIL_TEMP_PREFIX, THUMBNAIL_TEMP_SUFFIX),
        ManagedTempName(THUMBNAIL_RESULT_TEMP_PREFIX, STAGING_TEMP_SUFFIX),
    )
    private val directoryPermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )
    private val filePermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

    fun ensureDirectory(root: File): File {
        val path = root.toPath().toAbsolutePath().normalize()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            check(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                "Managed FileStore temporary root must be a real directory: $path"
            }
        } else {
            Files.createDirectories(path)
        }
        setPosixPermissionsIfSupported(path, directoryPermissions)
        return path.toFile()
    }

    fun create(root: File, prefix: String, suffix: String): File {
        val directory = ensureDirectory(root)
        val path = Files.createTempFile(directory.toPath(), prefix, suffix)
        return try {
            setPosixPermissionsIfSupported(path, filePermissions)
            path.toFile()
        } catch (failure: Throwable) {
            try {
                Files.deleteIfExists(path)
            } catch (cleanupFailure: Throwable) {
                val residue = ManagedTempResidueException(path, cleanupFailure)
                residue.addSuppressed(failure)
                throw residue
            }
            throw failure
        }
    }

    /**
     * 运行时回收是 fail-closed 的。不存在即成功；其余每种结果都由
     * 一个带类型的失败表示，使准入拥有者能保留其最坏情况的暂存预留。
     */
    fun retire(
        root: File,
        file: File,
        deleteFile: (Path) -> Unit = { path -> Files.delete(path) },
    ) {
        val directory = ensureDirectory(root).toPath().toAbsolutePath().normalize()
        val entry = file.toPath().toAbsolutePath().normalize()
        if (entry.parent != directory || entry == directory || !isCrashResidueName(entry.fileName.toString())) {
            throw ManagedTempResidueException(entry)
        }
        if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            throw ManagedTempResidueException(entry)
        }
        try {
            deleteFile(entry)
        } catch (failure: Throwable) {
            throw ManagedTempResidueException(entry, failure)
        }
        if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) throw ManagedTempResidueException(entry)
    }

    /**
     * 只回收 FileStore 拥有的崩溃残留。这必须在调用方持有 RocksDB
     * 锁之后运行：否则落败的服务器进程可能删除活跃拥有者正在使用的暂存文件。
     *
     * 受管名称是安全不变量，不是尽力而为的提示。拒绝符号链接、
     * 目录与失败的删除，可防止部分清理的临时根被
     * 发布为健康的 FileStore。
     */
    fun cleanupCrashResidue(
        root: File,
        deleteFile: (Path) -> Unit = { path -> Files.delete(path) },
    ): Int {
        val directory = ensureDirectory(root).toPath().toAbsolutePath().normalize()
        var deleted = 0
        Files.newDirectoryStream(directory).use { entries ->
            for (rawEntry in entries) {
                val entry = rawEntry.toAbsolutePath().normalize()
                check(entry.parent == directory && entry != directory) {
                    "Managed FileStore temporary entry escaped its root"
                }
                if (!isCrashResidueName(entry.fileName.toString())) continue
                if (!(
                    !Files.isSymbolicLink(entry) &&
                        Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                )) {
                    throw ManagedTempResidueException(entry)
                }
                try {
                    deleteFile(entry)
                } catch (failure: Throwable) {
                    throw ManagedTempResidueException(entry, failure)
                }
                if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) throw ManagedTempResidueException(entry)
                deleted += 1
            }
        }
        return deleted
    }

    private fun isCrashResidueName(fileName: String): Boolean = crashResidueNames.any { name ->
        fileName.startsWith(name.prefix) &&
            fileName.endsWith(name.suffix) &&
            fileName.length > name.prefix.length + name.suffix.length
    }

    private fun setPosixPermissionsIfSupported(
        path: java.nio.file.Path,
        permissions: Set<PosixFilePermission>,
    ) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Windows 与其他非 POSIX 文件系统使用平台的 createTempFile ACL。
        }
    }

    private data class ManagedTempName(
        val prefix: String,
        val suffix: String,
    )
}

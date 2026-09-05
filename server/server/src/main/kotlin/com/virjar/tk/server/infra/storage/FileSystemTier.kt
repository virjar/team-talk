package com.virjar.tk.server.infra.storage

import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.virjar.tk.server.runtime.mergeRuntimeFailure
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class FileSystemTier(
    db: RocksDB,
    dataCf: ColumnFamilyHandle,
    private val dataRoot: File,
) : StorageTierBackend(db, dataCf) {

    init {
        dataRoot.mkdirs()
    }

    override suspend fun streamTo(meta: FileMetadata, channel: ByteWriteChannel, range: ReadRange?) {
        val file = resolveFile(meta.storageKey)
        streamFilesystemEntry(file, meta.storageKey, channel, range)
    }

    fun moveFrom(storageKey: String, sourceFile: File) {
        val target = resolveFile(storageKey)
        check((target.parentFile.isDirectory || target.parentFile.mkdirs()) && target.parentFile.isDirectory) {
            "Cannot create attachment storage directory: ${target.parentFile}"
        }
        try {
            if (!sourceFile.renameTo(target)) {
                sourceFile.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                        }
                    }
                }
                check(sourceFile.delete()) { "Failed to retire staged attachment after copy" }
            }
            forcePublishedAttachment(target, dataRoot)
        } catch (failure: Throwable) {
            var terminalFailure = failure
            try {
                if (target.exists()) check(target.delete())
            } catch (cleanupFailure: Throwable) {
                terminalFailure = mergeRuntimeFailure(terminalFailure, cleanupFailure)
            }
            throw terminalFailure
        }
    }

    internal fun storedSize(storageKey: String): Long? =
        resolveFile(storageKey).takeIf(File::isFile)?.length()

    internal fun deleteIfExists(storageKey: String): Boolean =
        resolveFile(storageKey).let { target -> !target.exists() || target.delete() }

    private fun resolveFile(storageKey: String): File {
        val level1: String
        val level2: String
        if (storageKey.length >= 4) {
            level1 = storageKey.substring(0, 2)
            level2 = storageKey.substring(2, 4)
        } else {
            level1 = "00"
            level2 = "00"
        }
        return File(File(File(dataRoot, level1), level2), "$storageKey.dat")
    }
}

/** 让每次阻塞的文件系统读取（包括元数据探测）远离请求/默认线程。 */
internal suspend fun streamFilesystemEntry(
    file: File,
    storageKey: String,
    channel: ByteWriteChannel,
    range: ReadRange?,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) = withContext(ioDispatcher) {
    if (!file.exists()) throw IllegalStateException("File data missing for key: $storageKey")
    val buf = ByteArray(64 * 1024)
    if (range != null) {
        val slice = range.boundedReadSlice(file.length())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(slice.offset)
            var remaining = slice.length
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = raf.read(buf, 0, toRead)
                if (read == -1) break
                channel.writeFully(buf, 0, read)
                remaining -= read
            }
            channel.flush()
        }
    } else {
        file.inputStream().buffered().use { input ->
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                channel.writeFully(buf, 0, read)
            }
            channel.flush()
        }
    }
}

/**
 * 先强制落盘对象字节，再逐级强制每个目录条目直到配置的存储根。
 * 元数据只在此边界成功返回之后才允许提交到 RocksDB。
 */
internal fun forcePublishedAttachment(
    target: File,
    dataRoot: File,
    force: (Path, Boolean) -> Unit = ::forceAttachmentPath,
) {
    val targetPath = target.toPath().toAbsolutePath().normalize()
    val rootPath = dataRoot.toPath().toAbsolutePath().normalize()
    require(targetPath.startsWith(rootPath) && targetPath != rootPath) {
        "Attachment target must remain below its storage root"
    }
    require(targetPath.toFile().isFile) { "Attachment target is not a regular file" }

    force(targetPath, true)
    var directory = targetPath.parent
    while (directory != null && directory.startsWith(rootPath)) {
        force(directory, true)
        if (directory == rootPath) return
        directory = directory.parent
    }
    error("Attachment directory chain does not reach its storage root")
}

private fun forceAttachmentPath(path: Path, metadata: Boolean) {
    val option = if (path.toFile().isDirectory) StandardOpenOption.READ else StandardOpenOption.WRITE
    FileChannel.open(path, option).use { channel -> channel.force(metadata) }
}

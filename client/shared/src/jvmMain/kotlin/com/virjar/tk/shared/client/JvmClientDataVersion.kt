package com.virjar.tk.shared.client

import com.virjar.tk.protocol.ProtocolVersions
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 在任何凭据/数据库打开前执行；Desktop 调用方必须已经持有本数据根的进程锁。 */
fun prepareJvmClientDataVersion(dataDir: File, currentMajor: Int = ProtocolVersions.MAJOR): Boolean {
    val directory = JvmPrivateDataDirectory.openExisting(dataDir)
    val marker = directory.atomicTextFile(fileName = CLIENT_DATA_VERSION_FILE)
    return prepareClientDataVersion(
        currentMajor = currentMajor,
        readMarker = { marker.readText(128) },
        writeMarker = marker::replaceText,
        resetOwnedData = {
            Files.newDirectoryStream(directory.root).use { children ->
                children.filter { it.fileName.toString() !in retainedInstallationFiles }.forEach(::deleteOwnedTree)
            }
        },
    )
}

private const val CLIENT_DATA_VERSION_FILE = ".client-data-version"
// 仅保留安装目录认领、进程锁和本次恢复日志，账号、草稿、缓存及本地凭据全部重建。
private val retainedInstallationFiles = setOf(
    CLIENT_DATA_VERSION_FILE, ".teamtalk-desktop-data", ".tt-agent-data", ".lock",
)

/** 不跟随任何符号链接；失败上抛并保留 reset 标记，由下一次启动继续同一重置。 */
private fun deleteOwnedTree(root: Path) {
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

/** 无头入口与 Desktop 的 .lock 使用同一互斥边界；必须持有到缓存和连接完全关闭。 */
class JvmClientDataLease private constructor(
    private val channel: java.nio.channels.FileChannel,
    private val lock: java.nio.channels.FileLock,
) : AutoCloseable {
    override fun close() {
        try { lock.release() } finally { channel.close() }
    }

    companion object {
        fun acquire(dataDir: File): JvmClientDataLease {
            val file = JvmPrivateDataDirectory.openExisting(dataDir).preparePrivateFile(emptyList(), ".lock")
            val channel = java.nio.channels.FileChannel.open(
                file.toPath(), java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE,
            )
            return try {
                val lock = checkNotNull(channel.tryLock()) { "Another client is already using this data directory" }
                JvmClientDataLease(channel, lock)
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }
    }
}

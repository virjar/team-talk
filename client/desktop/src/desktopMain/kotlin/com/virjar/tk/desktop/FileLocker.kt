package com.virjar.tk.desktop

import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * 基于文件的锁，防止多个 Desktop 实例使用同一数据目录。
 * JVM 进程退出（即使崩溃）时锁会被自动释放。
 */
class FileLocker(private val dataDir: File) {
    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /**
     * 尝试获取数据目录上的排他锁。
     * @return 获取成功返回 true；若另一个实例持有该锁则返回 false。
     */
    fun tryLock(): Boolean {
        val privateData = JvmPrivateDataDirectory.openExisting(dataDir)
        val lockFile = privateData.preparePrivateFile(emptyList(), ".lock")
        try {
            raf = RandomAccessFile(lockFile, "rw")
            channel = raf!!.channel
            lock = channel!!.tryLock()
            if (lock != null) {
                raf!!.setLength(0)
                raf!!.writeBytes("pid=${ProcessHandle.current().pid()}\n")
                raf!!.fd.sync()
                privateData.requirePrivateFile(emptyList(), ".lock")
                return true
            }
        } catch (_: IOException) {
            // 某些平台会把已持有的锁报告为 IOException，而不是返回 null。
        } catch (_: OverlappingFileLockException) {
            // 本 JVM 已持有一把重叠的锁。
        }
        release()
        return false
    }

    /** 释放锁。在正常关闭时调用。 */
    fun release() {
        try { lock?.release() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        try { raf?.close() } catch (_: Exception) {}
        lock = null
        channel = null
        raf = null
    }
}

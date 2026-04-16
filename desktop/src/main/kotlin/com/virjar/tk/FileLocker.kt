package com.virjar.tk

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * File-based lock to prevent multiple Desktop instances from using the same data directory.
 * The lock is released automatically when the JVM process exits (even on crash).
 */
class FileLocker(private val dataDir: File) {
    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /**
     * Try to acquire an exclusive lock on the data directory.
     * @return true if lock acquired, false if another instance holds the lock.
     */
    fun tryLock(): Boolean {
        try {
            dataDir.mkdirs()
            val lockFile = File(dataDir, ".lock")
            raf = RandomAccessFile(lockFile, "rw")
            channel = raf!!.channel
            lock = channel!!.tryLock()
            if (lock != null) {
                // Write PID for debugging
                raf!!.setLength(0)
                raf!!.writeBytes("pid=${ProcessHandle.current().pid()}\n")
                return true
            }
        } catch (_: Exception) {
            // Lock failed
        }
        release()
        return false
    }

    /** Release the lock. Called on normal shutdown. */
    fun release() {
        try { lock?.release() } catch (_: Exception) {}
        try { channel?.close() } catch (_: Exception) {}
        try { raf?.close() } catch (_: Exception) {}
        lock = null
        channel = null
        raf = null
    }
}

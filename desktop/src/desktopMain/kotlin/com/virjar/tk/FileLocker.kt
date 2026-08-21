package com.virjar.tk

import com.virjar.tk.client.JvmPrivateDataDirectory
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

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
            // A platform can report an already-held lock as an IOException instead of null.
        } catch (_: OverlappingFileLockException) {
            // This JVM already owns an overlapping lock.
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

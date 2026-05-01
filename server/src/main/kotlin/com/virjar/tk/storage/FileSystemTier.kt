package com.virjar.tk.storage

import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class FileSystemTier(private val dataRoot: File) {

    init {
        dataRoot.mkdirs()
    }

    fun moveFrom(storageKey: String, sourceFile: File) {
        val target = resolveFile(storageKey)
        target.parentFile.mkdirs()
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
            sourceFile.delete()
        }
    }

    suspend fun streamTo(storageKey: String, channel: ByteWriteChannel, range: ReadRange? = null) {
        val file = resolveFile(storageKey)
        if (!file.exists()) throw IllegalStateException("File data missing for key: $storageKey")
        val buf = ByteArray(64 * 1024)
        if (range != null) {
            withContext(Dispatchers.IO) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(range.start)
                    var remaining = range.end - range.start + 1
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = raf.read(buf, 0, toRead)
                        if (read == -1) break
                        channel.writeFully(buf, 0, read)
                        remaining -= read
                    }
                    channel.flush()
                }
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

    fun delete(storageKey: String): Boolean {
        val file = resolveFile(storageKey)
        return file.delete()
    }

    fun resolveFile(storageKey: String): File {
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

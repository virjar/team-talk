package com.virjar.tk.client

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

/**
 * Crash 日志持久化 + 重启上传。
 *
 * Desktop: UncaughtExceptionHandler 捕获后同步尝试上传（3s），失败则落盘。
 * Android: crash 后进程死亡，落盘后下次启动上传。
 *
 * pending 文件原子写：先写 .tmp 再 rename，避免进程中途死亡产生半写文件。
 */
class CrashDumper(
    private val dataDir: File,
) {
    private val pendingFile = File(dataDir, "pending-crash.log")
    private val tmpFile = File(dataDir, "pending-crash.log.tmp")

    /** 是否有待上传的 crash 日志。 */
    fun hasPending(): Boolean = pendingFile.exists() && pendingFile.length() > 0

    /**
     * 原子写入 pending 文件。
     * 进程随时可能死亡，必须先写 .tmp 再 rename（同文件系统内 rename 是原子的）。
     */
    fun flushPending(content: String) {
        synchronized(this) {
            tmpFile.writeText(content)
            tmpFile.renameTo(pendingFile)
        }
    }

    /**
     * 上传 pending crash 日志（启动时调用）。
     * 上传成功后删除 pending 文件。
     */
    fun uploadPending(serverUrl: String, deviceId: String) {
        if (!hasPending()) return
        synchronized(this) {
            val text = pendingFile.readText()
            try {
                val compressed = gzip(text)
                val conn = (URL("$serverUrl/api/client-logs").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/gzip")
                    setRequestProperty("X-Device-Id", deviceId)
                }
                conn.outputStream.use { it.write(compressed) }
                if (conn.responseCode == 200) {
                    pendingFile.delete()
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // 上传失败，保留 pending 文件，下次启动再试
            }
        }
    }

    private fun gzip(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.encodeToByteArray()) }
        return bos.toByteArray()
    }
}

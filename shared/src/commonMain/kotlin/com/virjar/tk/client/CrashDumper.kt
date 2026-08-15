package com.virjar.tk.client

import com.virjar.tk.util.HttpUtil
import java.io.File

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
     * 原子写入 pending 文件，内部容错。
     * 进程随时可能死亡，必须先写 .tmp 再 rename（同文件系统内 rename 是原子的）。
     * 如果目录不存在则自动创建；写入失败不抛出异常，避免掩盖原始异常。
     */
    fun flushPending(content: String) {
        synchronized(this) {
            try {
                dataDir.mkdirs()
                tmpFile.writeText(content)
                tmpFile.renameTo(pendingFile)
            } catch (_: Exception) {
                // 即使持久化失败也不能让 crash dump 本身变成二次崩溃
            }
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
                val compressed = HttpUtil.gzip(text)
                val code = HttpUtil.postGzip(
                    "$serverUrl/api/client-logs",
                    compressed,
                    mapOf("X-Device-Id" to deviceId),
                )
                if (code == 200) {
                    pendingFile.delete()
                }
            } catch (_: Exception) {
                // 上传失败，保留 pending 文件，下次启动再试
            }
        }
    }
}

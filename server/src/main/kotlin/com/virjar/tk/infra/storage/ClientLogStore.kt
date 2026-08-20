package com.virjar.tk.infra.storage

import com.virjar.tk.env.Environment
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 客户端日志文件存储。
 *
 * 目录结构：`$dataRoot/client-logs/{uid}/{deviceId}/{date}.log`
 * 日志以纯文本追加写入，自动清理超过 7 天的文件。
 */
class ClientLogStore(
    private val rootDir: File = File(Environment.dataRoot, "client-logs"),
) {
    private val logger = LoggerFactory.getLogger("ClientLogStore")

    /** 日志保留天数 */
    private val retentionDays = 7

    /** Persist a decoded log under the authenticated identity supplied by the HTTP boundary. */
    fun store(uid: String, deviceId: String, text: String) {
        requireSafeSegment(uid, "uid")
        requireSafeSegment(deviceId, "deviceId")
        synchronized(this) { writeText(uid, deviceId, text) }
    }

    private fun writeText(uid: String, deviceId: String, text: String) {
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dir = File(File(rootDir, uid), deviceId)
        dir.mkdirs()
        val file = File(dir, "$date.log")
        file.appendText(text)
        logger.debug("Client log appended: uid=$uid device=$deviceId size=${text.length}")
    }

    private fun requireSafeSegment(value: String, field: String) {
        require(
            value.length in 1..100 &&
                value != "." &&
                value != ".." &&
                value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' },
        ) { "Invalid $field for client log storage" }
    }

    /**
     * 清理过期日志文件。由调用方定期调用。
     */
    fun cleanup() {
        val cutoff = LocalDate.now().minusDays(retentionDays.toLong())
        cleanupDir(rootDir, cutoff)
    }

    private fun cleanupDir(dir: File, cutoff: LocalDate) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                cleanupDir(file, cutoff)
                // 空目录删除
                if (file.listFiles()?.isEmpty() == true) {
                    file.delete()
                }
            } else if (file.name.endsWith(".log")) {
                val dateStr = file.nameWithoutExtension
                try {
                    val fileDate = LocalDate.parse(dateStr)
                    if (fileDate.isBefore(cutoff)) {
                        file.delete()
                    }
                } catch (_: Exception) {
                    // 文件名不是日期格式，跳过
                }
            }
        }
    }
}

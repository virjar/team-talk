package com.virjar.tk.util

import java.io.File
import java.io.PrintWriter
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Desktop 平台日志实现：极简 FileWriter 替代 logback。
 *
 * - 按天单文件（app-yyyy-MM-dd.log），不做滚动压缩
 * - 自动清理 7 天前的旧日志
 * - 不依赖 logback/slf4j 实现，不依赖 java.xml
 */
internal actual fun platformLog(level: String, tag: String, msg: String, throwable: Throwable?) {
    LocalLogFile.append(level, tag, msg, throwable)
}

internal object LocalLogFile {
    private val logDir = File(System.getProperty("teamtalk.data.dir"), "logs").apply { mkdirs() }
    private val tsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    private var currentDate: LocalDate = LocalDate.now()
    private var writer: PrintWriter = openWriter(currentDate)

    @Synchronized
    fun append(level: String, tag: String, msg: String, throwable: Throwable?) {
        // 跨天时切换文件
        val today = LocalDate.now()
        if (today != currentDate) {
            writer.close()
            currentDate = today
            writer = openWriter(today)
            cleanOldLogs(7)
        }

        val ts = LocalDateTime.now().format(tsFormatter)
        writer.println("$ts|$level|$tag|${msg.replace("\n", " ")}")
        if (throwable != null) {
            throwable.printStackTrace(writer)
        }
        writer.flush()
    }

    private fun openWriter(date: LocalDate): PrintWriter {
        val file = File(logDir, "app-${date}.log")
        return PrintWriter(FileWriter(file, true), true)
    }

    private fun cleanOldLogs(maxDays: Long) {
        val cutoff = LocalDate.now().minusDays(maxDays)
        logDir.listFiles()?.forEach { f ->
            val name = f.name
            if (name.startsWith("app-") && name.endsWith(".log")) {
                val dateStr = name.removePrefix("app-").removeSuffix(".log")
                runCatching {
                    LocalDate.parse(dateStr).takeIf { it.isBefore(cutoff) }?.let { f.delete() }
                }
            }
        }
    }
}

package com.virjar.tk.shared.log

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal actual fun platformLog(level: String, tag: String, msg: String, throwable: Throwable?) {
    try {
        if (!LocalLogFile.append(level, tag, msg, throwable)) {
            consoleFallback(level, tag, msg, throwable)
        }
    } catch (_: Throwable) {
        // 本地诊断绝不能改变业务操作的结果。
        consoleFallback(level, tag, msg, throwable)
    }
}

/** 进程外观，同时把测试/嵌入式 Desktop 运行时中的数据根目录变更挡在栅栏外。 */
internal object LocalLogFile {
    private var store: DesktopLocalLogStore? = null
    private var storeRoot: File? = null

    @Synchronized
    fun append(level: String, tag: String, msg: String, throwable: Throwable?): Boolean {
        val configuredRoot = System.getProperty("teamtalk.data.dir") ?: return false
        val root = File(configuredRoot).absoluteFile.normalize()
        if (store == null || storeRoot != root) {
            val retired = store
            store = null
            storeRoot = null
            retired?.close()
            store = DesktopLocalLogStore(File(root, "logs"))
            storeRoot = root
        }
        try {
            checkNotNull(store).append(level, tag, msg, throwable)
        } catch (failure: Throwable) {
            val failed = store
            store = null
            storeRoot = null
            runCatching { failed?.close() }
            throw failure
        }
        return true
    }
}

private fun consoleFallback(level: String, tag: String, msg: String, throwable: Throwable?) {
    try {
        val line = "[$level][$tag] ${msg.replace('\r', ' ').replace('\n', ' ')}"
        if (level == "fault" || level == "error" || level == "fatal") {
            System.err.println(line)
            throwable?.printStackTrace(System.err)
        } else {
            println(line)
        }
    } catch (_: Throwable) {
        // 即使进程控制台本身不可用，日志仍然保持非权威。
    }
}

/**
 * 保留七天的 Desktop 日志存储，单段上限 8 MiB，目录上限 64 MiB。
 * 清理在构造期间执行（因此发生在应用的第一次日志上），而不只是在
 * 日期边界。每次日期/大小切换都会先关闭上一个 writer，再发布新的。
 */
internal class DesktopLocalLogStore(
    private val logDir: File,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val retentionDays: Long = DEFAULT_RETENTION_DAYS,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) : Closeable {
    private var closed = false
    private var currentDate: LocalDate
    private var currentIndex: Int
    private var currentFile: File
    private var currentBytes: Long
    private var writer: BufferedWriter

    init {
        require(retentionDays > 0L) { "Desktop log retention must be positive" }
        require(maxFileBytes in MIN_FILE_BYTES..MAX_ABSOLUTE_FILE_BYTES) {
            "Invalid Desktop log segment budget"
        }
        require(maxTotalBytes >= maxFileBytes && maxTotalBytes <= MAX_ABSOLUTE_TOTAL_BYTES) {
            "Invalid Desktop log directory budget"
        }
        require(logDir.mkdirs() || logDir.isDirectory) { "Cannot create Desktop log directory" }
        val initialNow = now()
        cleanExpired(initialNow.toLocalDate())
        val selected = selectWritableFile(initialNow.toLocalDate())
        currentDate = initialNow.toLocalDate()
        currentIndex = selected.first
        currentFile = selected.second
        currentBytes = currentFile.length()
        writer = openWriter(currentFile)
        trimTotalBudget()
    }

    @Synchronized
    fun append(level: String, tag: String, msg: String, throwable: Throwable?) {
        check(!closed) { "Desktop local log store is closed" }
        val timestamp = now()
        if (timestamp.toLocalDate() != currentDate) rotateToDate(timestamp.toLocalDate())
        val entry = boundEntry(encodeEntry(timestamp, level, tag, msg, throwable))
        val encodedSize = entry.toByteArray(StandardCharsets.UTF_8).size
        if (currentBytes > 0L && currentBytes + encodedSize > maxFileBytes) rotateForSize()
        writer.write(entry)
        writer.flush()
        currentBytes += encodedSize
        trimTotalBudget()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        writer.close()
    }

    private fun rotateToDate(date: LocalDate) {
        writer.close()
        cleanExpired(date)
        val selected = selectWritableFile(date)
        currentDate = date
        currentIndex = selected.first
        currentFile = selected.second
        currentBytes = currentFile.length()
        writer = openWriter(currentFile)
        trimTotalBudget()
    }

    private fun rotateForSize() {
        writer.close()
        currentIndex++
        currentFile = logFile(currentDate, currentIndex)
        currentBytes = currentFile.length()
        writer = openWriter(currentFile)
        trimTotalBudget()
    }

    private fun selectWritableFile(date: LocalDate): Pair<Int, File> {
        val indices = logDir.listFiles().orEmpty()
            .mapNotNull { file -> parseLogFile(file)?.takeIf { it.first == date }?.second }
        var index = indices.maxOrNull() ?: 0
        var file = logFile(date, index)
        if (file.length() >= maxFileBytes) {
            index++
            file = logFile(date, index)
        }
        return index to file
    }

    private fun cleanExpired(today: LocalDate) {
        val cutoff = today.minusDays(retentionDays)
        logDir.listFiles().orEmpty().forEach { file ->
            val date = parseLogFile(file)?.first ?: return@forEach
            if (date.isBefore(cutoff)) check(file.delete()) { "Cannot delete expired Desktop log" }
        }
    }

    private fun trimTotalBudget() {
        var files = logDir.listFiles().orEmpty().filter { parseLogFile(it) != null }
        var total = files.sumOf(File::length)
        if (total <= maxTotalBytes) return
        files = files.sortedWith(compareBy<File>({ parseLogFile(it)?.first }, { it.lastModified() }, { it.name }))
        files.forEach { file ->
            if (total <= maxTotalBytes) return
            if (file.absoluteFile == currentFile.absoluteFile) return@forEach
            val size = file.length()
            check(file.delete()) { "Cannot trim Desktop log directory" }
            total -= size
        }
    }

    private fun encodeEntry(
        timestamp: LocalDateTime,
        level: String,
        tag: String,
        msg: String,
        throwable: Throwable?,
    ): String = buildString {
            append(timestamp.format(TIMESTAMP_FORMATTER))
            append('|').append(level)
            append('|').append(tag)
            append('|').append(msg.replace('\r', ' ').replace('\n', ' '))
            append('\n')
            if (throwable != null) {
                val stack = StringWriter()
                throwable.printStackTrace(PrintWriter(stack))
                append(stack.toString().take(MAX_STACK_TRACE_CHARS))
                if (!endsWith('\n')) append('\n')
            }
        }

    private fun boundEntry(entry: String): String {
        if (entry.toByteArray(StandardCharsets.UTF_8).size.toLong() <= maxFileBytes) return entry
        // 每个 UTF-16 码元四个字节是一个安全上界，包括未配对的代理项。
        return entry.take((maxFileBytes / 4L).toInt().coerceAtLeast(1))
    }

    private fun logFile(date: LocalDate, index: Int): File = File(
        logDir,
        if (index == 0) "app-$date.log" else "app-$date.$index.log",
    )

    private fun parseLogFile(file: File): Pair<LocalDate, Int>? {
        val match = LOG_FILE_REGEX.matchEntire(file.name) ?: return null
        val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
        val index = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 0
        return date to index
    }

    private fun openWriter(file: File): BufferedWriter = BufferedWriter(
        OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8),
    )

    private companion object {
        const val DEFAULT_RETENTION_DAYS = 7L
        const val DEFAULT_MAX_FILE_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024L * 1024L
        const val MAX_ABSOLUTE_FILE_BYTES = 64L * 1024L * 1024L
        const val MAX_ABSOLUTE_TOTAL_BYTES = 512L * 1024L * 1024L
        const val MIN_FILE_BYTES = 64L
        const val MAX_STACK_TRACE_CHARS = 256 * 1024
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        val LOG_FILE_REGEX = Regex("app-(\\d{4}-\\d{2}-\\d{2})(?:\\.(\\d+))?\\.log")
    }
}

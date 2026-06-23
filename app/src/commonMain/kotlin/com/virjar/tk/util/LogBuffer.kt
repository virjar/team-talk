package com.virjar.tk.util

import java.text.SimpleDateFormat
import java.util.*

/**
 * 环形日志缓冲区。定量（500 条）或定时触发上传。
 * 线程安全（synchronized）。
 */
class LogBuffer(
    private val capacity: Int = 500,
) {
    private val buffer = ArrayDeque<String>(capacity + 16)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun append(level: String, tag: String, msg: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val line = if (throwable != null) {
            "$timestamp|$level|$tag|${msg.replace("\n", " ")}\n${throwable.stackTraceToString()}"
        } else {
            "$timestamp|$level|$tag|${msg.replace("\n", " ")}"
        }
        if (buffer.size >= capacity) {
            buffer.removeFirst()
        }
        buffer.addLast(line)
    }

    @Synchronized
    fun drain(): String? {
        if (buffer.isEmpty()) return null
        val text = buffer.joinToString("\n")
        buffer.clear()
        return text
    }

    @Synchronized
    fun size(): Int = buffer.size
}

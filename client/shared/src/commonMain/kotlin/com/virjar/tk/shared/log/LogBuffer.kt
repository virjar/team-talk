package com.virjar.tk.shared.log

import com.virjar.tk.shared.platform.*

/**
 * 环形日志缓冲区。定量（500 条）或定时触发上传。
 * 线程安全（synchronized）。
 */
class LogBuffer(
    private val capacity: Int = 500,
) {
    private val buffer = ArrayDeque<String>(capacity + 16)
    private val methodLock = PlatformLock()

    fun append(level: String, tag: String, msg: String, throwable: Throwable? = null): Unit = synchronized(methodLock) {
        val timestamp = platformLogTimestamp()
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

    fun drain(): String? = synchronized(methodLock) {
        if (buffer.isEmpty()) return null
        val text = buffer.joinToString("\n")
        buffer.clear()
        return text
    }

    fun size(): Int = synchronized(methodLock) { buffer.size }
}

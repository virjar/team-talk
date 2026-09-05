package com.virjar.tk.shared.log

internal actual fun platformLog(level: String, tag: String, msg: String, throwable: Throwable?) {
    // 诊断行为绝不能改变业务结果。Android 本地 JVM 测试桩会对 Log 调用抛出
    // RuntimeException，厂商的日志实现也可能因格式错误的输入而失败。
    // 隔离这些平台故障，同时刻意放行致命 Error 值，
    // 使其保留正常的进程语义。
    try {
        when (level) {
            "trace" -> android.util.Log.i(tag, msg)
            "fault" -> {
                if (throwable != null) android.util.Log.e(tag, msg, throwable)
                else android.util.Log.e(tag, msg)
            }
            "snapshot" -> android.util.Log.i(tag, "[snapshot] $msg")
        }
    } catch (_: RuntimeException) {
        // 平台日志属于尽力而为；AppLog 的有界内存缓冲区仍然可用。
    }
}

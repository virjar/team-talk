package com.virjar.tk.client

import java.io.File

/**
 * 平台数据目录。各平台通过 actual 实现提供。
 *
 * - Android: app 私有目录 (context.getDir)
 * - Desktop: ~/.teamtalk/
 */
expect fun platformDataDir(): File

/**
 * 未处理异常的兜底：打印到平台日志 + 原子写入 crash pending 文件。
 * 用于 CoroutineExceptionHandler 和 UncaughtExceptionHandler。
 */
fun logUnhandledError(tag: String, throwable: Throwable) {
    System.err.println("[Unhandled:$tag] ${throwable.stackTraceToString()}")
    try {
        CrashDumper(platformDataDir()).flushPending("Unhandled $tag: ${throwable.stackTraceToString()}")
    } catch (_: Exception) {
        // crash dump 本身失败不能掩盖原始异常
    }
}

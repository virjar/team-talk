package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.platform.PlatformFile as File

/**
 * 平台数据目录。各平台通过 actual 实现提供。
 *
 * - Android: app 私有目录 (context.getDir)
 * - Desktop: ~/.teamtalk/
 * - iOS: Application Support/TeamTalk，使用应用沙箱与系统文件保护
 */
expect fun platformDataDir(): File

/**
 * 未处理异常的兜底：打印到平台日志 + 原子写入 crash pending 文件。
 * 用于 CoroutineExceptionHandler 和 UncaughtExceptionHandler。
 */
fun logUnhandledError(tag: String, throwable: Throwable) {
    com.virjar.tk.shared.log.platformLog("error", tag, "Unhandled exception", throwable)
    try {
        flushPendingCrash(platformDataDir(), "Unhandled $tag: ${throwable.stackTraceToString()}")
    } catch (_: Exception) {
        // crash dump 本身失败不能掩盖原始异常
    }
}

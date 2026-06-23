package com.virjar.tk

import com.virjar.tk.env.DesktopEnvironment
import com.virjar.tk.log.TkLoggerFactory
import com.virjar.tk.util.AppLog
import com.virjar.tk.util.AppLogTkLogger
import java.io.File

/**
 * Desktop 客户端入口。
 *
 * 初始化顺序（在 application {} 外部，只执行一次）：
 * 1. 解析数据目录 → 设置 teamtalk.data.dir 系统属性（影响 logback 日志路径）
 * 2. 获取文件锁（防止同一数据目录启动多个实例）
 * 3. 设置未捕获异常处理器
 *
 * 然后进入 Compose application {} 渲染窗口（[teamTalkApplication]）。
 */
fun main() {
    // ── 1. 数据目录初始化（必须在 logback 初始化前） ──
    val dataDir = DesktopEnvironment.dataDir
    System.setProperty("teamtalk.data.dir", dataDir.absolutePath)

    // ── 2. 文件锁：同一数据目录不允许启动多个实例 ──
    val locker = FileLocker(dataDir)
    if (!locker.tryLock()) {
        showAlreadyRunningDialog(dataDir)
        return
    }

    // ── 2.5 注入日志实现：shared 模块的 TkLogger → AppLog ──
    TkLoggerFactory.install { name -> AppLogTkLogger(name) }

    // ── 3. 未捕获异常 ──
    val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        AppLog.fault("Uncaught", "Uncaught exception in thread: ${thread.name}", throwable)
        try {
            com.virjar.tk.client.CrashDumper(com.virjar.tk.client.platformDataDir())
                .flushPending("Desktop crash in ${thread.name}: ${throwable.stackTraceToString()}")
        } catch (_: Exception) {
            // crash dump 本身失败不掩盖原始异常
        }
        oldHandler?.uncaughtException(thread, throwable)
    }

    AppLog.trace("Main", "TeamTalk starting, dataDir=${dataDir.absolutePath}")
    // 构建溯源：启动即打印 commit/build time，排查问题时可从日志确认产物来源
    AppLog.trace("Main", "Build: commit=${BuildConfig.GIT_COMMIT_ID} time=${BuildConfig.BUILD_TIME}")

    // ── 4. 进入 Compose ──
    teamTalkApplication(dataDir, locker)
}

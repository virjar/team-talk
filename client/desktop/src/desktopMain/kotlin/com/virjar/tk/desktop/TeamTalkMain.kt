@file:JvmName("TeamTalkMain")

package com.virjar.tk.desktop

import com.virjar.tk.desktop.env.DesktopEnvironment
import com.virjar.tk.shared.log.AppLog
import javax.swing.JOptionPane

/**
 * Desktop 客户端入口。
 *
 * 初始化顺序（在 application {} 外部，只执行一次）：
 * 1. 解析数据目录 → 设置 teamtalk.data.dir 系统属性（影响 logback 日志路径）
 * 2. 获取文件锁（防止同一数据目录启动多个实例）
 * 3. 设置未捕获异常处理器
 * 4. 校验 macOS 本地媒体原生缓存
 *
 * 然后进入 Compose application {} 渲染窗口（[teamTalkApplication]）。
 */
fun main() {
    // dev/裸 JVM 启动时 macOS 菜单栏默认显示 "java"；必须在 AWT 初始化前声明应用名。
    // 打包产物由 Conveyor 写入的 Info.plist CFBundleName 决定，此属性不生效也无副作用。
    System.setProperty("apple.awt.application.name", "TeamTalk")

    // ── 1. 数据目录初始化（必须在 logback 初始化前） ──
    val dataDir = try {
        DesktopEnvironment.prepareDataDirectory()
    } catch (failure: Throwable) {
        showDataDirectoryFailure(failure)
        return
    }
    System.setProperty("teamtalk.data.dir", dataDir.absolutePath)

    // ── 2. 文件锁：同一数据目录不允许启动多个实例 ──
    val locker = FileLocker(dataDir)
    val lockAcquired = try {
        locker.tryLock()
    } catch (failure: Throwable) {
        showDataDirectoryFailure(failure)
        return
    }
    if (!lockAcquired) {
        showAlreadyRunningDialog(dataDir)
        return
    }

    // 新 major 是本安装的显式重置边界；先持有进程锁，再清理，随后才读取凭据或打开 SQLite。
    try {
        com.virjar.tk.shared.client.prepareJvmClientDataVersion(dataDir)
    } catch (failure: Throwable) {
        locker.release()
        showDataDirectoryFailure(failure)
        return
    }

    // ── 3. 未捕获异常 ──
    val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        AppLog.fault("Uncaught", "Uncaught exception in thread: ${thread.name}", throwable)
        try {
            com.virjar.tk.shared.client.flushPendingCrash(
                com.virjar.tk.shared.client.platformDataDir(),
                "Desktop crash in ${thread.name}: ${throwable.stackTraceToString()}",
            )
        } catch (_: Exception) {
            // crash dump 本身失败不掩盖原始异常
        }
        oldHandler?.uncaughtException(thread, throwable)
    }

    AppLog.trace("Main", "TeamTalk starting, dataDir=${dataDir.absolutePath}")
    // 构建溯源：启动即打印 commit/build time，排查问题时可从日志确认产物来源
    AppLog.trace("Main", "Build: identity=${BuildConfig.BUILD_IDENTITY} time=${BuildConfig.BUILD_TIME}")

    // ── 4. 原生媒体缓存必须在 ComposeMediaPlayer bridge 首次初始化前收敛 ──
    MacNativeMediaBootstrap.prepare()

    // ── 5. 进入 Compose ──
    teamTalkApplication(dataDir, locker)
}

private fun showDataDirectoryFailure(failure: Throwable) {
    val detail = generateSequence(failure) { it.cause }
        .mapNotNull(Throwable::message)
        .firstOrNull()
        ?: failure::class.simpleName.orEmpty()
    runCatching {
        JOptionPane.showMessageDialog(
            null,
            "TeamTalk cannot open its private data directory.\n\n$detail\n\n" +
                "Startup stopped before opening the workspace. Resolve the data version or directory conflict and start again.",
            "TeamTalk data directory",
            JOptionPane.ERROR_MESSAGE,
        )
    }.onFailure {
        System.err.write("TeamTalk data directory error: $detail\n".encodeToByteArray())
        System.err.flush()
    }
}

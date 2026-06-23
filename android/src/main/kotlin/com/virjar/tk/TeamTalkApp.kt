package com.virjar.tk

import android.app.Application
import android.util.Log
import com.virjar.tk.android.BuildConfig
import com.virjar.tk.client.ServerConfig
import com.virjar.tk.client.configureServerConfig
import com.virjar.tk.log.TkLoggerFactory
import com.virjar.tk.util.AppLogTkLogger

/**
 * TeamTalk Application。
 *
 * 全局初始化放在此处（而非 Activity），因为：
 * - Application.onCreate 保证在所有 Activity 之前执行
 * - 配置变更（旋转屏幕）重建 Activity 时不会重复初始化
 * - 日志注入、ServerConfig 等是进程级单次初始化
 */
class TeamTalkApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. 日志注入：shared 模块的 TkLogger → AppLog
        TkLoggerFactory.install { name -> AppLogTkLogger(name) }

        // 2. 全局未捕获异常 → fault 日志 + crash 持久化
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("Crash", "Uncaught exception in thread: ${thread.name}", throwable)
            // crash 持久化：写入 pending 文件，下次启动上传
            // Android 主进程即将死亡，不能做网络请求
            try {
                val dataDir = getDir("teamtalk", MODE_PRIVATE)
                val crashDumper = com.virjar.tk.client.CrashDumper(dataDir)
                crashDumper.flushPending("Crash in ${thread.name}: ${throwable.stackTraceToString()}")
            } catch (_: Exception) {
                // 即使持久化失败也不能阻止默认行为
            }
            // 不调用默认 handler，让进程正常死亡
        }

        // 3. ServerConfig 初始化（从 BuildConfig 注入）
        configureServerConfig(ServerConfig(
            serverUrl = BuildConfig.SERVER_BASE_URL,
            tcpHost = BuildConfig.TCP_HOST,
            tcpPort = BuildConfig.TCP_PORT,
            allowCustomServer = BuildConfig.ALLOW_CUSTOM_SERVER,
        ))
    }
}

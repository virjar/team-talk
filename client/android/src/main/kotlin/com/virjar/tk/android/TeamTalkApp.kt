package com.virjar.tk.android

import android.app.Application
import okio.Path.Companion.toPath
import android.util.Log
import com.virjar.tk.android.BuildConfig
import com.virjar.tk.shared.client.ServerConfig

/**
 * TeamTalk Application。
 *
 * 全局初始化放在此处（而非 Activity），因为：
 * - Application.onCreate 保证在所有 Activity 之前执行
 * - 配置变更（旋转屏幕）重建 Activity 时不会重复初始化
 * - 平台环境和异常边界是进程级单次初始化
 */
class TeamTalkApp : Application(), coil3.SingletonImageLoader.Factory {
    /** 由本 Android 进程持有的不可变部署配置。 */
    internal val serverConfig = ServerConfig(
        serverUrl = BuildConfig.SERVER_BASE_URL,
        tcpHost = BuildConfig.TCP_HOST,
        tcpPort = BuildConfig.TCP_PORT,
    )

    /** 进程持有的写入器，被本安装中每个 Activity/会话草稿存储共享。 */
    internal lateinit var documentDraftPersistence: AndroidDocumentDraftPersistence
        private set

    /**
     * Coil 全局图片加载器：媒体缓存体系（Android 端实现）——
     * 内存 LRU + 磁盘 LRU（250MB 配额，磁盘爆炸防护），磁盘键含日期（Coil journal 自管理淘汰）。
     */
    override fun newImageLoader(context: android.content.Context): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(context.cacheDir.resolve("media_coil").absolutePath.toPath())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()

        // 在凭据、草稿写入器与任何数据库打开前处理本安装的 major 边界。
        com.virjar.tk.shared.client.prepareAndroidClientDataVersion(this)

        // 初始化 AndroidContext（供 platformDataDir() 使用）
        com.virjar.tk.shared.client.AndroidContext.appContext = this
        documentDraftPersistence = AndroidDocumentDraftPersistence(this)

        // 全局未捕获异常 → fault 日志 + crash 持久化
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("Crash", "Uncaught exception in thread: ${thread.name}", throwable)
            // crash 持久化：写入 pending 文件，下次启动上传
            // Android 主进程即将死亡，不能做网络请求
            try {
                val dataDir = getDir("teamtalk", MODE_PRIVATE)
                com.virjar.tk.shared.client.flushPendingCrash(
                    dataDir,
                    "Crash in ${thread.name}: ${throwable.stackTraceToString()}",
                )
            } catch (e: Exception) {
                // 即使持久化失败也不能阻止默认行为，但要打印到 logcat
                Log.e("Crash", "Failed to persist crash log", e)
            }
            // 委托给默认 handler 让进程正常死亡
            oldHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onTerminate() {
        // 真机会直接终止进程；模拟器/测试才会调用此钩子。异步关闭仍然可以
        // 定义并验证所有权，且不阻塞主循环。
        val failures = mutableListOf<Pair<String, Throwable>>()
        fun release(owner: String, action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                failures += owner to failure
            }
        }
        release("voice playback", VoicePlayer::close)
        if (::documentDraftPersistence.isInitialized) {
            release("document draft persistence", documentDraftPersistence::close)
        }
        release("Application") { super.onTerminate() }
        reportAndroidRetirementFailures("process termination", failures)
    }
}

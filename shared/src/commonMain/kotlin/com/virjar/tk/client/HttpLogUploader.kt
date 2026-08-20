package com.virjar.tk.client

import com.virjar.tk.util.AppLog
import com.virjar.tk.util.HttpUtil
import com.virjar.tk.util.LogBuffer
import kotlinx.coroutines.*

/**
 * HTTP 日志上传器。替代 TCP LogUploader，解决 TCP 断连时无法上传日志的悖论。
 *
 * 触发条件：
 * - fault 日志 → debounce 3s 批量上传
 * - 定时（5min）批量上传 trace（开发构建）
 * - 用户手动触发（正式版本反馈功能）
 *
 * 失败兜底：落本地 pending 文件，下次启动重试。
 */
class HttpLogUploader(
    private val traceBuffer: LogBuffer,
    private val faultBuffer: LogBuffer,
    private val serverUrl: String,
    private val accessTokenProvider: () -> String?,
    private val crashDumper: CrashDumper,
    private val intervalMs: Long = 5 * 60 * 1000L,
) {
    private val lifecycleLock = Any()
    private val lifecycleJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + lifecycleJob +
        CoroutineExceptionHandler { _, throwable ->
            logUnhandledError("HttpLogUploader", throwable)
        })
    private var timerJob: Job? = null
    private var faultDebounceJob: Job? = null
    private var stopped = false

    fun start() = synchronized(lifecycleLock) {
        if (stopped) return@synchronized

        // 幂等：重复 start 先取消旧定时任务。
        timerJob?.cancel()
        faultDebounceJob?.cancel()
        // 启动时优先上传上次崩溃日志。该任务也归会话 scope 所有。
        scope.launch { crashDumper.uploadPending(serverUrl, accessTokenProvider()) }

        // 定时上传（开发构建：全量 trace）
        timerJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                uploadAll()
            }
        }
    }

    fun stop() {
        val shouldStop = synchronized(lifecycleLock) {
            if (stopped) {
                false
            } else {
                stopped = true
                timerJob?.cancel()
                faultDebounceJob?.cancel()
                timerJob = null
                faultDebounceJob = null
                true
            }
        }
        if (!shouldStop) return

        // close/logout 可能发生在 UI 线程，这里绝不等待 HTTP。已启动的上传也由
        // 会话 job 统一取消，失败数据仍由 uploadAll 的 pending 文件兜底。
        lifecycleJob.cancel()
    }

    /**
     * fault 触发上传（debounce 3s，避免连续异常打满 HTTP）。
     * 由 AppLog.onFault 回调调用。
     */
    fun trigger() {
        synchronized(lifecycleLock) {
            if (stopped) return
            faultDebounceJob?.cancel()
            faultDebounceJob = scope.launch {
                delay(3000)
                uploadAll()
            }
        }
    }

    /** 用户手动触发：打包上传最近日志。 */
    fun manualUpload() {
        synchronized(lifecycleLock) {
            if (stopped) return
            scope.launch { uploadAll() }
        }
    }

    private fun uploadAll() {
        val traceText = traceBuffer.drain() ?: ""
        val faultText = faultBuffer.drain() ?: ""
        val combined = buildString {
            if (traceText.isNotBlank()) appendLine("=== TRACE ===").appendLine(traceText)
            if (faultText.isNotBlank()) appendLine("=== FAULT ===").appendLine(faultText)
        }
        if (combined.isBlank()) return

        try {
            val compressed = HttpUtil.gzip(combined)
            val accessToken = accessTokenProvider()
                ?: throw IllegalStateException("No authenticated access token for log upload")
            val code = HttpUtil.postGzip(
                "$serverUrl/api/client-logs",
                compressed,
                mapOf("Authorization" to "Bearer $accessToken"),
            )
            if (code != 200) throw RuntimeException("HTTP $code")
        } catch (e: Exception) {
            // 先打印原始异常到控制台，确保 logcat 可见，再落盘
            System.err.println("[Crash] Upload failed, saving to pending: ${e.message}")
            e.printStackTrace()
            crashDumper.flushPending(combined)
            AppLog.trace("HttpLogUploader", "Upload failed, saved to pending: ${e.message}")
        }
    }
}

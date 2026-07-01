package com.virjar.tk.client

import com.virjar.tk.util.AppLog
import com.virjar.tk.util.LogBuffer
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * HTTP 日志上传器。替代 TCP LogUploader，解决 TCP 断连时无法上传日志的悖论。
 *
 * 触发条件：
 * - fault 日志 → debounce 3s 批量上传
 * - 定时（5min）批量上传 trace（Demo 版本）
 * - 用户手动触发（正式版本反馈功能）
 *
 * 失败兜底：落本地 pending 文件，下次启动重试。
 */
class HttpLogUploader(
    private val traceBuffer: LogBuffer,
    private val faultBuffer: LogBuffer,
    private val serverUrl: String,
    private val deviceId: String,
    private val crashDumper: CrashDumper,
    private val intervalMs: Long = 5 * 60 * 1000L,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() +
        CoroutineExceptionHandler { _, throwable ->
            logUnhandledError("HttpLogUploader", throwable)
        })
    private var timerJob: Job? = null
    private var faultDebounceJob: Job? = null

    fun start() {
        // 幂等：重复 start 先取消旧定时任务
        timerJob?.cancel()
        faultDebounceJob?.cancel()
        // 启动时优先上传上次崩溃日志
        scope.launch { crashDumper.uploadPending(serverUrl, deviceId) }

        // 定时上传（Demo: 全量 trace）
        timerJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                uploadAll()
            }
        }
    }

    fun stop() {
        timerJob?.cancel()
        faultDebounceJob?.cancel()
        runCatching { runBlocking { uploadAll() } }
    }

    /**
     * fault 触发上传（debounce 3s，避免连续异常打满 HTTP）。
     * 由 AppLog.onFault 回调调用。
     */
    fun trigger() {
        faultDebounceJob?.cancel()
        faultDebounceJob = scope.launch {
            delay(3000)
            uploadAll()
        }
    }

    /** 用户手动触发：打包上传最近日志。 */
    fun manualUpload() {
        scope.launch { uploadAll() }
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
            val compressed = gzip(combined)
            upload(compressed)
        } catch (e: Exception) {
            // 先打印原始异常到控制台，确保 logcat 可见，再落盘
            System.err.println("[Crash] Upload failed, saving to pending: ${e.message}")
            e.printStackTrace()
            crashDumper.flushPending(combined)
            AppLog.trace("HttpLogUploader", "Upload failed, saved to pending: ${e.message}")
        }
    }

    private fun upload(gzipData: ByteArray) {
        val conn = (URL("$serverUrl/api/client-logs").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/gzip")
            setRequestProperty("X-Device-Id", deviceId)
        }
        conn.outputStream.use { it.write(gzipData) }
        if (conn.responseCode != 200) {
            throw RuntimeException("HTTP ${conn.responseCode}")
        }
        conn.disconnect()
    }

    private fun gzip(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.encodeToByteArray()) }
        return bos.toByteArray()
    }
}

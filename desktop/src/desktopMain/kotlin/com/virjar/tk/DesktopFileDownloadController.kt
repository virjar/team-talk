package com.virjar.tk

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.repository.FileOps
import com.virjar.tk.model.Attachment
import com.virjar.tk.ui.component.FileDownloadController
import com.virjar.tk.ui.component.FileDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Desktop 文件附件下载控制器：本地缓存 + 气泡进度动画数据源。 */
class DesktopFileDownloadController(
    private val serverUrl: String,
    private val accessToken: String?,
    private val cacheDir: File,
    private val onDownloaded: (File) -> Unit,
) : FileDownloadController {

    override val states: SnapshotStateMap<String, FileDownloadState> = mutableStateMapOf()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = mutableSetOf<String>()
    private val openAfterDownload = mutableSetOf<String>()
    private val mutex = Mutex()

    override fun ensure(attachment: Attachment) {
        if (states.containsKey(attachment.path)) return
        states[attachment.path] = if (cachedFile(attachment).isFile) FileDownloadState.Done else FileDownloadState.Idle
    }

    override fun download(attachment: Attachment) {
        scope.launch { downloadInternal(attachment, openWhenDone = false) }
    }

    override fun openOrDownload(attachment: Attachment) {
        val cached = cachedFile(attachment)
        if (states[attachment.path] is FileDownloadState.Done && cached.isFile) {
            openCached(cached)
            return
        }
        scope.launch { downloadInternal(attachment, openWhenDone = true) }
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun downloadInternal(attachment: Attachment, openWhenDone: Boolean) {
        val key = attachment.path
        val shouldStart = mutex.withLock {
            if (openWhenDone) openAfterDownload += key
            inFlight.add(key)
        }
        if (!shouldStart) return

        val target = cachedFile(attachment)
        val partial = File(target.parentFile, "${target.name}.part")
        try {
            states[key] = FileDownloadState.Downloading(0f)
            val conn = URL(FileOps.resolveUrl(serverUrl, attachment)).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 120_000
            accessToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("下载失败 HTTP $code")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastEmit = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 100) {
                                lastEmit = now
                                states[key] = FileDownloadState.Downloading(
                                    if (total > 0) downloaded.toFloat() / total else -1f,
                                )
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (target.exists() && !target.delete()) error("无法替换旧缓存文件")
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            states[key] = FileDownloadState.Done
            val shouldOpen = mutex.withLock { openAfterDownload.remove(key) }
            if (shouldOpen) openCached(target)
        } catch (e: Exception) {
            com.virjar.tk.util.AppLog.fault("FileDownload", "download failed path=$key: ${e.message}")
            states[key] = FileDownloadState.Failed(e.message)
            partial.delete()
            mutex.withLock { openAfterDownload.remove(key) }
        } finally {
            mutex.withLock { inFlight.remove(key) }
        }
    }

    private fun openCached(file: File) {
        runCatching { onDownloaded(file) }
            .onFailure { com.virjar.tk.util.AppLog.fault("FileDownload", "open failed ${file.name}: ${it.message}") }
    }

    private fun cachedFile(attachment: Attachment): File {
        cacheDir.mkdirs()
        val leaf = attachment.name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .take(120)
            .ifBlank { "attachment" }
        val key = attachment.path.hashCode().toUInt().toString(16)
        return File(cacheDir, "$key-$leaf")
    }
}

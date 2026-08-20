package com.virjar.tk

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.virjar.tk.repository.FileOps
import com.virjar.tk.model.Attachment
import com.virjar.tk.ui.component.FileDownloadController
import com.virjar.tk.ui.component.FileDownloadState
import com.virjar.tk.ui.component.textAttachmentPreviewKind
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
import java.util.UUID

/** Android 文件附件下载控制器：会话隔离缓存 + 气泡进度动画数据源。 */
class AndroidFileDownloadController(
    context: Context,
    private val serverUrl: String,
    private val accessToken: String?,
    private val cacheNamespace: String = mediaCacheNamespace(
        uid = null,
        accessToken = accessToken,
        fallbackNonce = UUID.randomUUID().toString(),
    ),
    private val onTextAttachmentPreview: ((Attachment) -> Unit)? = null,
) : FileDownloadController {

    private val appContext = context.applicationContext
    override val states: SnapshotStateMap<String, FileDownloadState> = mutableStateMapOf()

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val inFlight = mutableSetOf<String>()
    private val openAfterDownload = mutableSetOf<String>()
    private val mutex = Mutex()
    private val cacheLease = acquireMediaCacheLease(appContext.cacheDir, cacheNamespace)

    override fun ensure(attachment: Attachment) {
        if (states.containsKey(attachment.path)) return
        states[attachment.path] = if (cachedFile(attachment).isFile) FileDownloadState.Done else FileDownloadState.Idle
    }

    override fun download(attachment: Attachment) {
        scope.launch { downloadInternal(attachment, openWhenDone = false) }
    }

    override fun openOrDownload(attachment: Attachment) {
        if (onTextAttachmentPreview != null && textAttachmentPreviewKind(attachment) != null) {
            onTextAttachmentPreview.invoke(attachment)
            return
        }
        val cached = cachedFile(attachment)
        if (states[attachment.path] is FileDownloadState.Done && cached.isFile) {
            openCached(cached, attachment.contentType)
            return
        }
        scope.launch { downloadInternal(attachment, openWhenDone = true) }
    }

    override fun close() {
        // URLConnection 的阻塞读取不一定在 cancel() 当下退出。子任务真正结束后再释放租约；
        // 同 token 的其他页面仍持有租约时，关闭本控制器不会删除共享缓存。
        scopeJob.invokeOnCompletion {
            cacheLease.close()
        }
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
        try {
            states[key] = FileDownloadState.Downloading(0f)
            val cached = downloadAttachmentToCache(
                cacheRoot = appContext.cacheDir,
                cacheNamespace = cacheNamespace,
                serverUrl = serverUrl,
                accessToken = accessToken,
                attachment = attachment,
            ) { progress ->
                states[key] = FileDownloadState.Downloading(progress)
            }
            states[key] = FileDownloadState.Done
            val shouldOpen = mutex.withLock { openAfterDownload.remove(key) }
            if (shouldOpen) openCached(cached, attachment.contentType)
        } catch (e: Exception) {
            Log.e("FileDownload", "下载失败 path=$key", e)
            states[key] = FileDownloadState.Failed(e.message)
            mutex.withLock { openAfterDownload.remove(key) }
        } finally {
            mutex.withLock { inFlight.remove(key) }
        }
    }

    private fun openCached(file: File, contentType: String) {
        runCatching { MediaHelper.openFile(appContext, file, contentType) }
            .onFailure { Log.e("FileDownload", "打开失败 file=${file.name}", it) }
    }

    private fun cachedFile(attachment: Attachment): File {
        return attachmentCacheFile(appContext.cacheDir, cacheNamespace, attachment)
    }
}

internal fun attachmentCacheFile(
    cacheRoot: File,
    cacheNamespace: String,
    attachment: Attachment,
): File {
    val directory = mediaCacheDirectory(cacheRoot, cacheNamespace, "attachments").apply { mkdirs() }
    val leaf = attachment.name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .take(120)
        .ifBlank { "attachment" }
    val key = attachment.path.hashCode().toUInt().toString(16)
    return File(directory, "$key-$leaf")
}

/** 聊天附件与内嵌预览共用的认证下载和原子缓存路径。 */
internal suspend fun downloadAttachmentToCache(
    cacheRoot: File,
    cacheNamespace: String,
    serverUrl: String,
    accessToken: String?,
    attachment: Attachment,
    onProgress: ((Float) -> Unit)? = null,
): File {
    val target = attachmentCacheFile(cacheRoot, cacheNamespace, attachment)
    val cached = materializeMediaCacheFile(target) { partial ->
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
                        if (now - lastEmit >= 100L) {
                            lastEmit = now
                            onProgress?.invoke(if (total > 0L) downloaded.toFloat() / total else -1f)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
    onProgress?.invoke(1f)
    return cached
}

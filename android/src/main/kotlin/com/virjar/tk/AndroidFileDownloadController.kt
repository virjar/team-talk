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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/** Android 文件附件下载控制器：会话隔离缓存 + 气泡进度动画数据源。 */
class AndroidFileDownloadController(
    context: Context,
    private val mediaSession: AndroidMediaSession,
    private val onTextAttachmentPreview: ((Attachment) -> Unit)? = null,
) : FileDownloadController {

    private val appContext = context.applicationContext
    override val states: SnapshotStateMap<String, FileDownloadState> = mutableStateMapOf()

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val inFlight = mutableSetOf<String>()
    private val openAfterDownload = mutableSetOf<String>()
    private val mutex = Mutex()
    private val cacheLease = acquireMediaCacheLease(appContext.cacheDir, mediaSession.cacheNamespace)
    private val closed = AtomicBoolean(false)

    override fun ensure(attachment: Attachment) {
        if (closed.get() || !mediaSession.isCurrentOwner()) return
        if (states.containsKey(attachment.path)) return
        publishState(
            attachment.path,
            if (cachedFile(attachment).isFile) FileDownloadState.Done else FileDownloadState.Idle,
        )
    }

    override fun download(attachment: Attachment) {
        if (closed.get() || !mediaSession.isCurrentOwner()) return
        scope.launch { downloadInternal(attachment, openWhenDone = false) }
    }

    override fun openOrDownload(attachment: Attachment) {
        if (closed.get()) return
        if (!mediaSession.isCurrentOwner()) {
            publishState(attachment.path, FileDownloadState.Failed("登录会话已切换"))
            return
        }
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
        if (!closed.compareAndSet(false, true)) return
        // URLConnection 的阻塞读取不一定在 cancel() 当下退出。子任务真正结束后再释放租约；
        // 同账号的其他页面仍持有租约时，关闭本控制器不会删除共享缓存。
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
            publishState(key, FileDownloadState.Downloading(0f))
            val cached = downloadAttachmentToCache(
                cacheRoot = appContext.cacheDir,
                mediaSession = mediaSession,
                attachment = attachment,
            ) { progress ->
                publishState(key, FileDownloadState.Downloading(progress))
            }
            if (closed.get() || !mediaSession.isCurrentOwner()) return
            publishState(key, FileDownloadState.Done)
            val shouldOpen = mutex.withLock { openAfterDownload.remove(key) }
            if (shouldOpen && !closed.get()) openCached(cached, attachment.contentType)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (closed.get() || !mediaSession.isCurrentOwner()) return
            Log.e("FileDownload", "下载失败 path=$key", e)
            publishState(key, FileDownloadState.Failed(e.message))
            mutex.withLock { openAfterDownload.remove(key) }
        } finally {
            mutex.withLock {
                inFlight.remove(key)
                if (closed.get()) openAfterDownload.remove(key)
            }
        }
    }

    private fun openCached(file: File, contentType: String) {
        if (closed.get() || !mediaSession.isCurrentOwner()) return
        runCatching { MediaHelper.openFile(appContext, file, contentType) }
            .onFailure { Log.e("FileDownload", "打开失败 file=${file.name}", it) }
    }

    private fun cachedFile(attachment: Attachment): File {
        return attachmentCacheFile(appContext.cacheDir, mediaSession.cacheNamespace, attachment)
    }

    private fun publishState(key: String, state: FileDownloadState) {
        if (closed.get()) return
        mediaSession.runIfOpen {
            if (!closed.get()) states[key] = state
        }
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
    val key = sha256Hex(attachment.path).take(32)
    return File(directory, "$key-$leaf")
}

/** 聊天附件与内嵌预览共用的认证下载和原子缓存路径。 */
internal suspend fun downloadAttachmentToCache(
    cacheRoot: File,
    mediaSession: AndroidMediaSession,
    attachment: Attachment,
    onProgress: ((Float) -> Unit)? = null,
): File {
    mediaSession.ensureOpen()
    val operationContext = currentCoroutineContext()
    val target = attachmentCacheFile(cacheRoot, mediaSession.cacheNamespace, attachment)
    val cached = materializeMediaCacheFile(
        target = target,
        install = mediaSession::installCacheFile,
    ) { partial ->
        mediaSession.withAuthenticatedConnection(
            url = FileOps.resolveUrl(mediaSession.serverUrl, attachment),
            configure = { conn ->
                conn.connectTimeout = 10_000
                conn.readTimeout = 120_000
            },
        ) { conn ->
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("下载失败 HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastEmit = 0L
                    while (true) {
                        operationContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        operationContext.ensureActive()
                        mediaSession.ensureOpen()
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 100L) {
                            lastEmit = now
                            mediaSession.runIfOpen {
                                onProgress?.invoke(if (total > 0L) downloaded.toFloat() / total else -1f)
                            }
                        }
                    }
                }
            }
        }
    }
    operationContext.ensureActive()
    check(mediaSession.runIfOpen { onProgress?.invoke(1f) }) { "媒体会话已经关闭" }
    return cached
}

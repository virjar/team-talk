package com.virjar.tk

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.virjar.tk.body.FileBody
import com.virjar.tk.model.MessageBody
import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.VideoBody
import com.virjar.tk.body.VoiceBody
import com.virjar.tk.client.defaultServerConfig
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.repository.FileRepository
import com.virjar.tk.viewmodel.ChatViewModel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import javax.sound.sampled.*
import kotlin.concurrent.thread

/**
 * Desktop 媒体工具：下载缓存、图片解码、音频播放、文件打开、文件上传、录音。
 */
object DesktopMediaHelper {

    /**
     * 媒体缓存目录。在 dataDir/media 下，与数据库等数据统一管理。
     * 不用 java.io.tmpdir（进程退出可能被系统清理，且用户不可见不可控）。
     */
    private val cacheDir = File(System.getProperty("teamtalk.data.dir"), "media").also {
        it.mkdirs()
    }

    /** 下载文件到本地缓存，返回缓存文件。 */
    fun downloadToCache(url: String): File {
        val decoded = URLDecoder.decode(url, "UTF-8")
        val name = decoded.substringAfterLast("/").substringBefore("?")
        val cached = File(cacheDir, if (name.isNotBlank()) name else "file_${decoded.hashCode()}")
        if (cached.exists()) return cached

        java.net.URL(decoded).openStream().use { input ->
            cached.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return cached
    }

    /** 下载图片到本地缓存并解码为 Compose ImageBitmap（本地优先，已缓存则直接读文件）。 */
    fun loadImageBitmap(url: String): ImageBitmap? {
        return try {
            val cached = downloadToCache(url)
            val bytes = cached.readBytes()
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    /** 解码本地文件（媒体缓存体系渲染入口；loadImageBitmap 是按 URL 下载版，曾混用致解码 null）。 */
    fun decodeLocalImage(file: File): ImageBitmap? = try {
        SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    } catch (e: Throwable) {
        com.virjar.tk.util.AppLog.fault("Decode", "local decode failed: ${file.name} ${file.length()}B ${e::class.simpleName}: ${e.message}")
        null
    }

    /** 下载并打开文件（用系统默认应用）。 */
    fun openFile(url: String) {
        try {
            Desktop.getDesktop().open(downloadToCache(url))
        } catch (_: Exception) {
            try { Desktop.getDesktop().browse(URI(url)) } catch (_: Exception) {}
        }
    }

    // ── 上传 ──




    /**
     * 上传文件到服务端，返回相对 path（如 "uid/uuid.ext"）。
     * 调用方拼完整 URL：`fileRepo.resolveUrl(path)`
     */
    private fun fileRepo() = FileRepository(defaultServerConfig().serverUrl, com.virjar.tk.client.SessionContext.accessToken)

    fun uploadFile(bytes: ByteArray, fileName: String, contentType: String): String {
        return runBlocking { fileRepo().upload(bytes, fileName, contentType).getOrThrow() }
    }

    /** 上传并返回服务端媒体元数据（缩略图/宽高/时长；url 绝对化——body 直用，曾现相对路径致渲染 MalformedURLException）。 */
    fun uploadWithMeta(bytes: ByteArray, fileName: String, contentType: String): com.virjar.tk.repository.UploadResult {
        val r = runBlocking { fileRepo().uploadWithMeta(bytes, fileName, contentType).getOrThrow() }
        val base = serverUrlBase()
        return r.copy(
            url = if (r.url.startsWith("http")) r.url else "$base${r.url}",
            thumbUrl = r.thumbUrl?.let { if (it.startsWith("http")) it else "$base$it" },
        )
    }

    private fun serverUrlBase(): String {
        val u = fileUrl("")  // resolveUrl 拼装出的完整前缀
        return u.substringBefore("/api/v1/files")
    }

    /** 根据相对 path 拼装完整下载 URL。 */
    fun fileUrl(path: String): String = fileRepo().resolveUrl(path)


    /**
     * 媒体发送统一流程（上传动画）：先本地插入 UPLOADING 占位消息（气泡立即渲染），
     * 上传（进度驱动动画）完成后以同 clientMsgId 发送真实消息（upsert 覆盖）。
     */
    private fun uploadAndSendWithPlaceholder(
        chatId: String,
        myUid: String,
        viewModel: ChatViewModel,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        buildBody: (url: String, meta: com.virjar.tk.repository.UploadResult, size: Long) -> MessageBody,
        messageType: Int,
    ) {
        val clientMsgId = UUID.randomUUID().toString()
        // 占位：url 为空（渲染层按 UPLOADING 状态显示进度）
        val placeholder = Message(
            chatId, clientMsgId, 0L, myUid, messageType, System.currentTimeMillis(),
            body = buildBody("", com.virjar.tk.repository.UploadResult("", ""), bytes.size.toLong()),
            sendStatus = Message.SEND_STATUS_UPLOADING,
        )
        viewModel.insertUploadingPlaceholder(placeholder)
        thread {
            try {
                val meta = runBlocking {
                    fileRepo().uploadWithMeta(bytes, fileName, contentType) { p ->
                        viewModel.updateUploadProgress(chatId, clientMsgId, p)
                    }.getOrThrow()
                }
                val url = if (meta.url.startsWith("http")) meta.url else fileUrl(meta.path)
                val thumbUrl = meta.thumbUrl?.let { if (it.startsWith("http")) it else fileUrl(meta.thumbPath!!) }
                val fixedMeta = meta.copy(url = url, thumbUrl = thumbUrl)
                val realBody = buildBody(url, fixedMeta, bytes.size.toLong())
                viewModel.sendMessage(placeholder.copy(body = realBody, sendStatus = Message.SEND_STATUS_SENDING, uploadProgress = 0f))
            } catch (e: Exception) {
                viewModel.onError("上传失败: ${e.message}")
                viewModel.markUploadFailed(chatId, clientMsgId)
            }
        }
    }

    // ── 文件/图片选择并发送 ──

    /** 弹文件选择框，选择图片 → 上传 → 发送 ImageBody 消息。 */
    fun pickAndSendImage(chatId: String, myUid: String, viewModel: ChatViewModel) {
        val file = pickFile(title = "选择图片", filter = FilenameFilter { _, name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        }) ?: return

        thread {
            try {
                val bytes = file.readBytes()
                val ct = guessContentType(file.name)
                uploadAndSendWithPlaceholder(chatId, myUid, viewModel, file.name, ct, bytes,
                    { url, meta, size ->
                        val (w, h) = if (meta.width > 0 && meta.height > 0) meta.width to meta.height else decodeImageSize(bytes)
                        ImageBody(url, width = w, height = h, size = size, thumbnailUrl = meta.thumbUrl)
                    }, MessageType.IMAGE.code)
            } catch (e: Exception) {
                viewModel.onError("图片发送失败: ${e.message}")
            }
        }
    }

    /** 弹文件选择框，选择任意文件 → 上传 → 发送 FileBody 消息。 */
    fun pickAndSendFile(chatId: String, myUid: String, viewModel: ChatViewModel) {
        val file = pickFile(title = "选择文件", filter = null) ?: return

        thread {
            try {
                val bytes = file.readBytes()
                val ct = guessContentType(file.name)
                uploadAndSendWithPlaceholder(chatId, myUid, viewModel, file.name, ct, bytes,
                    { url, _, size -> FileBody(url, fileName = file.name, size = size) }, MessageType.FILE.code)
            } catch (e: Exception) {
                viewModel.onError("文件发送失败: ${e.message}")
            }
        }
    }

    /** 弹文件选择框，选择视频 → 上传 → 发送 VideoBody 消息。 */
    fun pickAndSendVideo(chatId: String, myUid: String, viewModel: ChatViewModel) {
        val file = pickFile(title = "选择视频", filter = FilenameFilter { _, name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext in setOf("mp4", "avi", "mov", "mkv", "flv", "wmv")
        }) ?: return

        thread {
            try {
                val bytes = file.readBytes()
                uploadAndSendWithPlaceholder(chatId, myUid, viewModel, file.name, "video/mp4", bytes,
                    { url, meta, size ->
                        // 服务端 ffprobe 元数据 + javacv 抽帧缩略图（native 缺失时降级为空）
                        VideoBody(
                            url,
                            duration = meta.durationSec ?: 0,
                            width = meta.width,
                            height = meta.height,
                            size = size,
                            thumbnailUrl = meta.thumbUrl,
                        )
                    }, MessageType.VIDEO.code)
            } catch (e: Exception) {
                viewModel.onError("视频发送失败: ${e.message}")
            }
        }
    }

    /**
     * 拖拽文件发送：根据文件扩展名自动识别图片/视频/文件，走对应流程。
     * @param file 拖拽进来的本地文件
     */
    fun sendDroppedFile(chatId: String, myUid: String, file: File, viewModel: ChatViewModel) {
        val ext = file.extension.lowercase()
        thread {
            val bytes = file.readBytes()
            val ct = guessContentType(file.name)
            when {
                ext in IMAGE_EXTS -> uploadAndSendWithPlaceholder(chatId, myUid, viewModel, file.name, ct, bytes,
                    { url, meta, size ->
                        val (w, h) = if (meta.width > 0 && meta.height > 0) meta.width to meta.height else decodeImageSize(bytes)
                        ImageBody(url, width = w, height = h, size = size, thumbnailUrl = meta.thumbUrl)
                    }, MessageType.IMAGE.code)
                ext in VIDEO_EXTS -> uploadAndSendWithPlaceholder(chatId, myUid, viewModel, file.name, ct, bytes,
                    { url, meta, size ->
                        VideoBody(url, duration = meta.durationSec ?: 0, width = meta.width, height = meta.height, size = size, thumbnailUrl = meta.thumbUrl)
                    }, MessageType.VIDEO.code)
                else -> uploadAndSendWithPlaceholder(chatId, myUid, viewModel, file.name, ct, bytes,
                    { url, _, size -> FileBody(url, fileName = file.name, size = size) }, MessageType.FILE.code)
            }
        }
    }

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val VIDEO_EXTS = setOf("mp4", "avi", "mov", "mkv", "flv", "wmv")

    /** AWT FileDialog 包装（阻塞式选择）。 */
    private fun pickFile(title: String, filter: FilenameFilter?): File? {
        val dialog = FileDialog(Frame(), title, FileDialog.LOAD)
        if (filter != null) dialog.filenameFilter = filter
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(dir, name)
    }

    private fun guessContentType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "amr" -> "audio/amr"
            else -> "application/octet-stream"
        }
    }

    /** 用 Skia 解码图片宽高（不创建完整 ImageBitmap，避免内存浪费）。 */
    private fun decodeImageSize(bytes: ByteArray): Pair<Int, Int> {
        return try {
            val img = SkiaImage.makeFromEncoded(bytes)
            img.width to img.height
        } catch (_: Exception) {
            0 to 0
        }
    }

    // ── 录音 ──

    @Volatile
    private var recordingThread: Thread? = null
    @Volatile
    private var targetLine: TargetDataLine? = null
    @Volatile
    private var recordingFile: File? = null

    private val audioFormat = AudioFormat(16000f, 16, 1, true, false)

    /** 开始录音（非阻塞，后台线程录制 WAV 到临时文件）。 */
    private var recordStartedAt = 0L

    fun startRecording() {
        if (recordingThread != null) return
        recordStartedAt = System.currentTimeMillis()
        val outFile = File(cacheDir, "voice_${System.currentTimeMillis()}.wav")
        recordingFile = outFile
        recordingThread = thread {
            try {
                val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
                targetLine = AudioSystem.getLine(info) as TargetDataLine
                targetLine!!.open(audioFormat)
                targetLine!!.start()

                val ais = AudioInputStream(targetLine!!)
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outFile)
            } catch (_: Exception) {
                // 录音失败静默
            }
        }
    }

    /** 停止录音，上传并发送 VoiceBody 消息。 */
    fun stopAndSendVoice(chatId: String, myUid: String, viewModel: ChatViewModel) {
        val file = recordingFile ?: return
        val line = targetLine
        targetLine = null
        recordingFile = null
        val thread0 = recordingThread
        recordingThread = null

        try { line?.stop(); line?.close() } catch (_: Exception) {}
        // 等录音线程写完
        thread0?.join(2000)

        // 按压真实时长（文件头解析在写入竞态下可能失败返回 0，曾致 10s 录制被误判太短）
        val heldSec = if (recordStartedAt > 0) ((System.currentTimeMillis() - recordStartedAt) / 1000).toInt() else 0
        recordStartedAt = 0L
        var durSec = getWavDurationSeconds(file)
        if (durSec <= 0) durSec = heldSec
        // 单击误触（<1s 或音频系统未就绪）：提示太短并取消
        if (heldSec < 1 || !file.exists() || file.length() == 0L) {
            viewModel.onError("录音时间太短")
            file.delete()
            return
        }
        thread {
            try {
                val bytes = file.readBytes()
                val path = uploadFile(bytes, file.name, "audio/wav")
                val url = fileUrl(path)
                val msg = Message(
                    chatId = chatId,
                    clientMsgId = UUID.randomUUID().toString(),
                    serverSeq = 0L,
                    senderUid = myUid,
                    messageType = MessageType.VOICE.code,
                    timestamp = System.currentTimeMillis(),
                    body = VoiceBody(url, duration = durSec, size = bytes.size.toLong()),
                )
                viewModel.sendMessage(msg)
            } catch (e: Exception) {
                viewModel.onError("语音发送失败: ${e.message}")
            } finally {
                file.delete()
            }
        }
    }

    /** 粗略计算 WAV 时长（秒）。 */
    private fun getWavDurationSeconds(file: File): Int {
        return try {
            val ais = AudioSystem.getAudioInputStream(file)
            val frames = ais.frameLength
            val fps = ais.format.frameRate
            ais.close()
            if (fps > 0) (frames / fps).toInt() else 0
        } catch (_: Exception) { 0 }
    }
}

/**
 * Desktop 图片内容渲染 Composable。
 * 异步下载并解码图片，带 loading 占位。
 *
 * 尺寸由 ImageThumbCard 传入的 modifier 控制（已根据 ImageBody 宽高等比缩放），
 * 这里不做额外尺寸约束，避免与共享层的缩放逻辑冲突。
 */
@Composable
fun DesktopImageContent(url: String, modifier: Modifier = Modifier) {
    val bitmapState = produceState<ImageBitmap?>(initialValue = null, url) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            DesktopMediaHelper.loadImageBitmap(url)
        }
    }
    val bmp = bitmapState.value
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "图片",
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(com.virjar.tk.ui.theme.Tk.colors.bubbleIncoming),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.height(24.dp))
        }
    }
}

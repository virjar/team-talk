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

    /** 下载图片文件到缓存。 */
    fun downloadImage(url: String): File = downloadToCache(url)

    /** 下载并播放音频（阻塞当前线程，调用方需切到后台线程）。 */
    fun playAudio(url: String) {
        try {
            val file = downloadToCache(url)
            val stream = AudioSystem.getAudioInputStream(file)
            val clip = AudioSystem.getClip()
            clip.open(stream)
            clip.start()
            // 等待播放完毕
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP) {
                    clip.close()
                    stream.close()
                }
            }
        } catch (_: Exception) {
            // 回退：用系统播放器
            try { Desktop.getDesktop().open(downloadToCache(url)) } catch (_: Exception) {}
        }
    }

    /** 下载并打开文件（用系统默认应用）。 */
    fun openFile(url: String) {
        try {
            Desktop.getDesktop().open(downloadToCache(url))
        } catch (_: Exception) {
            try { Desktop.getDesktop().browse(URI(url)) } catch (_: Exception) {}
        }
    }

    /** 用系统播放器打开视频。 */
    fun openVideo(url: String) {
        try {
            Desktop.getDesktop().open(downloadToCache(url))
        } catch (_: Exception) {
            try { Desktop.getDesktop().browse(URI(url)) } catch (_: Exception) {}
        }
    }

    // ── 上传 ──

    private val fileRepo by lazy { FileRepository(defaultServerConfig().serverUrl) }

    /**
     * 上传文件到服务端，返回相对 path（如 "uid/uuid.ext"）。
     * 调用方拼完整 URL：`fileRepo.resolveUrl(path)`
     */
    fun uploadFile(bytes: ByteArray, fileName: String, contentType: String): String {
        return runBlocking { fileRepo.upload(bytes, fileName, contentType).getOrThrow() }
    }

    /** 根据相对 path 拼装完整下载 URL。 */
    fun fileUrl(path: String): String = fileRepo.resolveUrl(path)

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
                val path = uploadFile(bytes, file.name, ct)
                val url = fileUrl(path)
                // 读取图片宽高
                val (w, h) = decodeImageSize(bytes)
                val msg = Message(
                    chatId = chatId,
                    clientMsgId = UUID.randomUUID().toString(),
                    serverSeq = 0L,
                    senderUid = myUid,
                    messageType = MessageType.IMAGE.code,
                    timestamp = System.currentTimeMillis(),
                    body = ImageBody(url, width = w, height = h, size = bytes.size.toLong()),
                )
                viewModel.sendMessage(msg)
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
                val path = uploadFile(bytes, file.name, ct)
                val url = fileUrl(path)
                val msg = Message(
                    chatId = chatId,
                    clientMsgId = UUID.randomUUID().toString(),
                    serverSeq = 0L,
                    senderUid = myUid,
                    messageType = MessageType.FILE.code,
                    timestamp = System.currentTimeMillis(),
                    body = FileBody(url, fileName = file.name, size = bytes.size.toLong()),
                )
                viewModel.sendMessage(msg)
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
                val path = uploadFile(bytes, file.name, "video/mp4")
                val url = fileUrl(path)
                val msg = Message(
                    chatId = chatId,
                    clientMsgId = UUID.randomUUID().toString(),
                    serverSeq = 0L,
                    senderUid = myUid,
                    messageType = MessageType.VIDEO.code,
                    timestamp = System.currentTimeMillis(),
                    body = VideoBody(url, size = bytes.size.toLong()),
                )
                viewModel.sendMessage(msg)
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
            try {
                val bytes = file.readBytes()
                val ct = guessContentType(file.name)
                val path = uploadFile(bytes, file.name, ct)
                val url = fileUrl(path)
                val msg = when {
                    ext in IMAGE_EXTS -> {
                        val (w, h) = decodeImageSize(bytes)
                        Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.IMAGE.code,
                            System.currentTimeMillis(), body = ImageBody(url, width = w, height = h, size = bytes.size.toLong()))
                    }
                    ext in VIDEO_EXTS -> {
                        Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.VIDEO.code,
                            System.currentTimeMillis(), body = VideoBody(url, size = bytes.size.toLong()))
                    }
                    else -> {
                        Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.FILE.code,
                            System.currentTimeMillis(), body = FileBody(url, fileName = file.name, size = bytes.size.toLong()))
                    }
                }
                viewModel.sendMessage(msg)
            } catch (e: Exception) {
                viewModel.onError("发送失败: ${e.message}")
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
    fun startRecording() {
        if (recordingThread != null) return
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
        recordingThread = null

        try { line?.stop(); line?.close() } catch (_: Exception) {}
        // 等录音线程写完
        recordingThread?.join(2000)

        val durSec = getWavDurationSeconds(file)
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
            modifier = modifier.background(androidx.compose.ui.graphics.Color(0xFFE0E0E0)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.height(24.dp))
        }
    }
}

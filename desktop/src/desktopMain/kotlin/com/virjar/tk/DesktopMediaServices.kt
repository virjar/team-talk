package com.virjar.tk

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.virjar.tk.body.FileBody
import com.virjar.tk.body.ImageBody
import com.virjar.tk.body.MessageBody
import com.virjar.tk.body.VideoBody
import com.virjar.tk.body.VoiceBody
import com.virjar.tk.http.UploadResult
import com.virjar.tk.media.DesktopSessionDiagnosticEvent
import com.virjar.tk.media.DesktopSessionDiagnostics
import com.virjar.tk.media.DesktopSessionResources
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.repository.asUploadSource
import com.virjar.tk.viewmodel.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.makeFromFileName
import org.jetbrains.skia.impl.use
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.Closeable
import java.io.File
import java.io.FilenameFilter
import java.util.UUID
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.coroutines.coroutineContext

/** 只负责平台文件选择，不持有认证、网络或缓存状态。 */
internal object DesktopFilePicker {
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    internal val videoExtensions = setOf("mp4", "avi", "mov", "mkv", "flv", "wmv")

    fun chooseImage(): File? = choose("选择图片") { _, name ->
        name.substringAfterLast('.', "").lowercase() in imageExtensions
    }

    fun chooseVideo(): File? = choose("选择视频") { _, name ->
        name.substringAfterLast('.', "").lowercase() in videoExtensions
    }

    fun chooseFile(title: String = "选择文件"): File? = choose(title, null)

    private fun choose(title: String, filter: FilenameFilter?): File? {
        val owner = Frame()
        val dialog = FileDialog(owner, title, FileDialog.LOAD)
        return try {
            dialog.filenameFilter = filter
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val name = dialog.file ?: return null
            File(directory, name)
        } finally {
            dialog.dispose()
            owner.dispose()
        }
    }
}

/** 文件类型推导是纯规则，不与文件选择或网络传输耦合。 */
internal fun desktopContentType(fileName: String): String = when (fileName.extensionLowercase()) {
    "txt" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "avi" -> "video/x-msvideo"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg" -> "audio/ogg"
    "wav" -> "audio/wav"
    "amr" -> "audio/amr"
    else -> "application/octet-stream"
}

private fun String.extensionLowercase(): String = substringAfterLast('.', "").lowercase()

/** 只负责可信本地文件的系统打开；不会把带认证语义的远端 URL 交给浏览器。 */
internal object DesktopExternalFileOpener {
    fun open(file: File) {
        require(file.isFile) { "文件不存在: ${file.name}" }
        Desktop.getDesktop().open(file)
    }
}

/** 图片编码/解码，不负责下载。 */
internal object DesktopImageCodec {
    fun decode(file: File, diagnostics: DesktopSessionDiagnostics): ImageBitmap? = try {
        Data.makeFromFileName(file.absolutePath).use { encoded ->
            Codec.makeFromData(encoded).use { codec ->
                codec.readPixels().use { bitmap ->
                    SkiaImage.makeFromBitmap(bitmap).toComposeImageBitmap()
                }
            }
        }
    } catch (_: Exception) {
        diagnostics.record(DesktopSessionDiagnosticEvent.IMAGE_DECODE_FAILED)
        null
    }

    fun dimensions(file: File): Pair<Int, Int> = try {
        Data.makeFromFileName(file.absolutePath).use { encoded ->
            Codec.makeFromData(encoded).use { codec -> codec.size.x to codec.size.y }
        }
    } catch (_: Exception) {
        0 to 0
    }
}

/**
 * 会话固定凭据下的上传入口。调用前后都检查门禁：退出时即使底层 HTTP 尚未返回，
 * 旧上传也不能再驱动已销毁页面或把结果发送进新账号。
 */
internal class DesktopFileTransfer(
    private val resources: DesktopSessionResources,
) {
    suspend fun upload(file: File, contentType: String = desktopContentType(file.name)): Attachment {
        resources.ensureOpen()
        require(file.isFile) { "文件不存在: ${file.name}" }
        val result = resources.fileRepository
            .upload(file.asUploadSource(), file.name, contentType)
            .getOrThrow()
        resources.ensureOpen()
        return result
    }

    suspend fun uploadWithMeta(
        file: File,
        contentType: String,
        onProgress: (Float) -> Unit = {},
    ): UploadResult {
        resources.ensureOpen()
        require(file.isFile) { "文件不存在: ${file.name}" }
        val result = resources.fileRepository
            .uploadWithMeta(file.asUploadSource(), file.name, contentType) { progress ->
                resources.ensureOpen()
                onProgress(progress)
            }
            .getOrThrow()
        resources.ensureOpen()
        return result
    }
}

/** 聊天附件发送协调器；所有任务属于当前 Desktop 认证会话。 */
internal class DesktopMediaSender(
    private val resources: DesktopSessionResources,
    private val transfer: DesktopFileTransfer,
) : Closeable {
    private val scope: CoroutineScope = resources.childScope("media-send")

    fun pickAndSendImage(chatId: String, myUid: String, viewModel: ChatViewModel) {
        DesktopFilePicker.chooseImage()?.let { sendFile(chatId, myUid, it, viewModel, MediaKind.IMAGE) }
    }

    fun pickAndSendFile(chatId: String, myUid: String, viewModel: ChatViewModel) {
        DesktopFilePicker.chooseFile()?.let { sendFile(chatId, myUid, it, viewModel, MediaKind.FILE) }
    }

    fun pickAndSendVideo(chatId: String, myUid: String, viewModel: ChatViewModel) {
        DesktopFilePicker.chooseVideo()?.let { sendFile(chatId, myUid, it, viewModel, MediaKind.VIDEO) }
    }

    fun sendDroppedFile(chatId: String, myUid: String, file: File, viewModel: ChatViewModel) {
        val kind = when (file.extension.lowercase()) {
            in IMAGE_EXTENSIONS -> MediaKind.IMAGE
            in DesktopFilePicker.videoExtensions -> MediaKind.VIDEO
            else -> MediaKind.FILE
        }
        sendFile(chatId, myUid, file, viewModel, kind)
    }

    private fun sendFile(
        chatId: String,
        myUid: String,
        file: File,
        viewModel: ChatViewModel,
        kind: MediaKind,
    ) {
        check(myUid == resources.ownerUid) { "媒体发送账号与认证会话不一致" }
        scope.launch {
            try {
                resources.ensureOpen()
                coroutineContext.ensureActive()
                val contentType = desktopContentType(file.name)
                uploadAndSendWithPlaceholder(
                    chatId = chatId,
                    myUid = myUid,
                    viewModel = viewModel,
                    file = file,
                    contentType = contentType,
                    kind = kind,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (runCatching { resources.ensureOpen() }.isFailure) return@launch
                viewModel.onError("${kind.displayName}发送失败: ${error.message}")
            }
        }
    }

    private suspend fun uploadAndSendWithPlaceholder(
        chatId: String,
        myUid: String,
        viewModel: ChatViewModel,
        file: File,
        contentType: String,
        kind: MediaKind,
    ) {
        val clientMsgId = UUID.randomUUID().toString()
        val pendingAttachment = Attachment("", file.name, contentType, file.length())
        val placeholder = Message(
            chatId = chatId,
            clientMsgId = clientMsgId,
            serverSeq = 0L,
            senderUid = myUid,
            messageType = kind.messageType.code,
            timestamp = System.currentTimeMillis(),
            body = kind.body(UploadResult(file = pendingAttachment), file),
            sendStatus = Message.SEND_STATUS_UPLOADING,
        )
        viewModel.insertUploadingPlaceholder(placeholder)

        try {
            val metadata = transfer.uploadWithMeta(file, contentType) { progress ->
                viewModel.updateUploadProgress(chatId, clientMsgId, progress)
            }
            resources.ensureOpen()
            val body = kind.body(metadata, file)
            viewModel.sendMessage(
                placeholder.copy(
                    body = body,
                    sendStatus = Message.SEND_STATUS_SENDING,
                    uploadProgress = 0f,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (runCatching { resources.ensureOpen() }.isFailure) return
            viewModel.onError("上传失败: ${error.message}")
            viewModel.markUploadFailed(chatId, clientMsgId)
        }
    }

    override fun close() {
        scope.cancel()
    }

    private enum class MediaKind(
        val messageType: MessageType,
        val displayName: String,
    ) {
        IMAGE(MessageType.IMAGE, "图片"),
        VIDEO(MessageType.VIDEO, "视频"),
        FILE(MessageType.FILE, "文件");

        fun body(metadata: UploadResult, sourceFile: File): MessageBody = when (this) {
            IMAGE -> {
                val dimensions = if (metadata.width > 0 && metadata.height > 0) {
                    metadata.width to metadata.height
                } else {
                    DesktopImageCodec.dimensions(sourceFile)
                }
                ImageBody(
                    attachment = metadata.file,
                    width = dimensions.first,
                    height = dimensions.second,
                    thumbnail = metadata.thumbnail,
                )
            }
            VIDEO -> VideoBody(
                attachment = metadata.file,
                duration = metadata.durationSec ?: 0,
                width = metadata.width,
                height = metadata.height,
                thumbnail = metadata.thumbnail,
            )
            FILE -> FileBody(metadata.file)
        }
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    }
}

/** 会话拥有的录音器；使用结构化协程，关闭资源会主动关闭声卡并取消写入。 */
internal class DesktopVoiceRecorder(
    private val resources: DesktopSessionResources,
    private val transfer: DesktopFileTransfer,
) : Closeable {
    private data class ActiveRecording(
        val file: File,
        val startedAt: Long,
        var line: TargetDataLine? = null,
        var job: Job? = null,
    )

    private val scope = resources.childScope("voice-record")
    private val stateLock = Any()
    private val audioFormat = AudioFormat(16_000f, 16, 1, true, false)
    private val recordingDir = File(resources.mediaDirectory, "recording").apply { mkdirs() }
    private var active: ActiveRecording? = null

    fun start() {
        resources.ensureOpen()
        val recording = synchronized(stateLock) {
            if (active != null) return
            ActiveRecording(
                file = File(recordingDir, "voice-${UUID.randomUUID()}.wav"),
                startedAt = System.currentTimeMillis(),
            ).also { active = it }
        }
        val recordingJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
                val line = AudioSystem.getLine(info) as TargetDataLine
                synchronized(stateLock) {
                    if (active !== recording) {
                        line.close()
                        return@launch
                    }
                    recording.line = line
                }
                line.open(audioFormat)
                synchronized(stateLock) {
                    if (active !== recording) {
                        line.close()
                        return@launch
                    }
                }
                coroutineContext.ensureActive()
                line.start()
                AudioInputStream(line).use { input ->
                    AudioSystem.write(input, AudioFileFormat.Type.WAVE, recording.file)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                resources.diagnostics.record(DesktopSessionDiagnosticEvent.VOICE_RECORD_FAILED)
            }
        }
        synchronized(stateLock) {
            if (active === recording) recording.job = recordingJob else recordingJob.cancel()
        }
        recordingJob.start()
    }

    fun stopAndSend(chatId: String, myUid: String, viewModel: ChatViewModel) {
        check(myUid == resources.ownerUid) { "语音发送账号与认证会话不一致" }
        val recording = synchronized(stateLock) {
            active?.also { active = null }
        } ?: return
        val durationSeconds = ((System.currentTimeMillis() - recording.startedAt) / 1_000).toInt()
        runCatching { recording.line?.stop() }
        runCatching { recording.line?.close() }

        scope.launch {
            try {
                val finished = withTimeoutOrNull(RECORDING_FINISH_TIMEOUT_MS) {
                    recording.job?.join()
                    true
                } ?: false
                if (!finished) {
                    recording.job?.cancel()
                    withTimeoutOrNull(RECORDING_CANCEL_TIMEOUT_MS) { recording.job?.join() }
                }
                if (durationSeconds < 1 || !recording.file.isFile || recording.file.length() == 0L) {
                    viewModel.onError("录音时间太短")
                    return@launch
                }
                val attachment = transfer.upload(recording.file, "audio/wav")
                resources.ensureOpen()
                viewModel.sendMessage(
                    Message(
                        chatId = chatId,
                        clientMsgId = UUID.randomUUID().toString(),
                        serverSeq = 0L,
                        senderUid = myUid,
                        messageType = MessageType.VOICE.code,
                        timestamp = System.currentTimeMillis(),
                        body = VoiceBody(attachment, duration = durationSeconds),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (runCatching { resources.ensureOpen() }.isFailure) return@launch
                viewModel.onError("语音发送失败: ${error.message}")
            } finally {
                recording.file.delete()
            }
        }
    }

    override fun close() {
        val recording = synchronized(stateLock) {
            active?.also { active = null }
        }
        runCatching { recording?.line?.stop() }
        runCatching { recording?.line?.close() }
        recording?.job?.invokeOnCompletion { recording.file.delete() }
        scope.cancel()
    }

    private companion object {
        const val RECORDING_FINISH_TIMEOUT_MS = 2_000L
        const val RECORDING_CANCEL_TIMEOUT_MS = 500L
    }
}

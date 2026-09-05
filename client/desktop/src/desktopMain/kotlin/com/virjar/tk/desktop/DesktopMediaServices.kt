package com.virjar.tk.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.virjar.tk.protocol.body.VideoBody
import com.virjar.tk.protocol.body.VoiceBody
import com.virjar.tk.protocol.http.AttachmentUploadIdentity
import com.virjar.tk.protocol.http.UploadResult
import com.virjar.tk.desktop.media.DesktopSessionDiagnosticEvent
import com.virjar.tk.desktop.media.DesktopSessionDiagnostics
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.shared.repository.asUploadSource
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.MediaOperation
import com.virjar.tk.app.telemetry.UserFeedbackCode
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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.coroutines.coroutineContext
import kotlin.concurrent.withLock

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

    /** 用于同进程富媒体重试的精确重放变体。 */
    suspend fun uploadWithMeta(
        file: File,
        contentType: String,
        identity: AttachmentUploadIdentity,
        onProgress: (Float) -> Unit = {},
    ): UploadResult {
        resources.ensureOpen()
        require(file.isFile) { "文件不存在: ${file.name}" }
        val result = resources.fileRepository
            .uploadWithMeta(file.asUploadSource(), file.name, contentType, identity) { progress ->
                resources.ensureOpen()
                onProgress(progress)
            }
            .getOrThrow()
        resources.ensureOpen()
        return result
    }
}

/** 视频消息发送；图片和文件由富资产导入器加入正文，所有任务属于当前认证会话。 */
internal class DesktopVideoSender(
    private val resources: DesktopSessionResources,
    private val transfer: DesktopFileTransfer,
) : Closeable {
    private val scope: CoroutineScope = resources.childScope("media-send")

    fun pickAndSendVideo(chatId: String, myUid: String, viewModel: ChatViewModel) {
        val file = DesktopFilePicker.chooseVideo() ?: return
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
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!resources.canDeliverUiResult()) return@launch
                val reason = classifyDesktopMediaFailure(error)
                resources.telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    ClientMediaKind.VIDEO,
                    MediaOperation.UPLOAD,
                    ClientActionOutcome.FAILED,
                    reason,
                )
                viewModel.onError(UserFeedbackCode.MEDIA_UPLOAD_FAILED.publicMessage)
            }
        }
    }

    private suspend fun uploadAndSendWithPlaceholder(
        chatId: String,
        myUid: String,
        viewModel: ChatViewModel,
        file: File,
        contentType: String,
    ) {
        val clientMsgId = UUID.randomUUID().toString()
        val pendingAttachment = Attachment("", file.name, contentType, file.length())
        val placeholder = Message(
            chatId = chatId,
            clientMsgId = clientMsgId,
            serverSeq = 0L,
            senderUid = myUid,
            messageType = MessageType.VIDEO.code,
            timestamp = System.currentTimeMillis(),
            body = VideoBody(attachment = pendingAttachment),
            sendStatus = Message.SEND_STATUS_UPLOADING,
        )
        viewModel.insertUploadingPlaceholder(placeholder)

        try {
            resources.telemetry.recordMedia(
                ClientUiPage.CHAT,
                ClientMediaKind.VIDEO,
                MediaOperation.UPLOAD,
                ClientActionOutcome.STARTED,
            )
            val metadata = transfer.uploadWithMeta(file, contentType) { progress ->
                viewModel.updateUploadProgress(chatId, clientMsgId, progress)
            }
            resources.ensureOpen()
            val body = VideoBody(
                attachment = metadata.file,
                duration = metadata.durationSec ?: 0,
                width = metadata.width,
                height = metadata.height,
                thumbnail = metadata.thumbnail,
            )
            viewModel.sendMessage(
                placeholder.copy(
                    body = body,
                    sendStatus = Message.SEND_STATUS_SENDING,
                    uploadProgress = 0f,
                ),
            )
            resources.telemetry.recordMedia(
                ClientUiPage.CHAT,
                ClientMediaKind.VIDEO,
                MediaOperation.UPLOAD,
                ClientActionOutcome.SUCCEEDED,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!resources.canDeliverUiResult()) return
            val reason = classifyDesktopMediaFailure(error)
            resources.telemetry.recordMedia(
                ClientUiPage.CHAT,
                ClientMediaKind.VIDEO,
                MediaOperation.UPLOAD,
                ClientActionOutcome.FAILED,
                reason,
            )
            viewModel.onError(UserFeedbackCode.MEDIA_UPLOAD_FAILED.publicMessage)
            viewModel.markUploadFailed(chatId, clientMsgId)
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/** 会话拥有的录音器；使用结构化协程，关闭资源会主动关闭声卡并取消写入。 */
internal class DesktopVoiceRecorder(
    private val resources: DesktopSessionResources,
    private val transfer: DesktopFileTransfer,
    private val lineProvider: (DataLine.Info) -> TargetDataLine = { info ->
        AudioSystem.getLine(info) as TargetDataLine
    },
) : Closeable {
    private data class ActiveRecording(
        val file: File,
        val startedAt: Long,
        var line: TargetDataLine? = null,
        var job: Job? = null,
        var failure: Throwable? = null,
        var terminalOutcome: ClientActionOutcome? = null,
    )

    private val scope = resources.childScope("voice-record")
    private val stateLock = ReentrantLock()
    private val closeCompleted = stateLock.newCondition()
    private val audioFormat = AudioFormat(16_000f, 16, 1, true, false)
    private val recordingDir = File(resources.mediaDirectory, "recording").apply { mkdirs() }
    private var active: ActiveRecording? = null
    private var closePhase = VoiceRecorderClosePhase.OPEN
    private var closingThread: Thread? = null
    private var completedCloseFailure: Throwable? = null

    fun start() {
        resources.ensureOpen()
        val recording = stateLock.withLock {
            check(closePhase == VoiceRecorderClosePhase.OPEN) { "Desktop 录音器已经关闭" }
            if (active != null) return
            ActiveRecording(
                file = File(recordingDir, "voice-${UUID.randomUUID()}.wav"),
                startedAt = System.currentTimeMillis(),
            ).also { active = it }
        }
        resources.telemetry.recordMedia(
            ClientUiPage.CHAT,
            ClientMediaKind.AUDIO,
            MediaOperation.RECORD,
            ClientActionOutcome.STARTED,
        )
        val recordingJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
                val line = lineProvider(info)
                stateLock.withLock {
                    if (closePhase != VoiceRecorderClosePhase.OPEN || active !== recording) {
                        line.close()
                        return@launch
                    }
                    recording.line = line
                }
                line.open(audioFormat)
                stateLock.withLock {
                    if (closePhase != VoiceRecorderClosePhase.OPEN || active !== recording) {
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
            } catch (failure: Exception) {
                failRecordingOnce(recording, failure)
            }
        }
        stateLock.withLock {
            if (closePhase == VoiceRecorderClosePhase.OPEN && active === recording) {
                recording.job = recordingJob
            } else {
                recordingJob.cancel()
            }
        }
        recordingJob.start()
    }

    fun stopAndSend(chatId: String, myUid: String, viewModel: ChatViewModel) {
        check(myUid == resources.ownerUid) { "语音发送账号与认证会话不一致" }
        val recording = stateLock.withLock {
            active?.also { active = null }
        } ?: return
        val durationSeconds = ((System.currentTimeMillis() - recording.startedAt) / 1_000).toInt()
        val lineReleaseFailure = try {
            releaseDesktopVoiceLineForSend(recording.line)
            null
        } catch (failure: Throwable) {
            failure
        }
        if (lineReleaseFailure != null) {
            val terminalFailure = discardFailedRecording(recording, lineReleaseFailure)
            when (terminalFailure) {
                is CancellationException -> {
                    recordTerminalOnce(recording, ClientActionOutcome.CANCELLED)
                    throw terminalFailure
                }
                is Exception -> {
                    failRecordingOnce(recording, terminalFailure)
                    viewModel.onError(UserFeedbackCode.VOICE_RECORDING_FAILED.publicMessage)
                    return
                }
                else -> {
                    failRecordingOnce(recording, terminalFailure)
                    throw terminalFailure
                }
            }
        }

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
                val recordingFailure = stateLock.withLock { recording.failure }
                if (recordingFailure != null) {
                    viewModel.onError(UserFeedbackCode.VOICE_RECORDING_FAILED.publicMessage)
                    return@launch
                }
                if (!finished) {
                    failRecordingOnce(recording, DesktopVoiceRecordingTimeoutException())
                    viewModel.onError(UserFeedbackCode.VOICE_RECORDING_FAILED.publicMessage)
                    return@launch
                }
                if (durationSeconds < 1) {
                    recordTerminalOnce(
                        recording,
                        ClientActionOutcome.FAILED,
                        MediaFailureReason.SIZE_VALIDATION,
                    )
                    viewModel.onError(UserFeedbackCode.VOICE_TOO_SHORT.publicMessage)
                    return@launch
                }
                if (!recording.file.isFile || recording.file.length() == 0L) {
                    failRecordingOnce(recording, DesktopVoiceRecordingOutputException())
                    viewModel.onError(UserFeedbackCode.VOICE_RECORDING_FAILED.publicMessage)
                    return@launch
                }
                recordTerminalOnce(recording, ClientActionOutcome.SUCCEEDED)
                resources.telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    ClientMediaKind.AUDIO,
                    MediaOperation.UPLOAD,
                    ClientActionOutcome.STARTED,
                )
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
                resources.telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    ClientMediaKind.AUDIO,
                    MediaOperation.UPLOAD,
                    ClientActionOutcome.SUCCEEDED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!resources.canDeliverUiResult()) return@launch
                resources.telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    ClientMediaKind.AUDIO,
                    MediaOperation.UPLOAD,
                    ClientActionOutcome.FAILED,
                    classifyDesktopMediaFailure(error),
                )
                viewModel.onError(UserFeedbackCode.MEDIA_UPLOAD_FAILED.publicMessage)
            } finally {
                recording.file.delete()
            }
        }
    }

    private fun failRecordingOnce(recording: ActiveRecording, failure: Throwable) {
        val accepted = stateLock.withLock {
            if (recording.terminalOutcome != null) return@withLock false
            recording.failure = failure
            recording.terminalOutcome = ClientActionOutcome.FAILED
            true
        }
        if (!accepted) return
        resources.telemetry.recordMedia(
            ClientUiPage.CHAT,
            ClientMediaKind.AUDIO,
            MediaOperation.RECORD,
            ClientActionOutcome.FAILED,
            classifyDesktopMediaFailure(failure),
        )
        resources.diagnostics.record(DesktopSessionDiagnosticEvent.VOICE_RECORD_FAILED)
    }

    private fun recordTerminalOnce(
        recording: ActiveRecording,
        outcome: ClientActionOutcome,
        failureReason: MediaFailureReason? = null,
    ): Boolean {
        val accepted = stateLock.withLock {
            if (recording.terminalOutcome != null) return@withLock false
            recording.terminalOutcome = outcome
            true
        }
        if (!accepted) return false
        resources.telemetry.recordMedia(
            ClientUiPage.CHAT,
            ClientMediaKind.AUDIO,
            MediaOperation.RECORD,
            outcome,
            failureReason,
        )
        return true
    }

    private fun discardFailedRecording(recording: ActiveRecording, primaryFailure: Throwable): Throwable {
        val failures = mutableListOf(primaryFailure)
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (cleanupFailure: Throwable) {
                if (failures.none { observed -> observed === cleanupFailure }) failures += cleanupFailure
            }
        }
        cleanup { recording.job?.cancel() }
        cleanup { recording.job?.invokeOnCompletion { recording.file.delete() } ?: recording.file.delete() }
        return requireNotNull(terminalVoiceRecorderCloseFailure(failures))
    }

    override fun close() {
        var leaderRecording: ActiveRecording? = null
        while (true) {
            val role = stateLock.withLock {
                when (closePhase) {
                    VoiceRecorderClosePhase.OPEN -> {
                        closePhase = VoiceRecorderClosePhase.CLOSING
                        closingThread = Thread.currentThread()
                        leaderRecording = active?.also { active = null }
                        VoiceRecorderCloseRole.LEADER
                    }

                    VoiceRecorderClosePhase.CLOSING -> if (closingThread === Thread.currentThread()) {
                        VoiceRecorderCloseRole.REENTRANT
                    } else {
                        VoiceRecorderCloseRole.FOLLOWER
                    }

                    VoiceRecorderClosePhase.CLOSED -> VoiceRecorderCloseRole.COMPLETE
                }
            }

            when (role) {
                VoiceRecorderCloseRole.LEADER -> return closeAsLeader(leaderRecording)
                VoiceRecorderCloseRole.FOLLOWER -> stateLock.withLock {
                    while (closePhase == VoiceRecorderClosePhase.CLOSING) {
                        closeCompleted.awaitUninterruptibly()
                    }
                }
                VoiceRecorderCloseRole.COMPLETE -> {
                    stateLock.withLock { completedCloseFailure }?.let { throw it }
                    return
                }
                VoiceRecorderCloseRole.REENTRANT -> throw DesktopVoiceRecorderReentrantCloseException()
            }
        }
    }

    private fun closeAsLeader(recording: ActiveRecording?) {
        val failures = mutableListOf<Throwable>()
        fun release(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                if (failures.none { observed -> observed === failure }) failures += failure
            }
        }

        release { recording?.line?.stop() }
        release { recording?.line?.close() }
        recording?.let { owned ->
            release { owned.job?.invokeOnCompletion { owned.file.delete() } }
        }
        release { scope.cancel() }

        val terminalFailure = terminalVoiceRecorderCloseFailure(failures)
        recording?.let { owned ->
            when {
                stateLock.withLock { owned.failure != null } -> Unit
                terminalFailure == null || terminalFailure is CancellationException -> {
                    recordTerminalOnce(owned, ClientActionOutcome.CANCELLED)
                }
                else -> failRecordingOnce(owned, terminalFailure)
            }
        }
        stateLock.withLock {
            completedCloseFailure = terminalFailure
            closingThread = null
            closePhase = VoiceRecorderClosePhase.CLOSED
            closeCompleted.signalAll()
        }
        terminalFailure?.let { throw it }
    }

    private companion object {
        const val RECORDING_FINISH_TIMEOUT_MS = 2_000L
        const val RECORDING_CANCEL_TIMEOUT_MS = 500L
    }
}

private enum class VoiceRecorderClosePhase { OPEN, CLOSING, CLOSED }

private enum class VoiceRecorderCloseRole { LEADER, FOLLOWER, COMPLETE, REENTRANT }

private class DesktopVoiceRecorderReentrantCloseException : IllegalStateException(
    "Desktop 录音器不能从正在执行的关闭流程中重入关闭",
)

private class DesktopVoiceRecorderCloseException(failures: List<Throwable>) : IllegalStateException(
    "Desktop 录音器关闭时有 ${failures.size} 个资源未能正常释放",
) {
    init {
        failures.forEach(::addSuppressed)
    }
}

private class DesktopVoiceRecordingTimeoutException : IOException(
    "Desktop voice recording did not stop before its bounded deadline",
)

private class DesktopVoiceRecordingOutputException : IOException(
    "Desktop voice recording produced no usable output",
)

/** 停止发送前先完整释放声卡；任何失败都禁止继续上传可能未完成的录音。 */
internal fun releaseDesktopVoiceLineForSend(line: TargetDataLine?) {
    if (line == null) return
    val failures = mutableListOf<Throwable>()
    fun release(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            if (failures.none { observed -> observed === failure }) failures += failure
        }
    }

    release(line::stop)
    release(line::close)
    terminalVoiceRecorderCloseFailure(failures)?.let { throw it }
}

private fun terminalVoiceRecorderCloseFailure(failures: List<Throwable>): Throwable? {
    if (failures.isEmpty()) return null
    val fatal = failures.firstOrNull { failure ->
        failure is CancellationException || failure !is Exception
    } ?: return DesktopVoiceRecorderCloseException(failures)
    failures.forEach { failure ->
        if (failure !== fatal && fatal.suppressed.none { existing -> existing === failure }) {
            fatal.addSuppressed(failure)
        }
    }
    return fatal
}

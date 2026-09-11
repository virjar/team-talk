package com.virjar.tk.app.navigation.feature.chat

import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.MediaOperation
import com.virjar.tk.app.telemetry.UserFeedbackCode
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.body.VideoBody
import com.virjar.tk.protocol.body.VoiceBody
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** 视频上传结果：平台上传实现给出已上传附件与最终元数据。 */
class UploadedVideoMedia(
    val attachment: Attachment,
    val durationSec: Int,
    val width: Int,
    val height: Int,
    val thumbnail: Attachment?,
)

/**
 * 出站视频/语音消息的统一编排。视频先插入 UPLOADING 占位、上传期间由平台实现回传进度，
 * 成功后组装协议 Message 并发送；失败把占位标记为失败、上报遥测并提示用户。语音保持
 * 即传即发。两端壳只提供"读取本地源并上传"的平台实现与失败分类，不再手写协议组装。
 */
class OutgoingMediaSender(
    private val telemetry: ClientUiTelemetrySink,
    private val page: ClientUiPage = ClientUiPage.CHAT,
) {
    /**
     * [upload] 在调用方协程内执行平台上传；上传前插入占位，上传失败标记占位失败。
     * 占位、发送、遥测与失败提示都收敛在此，调用方无需复制三段式样板。
     */
    suspend fun sendVideo(
        chatId: String,
        myUid: String,
        viewModel: ChatViewModel,
        onUploadingChanged: (Boolean) -> Unit = {},
        classifyFailure: (Throwable) -> MediaFailureReason? = { null },
        reportFailure: ((error: Exception, reason: MediaFailureReason) -> Unit)? = null,
        upload: suspend (onProgress: (Float) -> Unit) -> UploadedVideoMedia,
    ) {
        val clientMsgId = UUID.randomUUID().toString()
        val placeholder = Message(
            chatId = chatId,
            clientMsgId = clientMsgId,
            serverSeq = 0L,
            senderUid = myUid,
            messageType = MessageType.VIDEO.code,
            timestamp = System.currentTimeMillis(),
            body = VideoBody(attachment = Attachment("", "", "", 0L)),
            sendStatus = Message.SEND_STATUS_UPLOADING,
        )
        viewModel.insertUploadingPlaceholder(placeholder)
        onUploadingChanged(true)
        try {
            telemetry.recordMedia(page, ClientMediaKind.VIDEO, MediaOperation.UPLOAD, ClientActionOutcome.STARTED)
            val media = upload { progress -> viewModel.updateUploadProgress(chatId, clientMsgId, progress) }
            viewModel.sendMessage(
                placeholder.copy(
                    body = VideoBody(media.attachment, media.durationSec, media.width, media.height, media.thumbnail),
                    sendStatus = Message.SEND_STATUS_SENDING,
                    uploadProgress = 0f,
                ),
            )
            telemetry.recordMedia(page, ClientMediaKind.VIDEO, MediaOperation.UPLOAD, ClientActionOutcome.SUCCEEDED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onMediaFailed(viewModel, ClientMediaKind.VIDEO, classifyFailure(error), error, reportFailure)
            viewModel.markUploadFailed(chatId, clientMsgId)
        } finally {
            onUploadingChanged(false)
        }
    }

    /** 语音：上传成功即发送；无占位，失败只上报遥测并提示。 */
    suspend fun sendVoice(
        chatId: String,
        myUid: String,
        viewModel: ChatViewModel,
        durationSeconds: Int,
        onUploadingChanged: (Boolean) -> Unit = {},
        classifyFailure: (Throwable) -> MediaFailureReason? = { null },
        reportFailure: ((error: Exception, reason: MediaFailureReason) -> Unit)? = null,
        upload: suspend () -> Attachment,
    ) {
        onUploadingChanged(true)
        try {
            telemetry.recordMedia(page, ClientMediaKind.AUDIO, MediaOperation.UPLOAD, ClientActionOutcome.STARTED)
            val attachment = upload()
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
            telemetry.recordMedia(page, ClientMediaKind.AUDIO, MediaOperation.UPLOAD, ClientActionOutcome.SUCCEEDED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onMediaFailed(viewModel, ClientMediaKind.AUDIO, classifyFailure(error), error, reportFailure)
        } finally {
            onUploadingChanged(false)
        }
    }

    private fun onMediaFailed(
        viewModel: ChatViewModel,
        kind: ClientMediaKind,
        reason: MediaFailureReason?,
        error: Exception,
        reportFailure: ((Exception, MediaFailureReason) -> Unit)?,
    ) {
        val resolved = reason ?: MediaFailureReason.UNKNOWN
        telemetry.recordMedia(page, kind, MediaOperation.UPLOAD, ClientActionOutcome.FAILED, resolved)
        if (reportFailure != null) {
            reportFailure(error, resolved)
        } else {
            viewModel.onError(UserFeedbackCode.MEDIA_UPLOAD_FAILED.publicMessage)
        }
    }
}

package com.virjar.tk.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.virjar.tk.app.navigation.feature.chat.OutgoingMediaSender
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.MediaOperation
import com.virjar.tk.app.telemetry.UserFeedbackCode
import com.virjar.tk.app.telemetry.uploadFeedbackCode
import com.virjar.tk.app.viewmodel.ChatViewModel
import java.io.File

/**
 * 聊天语音录制的编排入口：MediaRecorder 生命周期、麦克风权限门与上传发送。
 * Composable 只保留权限启动器与 UI 反馈状态接线；对齐 iOS IosVoiceRecorder 与
 * Desktop DesktopVoiceRecorder 的分工边界。会话退役排空由
 * [VoiceRecordingLease.sealAndDiscard] 经媒体资源链路完成。
 */
internal class AndroidChatVoiceController(
    private val context: Context,
    private val chatId: String,
    private val myUid: String,
    private val viewModel: ChatViewModel,
    private val telemetry: ClientUiTelemetrySink,
    private val mediaSession: () -> AndroidMediaSession,
    val lease: VoiceRecordingLease<MediaRecorder>,
    val permissionGate: VoiceRecordPermissionGate,
) {
    /** 每次重组由 Composable 刷新的易变接线；构造时置默认避免遗漏调用。 */
    var wiring: Wiring = Wiring.NOOP

    fun interface LaunchOwnedMediaSource {
        fun launch(ownedFile: File, action: suspend () -> Unit)
    }

    internal class Wiring(
        val queueMediaFeedback: (UserFeedbackCode, ClientUiAction, FeedbackOrigin) -> Unit,
        val reportMediaFailure: (ClientMediaKind, MediaOperation, Throwable) -> Unit,
        val launchOwnedMediaSource: LaunchOwnedMediaSource,
        val onUploadingChanged: (Boolean) -> Unit,
    ) {
        companion object {
            val NOOP = Wiring(
                queueMediaFeedback = { _, _, _ -> },
                reportMediaFailure = { _, _, _ -> },
                launchOwnedMediaSource = LaunchOwnedMediaSource { _, _ -> },
                onUploadingChanged = {},
            )
        }
    }

    fun onPermissionResult(granted: Boolean) {
        // 权限结果只更新准备状态，不续接已被系统弹窗取消的长按手势。
        permissionGate.onPermissionResult(granted)
        if (!granted) {
            telemetry.recordMedia(
                ClientUiPage.CHAT,
                ClientMediaKind.AUDIO,
                MediaOperation.RECORD,
                ClientActionOutcome.FAILED,
                MediaFailureReason.PERMISSION,
            )
            wiring.queueMediaFeedback(
                UserFeedbackCode.MICROPHONE_PERMISSION_REQUIRED,
                ClientUiAction.START_VOICE_RECORDING,
                FeedbackOrigin.SNACKBAR,
            )
        }
    }

    fun hasVoicePermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    fun prepareVoiceMode(requestPermission: () -> Unit) {
        when (permissionGate.enterVoiceMode(hasVoicePermission())) {
            VoicePermissionDecision.REQUEST_PERMISSION -> requestPermission()
            VoicePermissionDecision.NO_ACTION,
            VoicePermissionDecision.START_RECORDING -> Unit
        }
    }

    fun startVoice(requestPermission: () -> Unit) {
        when (permissionGate.requestStart(hasVoicePermission())) {
            VoicePermissionDecision.START_RECORDING -> startVoiceRecording()
            VoicePermissionDecision.REQUEST_PERMISSION -> requestPermission()
            VoicePermissionDecision.NO_ACTION -> Unit
        }
    }

    private fun startVoiceRecording() {
        val session = mediaSession()
        if (!session.isCurrentOwner()) return
        if (lease.isActive) return
        var partialFile: File? = null
        var recorder: MediaRecorder? = null
        try {
            telemetry.recordMedia(
                ClientUiPage.CHAT,
                ClientMediaKind.AUDIO,
                MediaOperation.RECORD,
                ClientActionOutcome.STARTED,
            )
            val directory = mediaCacheDirectory(
                context.cacheDir,
                session.cacheNamespace,
                "outgoing-voice",
            ).apply { mkdirs() }
            val file = File.createTempFile("voice-", ".aac", directory)
            partialFile = file
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = rec
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setOutputFile(file.absolutePath)
            }
            rec.prepare()
            rec.start()
            check(lease.attach(rec, file, System.currentTimeMillis())) {
                "录音资源已被占用"
            }
        } catch (failure: Throwable) {
            val terminalFailure = cleanupFailedVoiceRecordingStart(
                startFailure = failure,
                stop = {
                    recorder?.stop()
                },
                release = {
                    recorder?.release()
                },
                deletePartial = {
                    deleteVoiceRecordingFile(partialFile)
                },
            )
            if (isFatalAndroidLifecycleFailure(terminalFailure)) throw terminalFailure
            wiring.reportMediaFailure(ClientMediaKind.AUDIO, MediaOperation.RECORD, terminalFailure)
        }
    }

    fun stopVoice() {
        // 用户在系统权限弹窗期间松手时，录音器尚未创建；也必须取消待续接动作，
        // 避免授权结果返回后在手指已经离开时意外启动麦克风。
        permissionGate.clear()
        val finishedAt = System.currentTimeMillis()
        val completed = when (
            val result = lease.finishForSend(
                stop = { recorder -> recorder.stop() },
                release = { recorder -> recorder.release() },
            )
        ) {
            VoiceRecordingFinishResult.Inactive -> return
            is VoiceRecordingFinishResult.Failed -> {
                result.stopFailure?.let { Log.w("Chat", "Voice recorder stop failed", it) }
                result.releaseFailure?.let { Log.w("Chat", "Voice recorder release failed", it) }
                result.deleteFailure?.let {
                    Log.w("Chat", "Voice recording fragment cleanup failed", it)
                }
                val primaryFailure = result.stopFailure ?: result.releaseFailure
                val tooShort = result.isTooShort(finishedAt)
                val reason = when {
                    primaryFailure != null -> classifyAndroidMediaFailure(primaryFailure)
                    tooShort -> MediaFailureReason.SIZE_VALIDATION
                    else -> MediaFailureReason.IO
                }
                telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    ClientMediaKind.AUDIO,
                    MediaOperation.RECORD,
                    ClientActionOutcome.FAILED,
                    reason,
                )
                wiring.queueMediaFeedback(
                    if (tooShort) {
                        UserFeedbackCode.VOICE_TOO_SHORT
                    } else {
                        UserFeedbackCode.VOICE_RECORDING_FAILED
                    },
                    ClientUiAction.SEND_VOICE_RECORDING,
                    FeedbackOrigin.SNACKBAR,
                )
                return
            }
            is VoiceRecordingFinishResult.Ready -> result
        }
        val file = completed.file
        val durationMillis = (finishedAt - completed.startedAt).coerceAtLeast(0L)
        if (durationMillis < MINIMUM_VOICE_RECORDING_DURATION_MILLIS) {
            telemetry.recordMedia(
                ClientUiPage.CHAT,
                ClientMediaKind.AUDIO,
                MediaOperation.RECORD,
                ClientActionOutcome.FAILED,
                MediaFailureReason.SIZE_VALIDATION,
            )
            wiring.queueMediaFeedback(
                UserFeedbackCode.VOICE_TOO_SHORT,
                ClientUiAction.SEND_VOICE_RECORDING,
                FeedbackOrigin.SNACKBAR,
            )
            try {
                deleteVoiceRecordingFile(file)
            } catch (error: Exception) {
                Log.w("Chat", "Voice recording temporary file cleanup failed", error)
            }
            return
        }
        telemetry.recordMedia(
            ClientUiPage.CHAT,
            ClientMediaKind.AUDIO,
            MediaOperation.RECORD,
            ClientActionOutcome.SUCCEEDED,
        )
        val duration = (durationMillis / 1_000L).toInt().coerceAtLeast(1)
        wiring.launchOwnedMediaSource.launch(file) {
            OutgoingMediaSender(telemetry).sendVoice(
                chatId = chatId,
                myUid = myUid,
                viewModel = viewModel,
                durationSeconds = duration,
                onUploadingChanged = { value -> wiring.onUploadingChanged(value) },
                classifyFailure = ::classifyAndroidMediaFailure,
                reportFailure = { _, reason ->
                    wiring.queueMediaFeedback(reason.uploadFeedbackCode, ClientUiAction.UPLOAD_MEDIA, FeedbackOrigin.SNACKBAR)
                },
            ) {
                if (file.length() > MAX_SELECTED_MEDIA_BYTES) throw SelectedMediaTooLargeException(MAX_SELECTED_MEDIA_BYTES)
                MediaHelper.uploadFile(file, file.name, "audio/aac", mediaSession())
            }
        }
    }

    fun cancelVoiceRecording() {
        permissionGate.clear()
        when (val result = lease.discard(
            stop = { recorder -> recorder.stop() },
            release = { recorder -> recorder.release() },
        )) {
            VoiceRecordingDiscardResult.Inactive -> Unit
            VoiceRecordingDiscardResult.Discarded -> telemetry.recordMedia(
                ClientUiPage.CHAT,
                ClientMediaKind.AUDIO,
                MediaOperation.RECORD,
                ClientActionOutcome.CANCELLED,
            )
            is VoiceRecordingDiscardResult.Failed -> {
                if (isFatalAndroidLifecycleFailure(result.failure)) throw result.failure
                Log.w("Chat", "Voice recording cancellation failed", result.failure)
                telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    ClientMediaKind.AUDIO,
                    MediaOperation.RECORD,
                    ClientActionOutcome.FAILED,
                    classifyAndroidMediaFailure(result.failure),
                )
            }
        }
    }
}

package com.virjar.tk.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.MediaOperation
import com.virjar.tk.app.telemetry.MediaOperationAttemptTracker
import com.virjar.tk.app.ui.component.VoicePlaybackController
import java.net.URI

@Composable
internal fun rememberAndroidChatMediaResources(
    context: Context,
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    myUid: String,
    credentialsProvider: () -> SessionHttpCredentials,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    telemetry: ClientUiTelemetrySink,
    onAuthExpired: (rejectedAccessToken: String) -> Unit,
    onTextAttachmentPreview: ((Attachment) -> Unit)?,
    voicePermissionGate: VoiceRecordPermissionGate,
    voiceRecording: VoiceRecordingLease<MediaRecorder>,
): AndroidAuthenticatedMediaResources? {
    val latestTextAttachmentPreview = rememberUpdatedState(onTextAttachmentPreview)
    val fileDownloadUiScope = rememberCoroutineScope()
    val mediaResourcesLease = remember(
        context,
        deploymentIdentity,
        datasetId,
        myUid,
        credentialsProvider,
        resourceOwner,
        fileDownloadUiScope,
        telemetry,
    ) {
        resourceOwner.acquire {
            AndroidAuthenticatedMediaResources.create(
                createMediaSession = {
                    AndroidMediaSession.create(
                        deploymentIdentity = deploymentIdentity,
                        datasetId = datasetId,
                        ownerUid = myUid,
                        credentialsProvider = credentialsProvider,
                        onAuthExpired = onAuthExpired,
                    )
                },
                createFileDownloads = { mediaSession ->
                    AndroidFileDownloadController(
                        context,
                        mediaSession,
                        uiScope = fileDownloadUiScope,
                        onTextAttachmentPreview = { attachment ->
                            latestTextAttachmentPreview.value?.invoke(attachment)
                        },
                        telemetry = telemetry,
                        telemetryPage = ClientUiPage.CHAT,
                    )
                },
                stopVoice = { mediaSession ->
                    closeAndroidChatVoiceResources(
                        permissionGate = voicePermissionGate,
                        recording = voiceRecording,
                        mediaSession = mediaSession,
                    )
                },
            )
        }
    }
    DisposableEffect(mediaResourcesLease, telemetry) {
        val lifecycleFault = AndroidPlatformLifecycleFaultReporter(
            telemetry = telemetry,
            page = ClientUiPage.CHAT,
        )
        onDispose {
            disposeAndroidAuthenticatedResources(
                closeResources = mediaResourcesLease::close,
                recordFailure = { failure ->
                    lifecycleFault.report()
                    Log.e("Chat", "Failed to dispose authenticated media", failure)
                },
            )
        }
    }
    return mediaResourcesLease.resourceOrNull()
}

internal tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/**
 * 把消息中的外链收敛到 Android 可以安全交给外部应用的协议集合。
 *
 * 不接受相对地址、自定义 scheme、无 host 的 http(s) 地址或带账号信息的地址，避免消息内容
 * 触发应用深链/本地资源，也避免把 URL 中的凭据交给外部浏览器。
 */
internal fun safeExternalLinkOrNull(rawUrl: String): String? {
    val candidate = rawUrl.trim()
    if (candidate.isEmpty()) return null

    val uri = try {
        URI(candidate)
    } catch (_: Exception) {
        return null
    }
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> candidate.takeIf {
            uri.host?.isNotBlank() == true && uri.userInfo == null
        }
        "mailto" -> candidate.takeIf {
            uri.rawSchemeSpecificPart?.isNotBlank() == true && !uri.rawSchemeSpecificPart.startsWith("//")
        }
        else -> null
    }
}

internal fun openSafeExternalLink(context: Context, rawUrl: String) {
    val url = safeExternalLinkOrNull(rawUrl) ?: return
    val uri = Uri.parse(url)
    val intent = if (uri.scheme.equals("mailto", ignoreCase = true)) {
        Intent(Intent.ACTION_SENDTO, uri)
    } else {
        Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }
    try {
        context.startActivity(intent)
    } catch (error: Exception) {
        Log.w("Chat", "No application can open external link", error)
    }
}

/**
 * Android 语音应用内播放控制器：包装全局 [VoicePlayer]（MediaPlayer 单例），
 * 轮询其非 Compose 状态转为可订阅状态，驱动气泡波形着色。
 */
@Composable
internal fun rememberAndroidVoicePlayback(
    context: Context,
    mediaSession: AndroidMediaSession,
    telemetry: ClientUiTelemetrySink,
): VoicePlaybackController {
    val urlState = remember { mutableStateOf<String?>(null) }
    val progressState = remember { mutableFloatStateOf(0f) }
    val playbackAttempt = remember(mediaSession, telemetry) {
        MediaOperationAttemptTracker { outcome, reason ->
            telemetry.recordMedia(
                ClientUiPage.CHAT,
                ClientMediaKind.AUDIO,
                MediaOperation.PLAY,
                outcome,
                reason,
            )
        }
    }
    val controller = remember(mediaSession, playbackAttempt) {
        object : VoicePlaybackController {
            override val playingUrl: String? by urlState
            override val progress: Float by progressState
            override fun toggle(attachment: Attachment, durationSec: Int) {
                // Android MediaPlayer 上报真实进度，durationSec hint 不需要
                val establishedSamePlayback =
                    VoicePlayer.playingUrl == attachment.path && !VoicePlayer.isLoading
                if (!establishedSamePlayback) playbackAttempt.start()
                urlState.value = attachment.path
                try {
                    VoicePlayer.play(
                        context = context,
                        attachment = attachment,
                        mediaSession = mediaSession,
                    )
                } catch (failure: Throwable) {
                    playbackAttempt.fail(classifyAndroidMediaFailure(failure))
                    throw failure
                }
            }
        }
    }
    DisposableEffect(controller, mediaSession.cacheNamespace) {
        onDispose {
            playbackAttempt.cancel()
            VoicePlayer.stop(mediaSession)
        }
    }
    LaunchedEffect(controller) {
        while (true) {
            if (playbackAttempt.hasActiveAttempt) {
                when {
                    VoicePlayer.isPlaying -> playbackAttempt.succeed()
                    VoicePlayer.playingUrl == null && VoicePlayer.error != null -> {
                        playbackAttempt.fail(MediaFailureReason.UNKNOWN)
                    }
                    VoicePlayer.playingUrl == null -> playbackAttempt.succeed()
                }
            }
            // VoicePlayer 暂停时保留 playingUrl（气泡维持暂停态），播完/停止时为 null
            if (VoicePlayer.playingUrl == null) urlState.value = null
            progressState.floatValue = VoicePlayer.progress
            kotlinx.coroutines.delay(200)
        }
    }
    return controller
}

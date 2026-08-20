package com.virjar.tk

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.virjar.tk.body.*
import com.virjar.tk.client.SessionHttpCredentials
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Attachment
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.PlatformMediaActions
import com.virjar.tk.ui.component.rememberMediaClickHandler
import com.virjar.tk.ui.screen.ChatPanel
import com.virjar.tk.ui.screen.ChatComposerContextStore
import com.virjar.tk.viewmodel.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidChatScreen(
    chatId: String,
    chatName: String,
    chatType: Int,
    viewModel: ChatViewModel,
    myUid: String,
    credentialsProvider: () -> SessionHttpCredentials,
    draft: String? = null,
    onDraftChange: ((String) -> Unit)? = null,
    composerContextStore: ChatComposerContextStore,
    onForward: (Message) -> Unit,
    onGroupDetail: () -> Unit,
    onBack: () -> Unit,
    serverUrl: String = "",
    resolveSender: ((uid: String) -> User?)? = null,
    mentionCandidates: List<User> = emptyList(),
    onMentionClick: ((uid: String) -> Unit)? = null,
    onTextAttachmentPreview: ((Attachment) -> Unit)? = null,
    scope: kotlinx.coroutines.CoroutineScope = rememberCoroutineScope(),
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val routeLifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val attachmentServerUrl = serverUrl.ifBlank { com.virjar.tk.client.defaultServerConfig().serverUrl }
    val mediaSession = remember(attachmentServerUrl, myUid, credentialsProvider) {
        AndroidMediaSession.create(
            serverUrl = attachmentServerUrl,
            ownerUid = myUid,
            credentialsProvider = credentialsProvider,
        )
    }
    val mediaCacheScope = mediaSession.cacheNamespace
    val fileDownloads = remember(context, mediaSession) {
        AndroidFileDownloadController(
            context,
            mediaSession,
            onTextAttachmentPreview = onTextAttachmentPreview,
        )
    }
    DisposableEffect(fileDownloads) {
        onDispose {
            fileDownloads.close()
            VoicePlayer.stop(mediaCacheScope)
            mediaSession.close()
        }
    }
    var isUploading by remember { mutableStateOf(false) }
    var mediaError by remember { mutableStateOf<String?>(null) }
    val mediaSnackbar = remember { SnackbarHostState() }
    LaunchedEffect(mediaError) {
        val message = mediaError ?: return@LaunchedEffect
        mediaSnackbar.showSnackbar(message)
        mediaError = null
    }

    fun reportMediaFailure(operation: String, error: Throwable) {
        Log.e("Chat", "$operation failed", error)
        mediaError = when (error) {
            is SelectedMediaTooLargeException -> error.message
            else -> error.message
                ?.takeIf { it.startsWith("无法读取") || it.startsWith("待上传文件") }
                ?: "$operation 失败，请稍后重试"
        }
    }

    // 全屏画廊 overlay 状态
    var showGallery by remember { mutableStateOf(false) }
    var galleryItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var galleryIndex by remember { mutableIntStateOf(0) }

    // 录音状态
    val voiceRecording = remember(mediaCacheScope) { VoiceRecordingLease<MediaRecorder>() }
    val voicePermissionGate = remember(mediaCacheScope) { VoiceRecordPermissionGate() }

    val recordAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 权限结果只更新准备状态，不续接已被系统弹窗取消的长按手势。
        voicePermissionGate.onPermissionResult(granted)
        if (!granted) {
            mediaError = "需要麦克风权限才能发送语音"
        }
    }

    // ── 上传通用函数 ──
    fun uploadSelectedAndSend(
        uri: Uri,
        buildMessage: (com.virjar.tk.model.Attachment) -> Message,
    ) {
        scope.launch {
            isUploading = true
            var selected: PreparedMedia? = null
            try {
                selected = MediaHelper.prepareSelectedMedia(
                    context,
                    uri,
                    mediaSession = mediaSession,
                )
                val attachment = MediaHelper.uploadFile(
                    selected.file,
                    selected.fileName,
                    selected.contentType,
                    mediaSession,
                )
                viewModel.sendMessage(buildMessage(attachment))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportMediaFailure("文件发送", error)
            } finally {
                selected?.delete()
                isUploading = false
            }
        }
    }

    // ── 文件选择器 ──
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            uploadSelectedAndSend(uri) { attachment ->
                Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.FILE.code, System.currentTimeMillis(), body = FileBody(attachment))
            }
        }
    }

    // ── 图片选择器 ──（服务端缩略图/宽高：uploadWithMeta，准确度优于本地解码）
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                isUploading = true
                var selected: PreparedMedia? = null
                try {
                    selected = MediaHelper.prepareSelectedMedia(
                        context,
                        uri,
                        mediaSession = mediaSession,
                    )
                    val meta = MediaHelper.uploadWithMeta(
                        selected.file,
                        selected.fileName,
                        selected.contentType,
                        mediaSession,
                    )
                    viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.IMAGE.code, System.currentTimeMillis(),
                        body = ImageBody(meta.file, width = meta.width, height = meta.height, thumbnail = meta.thumbnail)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportMediaFailure("图片发送", error)
                } finally {
                    selected?.delete()
                    isUploading = false
                }
            }
        }
    }

    // ── 视频选择器 ──
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                isUploading = true
                var selected: PreparedMedia? = null
                try {
                    selected = MediaHelper.prepareSelectedMedia(
                        context,
                        uri,
                        mediaSession = mediaSession,
                    )
                    val selectedFile = selected.file
                    // 服务端生成缩略图和元数据；字段缺失时再回退本地 MediaMetadataRetriever。
                    val up = MediaHelper.uploadWithMeta(
                        selectedFile,
                        selected.fileName,
                        selected.contentType,
                        mediaSession,
                    )
                    val attachment = up.file
                    var w = up.width
                    var h = up.height
                    var duration = up.durationSec ?: 0
                    var thumbnail = up.thumbnail
                    if (w == 0 || thumbnail == null) {
                        val local = withContext(Dispatchers.IO) { MediaHelper.getVideoMetadata(context, uri) }
                        duration = local?.first ?: duration
                        w = local?.second ?: w
                        h = local?.third ?: h
                        if (thumbnail == null) {
                            withContext(Dispatchers.IO) {
                                MediaHelper.extractVideoThumbnail(
                                    context,
                                    selectedFile,
                                    mediaSession = mediaSession,
                                )
                            }
                                ?.let { thumbnailFile ->
                                    try {
                                        thumbnail = MediaHelper.uploadFile(
                                            thumbnailFile,
                                            "thumb.jpg",
                                            "image/jpeg",
                                            mediaSession,
                                        )
                                    } finally {
                                        thumbnailFile.delete()
                                    }
                                }
                        }
                    }
                    viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.VIDEO.code, System.currentTimeMillis(), body = VideoBody(attachment, duration, w, h, thumbnail)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportMediaFailure("视频发送", error)
                } finally {
                    selected?.delete()
                    isUploading = false
                }
            }
        }
    }

    fun startVoiceRecording() {
        if (voiceRecording.isActive) return
        val directory = mediaCacheDirectory(
            context.cacheDir,
            mediaCacheScope,
            "outgoing-voice",
        ).apply { mkdirs() }
        val file = File.createTempFile("voice-", ".aac", directory)
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000); setAudioEncodingBitRate(32000)
            setOutputFile(file.absolutePath)
        }
        try {
            rec.prepare()
            rec.start()
            check(voiceRecording.attach(rec, file, System.currentTimeMillis())) {
                "录音资源已被占用"
            }
        } catch (error: Exception) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
            file.delete()
            reportMediaFailure("录音启动", error)
        }
    }

    fun hasVoicePermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    fun prepareVoiceMode() {
        when (voicePermissionGate.enterVoiceMode(hasVoicePermission())) {
            VoicePermissionDecision.REQUEST_PERMISSION -> {
                recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            VoicePermissionDecision.NO_ACTION,
            VoicePermissionDecision.START_RECORDING -> Unit
        }
    }

    fun startVoice() {
        when (voicePermissionGate.requestStart(hasVoicePermission())) {
            VoicePermissionDecision.START_RECORDING -> startVoiceRecording()
            VoicePermissionDecision.REQUEST_PERMISSION -> {
                recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            VoicePermissionDecision.NO_ACTION -> Unit
        }
    }

    fun stopVoice() {
        // 用户在系统权限弹窗期间松手时，录音器尚未创建；也必须取消待续接动作，
        // 避免授权结果返回后在手指已经离开时意外启动麦克风。
        voicePermissionGate.clear()
        val completed = when (
            val result = voiceRecording.finishForSend(
                stop = { recorder -> recorder.stop() },
                release = { recorder -> recorder.release() },
            )
        ) {
            VoiceRecordingFinishResult.Inactive -> return
            is VoiceRecordingFinishResult.Failed -> {
                result.stopFailure?.let { Log.w("Chat", "Voice recorder stop failed", it) }
                result.releaseFailure?.let { Log.w("Chat", "Voice recorder release failed", it) }
                mediaError = "录音时间太短，请重试"
                return
            }
            is VoiceRecordingFinishResult.Ready -> result
        }
        val file = completed.file
        val duration = ((System.currentTimeMillis() - completed.startedAt) / 1000)
            .toInt()
            .coerceAtLeast(1)
        scope.launch {
            isUploading = true
            try {
                if (file.length() > MAX_SELECTED_MEDIA_BYTES) throw SelectedMediaTooLargeException(MAX_SELECTED_MEDIA_BYTES)
                val attachment = MediaHelper.uploadFile(
                    file,
                    file.name,
                    "audio/aac",
                    mediaSession,
                )
                viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.VOICE.code, System.currentTimeMillis(), body = VoiceBody(attachment, duration)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportMediaFailure("语音发送", error)
            } finally {
                file.delete()
                isUploading = false
            }
        }
    }

    fun cancelVoiceRecording() {
        voicePermissionGate.clear()
        voiceRecording.discard(
            stop = { recorder -> recorder.stop() },
            release = { recorder -> recorder.release() },
        )
    }

    // 离开聊天页和应用退到后台都必须释放麦克风；后台录音不自动发送残片。
    var chatRouteResumed by remember(routeLifecycleOwner) {
        mutableStateOf(routeLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(routeLifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            chatRouteResumed = routeLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        routeLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { routeLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val latestCancelVoice by rememberUpdatedState(newValue = { cancelVoiceRecording() })
    DisposableEffect(activity, voiceRecording) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                latestCancelVoice()
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            latestCancelVoice()
        }
    }

    // 画廊可见时拦截返回手势，关闭画廊而非退出聊天页
    androidx.activity.compose.BackHandler(enabled = showGallery) {
        showGallery = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding(),
            snackbarHost = { SnackbarHost(mediaSnackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(chatName.ifEmpty { chatId.take(16) },
                            modifier = if (ChatType.fromCode(chatType) == ChatType.GROUP) Modifier.clickable(onClick = onGroupDetail) else Modifier)
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                )
                if (isUploading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            },
        ) { padding ->
            ChatPanel(
                chatId = chatId, chatName = chatName, viewModel = viewModel, myUid = myUid,
                chatType = chatType, resolveSender = resolveSender,
                onForward = onForward, initialDraft = draft, onDraftChange = onDraftChange,
                composerContextStore = composerContextStore,
                voicePlayback = rememberAndroidVoicePlayback(
                    context = context,
                    serverUrl = attachmentServerUrl,
                    mediaSession = mediaSession,
                ),
                mentionCandidates = mentionCandidates,
                readReceiptsEnabled = chatRouteResumed,
                media = com.virjar.tk.ui.bridge.ChatMediaConfig(
                    fileDownloads = fileDownloads,
                    onPickImage = { imagePicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onPickVideo = { videoPicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly).build()) },
                    onVoiceModeEntered = { prepareVoiceMode() },
                    onVoiceRecord = { if (it) startVoice() else stopVoice() },
                    onVoiceRecordCancel = { cancelVoiceRecording() },
                    onMentionClick = onMentionClick,
                    onUrlClick = { url -> openSafeExternalLink(context, url) },
                    imageContent = { url, mod ->
                        rememberAsyncThumb(
                            url = com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, url),
                            mediaSession = mediaSession,
                            modifier = mod,
                            placeholderColor = android.graphics.Color.LTGRAY,
                        )
                    },
                    videoContent = { url, mod ->
                        rememberAsyncThumb(
                            url = com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, url),
                            mediaSession = mediaSession,
                            modifier = mod,
                            placeholderColor = android.graphics.Color.DKGRAY,
                        )
                    },
                    onMediaClick = rememberMediaClickHandler(
                        messages = viewModel.messages.collectAsState(),
                        actions = object : PlatformMediaActions {
                            override fun playVoice(attachment: com.virjar.tk.model.Attachment) = VoicePlayer.play(
                                context,
                                com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, attachment),
                                mediaSession,
                            )
                            override fun openFile(attachment: com.virjar.tk.model.Attachment) {
                                fileDownloads.openOrDownload(attachment)
                            }
                            override fun showGallery(items: List<GalleryItem>, index: Int) {
                                openAndroidMediaGallery(
                                    items = items,
                                    requestedIndex = index,
                                    hideIme = {
                                        focusManager.clearFocus(force = true)
                                        softwareKeyboardController?.hide()
                                        activity?.window?.let { window ->
                                            WindowCompat.getInsetsController(window, window.decorView)
                                                .hide(WindowInsetsCompat.Type.ime())
                                        }
                                    },
                                    present = { media, safeIndex ->
                                        galleryIndex = safeIndex
                                        galleryItems = media
                                        showGallery = true
                                    },
                                )
                            }
                        },
                    ),
                ),
                modifier = Modifier.padding(padding),
            )
        }

        // 独立 Dialog 窗口不继承聊天页 IME padding，并位于 NavHost 转场与原生视频 surface 之上。
        AndroidMediaGalleryDialog(
            visible = showGallery,
            items = galleryItems,
            initialIndex = galleryIndex,
            onDismiss = { showGallery = false },
            imageRenderer = { url, mod ->
                rememberAsyncThumb(
                    url = com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, url),
                    mediaSession = mediaSession,
                    modifier = mod,
                    placeholderColor = android.graphics.Color.BLACK,
                )
            },
            videoRenderer = { url, isCurrentPage, mod ->
                rememberVideoPlayer(
                    url = com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, url),
                    mediaSession = mediaSession,
                    isCurrentPage = isCurrentPage,
                    modifier = mod,
                )
            },
        )
    }

}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

internal sealed interface VoiceRecordingFinishResult {
    data object Inactive : VoiceRecordingFinishResult

    data class Ready(
        val file: File,
        val startedAt: Long,
    ) : VoiceRecordingFinishResult

    data class Failed(
        val stopFailure: Throwable?,
        val releaseFailure: Throwable?,
    ) : VoiceRecordingFinishResult
}

/**
 * 单一所有者录音资源租约。正常抬手会先 stop/release 再交出文件；手势取消、离页与后台事件
 * 统一走 [discard]，即使 stop 抛错也会 finally release 并删除残片。
 */
internal class VoiceRecordingLease<R> {
    private data class Active<R>(
        val recorder: R,
        val file: File,
        val startedAt: Long,
    )

    private var active: Active<R>? = null

    val isActive: Boolean get() = active != null

    fun attach(recorder: R, file: File, startedAt: Long): Boolean {
        if (active != null) return false
        active = Active(recorder, file, startedAt)
        return true
    }

    fun finishForSend(
        stop: (R) -> Unit,
        release: (R) -> Unit,
    ): VoiceRecordingFinishResult {
        val recording = detach() ?: return VoiceRecordingFinishResult.Inactive
        var stopFailure: Throwable? = null
        var releaseFailure: Throwable? = null
        try {
            stop(recording.recorder)
        } catch (error: Throwable) {
            stopFailure = error
        } finally {
            try {
                release(recording.recorder)
            } catch (error: Throwable) {
                releaseFailure = error
            }
        }

        if (
            stopFailure != null ||
            releaseFailure != null ||
            !recording.file.isFile ||
            recording.file.length() <= 0L
        ) {
            recording.file.delete()
            return VoiceRecordingFinishResult.Failed(stopFailure, releaseFailure)
        }
        return VoiceRecordingFinishResult.Ready(recording.file, recording.startedAt)
    }

    fun discard(
        stop: (R) -> Unit,
        release: (R) -> Unit,
    ) {
        val recording = detach() ?: return
        try {
            runCatching { stop(recording.recorder) }
        } finally {
            try {
                runCatching { release(recording.recorder) }
            } finally {
                recording.file.delete()
            }
        }
    }

    private fun detach(): Active<R>? = active.also { active = null }
}

internal enum class VoicePermissionDecision { NO_ACTION, REQUEST_PERMISSION, START_RECORDING }

/**
 * 麦克风权限门禁。进入语音模式时预先申请，权限结果永远不直接启动录音；
 * 只有一次新的长按在已授权时才能获得 [VoicePermissionDecision.START_RECORDING]。
 */
internal class VoiceRecordPermissionGate {
    private var permissionRequestInFlight = false

    fun enterVoiceMode(permissionGranted: Boolean): VoicePermissionDecision =
        requestPermissionIfNeeded(permissionGranted)

    fun requestStart(permissionGranted: Boolean): VoicePermissionDecision {
        return if (permissionGranted) {
            permissionRequestInFlight = false
            VoicePermissionDecision.START_RECORDING
        } else {
            requestPermissionIfNeeded(permissionGranted = false)
        }
    }

    fun onPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean): VoicePermissionDecision {
        permissionRequestInFlight = false
        return VoicePermissionDecision.NO_ACTION
    }

    fun clear() {
        permissionRequestInFlight = false
    }

    private fun requestPermissionIfNeeded(permissionGranted: Boolean): VoicePermissionDecision {
        if (permissionGranted) {
            permissionRequestInFlight = false
            return VoicePermissionDecision.NO_ACTION
        }
        if (permissionRequestInFlight) return VoicePermissionDecision.NO_ACTION
        permissionRequestInFlight = true
        return VoicePermissionDecision.REQUEST_PERMISSION
    }
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

    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
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

internal fun openSafeExternalLink(context: android.content.Context, rawUrl: String) {
    val url = safeExternalLinkOrNull(rawUrl) ?: return
    val uri = Uri.parse(url)
    val intent = if (uri.scheme.equals("mailto", ignoreCase = true)) {
        Intent(Intent.ACTION_SENDTO, uri)
    } else {
        Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }
    runCatching { context.startActivity(intent) }
        .onFailure { error -> Log.w("Chat", "No application can open external link", error) }
}

/**
 * Android 语音应用内播放控制器：包装全局 [VoicePlayer]（MediaPlayer 单例），
 * 轮询其非 Compose 状态转为可订阅状态，驱动气泡波形着色。
 */
@Composable
private fun rememberAndroidVoicePlayback(
    context: android.content.Context,
    serverUrl: String,
    mediaSession: AndroidMediaSession,
): com.virjar.tk.ui.component.VoicePlaybackController {
    val urlState = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val progressState = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val controller = remember(serverUrl, mediaSession) {
        object : com.virjar.tk.ui.component.VoicePlaybackController {
            override val playingUrl: String? by urlState
            override val progress: Float by progressState
            override fun toggle(url: String, durationSec: Int) {
                // Android MediaPlayer 上报真实进度，durationSec hint 不需要
                urlState.value = url
                VoicePlayer.play(
                    context = context,
                    url = com.virjar.tk.repository.FileOps.resolveUrl(serverUrl, url),
                    mediaSession = mediaSession,
                )
            }
        }
    }
    DisposableEffect(controller, mediaSession.cacheNamespace) {
        onDispose { VoicePlayer.stop(mediaSession.cacheNamespace) }
    }
    LaunchedEffect(controller) {
        while (true) {
            // VoicePlayer 暂停时保留 playingUrl（气泡维持暂停态），播完/停止时为 null
            if (VoicePlayer.playingUrl == null) urlState.value = null
            progressState.floatValue = VoicePlayer.progress
            kotlinx.coroutines.delay(200)
        }
    }
    return controller
}

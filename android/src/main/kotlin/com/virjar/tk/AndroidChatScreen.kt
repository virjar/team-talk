package com.virjar.tk

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.virjar.tk.body.*
import com.virjar.tk.model.ChatType
import com.virjar.tk.model.Message
import com.virjar.tk.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.ui.component.GalleryItem
import com.virjar.tk.ui.component.MediaGallery
import com.virjar.tk.ui.component.PlatformMediaActions
import com.virjar.tk.ui.component.rememberMediaClickHandler
import com.virjar.tk.ui.screen.ChatPanel
import com.virjar.tk.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidChatScreen(
    chatId: String,
    chatName: String,
    chatType: Int,
    viewModel: ChatViewModel,
    myUid: String,
    draft: String? = null,
    onDraftChange: ((String) -> Unit)? = null,
    onForward: (Message) -> Unit,
    onGroupDetail: () -> Unit,
    onBack: () -> Unit,
    serverUrl: String = "",
    accessToken: String? = null,
    resolveSender: ((uid: String) -> User?)? = null,
    mentionCandidates: List<User> = emptyList(),
    scope: kotlinx.coroutines.CoroutineScope = rememberCoroutineScope(),
) {
    val context = LocalContext.current
    val attachmentServerUrl = serverUrl.ifBlank { com.virjar.tk.client.defaultServerConfig().serverUrl }
    val fileDownloads = remember(context, attachmentServerUrl, accessToken) {
        AndroidFileDownloadController(context, attachmentServerUrl, accessToken)
    }
    DisposableEffect(fileDownloads) {
        onDispose { fileDownloads.close() }
    }
    var isUploading by remember { mutableStateOf(false) }

    // 全屏画廊 overlay 状态
    var showGallery by remember { mutableStateOf(false) }
    var galleryItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var galleryIndex by remember { mutableIntStateOf(0) }

    // 录音状态
    var voiceRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceRecordStartTime by remember { mutableLongStateOf(0L) }
    var voiceOutputFile by remember { mutableStateOf<File?>(null) }

    val recordAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    // ── 上传通用函数 ──
    fun uploadAndSend(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        buildMessage: (com.virjar.tk.model.Attachment) -> Message,
    ) {
        scope.launch {
            isUploading = true
            try {
                val attachment = MediaHelper.uploadFile(bytes, fileName, mimeType, attachmentServerUrl)
                viewModel.sendMessage(buildMessage(attachment))
            } catch (e: Exception) { Log.e("Chat", "Operation failed", e) }
            isUploading = false
        }
    }

    // ── 文件选择器 ──
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            uploadAndSend(
                MediaHelper.readBytes(context, uri),
                MediaHelper.getFileName(context, uri),
                MediaHelper.getMimeType(context, uri),
            ) { attachment ->
                Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.FILE.code, System.currentTimeMillis(), body = FileBody(attachment))
            }
        }
    }

    // ── 图片选择器 ──（服务端缩略图/宽高：uploadWithMeta，准确度优于本地解码）
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                isUploading = true
                try {
                    val bytes = MediaHelper.readBytes(context, uri)
                    val meta = MediaHelper.uploadWithMeta(bytes, MediaHelper.getFileName(context, uri), MediaHelper.getMimeType(context, uri), attachmentServerUrl)
                    viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.IMAGE.code, System.currentTimeMillis(),
                        body = ImageBody(meta.file, width = meta.width, height = meta.height, thumbnail = meta.thumbnail)))
                } catch (e: Exception) { Log.e("Chat", "Operation failed", e) }
                isUploading = false
            }
        }
    }

    // ── 视频选择器 ──
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                isUploading = true
                try {
                    val bytes = MediaHelper.readBytes(context, uri)
                    val fileName = MediaHelper.getFileName(context, uri)
                    val mimeType = MediaHelper.getMimeType(context, uri)
                    // 服务端 javacv 生成缩略图+元数据（准确）；失败回退本地 MediaMetadataRetriever 抽帧
                    val up = kotlin.runCatching {
                        MediaHelper.uploadWithMeta(bytes, fileName, mimeType, attachmentServerUrl)
                    }.getOrNull()
                    val attachment = up?.file ?: run {
                        MediaHelper.uploadFile(bytes, fileName, mimeType, attachmentServerUrl)
                    }
                    var w = up?.width ?: 0
                    var h = up?.height ?: 0
                    var duration = up?.durationSec ?: 0
                    var thumbnail = up?.thumbnail
                    if (w == 0 || thumbnail == null) {
                        val local = MediaHelper.getVideoMetadata(context, uri)
                        w = local?.first ?: w; h = local?.second ?: h; duration = local?.third ?: duration
                        if (thumbnail == null) {
                            kotlin.runCatching { MediaHelper.extractVideoThumbnail(context, uri) }
                                .getOrNull()?.let { tf ->
                                    kotlin.runCatching {
                                        thumbnail = MediaHelper.uploadFile(tf.readBytes(), "thumb.jpg", "image/jpeg", attachmentServerUrl)
                                        tf.delete()
                                    }
                                }
                        }
                    }
                    viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.VIDEO.code, System.currentTimeMillis(), body = VideoBody(attachment, duration, w, h, thumbnail)))
                } catch (e: Exception) { Log.e("Chat", "Operation failed", e) }
                isUploading = false
            }
        }
    }

    fun startVoice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.aac")
        voiceOutputFile = file
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000); setAudioEncodingBitRate(32000)
            setOutputFile(file.absolutePath)
        }
        try { rec.prepare(); rec.start(); voiceRecorder = rec; voiceRecordStartTime = System.currentTimeMillis() }
        catch (e: Exception) { Log.e("Chat", "Voice recorder prepare failed", e); rec.release() }
    }

    fun stopVoice() {
        voiceRecorder?.apply { try { stop() } catch (e: Exception) { Log.w("Chat", "Voice recorder stop failed", e) }; try { release() } catch (e: Exception) { Log.w("Chat", "Voice recorder release failed", e) } }
        voiceRecorder = null
        val file = voiceOutputFile; voiceOutputFile = null
        if (file == null || file.length() == 0L) return
        scope.launch {
            isUploading = true
            try {
                val attachment = MediaHelper.uploadFile(file.readBytes(), file.name, "audio/aac", attachmentServerUrl)
                val dur = ((System.currentTimeMillis() - voiceRecordStartTime) / 1000).toInt()
                viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.VOICE.code, System.currentTimeMillis(), body = VoiceBody(attachment, dur)))
                file.delete()
            } catch (e: Exception) { Log.e("Chat", "Operation failed", e) }
            isUploading = false
        }
    }

    // 画廊可见时拦截返回手势，关闭画廊而非退出聊天页
    androidx.activity.compose.BackHandler(enabled = showGallery) {
        showGallery = false
    }

    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        Scaffold(
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
                voicePlayback = rememberAndroidVoicePlayback(context, attachmentServerUrl),
                mentionCandidates = mentionCandidates,
                media = com.virjar.tk.ui.bridge.ChatMediaConfig(
                    fileDownloads = fileDownloads,
                    onPickImage = { imagePicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onPickVideo = { videoPicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly).build()) },
                    onVoiceRecord = { if (it) startVoice() else stopVoice() },
                    imageContent = { url, mod ->
                        rememberAsyncThumb(com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, url), mod, android.graphics.Color.LTGRAY)
                    },
                    videoContent = { url, mod ->
                        rememberAsyncThumb(com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, url), mod, android.graphics.Color.DKGRAY)
                    },
                    onMediaClick = rememberMediaClickHandler(
                        messages = viewModel.messages.collectAsState(),
                        actions = object : PlatformMediaActions {
                            override fun playVoice(attachment: com.virjar.tk.model.Attachment) = VoicePlayer.play(
                                context,
                                com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, attachment),
                            )
                            override fun openFile(attachment: com.virjar.tk.model.Attachment) {
                                scope.launch {
                                    try {
                                        val full = com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, attachment)
                                        val f = File(context.cacheDir, "downloads/${attachment.name}")
                                        f.parentFile?.mkdirs()
                                        val connection = java.net.URL(full).openConnection() as java.net.HttpURLConnection
                                        accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                                        try {
                                            if (connection.responseCode !in 200..299) {
                                                error("下载失败 HTTP ${connection.responseCode}")
                                            }
                                            f.writeBytes(connection.inputStream.readBytes())
                                        } finally {
                                            connection.disconnect()
                                        }
                                        MediaHelper.openFile(context, f, attachment.contentType)
                                    } catch (e: Exception) { Log.e("Chat", "openFile failed path=${attachment.path}", e) }
                                }
                            }
                            override fun showGallery(items: List<GalleryItem>, index: Int) {
                                galleryIndex = index; galleryItems = items; showGallery = true
                            }
                        },
                    ),
                ),
                modifier = Modifier.padding(padding),
            )
        }

        // ── 全屏媒体画廊 overlay ──
        MediaGallery(
            visible = showGallery,
            items = galleryItems,
            initialIndex = galleryIndex,
            onDismiss = { showGallery = false },
            imageRenderer = { url, mod ->
                rememberAsyncThumb(com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, url), mod, android.graphics.Color.BLACK)
            },
            videoRenderer = { url, mod ->
                rememberVideoPlayer(com.virjar.tk.repository.FileOps.resolveUrl(attachmentServerUrl, url), mod)
            },
        )
    }

}

/**
 * Android 语音应用内播放控制器：包装全局 [VoicePlayer]（MediaPlayer 单例），
 * 轮询其非 Compose 状态转为可订阅状态，驱动气泡波形着色。
 */
@Composable
private fun rememberAndroidVoicePlayback(
    context: android.content.Context,
    serverUrl: String,
): com.virjar.tk.ui.component.VoicePlaybackController {
    val urlState = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val progressState = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val controller = remember(serverUrl) {
        object : com.virjar.tk.ui.component.VoicePlaybackController {
            override val playingUrl: String? by urlState
            override val progress: Float by progressState
            override fun toggle(url: String, durationSec: Int) {
                // Android MediaPlayer 上报真实进度，durationSec hint 不需要
                urlState.value = url
                VoicePlayer.play(context, com.virjar.tk.repository.FileOps.resolveUrl(serverUrl, url))
            }
        }
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

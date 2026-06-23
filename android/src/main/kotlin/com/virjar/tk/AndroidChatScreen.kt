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
    resolveSender: ((uid: String) -> User?)? = null,
    scope: kotlinx.coroutines.CoroutineScope = rememberCoroutineScope(),
) {
    val context = LocalContext.current
    var showAttachSheet by remember { mutableStateOf(false) }
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
    fun uploadAndSend(bytes: ByteArray, fileName: String, mimeType: String, buildBody: (String, Long) -> Message) {
        scope.launch {
            isUploading = true
            try {
                val path = MediaHelper.uploadFile(bytes, fileName, mimeType, serverUrl)
                val fileUrl = "$serverUrl/api/v1/files/$path"
                viewModel.sendMessage(buildBody(fileUrl, bytes.size.toLong()))
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
            ) { url, size ->
                Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.FILE.code, System.currentTimeMillis(), body = FileBody(url, MediaHelper.getFileName(context, uri), size))
            }
        }
    }

    // ── 图片选择器 ──
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            uploadAndSend(
                MediaHelper.readBytes(context, uri),
                MediaHelper.getFileName(context, uri),
                MediaHelper.getMimeType(context, uri),
            ) { url, size -> Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.IMAGE.code, System.currentTimeMillis(), body = ImageBody(url, size = size)) }
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
                    val meta = MediaHelper.getVideoMetadata(context, uri)
                    val path = MediaHelper.uploadFile(bytes, fileName, mimeType, serverUrl)
                    val fileUrl = "$serverUrl/api/v1/files/$path"
                    var thumbUrl: String? = null
                    kotlin.runCatching { MediaHelper.extractVideoThumbnail(context, uri) }
                        .getOrNull()?.let { tf ->
                            kotlin.runCatching {
                                val tp = MediaHelper.uploadFile(tf.readBytes(), "thumb.jpg", "image/jpeg", serverUrl)
                                thumbUrl = "$serverUrl/api/v1/files/$tp"; tf.delete()
                            }
                        }
                    viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.VIDEO.code, System.currentTimeMillis(), body = VideoBody(fileUrl, meta?.first ?: 0, meta?.second ?: 0, meta?.third ?: 0, bytes.size.toLong(), thumbUrl)))
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
                val path = MediaHelper.uploadFile(file.readBytes(), file.name, "audio/aac", serverUrl)
                val dur = ((System.currentTimeMillis() - voiceRecordStartTime) / 1000).toInt()
                viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.VOICE.code, System.currentTimeMillis(), body = VoiceBody("$serverUrl/api/v1/files/$path", dur, size = file.length())))
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
                media = com.virjar.tk.ui.bridge.ChatMediaConfig(
                    onAttachClick = { showAttachSheet = true },
                    onPickImage = { imagePicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onVoiceRecord = { if (it) startVoice() else stopVoice() },
                    imageContent = { url, mod -> rememberAsyncThumb(url, mod, android.graphics.Color.LTGRAY) },
                    videoContent = { url, mod -> rememberAsyncThumb(url, mod, android.graphics.Color.DKGRAY) },
                    onMediaClick = rememberMediaClickHandler(
                        messages = viewModel.messages.collectAsState(),
                        actions = object : PlatformMediaActions {
                            override fun playVoice(url: String) = VoicePlayer.play(context, url)
                            override fun openFile(url: String) {
                                scope.launch {
                                    try {
                                        val fileName = url.substringAfterLast("/")
                                        val f = File(context.cacheDir, "downloads/$fileName")
                                        f.parentFile?.mkdirs()
                                        f.writeBytes(java.net.URL(url).readBytes())
                                        MediaHelper.openFile(context, f, "application/octet-stream")
                                    } catch (e: Exception) { Log.e("Chat", "Operation failed", e) }
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
                rememberAsyncThumb(url, mod, android.graphics.Color.BLACK)
            },
            videoRenderer = { url, mod ->
                rememberVideoPlayer(url, mod)
            },
        )
    }

    // ── 附件菜单 ──
    if (showAttachSheet) {
        AlertDialog(
            onDismissRequest = { showAttachSheet = false },
            title = { Text("发送") },
            text = {
                Column {
                    TextButton(onClick = { showAttachSheet = false; filePicker.launch(arrayOf("*/*")) }) { Text("📄 文件") }
                    TextButton(onClick = { showAttachSheet = false; imagePicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) }) { Text("🖼 图片") }
                    TextButton(onClick = { showAttachSheet = false; videoPicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly).build()) }) { Text("🎬 视频") }
                }
            },
            confirmButton = { TextButton(onClick = { showAttachSheet = false }) { Text("取消") } },
        )
    }
}

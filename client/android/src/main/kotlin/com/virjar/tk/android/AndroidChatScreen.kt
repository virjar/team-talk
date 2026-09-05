package com.virjar.tk.android

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.virjar.tk.protocol.body.*
import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.SessionHttpCredentials
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.navigation.feature.OfficeReferenceKind
import com.virjar.tk.app.ui.component.GalleryItem
import com.virjar.tk.app.ui.component.GalleryMediaType
import com.virjar.tk.app.ui.component.OfficeRefPickerDialog
import com.virjar.tk.app.ui.component.PlatformMediaActions
import com.virjar.tk.app.ui.component.rememberEmbeddedMediaClickHandler
import com.virjar.tk.app.ui.component.rememberMediaClickHandler
import com.virjar.tk.app.ui.screen.ChatPanel
import com.virjar.tk.app.ui.screen.ChatComposerContextStore
import com.virjar.tk.app.ui.screen.ChatDraftLifecycleBridge
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.MediaFailureReason
import com.virjar.tk.app.telemetry.MediaOperation
import com.virjar.tk.app.telemetry.UserFeedbackCode
import com.virjar.tk.app.telemetry.UserFeedbackNotice
import com.virjar.tk.app.telemetry.UserFeedbackReporter
import com.virjar.tk.app.telemetry.recordingFeedbackCode
import com.virjar.tk.app.telemetry.uploadFeedbackCode
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.app.viewmodel.MessageFocusTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidChatScreen(
    chatId: String,
    chatName: String,
    chatType: Int,
    viewModel: ChatViewModel,
    myUid: String,
    credentialsProvider: () -> SessionHttpCredentials,
    cachedDraft: String? = null,
    onDraftChange: ((String) -> Unit)? = null,
    composerContextStore: ChatComposerContextStore,
    draftLifecycleBridge: ChatDraftLifecycleBridge,
    actionAdmission: UiActionAdmission,
    launchAdmittedAction: (suspend () -> Unit) -> Boolean,
    onForward: (Message) -> Unit,
    onSaveMessage: ((Message) -> Unit)? = null,
    /** 类型化引用打开：读取由 MessageActionsFeature 完成，平台只做导航与降级提示。 */
    onOpenOfficeRef: ((com.virjar.tk.protocol.body.OfficeRefBody, onDenied: (String) -> Unit) -> Unit)? = null,
    /** 提供引用候选；null 时附件面板不显示文档/群文件入口。 */
    officeRefHost: com.virjar.tk.app.navigation.AppDataState? = null,
    onGroupDetail: () -> Unit,
    onBack: () -> Unit,
    deploymentIdentity: DeploymentIdentity,
    datasetId: String,
    resourceOwner: AndroidAuthenticatedResourceOwner,
    embeddedAssetImports: AndroidEmbeddedAssetImportGateway,
    embeddedAssetSelector: AndroidEmbeddedAssetSelector,
    telemetry: ClientUiTelemetrySink,
    onAuthExpired: (rejectedAccessToken: String) -> Unit,
    resolveSender: ((uid: String) -> User?)? = null,
    mentionCandidates: List<User> = emptyList(),
    onMentionClick: ((uid: String) -> Unit)? = null,
    onTextAttachmentPreview: ((Attachment) -> Unit)? = null,
    messageFocusTarget: MessageFocusTarget? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val routeLifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val voiceRecording = remember(resourceOwner) { VoiceRecordingLease<MediaRecorder>() }
    val voicePermissionGate = remember(resourceOwner) { VoiceRecordPermissionGate() }
    val mediaResources = rememberAndroidChatMediaResources(
        context = context,
        deploymentIdentity = deploymentIdentity,
        datasetId = datasetId,
        myUid = myUid,
        credentialsProvider = credentialsProvider,
        resourceOwner = resourceOwner,
        telemetry = telemetry,
        onAuthExpired = onAuthExpired,
        onTextAttachmentPreview = onTextAttachmentPreview,
        voicePermissionGate = voicePermissionGate,
        voiceRecording = voiceRecording,
    ) ?: return
    val mediaSession = mediaResources.mediaSession
    val mediaCacheScope = mediaSession.cacheNamespace
    val fileDownloads = requireNotNull(mediaResources.fileDownloads)
    var isUploading by remember { mutableStateOf(false) }
    var mediaError by remember { mutableStateOf<UserFeedbackNotice?>(null) }
    val mediaSnackbar = remember { SnackbarHostState() }
    val feedbackReporter = remember(telemetry) { UserFeedbackReporter(telemetry) }
    LaunchedEffect(mediaError) {
        val notice = mediaError ?: return@LaunchedEffect
        mediaSnackbar.showSnackbar(
            feedbackReporter.displayed(
                feedbackCode = notice.feedbackCode,
                page = notice.page,
                action = notice.action,
                origin = notice.origin,
            ),
        )
        mediaError = null
    }

    fun queueMediaFeedback(
        code: UserFeedbackCode,
        action: ClientUiAction,
        origin: FeedbackOrigin = FeedbackOrigin.SNACKBAR,
    ) {
        mediaError = UserFeedbackNotice(
            feedbackCode = code,
            page = ClientUiPage.CHAT,
            action = action,
            origin = origin,
        )
    }

    fun reportMediaFailure(
        mediaKind: ClientMediaKind,
        operation: MediaOperation,
        error: Throwable,
    ) {
        val reason = classifyAndroidMediaFailure(error)
        Log.w("Chat", "媒体操作失败: ${operation.code}/${reason.code}")
        telemetry.recordMedia(
            ClientUiPage.CHAT,
            mediaKind,
            operation,
            ClientActionOutcome.FAILED,
            reason,
        )
        queueMediaFeedback(
            code = when (operation) {
                MediaOperation.RECORD -> reason.recordingFeedbackCode
                else -> reason.uploadFeedbackCode
            },
            action = when (operation) {
                MediaOperation.RECORD -> ClientUiAction.START_VOICE_RECORDING
                else -> ClientUiAction.UPLOAD_MEDIA
            },
        )
    }

    var officePickerKind by remember(chatId) { mutableStateOf<OfficeReferenceKind?>(null) }
    officeRefHost?.let { host ->
        officePickerKind?.let { kind ->
            OfficeRefPickerDialog(
                kind = kind,
                chatId = chatId,
                myUid = myUid,
                actions = host.messageActions,
                onSend = viewModel::sendMessage,
                onDismiss = { officePickerKind = null },
                modifier = Modifier.semantics { testTagsAsResourceId = true },
            )
        }
    }
    // 全屏画廊 overlay 状态
    var showGallery by remember(chatId) { mutableStateOf(false) }
    var galleryItems by remember(chatId) { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var galleryIndex by remember(chatId) { mutableIntStateOf(0) }
    val galleryMessages = viewModel.messages.collectAsState()
    val mediaActions = remember(
        chatId,
        context,
        mediaSession,
        fileDownloads,
        telemetry,
        focusManager,
        softwareKeyboardController,
        activity,
        onOpenOfficeRef,
    ) {
        object : PlatformMediaActions {
            override fun openOfficeRef(message: Message, body: com.virjar.tk.protocol.body.OfficeRefBody) {
                onOpenOfficeRef?.invoke(body, viewModel::onError)
            }

            override fun playVoice(attachment: Attachment) = VoicePlayer.play(
                context,
                attachment,
                mediaSession,
            )

            override fun openFile(attachment: Attachment) {
                fileDownloads.openOrDownload(attachment)
            }

            override fun showGallery(items: List<GalleryItem>, index: Int) {
                if (items.isEmpty()) return
                val selected = items[index.coerceIn(items.indices)]
                val mediaKind = when (selected.type) {
                    GalleryMediaType.IMAGE -> ClientMediaKind.IMAGE
                    GalleryMediaType.VIDEO -> ClientMediaKind.VIDEO
                }
                telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    mediaKind,
                    MediaOperation.OPEN,
                    ClientActionOutcome.STARTED,
                )
                try {
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
                    telemetry.recordMedia(
                        ClientUiPage.CHAT,
                        mediaKind,
                        MediaOperation.OPEN,
                        ClientActionOutcome.SUCCEEDED,
                    )
                } catch (failure: Throwable) {
                    telemetry.recordMedia(
                        ClientUiPage.CHAT,
                        mediaKind,
                        MediaOperation.OPEN,
                        ClientActionOutcome.FAILED,
                        classifyAndroidMediaFailure(failure),
                    )
                    throw failure
                }
            }
        }
    }
    val onMediaClick = rememberMediaClickHandler(galleryMessages, mediaActions)
    val onEmbeddedMediaClick = rememberEmbeddedMediaClickHandler(galleryMessages, mediaActions)

    val recordAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        actionAdmission.runIfOpen {
            // 权限结果只更新准备状态，不续接已被系统弹窗取消的长按手势。
            voicePermissionGate.onPermissionResult(granted)
            if (!granted) {
                telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    ClientMediaKind.AUDIO,
                    MediaOperation.RECORD,
                    ClientActionOutcome.FAILED,
                    MediaFailureReason.PERMISSION,
                )
                queueMediaFeedback(
                    UserFeedbackCode.MICROPHONE_PERMISSION_REQUIRED,
                    ClientUiAction.START_VOICE_RECORDING,
                    FeedbackOrigin.SNACKBAR,
                )
            }
        }
    }

    // ── 文件选择器 ──
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        embeddedAssetImports.completePicker(EmbeddedAssetPresentation.FILE, uri)
    }

    // ── 图片选择器 ──（服务端缩略图/宽高：uploadWithMeta，准确度优于本地解码）
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        embeddedAssetImports.completePicker(EmbeddedAssetPresentation.IMAGE, uri)
    }

    SideEffect {
        embeddedAssetSelector.pickImage = {
            imagePicker.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build(),
            )
        }
        embeddedAssetSelector.pickFile = { filePicker.launch(arrayOf("*/*")) }
    }

    // ── 视频选择器 ──
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            launchAdmittedAction {
                isUploading = true
                var selected: PreparedMedia? = null
                try {
                    telemetry.recordMedia(
                        ClientUiPage.CHAT,
                        ClientMediaKind.VIDEO,
                        MediaOperation.UPLOAD,
                        ClientActionOutcome.STARTED,
                    )
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
                    currentCoroutineContext().ensureActive()
                    viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.VIDEO.code, System.currentTimeMillis(), body = VideoBody(attachment, duration, w, h, thumbnail)))
                    telemetry.recordMedia(
                        ClientUiPage.CHAT,
                        ClientMediaKind.VIDEO,
                        MediaOperation.UPLOAD,
                        ClientActionOutcome.SUCCEEDED,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    actionAdmission.runIfOpen {
                        reportMediaFailure(ClientMediaKind.VIDEO, MediaOperation.UPLOAD, error)
                    }
                } finally {
                    selected?.delete()
                    actionAdmission.runIfOpen { isUploading = false }
                }
            }
        }
    }

    fun startVoiceRecording() {
        if (!mediaSession.isCurrentOwner()) return
        if (voiceRecording.isActive) return
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
                mediaCacheScope,
                "outgoing-voice",
            ).apply { mkdirs() }
            val file = File.createTempFile("voice-", ".aac", directory)
            partialFile = file
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                val legacyRecorder = MediaRecorder()
                legacyRecorder
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
            check(voiceRecording.attach(rec, file, System.currentTimeMillis())) {
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
            reportMediaFailure(ClientMediaKind.AUDIO, MediaOperation.RECORD, terminalFailure)
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
        val finishedAt = System.currentTimeMillis()
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
                queueMediaFeedback(
                    if (tooShort) {
                        UserFeedbackCode.VOICE_TOO_SHORT
                    } else {
                        UserFeedbackCode.VOICE_RECORDING_FAILED
                    },
                    ClientUiAction.SEND_VOICE_RECORDING,
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
            queueMediaFeedback(
                UserFeedbackCode.VOICE_TOO_SHORT,
                ClientUiAction.SEND_VOICE_RECORDING,
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
        launchAdmittedAction {
            isUploading = true
            try {
                telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    ClientMediaKind.AUDIO,
                    MediaOperation.UPLOAD,
                    ClientActionOutcome.STARTED,
                )
                if (file.length() > MAX_SELECTED_MEDIA_BYTES) throw SelectedMediaTooLargeException(MAX_SELECTED_MEDIA_BYTES)
                val attachment = MediaHelper.uploadFile(
                    file,
                    file.name,
                    "audio/aac",
                    mediaSession,
                )
                currentCoroutineContext().ensureActive()
                viewModel.sendMessage(Message(chatId, UUID.randomUUID().toString(), 0L, myUid, MessageType.VOICE.code, System.currentTimeMillis(), body = VoiceBody(attachment, duration)))
                telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    ClientMediaKind.AUDIO,
                    MediaOperation.UPLOAD,
                    ClientActionOutcome.SUCCEEDED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                actionAdmission.runIfOpen {
                    reportMediaFailure(ClientMediaKind.AUDIO, MediaOperation.UPLOAD, error)
                }
            } finally {
                actionAdmission.runIfOpen { isUploading = false }
                try {
                    deleteVoiceRecordingFile(file)
                } catch (error: Exception) {
                    Log.w("Chat", "Voice recording temporary file cleanup failed", error)
                }
            }
        }
    }

    fun cancelVoiceRecording() {
        voicePermissionGate.clear()
        when (val result = voiceRecording.discard(
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
                Column {
                    AndroidChatHeader(
                        title = chatName.ifEmpty { chatId.take(16) },
                        chatType = chatType,
                        onBack = actionAdmission.guard(onBack),
                        onGroupDetail = actionAdmission.guard(onGroupDetail),
                    )
                    if (isUploading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
        ) { padding ->
            ChatPanel(
                chatId = chatId, chatName = chatName, viewModel = viewModel, myUid = myUid,
                chatType = chatType, resolveSender = resolveSender,
                onForward = onForward, onSaveMessage = onSaveMessage,
                cachedDraft = cachedDraft, onDraftChange = onDraftChange,
                draftLifecycleBridge = draftLifecycleBridge,
                actionAdmission = actionAdmission,
                composerContextStore = composerContextStore,
                voicePlayback = rememberAndroidVoicePlayback(
                    context = context,
                    mediaSession = mediaSession,
                    telemetry = telemetry,
                ),
                mentionCandidates = mentionCandidates,
                chatForegroundActive = chatRouteResumed,
                messageFocusTarget = messageFocusTarget,
                telemetry = telemetry,
                media = com.virjar.tk.app.ui.bridge.ChatMediaConfig(
                    fileDownloads = fileDownloads,
                    embeddedAssetImports = embeddedAssetImports,
                    onPasteEmbeddedAsset = {
                        importAndroidClipboardAsset(context, embeddedAssetImports)
                    },
                    onPickDocument = officeRefHost?.let { { officePickerKind = OfficeReferenceKind.DOCUMENT } },
                    onPickGroupFile = if (officeRefHost != null && chatType == 2) {
                        { officePickerKind = OfficeReferenceKind.GROUP_FILE }
                    } else {
                        null
                    },
                    onPickVideo = { videoPicker.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly).build()) },
                    onVoiceModeEntered = { prepareVoiceMode() },
                    onVoiceRecord = { if (it) startVoice() else stopVoice() },
                    onVoiceRecordCancel = { cancelVoiceRecording() },
                    onMentionClick = onMentionClick,
                    onUrlClick = { url -> openSafeExternalLink(context, url) },
                    imageContent = { attachment, mod ->
                        rememberAsyncThumb(
                            attachment = attachment,
                            mediaSession = mediaSession,
                            modifier = mod,
                            placeholderColor = android.graphics.Color.LTGRAY,
                        )
                    },
                    onMediaClick = onMediaClick,
                    onEmbeddedMediaClick = onEmbeddedMediaClick,
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
            mediaSession = mediaSession,
            telemetry = telemetry,
        )
    }

}

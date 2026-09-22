package com.virjar.tk.android

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.navigation.feature.OfficeReferenceKind
import com.virjar.tk.app.ui.component.GalleryItem
import com.virjar.tk.app.ui.component.GalleryMediaType
import com.virjar.tk.app.ui.component.OfficeRefPickerDialog
import com.virjar.tk.app.ui.component.PlatformMediaActions
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSource
import com.virjar.tk.app.ui.bridge.EmbeddedAssetLocalSelection
import com.virjar.tk.app.ui.component.rememberEmbeddedMediaClickHandler
import com.virjar.tk.app.ui.component.rememberMediaClickHandler
import com.virjar.tk.app.ui.screen.ChatPanel
import com.virjar.tk.app.navigation.feature.chat.OutgoingMediaSender
import com.virjar.tk.app.navigation.feature.chat.ChatComposerContextStore
import com.virjar.tk.app.navigation.feature.chat.ChatDraftLifecycleBridge
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
import kotlinx.coroutines.launch
import java.io.File

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
    onUrlClick: (String) -> Unit,
    onSaveMessage: ((Message) -> Unit)? = null,
    /** 类型化引用打开：读取由 MessageActionsFeature 完成，平台只做导航与降级提示。 */
    onOpenOfficeRef: ((com.virjar.tk.protocol.body.OfficeRefBody, onDenied: (String) -> Unit) -> Unit)? = null,
    onOpenTaskRef: ((com.virjar.tk.protocol.body.TaskRefBody, onDenied: (String) -> Unit) -> Unit)? = null,
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
    /** null 表示该会话不启用 @ 候选（如保存的消息，内测 T038）。 */
    mentionCandidates: List<User>? = emptyList(),
    onMentionClick: ((uid: String) -> Unit)? = null,
    onTextAttachmentPreview: ((Attachment) -> Unit)? = null,
    messageFocusTarget: MessageFocusTarget? = null,
    pendingTasksContent: (@Composable () -> Unit)? = null,
    taskBanner: (@Composable () -> Unit)? = null,
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
    val voiceController = remember(resourceOwner, chatId, myUid, viewModel, mediaSession) {
        AndroidChatVoiceController(context, chatId, myUid, viewModel, telemetry, { mediaSession }, voiceRecording, voicePermissionGate)
    }
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
        Log.w("Chat", "媒体操作失败: ${operation.code}/${reason.code}", error)
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

    voiceController.wiring = AndroidChatVoiceController.Wiring(
        queueMediaFeedback = { code, action, origin ->
            queueMediaFeedback(code, action, origin)
        },
        reportMediaFailure = { kind, operation, error ->
            reportMediaFailure(kind, operation, error)
        },
        launchOwnedMediaSource = { ownedFile, action ->
            launchWithOwnedMediaSource(ownedFile, launchAdmittedAction, action)
        },
        onUploadingChanged = { value ->
            actionAdmission.runIfOpen { isUploading = value }
        },
    )
    var officePickerKind by remember(chatId) { mutableStateOf<OfficeReferenceKind?>(null) }
    var taskPickerVisible by remember(chatId) { mutableStateOf(false) }
    officeRefHost?.let { host ->
        if (taskPickerVisible) {
            com.virjar.tk.app.ui.component.TaskRefPickerDialog(
                chatId = chatId,
                myUid = myUid,
                actions = host.messageActions,
                onSend = viewModel::sendMessage,
                onDismiss = { taskPickerVisible = false },
                modifier = Modifier.semantics { testTagsAsResourceId = true },
            )
        }
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
        onOpenTaskRef,
    ) {
        object : PlatformMediaActions {
            override fun openTaskRef(message: Message, body: com.virjar.tk.protocol.body.TaskRefBody) {
                onOpenTaskRef?.invoke(body, viewModel::onError)
            }
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
        actionAdmission.runIfOpen { voiceController.onPermissionResult(granted) }
    }

    // ── 文件选择器 ──
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        embeddedAssetImports.completePicker(EmbeddedAssetPresentation.FILE, uri)
    }

    // ── 图片选择器 ──（服务端缩略图/宽高：uploadWithMeta，准确度优于本地解码）
    val imagePicker = rememberAndroidVisualMediaPicker { uri ->
        embeddedAssetImports.completePicker(EmbeddedAssetPresentation.IMAGE, uri)
    }

    SideEffect {
        embeddedAssetSelector.pickImage = imagePicker
        embeddedAssetSelector.pickFile = { filePicker.launch(arrayOf("*/*")) }
    }

    // ── 视频选择器 / 相机直录（内测 T022）──

    fun sendVideoFromUri(ownedFile: File? = null, sourceUri: () -> Uri) {
        Log.i(
            "ChatMedia",
            "sendVideoFromUri: owned=${ownedFile?.absolutePath} exists=${ownedFile?.isFile} size=${ownedFile?.length()}",
        )
        launchWithOwnedMediaSource(ownedFile, launchAdmittedAction) {
            OutgoingMediaSender(telemetry).sendVideo(
                chatId = chatId,
                myUid = myUid,
                viewModel = viewModel,
                onUploadingChanged = { value -> actionAdmission.runIfOpen { isUploading = value } },
                classifyFailure = ::classifyAndroidMediaFailure,
                reportFailure = { _, reason ->
                    queueMediaFeedback(reason.uploadFeedbackCode, ClientUiAction.UPLOAD_MEDIA)
                },
            ) {
                uploadAndroidVideo(context, sourceUri(), mediaSession)
            }
        }
    }
    // ── 相册（图片+视频混选，按实际类型分流）/ 应用内相机 ──
    val albumPicker = rememberAndroidVisualMediaPicker(ActivityResultContracts.PickVisualMedia.ImageAndVideo) { uri ->
        if (uri != null) {
            val mime = MediaHelper.getMimeType(context, uri)
            if (mime.startsWith("video/")) {
                sendVideoFromUri { uri }
            } else {
                embeddedAssetImports.import(
                    EmbeddedAssetLocalSelection(
                        localReference = uri.toString(),
                        displayName = MediaHelper.getFileName(context, uri),
                        contentType = mime.ifBlank { "image/jpeg" },
                        size = MediaHelper.getFileSize(context, uri),
                        presentation = EmbeddedAssetPresentation.IMAGE,
                        source = EmbeddedAssetImportSource.ANDROID_PICKER,
                    ),
                )
            }
        }
    }

    /** 相机直录：录制的视频写入应用缓存，不落入系统相册，直接发送。 */
    var pendingCaptureFile by remember(mediaSession) { mutableStateOf<File?>(null) }
    val videoCaptureLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CaptureVideo(),
    ) { recorded ->
        val target = pendingCaptureFile
        pendingCaptureFile = null
        if (recorded && target != null && target.length() > 0L) {
            sendVideoFromUri(ownedFile = target) {
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", target,
                )
            }
        } else {
            target?.delete()
        }
    }
    DisposableEffect(mediaSession) {
        onDispose {
            pendingCaptureFile?.delete()
            pendingCaptureFile = null
        }
    }

    fun startVideoCapture() {
        if (pendingCaptureFile != null || !mediaSession.isCurrentOwner()) return
        val directory = mediaCacheDirectory(context.cacheDir, mediaCacheScope, "captured").apply { mkdirs() }
        val target = File.createTempFile("capture-", ".mp4", directory)
        pendingCaptureFile = target
        try {
            videoCaptureLauncher.launch(
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target),
            )
        } catch (failure: Throwable) {
            pendingCaptureFile = null
            target.delete()
            throw failure
        }
    }

    // ── 应用内相机（点按拍照/长按录像；CameraX 不可用时回落系统相机录制）──
    val cameraFeedbackScope = rememberCoroutineScope()
    var chatCameraVisible by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            chatCameraVisible = true
        } else {
            // 权限拒绝只影响拍摄入口，聊天与其它媒体能力不受影响。
            cameraFeedbackScope.launch { mediaSnackbar.showSnackbar("未授予相机权限，无法拍摄") }
        }
    }

    fun startChatCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            chatCameraVisible = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
    val latestCancelVoice by rememberUpdatedState(newValue = { voiceController.cancelVoiceRecording() })
    DisposableEffect(activity, voiceController.lease) {
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

    val chatMedia = com.virjar.tk.app.ui.bridge.ChatMediaConfig(
        fileDownloads = fileDownloads,
        embeddedAssetImports = embeddedAssetImports,
        onPasteEmbeddedAsset = {
            importAndroidClipboardAsset(context, embeddedAssetImports)
        },
        onPickDocument = officeRefHost?.let { { officePickerKind = OfficeReferenceKind.DOCUMENT } },
        onPickTask = officeRefHost?.let { { taskPickerVisible = true } },
        onPickGroupFile = if (officeRefHost != null && chatType == 2) {
            { officePickerKind = OfficeReferenceKind.GROUP_FILE }
        } else {
            null
        },
        onPickMedia = albumPicker,
        onCapture = { startChatCamera() },
        onVoiceModeEntered = { voiceController.prepareVoiceMode { recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO) } },
        onVoiceRecord = { if (it) voiceController.startVoice { recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO) } else voiceController.stopVoice() },
        onVoiceRecordCancel = { voiceController.cancelVoiceRecording() },
        onMentionClick = onMentionClick,
        onUrlClick = onUrlClick,
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
    )
    val voicePlayback = rememberAndroidVoicePlayback(context, mediaSession, telemetry)
    val messageDetails = com.virjar.tk.app.ui.screen.rememberMessageDetails(viewModel)
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.imePadding().then(if (messageDetails.isOpen) Modifier.clearAndSetSemantics {} else Modifier),
            snackbarHost = { SnackbarHost(mediaSnackbar) },
            topBar = {
                Column {
                    AndroidChatHeader(
                        title = chatName.ifEmpty { chatId.take(16) },
                        chatType = chatType,
                        onBack = actionAdmission.guard(onBack),
                        onGroupDetail = actionAdmission.guard(onGroupDetail),
                    )
                    taskBanner?.invoke()
                    if (isUploading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
        ) { padding ->
            ChatPanel(
                chatId = chatId, chatName = chatName, viewModel = viewModel, myUid = myUid,
                chatType = chatType, resolveSender = resolveSender,
                onForward = onForward, onSaveMessage = onSaveMessage,
                onOpenFullMessage = { focusManager.clearFocus(); messageDetails.open(it) },
                cachedDraft = cachedDraft, onDraftChange = onDraftChange,
                draftLifecycleBridge = draftLifecycleBridge,
                actionAdmission = actionAdmission,
                composerContextStore = composerContextStore,
                voicePlayback = voicePlayback,
                mentionCandidates = mentionCandidates,
                chatForegroundActive = chatRouteResumed && !messageDetails.isOpen,
                messageFocusTarget = messageFocusTarget,
                pendingTasksContent = pendingTasksContent,
                telemetry = telemetry,
                media = chatMedia,
                modifier = Modifier.padding(padding),
            )
        }

        if (messageDetails.isOpen) {
            com.virjar.tk.app.ui.screen.MessageDetailsScreen(messageDetails.message, chatMedia, voicePlayback,
                actionAdmission, resolveSender, onBack = messageDetails::close, loading = messageDetails.loading)
        }

        // 独立 Dialog 窗口不继承聊天页 IME padding，并位于 NavHost 转场与原生视频 surface 之上。
        AndroidMediaGalleryDialog(
            visible = showGallery,
            items = galleryItems,
            initialIndex = galleryIndex,
            onDismiss = { showGallery = false },
            mediaSession = mediaSession,
            telemetry = telemetry,
            onSaveCurrent = { attachment -> fileDownloads.exportToUserLocation(attachment) },
        )

        // 应用内相机与拍摄确认；照片走嵌入资产导入（同相册选图），视频复用既有发送管线。
        if (chatCameraVisible) {
            AndroidChatCameraDialog(
                cacheDirectory = mediaCacheDirectory(context.cacheDir, mediaCacheScope, "captured").apply { mkdirs() },
                onResult = { result ->
                    android.util.Log.i("ChatMedia", "camera onResult: ${result::class.simpleName}")
                    chatCameraVisible = false
                    when (result) {
                        is ChatCameraResult.Photo -> embeddedAssetImports.import(
                            EmbeddedAssetLocalSelection(
                                // 与相册选图同构：导入网关经 ContentResolver 读取，
                                // 裸文件路径无法通过 Uri.parse 打开（无 scheme）。
                                localReference = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    result.file,
                                ).toString(),
                                displayName = "拍摄照片.jpg",
                                contentType = "image/jpeg",
                                size = result.file.length(),
                                presentation = EmbeddedAssetPresentation.IMAGE,
                                source = EmbeddedAssetImportSource.ANDROID_PICKER,
                                deleteAfterImport = true,
                            ),
                        )

                        is ChatCameraResult.Video -> sendVideoFromUri(ownedFile = result.file) {
                            androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                result.file,
                            )
                        }
                    }
                },
                onUnavailable = {
                    chatCameraVisible = false
                    startVideoCapture()
                },
                onDismiss = { chatCameraVisible = false },
            )
        }
    }

}

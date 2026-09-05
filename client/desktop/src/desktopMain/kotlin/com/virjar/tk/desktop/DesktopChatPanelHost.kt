package com.virjar.tk.desktop

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virjar.tk.desktop.media.DesktopSessionResources
import com.virjar.tk.protocol.model.ChatType
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.telemetry.ClientActionOutcome
import com.virjar.tk.app.telemetry.ClientMediaKind
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.ClientUiTelemetrySink
import com.virjar.tk.app.telemetry.MediaOperation
import com.virjar.tk.app.navigation.feature.OfficeReferenceKind
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportGateway
import com.virjar.tk.app.ui.component.GalleryItem
import com.virjar.tk.app.ui.component.GalleryMediaType
import com.virjar.tk.app.ui.component.OfficeRefPickerDialog
import com.virjar.tk.app.ui.component.PlatformMediaActions
import com.virjar.tk.app.ui.component.VoicePlaybackController
import com.virjar.tk.app.ui.component.rememberMediaClickHandler
import com.virjar.tk.app.ui.component.rememberEmbeddedMediaClickHandler
import com.virjar.tk.app.ui.screen.ChatComposerContextStore
import com.virjar.tk.app.ui.screen.ChatDraftLifecycleBridge
import com.virjar.tk.app.ui.screen.ChatPanel
import com.virjar.tk.app.ui.theme.Tk
import com.virjar.tk.app.viewmodel.ChatViewModel
import com.virjar.tk.app.viewmodel.MessageFocusTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun ChatPanelWrapper(
    chatId: String,
    chatName: String,
    chatType: Int,
    viewModel: ChatViewModel,
    myUid: String,
    presentationGate: DesktopSessionPresentationGate,
    resources: DesktopSessionResources,
    embeddedAssetImports: EmbeddedAssetImportGateway,
    telemetry: ClientUiTelemetrySink,
    /** 同步交给会话级写者；页面销毁不取消已入队的草稿。 */
    saveDraft: (chatId: String, draft: String?) -> Unit,
    draftLifecycleBridge: ChatDraftLifecycleBridge,
    cachedDraft: String?,
    composerContextStore: ChatComposerContextStore,
    onForward: (Message) -> Unit,
    onSaveMessage: ((Message) -> Unit)? = null,
    /** 类型化引用打开：MainAppContent 负责权限重校验与导航；onDenied 走聊天页错误提示。 */
    onOpenOfficeRef: ((Message, com.virjar.tk.protocol.body.OfficeRefBody, onDenied: (String) -> Unit) -> Unit)? = null,
    /** 提供引用候选；null 时附件面板不显示文档/群文件入口。 */
    officeRefHost: com.virjar.tk.app.navigation.AppDataState? = null,
    onGroupSettings: () -> Unit,
    resolveSender: ((uid: String) -> User?)? = null,
    voicePlayback: VoicePlaybackController,
    onMentionClick: ((uid: String) -> Unit)? = null,
    mentionCandidates: List<User> = emptyList(),
    chatForegroundActive: Boolean,
    messageFocusTarget: MessageFocusTarget? = null,
    messageFocusRequestId: Long = 0L,
) {
    val messagesState = viewModel.messages.collectAsState()
    val previewScope = rememberCoroutineScope()
    val textPreviewEventState = remember(chatId) {
        mutableStateOf<DesktopTextAttachmentPreviewEvent?>(null)
    }
    var textPreviewEvent by textPreviewEventState
    val textPreviewOwner = remember(chatId, previewScope, textPreviewEventState) {
        DesktopTextAttachmentPreviewOwner(previewScope, textPreviewEventState)
    }

    // 页面只持有状态控制器；下载、缓存和凭据都归认证会话资源所有。
    val fileDownloads = remember(
        chatId,
        resources,
        previewScope,
        presentationGate,
        textPreviewOwner,
        telemetry,
    ) {
        DesktopFileDownloadController(
            resources = resources,
            uiScope = previewScope,
            actionAdmission = presentationGate,
            onDownloaded = { file ->
                presentationGate.runIfOpen { DesktopExternalFileOpener.open(file) }
            },
            onTextAttachmentPreview = { event ->
                var delivery: kotlinx.coroutines.Deferred<Boolean>? = null
                presentationGate.runIfOpen {
                    delivery = textPreviewOwner.offer(event)
                }
                delivery
            },
            telemetry = telemetry,
            telemetryPage = ClientUiPage.CHAT,
        )
    }
    DisposableEffect(fileDownloads, textPreviewOwner) {
        onDispose {
            textPreviewOwner.close()
            fileDownloads.close()
        }
    }
    // 类型化引用选择器状态：null 关闭；DOCUMENT/GROUP_FILE 打开对应候选。
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
            )
        }
    }

    // 媒体画廊窗口状态
    var showGallery by remember(chatId) { mutableStateOf(false) }
    var galleryItems by remember(chatId) { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var galleryIndex by remember(chatId) { mutableIntStateOf(0) }

    val mediaActions = remember(chatId, fileDownloads, telemetry, onOpenOfficeRef) {
        object : PlatformMediaActions {
            override fun openOfficeRef(message: Message, body: com.virjar.tk.protocol.body.OfficeRefBody) {
                onOpenOfficeRef?.invoke(message, body, viewModel::onError)
            }
            // 语音已走 voicePlayback 应用内播放（ChatPanel.voicePlayback），此链路不再触达
            override fun playVoice(attachment: com.virjar.tk.protocol.model.Attachment) {}
            override fun openFile(attachment: com.virjar.tk.protocol.model.Attachment) {
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
                galleryIndex = index.coerceIn(items.indices)
                galleryItems = items
                showGallery = true
                telemetry.recordMedia(
                    ClientUiPage.CHAT,
                    mediaKind,
                    MediaOperation.OPEN,
                    ClientActionOutcome.SUCCEEDED,
                )
            }
        }
    }
    val onMediaClick = rememberMediaClickHandler(messagesState, mediaActions)
    val onEmbeddedMediaClick = rememberEmbeddedMediaClickHandler(messagesState, mediaActions)

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { presentationGate.isOpen },
                target = remember(chatId, embeddedAssetImports, presentationGate) {
                    object : DragAndDropTarget {
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            var accepted = false
                            presentationGate.runIfOpen {
                                val data = event.dragData()
                                if (data is DragData.FilesList) {
                                    accepted = importDesktopDroppedAssetUris(
                                        data.readFiles(),
                                        embeddedAssetImports,
                                    )
                                }
                            }
                            return accepted
                        }
                    }
                },
            ),
    ) {
        // 群名称保持纯标题；设置使用明确的齿轮入口，打开聊天右侧检查器。
        val isGroup = ChatType.fromCode(chatType) == ChatType.GROUP
        ListHeader(
            title = chatName.ifEmpty { chatId.take(16) },
            actions = {
                if (isGroup) {
                    IconButton(
                        onClick = presentationGate.guard(onGroupSettings),
                        modifier = Modifier.size(40.dp).testTag("chat.settings"),
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "群设置",
                            tint = Tk.colors.secondaryText,
                            modifier = Modifier.size(Tk.dimens.iconSize),
                        )
                    }
                }
            },
        )
        ChatPanel(
            chatId, chatName, viewModel, myUid,
            chatType = chatType,
            resolveSender = resolveSender,
            onForward = onForward,
            onSaveMessage = onSaveMessage,
            cachedDraft = cachedDraft,
            draftLifecycleBridge = draftLifecycleBridge,
            actionAdmission = presentationGate,
            composerContextStore = composerContextStore,
            voicePlayback = voicePlayback,
            mentionCandidates = mentionCandidates,
            selectableText = true,
            chatForegroundActive = chatForegroundActive,
            messageFocusTarget = messageFocusTarget,
            messageFocusRequestId = messageFocusRequestId,
            telemetry = telemetry,
            onDraftChange = { draft ->
                saveDraft(chatId, draft)
            },
            media = com.virjar.tk.app.ui.bridge.ChatMediaConfig(
                fileDownloads = fileDownloads,
                embeddedAssetImports = embeddedAssetImports,
                onPasteEmbeddedAsset = { importDesktopClipboardAsset(embeddedAssetImports) },
                onPickVideo = { resources.videoSender.pickAndSendVideo(chatId, myUid, viewModel) },
                onPickDocument = officeRefHost?.let { { officePickerKind = OfficeReferenceKind.DOCUMENT } },
                onPickGroupFile = if (officeRefHost != null && chatType == 2) {
                    { officePickerKind = OfficeReferenceKind.GROUP_FILE }
                } else {
                    null
                },
                onMentionClick = onMentionClick,
                onUrlClick = { rawUrl ->
                    safeDesktopExternalLinkOrNull(rawUrl)?.let { url ->
                        previewScope.launch(Dispatchers.IO) {
                            try { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Exception) {}
                        }
                    }
                },
                onVoiceRecord = { start ->
                    if (start) resources.voiceRecorder.start()
                    else resources.voiceRecorder.stopAndSend(chatId, myUid, viewModel)
                },
                imageContent = { attachment, modifier ->
                    com.virjar.tk.desktop.media.CachedImageContent(
                        attachment = attachment,
                        resources = resources,
                        actionAdmission = presentationGate,
                        modifier = modifier,
                    )
                },
                onMediaClick = onMediaClick,
                onEmbeddedMediaClick = onEmbeddedMediaClick,
            ),
        )
    }

    // 全屏媒体画廊（独立窗口）
    MediaGalleryWindow(
        visible = showGallery,
        items = galleryItems,
        initialIndex = galleryIndex,
        presentationGate = presentationGate,
        resources = resources,
        telemetry = telemetry,
        onDismiss = presentationGate.guard { showGallery = false },
    )
    DesktopTextAttachmentPreviewDialog(
        event = textPreviewEvent,
        presentationGate = presentationGate,
        onDismiss = presentationGate.guard(textPreviewOwner::clear),
        onRetry = presentationGate.guard(fileDownloads::openOrDownload),
        onOpenExternally = presentationGate.guard(fileDownloads::openExternally),
    )
}

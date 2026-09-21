package com.virjar.tk.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import com.virjar.tk.app.navigation.MainTab
import com.virjar.tk.app.navigation.feature.OfficeReferenceKind
import com.virjar.tk.app.navigation.feature.chat.OutgoingMediaSender
import com.virjar.tk.app.navigation.feature.chat.UploadedVideoMedia
import com.virjar.tk.app.ui.bridge.ChatMediaConfig
import com.virjar.tk.app.ui.component.*
import com.virjar.tk.app.ui.screen.*
import com.virjar.tk.app.viewmodel.MessageFocusTarget
import com.virjar.tk.protocol.body.*
import com.virjar.tk.protocol.model.*
import com.virjar.tk.shared.platform.*
import com.virjar.tk.shared.repository.asUploadSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IosChatScreen(route: IosRoute, ui: IosSessionUi) {
    val data = ui.data
    val chatId = route.id
    val conversations by data.conversationViewModel.conversations.collectAsState()
    val peers by data.conversationViewModel.peerUsers.collectAsState()
    val contacts by data.contactViewModel.contacts.collectAsState()
    val foreground by IosApplicationRuntime.foreground.collectAsState()
    val conversation = conversations.firstOrNull { it.chatId == chatId }
    val chatType = conversation?.chatType ?: ChatType.PERSONAL.code
    val remarks = remember(contacts) { contactRemarks(contacts) }
    val chatName = conversation?.let { conversationIdentityPresentation(it, it.peerUid?.let(peers::get), it.peerUid?.let(remarks::get)).name }
        ?: chatId.take(16)
    LaunchedEffect(chatId) { data.uiActionAdmission.runIfOpen { data.chat.prepareChat(chatId) } }
    LaunchedEffect(chatId, chatType) {
        if (chatType == ChatType.GROUP.code) data.groups.loadMentionCandidates(chatId) else data.groups.clearMentionCandidates()
        data.tasks.attention.watchGroup(chatId.takeIf { chatType == ChatType.GROUP.code })
    }
    DisposableEffect(chatId) {
        onDispose { data.groups.clearMentionCandidates(chatId); data.tasks.attention.clearGroup(chatId); ui.recorder.close() }
    }
    TaskAttentionRefresh(data.tasks.attention, foreground)
    val viewModel = data.chat.chatViewModelFor(chatId) ?: return
    val messages = viewModel.messages.collectAsState()
    val messageDetails = com.virjar.tk.app.ui.screen.rememberMessageDetails(viewModel)
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val candidates = when (chatType) {
        ChatType.SAVED.code -> null
        ChatType.GROUP.code -> data.groups.mentionUsers.takeIf { data.groups.mentionTargetChatId == chatId }.orEmpty()
        else -> contacts.mapNotNull { it.user }
    }
    var gallery by remember(chatId) { mutableStateOf<IosGalleryRequest?>(null) }
    var menuExpanded by remember(chatId) { mutableStateOf(false) }
    var officePicker by remember(chatId) { mutableStateOf<OfficeReferenceKind?>(null) }
    var taskPicker by remember(chatId) { mutableStateOf(false) }
    val mediaActions = remember(ui, chatId) { object : PlatformMediaActions {
        override fun playVoice(attachment: Attachment) = ui.voice.toggle(attachment, 0)
        override fun openFile(attachment: Attachment) = ui.files.openOrDownload(attachment)
        override fun showGallery(items: List<GalleryItem>, index: Int) { ui.recorder.close(); ui.voice.close(); gallery = IosGalleryRequest(items, index) }
        override fun openOfficeRef(message: Message, body: OfficeRefBody) {
            data.messageActions.openReference(body, onOpen = {
                if (body.isDocument) ui.navigation.document(body) else ui.navigation.open(IosRoute(IosPage.GROUP_FILES, body.spaceId))
            }, onDenied = ::showIosError)
        }
        override fun openTaskRef(message: Message, body: TaskRefBody) {
            data.messageActions.openTaskReference(body, onOpen = { ui.navigation.task(body.taskId) }, onDenied = ::showIosError)
        }
    } }
    val onMedia = rememberMediaClickHandler(messages, mediaActions)
    val onEmbedded = rememberEmbeddedMediaClickHandler(messages, mediaActions)
    val sender = remember(data) { OutgoingMediaSender(data.telemetry) }
    fun selectVideo(camera: Boolean) {
        ui.native.selectVisual(video = true, camera = camera) { selection ->
            val job = data.launchCancellableAdmittedUiAction {
                sender.sendVideo(chatId, data.userSession.uid, viewModel) { progress ->
                    val result = ui.media.repository.uploadWithMeta(PlatformFile(selection.localReference).asUploadSource(),
                        selection.displayName, selection.contentType, progress).getOrThrow()
                    UploadedVideoMedia(result.file, result.durationSec ?: 0, result.width, result.height, result.thumbnail)
                }
            }
            if (job == null) releaseIosEmbeddedAssetSelection(selection)
            else job.invokeOnCompletion { releaseIosEmbeddedAssetSelection(selection) }
        }
    }
    val media = ChatMediaConfig(
        fileDownloads = ui.files,
        imageContent = { attachment, modifier -> IosAttachmentImage(attachment, ui.media, modifier) },
        embeddedAssetImports = ui.imports, onPasteEmbeddedAsset = { ui.native.importClipboard(ui.imports) },
        onPickVideo = { selectVideo(false) }, onCaptureVideo = { selectVideo(true) },
        onPickDocument = { officePicker = OfficeReferenceKind.DOCUMENT },
        onPickGroupFile = if (chatType == ChatType.GROUP.code) { { officePicker = OfficeReferenceKind.GROUP_FILE } } else null,
        onPickTask = { taskPicker = true },
        onVoiceModeEntered = ui.recorder::requestPermission,
        onVoiceRecord = { start ->
            if (start) { ui.voice.close(); ui.recorder.start() }
            else ui.recorder.finish()?.let { recording ->
                val job = data.launchCancellableAdmittedUiAction {
                    sender.sendVoice(chatId, data.userSession.uid, viewModel, recording.durationSeconds) {
                        ui.media.repository.upload(recording.file.asUploadSource(), "语音.m4a", "audio/mp4").getOrThrow()
                    }
                }
                if (job == null) recording.file.delete() else job.invokeOnCompletion { recording.file.delete() }
            }
        },
        onVoiceRecordCancel = ui.recorder::close,
        onMediaClick = onMedia, onEmbeddedMediaClick = onEmbedded,
        onMentionClick = ui.navigation::profile,
        onUrlClick = { url ->
            val invite = data.discovery.inviteFromText(url)
            if (invite != null) ui.navigation.open(IosRoute(IosPage.JOIN_BY_INVITE, invite)) else openIosUrl(url)
        },
    )
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding().then(if (messageDetails.isOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
            TopAppBar(title = { Text(chatName, maxLines = 1) }, navigationIcon = {
                IconButton(onClick = ui.navigation::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }, actions = {
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Filled.MoreVert, "会话菜单") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (chatType != ChatType.SAVED.code) DropdownMenuItem(
                            text = { Text(if (chatType == ChatType.GROUP.code) "群资料" else "联系人资料") },
                            onClick = data.uiActionAdmission.guard {
                                menuExpanded = false
                                if (chatType == ChatType.GROUP.code) ui.navigation.open(IosRoute(IosPage.GROUP_DETAIL, chatId))
                                else conversation?.peerUid?.let(ui.navigation::profile)
                            },
                        )
                        DropdownMenuItem(text = { Text("会话设置") }, onClick = data.uiActionAdmission.guard {
                            menuExpanded = false
                            ui.navigation.open(IosRoute(IosPage.CHAT_TOOLS, chatId))
                        })
                    }
                }
            })
            ChatPanel(chatId, chatName, viewModel, data.userSession.uid,
                modifier = Modifier.weight(1f), chatType = chatType,
                resolveSender = { uid -> candidates?.firstOrNull { it.uid == uid } ?: data.chat.residentChatUser(uid) },
                onOpenFullMessage = { focusManager.clearFocus(); messageDetails.open(it) },
                onForward = { ui.navigation.open(IosRoute(IosPage.FORWARD, it.chatId, it.serverSeq)) },
                onSaveMessage = { data.messageActions.save(it.chatId, it.serverSeq) },
                cachedDraft = conversation?.draft?.let { it } ?: conversation?.let { "" },
                onDraftChange = { data.chat.saveDraft(chatId, it) }, draftLifecycleBridge = data.chat.draftLifecycle,
                actionAdmission = data.uiActionAdmission, composerContextStore = data.chat.composerContexts,
                media = media, voicePlayback = ui.voice, mentionCandidates = candidates, chatForegroundActive = foreground && !messageDetails.isOpen,
                messageFocusTarget = route.sequence.takeIf { it > 0 }?.let { MessageFocusTarget(chatId, it) }, telemetry = data.telemetry,
                pendingTasksContent = {
                    data.tasks.attention.group?.takeIf { data.tasks.attention.groupId == chatId }?.let { summary ->
                        TaskAttentionBanner(summary.openCount, summary.overdueCount, stale = data.tasks.attention.groupStale, group = true,
                            onOpen = { data.tasks.openGroupTodos(chatId); ui.navigation.home(MainTab.TASKS) })
                    }
                })
        }
        if (messageDetails.isOpen) {
            MessageDetailsScreen(messageDetails.message, media, ui.voice, data.uiActionAdmission,
                resolveSender = { uid -> candidates?.firstOrNull { it.uid == uid } ?: data.chat.residentChatUser(uid) },
                onBack = messageDetails::close, loading = messageDetails.loading)
        }
    }
    officePicker?.let { kind -> OfficeRefPickerDialog(kind, chatId, data.userSession.uid, data.messageActions,
        onSend = viewModel::sendMessage, onDismiss = { officePicker = null }) }
    if (taskPicker) TaskRefPickerDialog(chatId, data.userSession.uid, data.messageActions,
        onSend = viewModel::sendMessage, onDismiss = { taskPicker = false })
    gallery?.let { IosGallery(it, ui.media, ui.files) { gallery = null } }
}

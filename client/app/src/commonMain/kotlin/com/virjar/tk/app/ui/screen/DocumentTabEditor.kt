package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.virjar.tk.protocol.body.OfficeRefBody
import com.virjar.tk.app.ui.component.ChatPickerDialog
import com.virjar.tk.app.navigation.feature.document.DocumentShareToChatAction
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.virjar.tk.protocol.model.DocumentRevision
import com.virjar.tk.protocol.model.DocumentRevisionSummary
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.navigation.feature.document.DocumentDraftLifecycleBridge
import com.virjar.tk.app.ui.platform.TkBackHandler
import com.virjar.tk.app.navigation.feature.document.DocumentDraftUpdate
import com.virjar.tk.app.navigation.feature.document.DocumentTabState
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEventSink
import com.virjar.tk.app.ui.bridge.LocalEmbeddedAssetImportGateway
import com.virjar.tk.app.ui.bridge.LocalEmbeddedAssetMediaConfig
import com.virjar.tk.app.ui.bridge.consumeEmbeddedAssetPasteShortcut
import com.virjar.tk.app.ui.component.FileCardWithDownload
import com.virjar.tk.app.ui.component.ImageThumbCard
import com.virjar.tk.app.ui.component.rich.DocumentBlockEditor
import com.virjar.tk.app.ui.component.rich.DocumentBlockFormattingToolbar
import com.virjar.tk.app.ui.component.rich.DocumentToolbarAction
import com.virjar.tk.app.ui.component.rich.DocumentMarkdownPreview
import com.virjar.tk.app.ui.component.rich.EmbeddedAssetMarkdownContent
import com.virjar.tk.app.ui.component.rich.referencedPendingAssetJobs
import com.virjar.tk.app.ui.component.rich.normalizeRichTextLink
import com.virjar.tk.app.ui.component.rich.rememberDocumentBlockEditorController

/**
 * 移动端单文档导航主要是阅读面：已有文档以预览打开，而新建草稿必须立即可编辑。
 * Desktop 保持其编辑默认，且没有编辑权限的查看者在所有平台都始终使用预览。
 */
internal fun shouldStartDocumentInPreview(
    canEdit: Boolean,
    mobileSingleDocumentMode: Boolean,
    creating: Boolean,
): Boolean = !canEdit || (mobileSingleDocumentMode && !creating)

/** 文档图片内容渲染：按内容列宽铺满、已知宽高比时按比例撑高，超长图限高后由 Fit 居中。 */
/** 移动端文档编辑态上报（内测 T029）：编辑时宿主隐藏底部导航等壳层元素，给输入法与富文本菜单腾出空间。 */
val LocalDocumentEditingActiveReporter = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

@Composable
internal fun DocumentDocumentImageContent(
    asset: EmbeddedAsset,
    imageContent: @Composable (com.virjar.tk.protocol.model.Attachment, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ratio = asset.width.takeIf { it > 0 }?.let { width ->
        asset.height.takeIf { it > 0 }?.let { height -> width.toFloat() / height }
    }
    androidx.compose.foundation.layout.Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.heightIn(min = 140.dp))
            .heightIn(max = 720.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        imageContent(asset.attachment, Modifier.fillMaxSize())
    }
}

@Composable
internal fun DocumentTabEditor(
    shareToChat: DocumentShareToChatAction?,
    tab: DocumentTabState,
    revisions: List<DocumentRevisionSummary>,
    revisionPreview: DocumentRevision?,
    loadingRevisions: Boolean,
    loadingMoreRevisions: Boolean,
    hasMoreRevisions: Boolean,
    saving: Boolean,
    moving: Boolean,
    destructiveOperationPending: Boolean,
    canEdit: Boolean,
    historyAvailable: Boolean,
    mobileSingleDocumentMode: Boolean,
    draftLifecycleBridge: DocumentDraftLifecycleBridge,
    onUpdateDraft: (DocumentDraftUpdate) -> Unit,
    onRegisterDraftSnapshot: ((() -> DocumentEditorDraftSnapshot)?) -> Unit,
    onSave: () -> Unit,
    onRequestMove: (Long) -> Unit,
    onDelete: () -> Unit,
    onShowHistory: () -> Unit,
    onLoadMoreRevisions: () -> Unit,
    onOpenRevision: (DocumentRevisionSummary) -> Unit,
    onRestoreRevision: () -> Unit,
    onCloseRevisionPreview: () -> Unit,
    onCloseHistory: () -> Unit,
    /** 评论入口（内联触发按钮，放进标题行；避免独占整行——内测反馈）。 */
    commentsTrigger: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val documentMentionSupport = com.virjar.tk.app.ui.bridge.LocalDocumentMentionSupport.current
    val editorKey = "${tab.instanceId}:${tab.recoveryId}:${tab.tabId}:${tab.revision ?: 0}"
    var showSharePicker by remember(editorKey) { mutableStateOf(false) }
    var shareNotice by remember(editorKey) { mutableStateOf<String?>(null) }
    val blockController = rememberDocumentBlockEditorController(editorKey)
    val session = remember(editorKey) { DocumentEditorSession(tab, blockController, onUpdateDraft) }
    SideEffect { session.updateDraftPublisher(onUpdateDraft) }
    val title = session.title
    val blockMarkdown = session.blockMarkdown
    val sourceMarkdown = session.sourceMarkdown
    val sourceMode = session.sourceMode
    val currentMarkdown = session.currentMarkdown
    val dirty = session.dirty
    val embeddedAssetImports = LocalEmbeddedAssetImportGateway.current
    val embeddedAssetMedia = LocalEmbeddedAssetMediaConfig.current
    val embeddedAssetContent: EmbeddedAssetMarkdownContent? = embeddedAssetMedia?.let { media ->
        { asset, presentation, assetModifier ->
            when (presentation) {
                EmbeddedAssetPresentation.IMAGE -> DocumentDocumentImageContent(
                    asset = asset,
                    imageContent = media.imageContent,
                    modifier = assetModifier,
                )
                EmbeddedAssetPresentation.FILE -> FileCardWithDownload(
                    controller = media.fileDownloads,
                    attachment = asset.attachment,
                    modifier = assetModifier,
                )
            }
        }
    }
    var previewMode by remember(editorKey, canEdit, mobileSingleDocumentMode, tab.creating) {
        mutableStateOf(
            shouldStartDocumentInPreview(
                canEdit = canEdit,
                mobileSingleDocumentMode = mobileSingleDocumentMode,
                creating = tab.creating,
            )
        )
    }
    val reportEditingActive = LocalDocumentEditingActiveReporter.current
    LaunchedEffect(previewMode, canEdit) {
        reportEditingActive?.invoke(canEdit && !previewMode)
    }
    DisposableEffect(Unit) {
        onDispose { reportEditingActive?.invoke(false) }
    }
    var historyDialog by remember(editorKey) { mutableStateOf(false) }
    var deleteDialog by remember(editorKey) { mutableStateOf(false) }
    var documentMenu by remember(editorKey) { mutableStateOf(false) }

    LaunchedEffect(session) {
        session.beginInitialization()
        // 等待块画布挂载，避免把初始化期间的子编辑器状态误判为用户输入。
        withFrameNanos { }
        withFrameNanos { }
        session.finishInitialization()
    }
    LaunchedEffect(
        session.editorReady, title, currentMarkdown, sourceMode, session.embeddedAssetSnapshot.assets,
    ) {
        session.publishCurrentDraftIfReady()
    }

    val embeddedAssetImportEnabled = documentEmbeddedAssetImportEnabled(canEdit, previewMode)
    val onPasteEmbeddedAsset = embeddedAssetMedia?.onPasteEmbeddedAsset
    fun Modifier.withEmbeddedAssetPasteShortcut(): Modifier = onPreviewKeyEvent { event ->
        consumeEmbeddedAssetPasteShortcut(
            isKeyDown = event.type == KeyEventType.KeyDown,
            isPasteKey = event.key == Key.V,
            hasCommandModifier = event.isMetaPressed || event.isCtrlPressed,
            onPasteEmbeddedAsset = onPasteEmbeddedAsset.takeIf { embeddedAssetImportEnabled },
        )
    }
    LaunchedEffect(
        session,
        sourceMode,
        previewMode,
        blockController.embeddedAssetActionsBound,
        session.deferredEmbeddedAssetEvents,
    ) {
        session.drainEmbeddedAssetImports(previewMode)
    }
    DisposableEffect(session, embeddedAssetImports, embeddedAssetImportEnabled) {
        // 预览仍接收由本编辑器启动的上传完成帧，只拒绝新的导入。
        val registration = embeddedAssetImports?.bind(
            ownerKey = session.embeddedAssetOwnerKey,
            sink = EmbeddedAssetImportEventSink { event ->
                session.acceptEmbeddedAssetImport(event, previewMode)
            },
            acceptNewImports = embeddedAssetImportEnabled,
        )
        onDispose {
            registration?.close()
            // 已移交的帧留在 session：模式切换由上方 effect 重放，退出由最终草稿捕获排空。
        }
    }
    val stableDraftCapture = remember(session) { session::captureLatestDraft }
    DisposableEffect(session, draftLifecycleBridge) {
        val lifecycleRegistration = draftLifecycleBridge.register(
            owner = session.owner,
            captureAndPublish = { stableDraftCapture() },
        )
        onRegisterDraftSnapshot(stableDraftCapture)
        onDispose {
            draftLifecycleBridge.captureAndUnregister(lifecycleRegistration)
            onRegisterDraftSnapshot(null)
        }
    }
    LaunchedEffect(canEdit) {
        if (!canEdit) stableDraftCapture()
    }
    LaunchedEffect(historyAvailable) {
        if (!historyAvailable && historyDialog) {
            historyDialog = false
            onCloseHistory()
        }
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("删除“$title”？") },
            text = { Text("文档将从空间中移除；如有子文档，请先移动或删除子文档。") },
            confirmButton = {
                TextButton(
                    onClick = { deleteDialog = false; onDelete() },
                    modifier = Modifier.testTag("documents.document.delete.confirm"),
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } },
        )
    }
    if (historyDialog && historyAvailable && tab.documentId != null && !tab.remoteMissing) {
        DocumentRevisionDialog(
            title = tab.savedTitle,
            currentRevision = tab.revision ?: 1,
            revisions = revisions,
            preview = revisionPreview,
            loadingRevisions = loadingRevisions,
            loadingMoreRevisions = loadingMoreRevisions,
            hasMoreRevisions = hasMoreRevisions,
            saving = saving,
            canRestore = canEdit,
            onOpenRevision = onOpenRevision,
            onLoadMore = onLoadMoreRevisions,
            onRestore = onRestoreRevision,
            onClosePreview = onCloseRevisionPreview,
            onDismiss = { historyDialog = false; onCloseHistory() },
            embeddedAssetContent = embeddedAssetContent,
        )
    }

    val toggleSourceMode = session::toggleSourceMode
    val togglePreviewMode = {
        if (!previewMode) session.prepareForPreview()
        previewMode = !previewMode
    }
    // 两级导航（内测反馈）：移动端编辑态是独立全屏页，系统返回/手势先退回预览页；
    // 预览页再按返回才离开文档（该处理器由外层空间页注册）。后注册的处理器优先生效。
    if (mobileSingleDocumentMode && canEdit && !previewMode) {
        TkBackHandler { togglePreviewMode() }
    }
    val saveDocument: () -> Unit = {
        if (session.prepareSave()) onSave()
    }
    val requestMove = {
        val latest = session.captureLatestDraft()
        if (!latest.dirty && !tab.creating && !saving && !moving) onRequestMove(tab.instanceId)
    }
    val moveDisabledMessage = when {
        dirty -> "请先保存当前修改"
        saving -> "正在保存"
        moving -> "位置或名称变更等待确认"
        else -> null
    }
    val referencedAssetJobs = remember(currentMarkdown, session.embeddedAssetSnapshot.jobs) {
        referencedPendingAssetJobs(currentMarkdown, session.embeddedAssetSnapshot.jobs)
    }

    Box(modifier) {
    // 移动端编辑态顶部不留白：标题行已折叠，顶边距一并清零（内测反馈）。
    val mobileEditHidesTitleRow = mobileSingleDocumentMode && canEdit && !previewMode &&
        !tab.creating && !tab.remoteMissing
    val editorTopPadding = if (mobileEditHidesTitleRow) 0.dp else 14.dp
    Column(
        Modifier.fillMaxSize().padding(
            start = 22.dp,
            top = editorTopPadding,
            end = 22.dp,
            bottom = 14.dp,
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compactHeader = maxWidth < 620.dp
            // 移动端编辑态把标题整行让给正文（内测反馈）：标题块/保存/评论折叠进动作行。
            // 新建与远端缺失草稿必须保留标题输入，不参与折叠。
            if (compactHeader) {
                Column(Modifier.fillMaxWidth()) {
                    if (!mobileEditHidesTitleRow) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            DocumentTitleBlock(
                                title = title,
                                onTitleChange = { session.title = it },
                                canEdit = canEdit,
                                creating = tab.creating,
                                remoteMissing = tab.remoteMissing,
                                revision = tab.revision,
                                dirty = dirty,
                                modifier = Modifier.weight(1f),
                            )
                            if (canEdit) DocumentSaveAction(
                                saving = saving,
                                enabled = title.isNotBlank() && !saving && !moving &&
                                (dirty || tab.creating || tab.remoteMissing),
                            compact = true,
                            saveAsNew = tab.remoteMissing,
                            onSave = saveDocument,
                        )
                        commentsTrigger()
                        }
                    }
                    if (canEdit || !tab.creating) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DocumentHeaderActions(
                                canEdit = canEdit,
                                creating = tab.creating,
                                remoteMissing = tab.remoteMissing,
                                historyAvailable = historyAvailable,
                                moveEnabled = !dirty && !saving && !moving,
                                moveDisabledMessage = moveDisabledMessage,
                                previewMode = previewMode,
                                sourceMode = sourceMode,
                                documentMenu = documentMenu,
                                onToggleSource = toggleSourceMode,
                                onTogglePreview = togglePreviewMode,
                                onShowHistory = { historyDialog = true; onShowHistory() },
                                onShowDocumentMenu = { documentMenu = true },
                                onDismissDocumentMenu = { documentMenu = false },
                                onMove = { documentMenu = false; requestMove() },
                                onDelete = { documentMenu = false; deleteDialog = true },
                                // 紧凑头部同样接通分享入口；漏传会让窄窗口（<620dp）丢失"分享到会话"（T023 验收发现）
                                onShareToChat = if (shareToChat != null && !tab.creating && !tab.remoteMissing) {
                                    { documentMenu = false; showSharePicker = true }
                                } else null,
                            )
                            if (mobileEditHidesTitleRow) {
                                // 标题行折叠后，保存与评论入口并到动作行尾部，能力不丢。
                                if (canEdit) DocumentSaveAction(
                                    saving = saving,
                                    enabled = title.isNotBlank() && !saving && !moving &&
                                        (dirty || tab.creating || tab.remoteMissing),
                                    compact = true,
                                    saveAsNew = tab.remoteMissing,
                                    onSave = saveDocument,
                                )
                                commentsTrigger()
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DocumentTitleBlock(
                        title = title,
                        onTitleChange = { session.title = it },
                        canEdit = canEdit,
                        creating = tab.creating,
                        remoteMissing = tab.remoteMissing,
                        revision = tab.revision,
                        dirty = dirty,
                        modifier = Modifier.weight(1f),
                    )
                    DocumentHeaderActions(
                        canEdit = canEdit,
                        creating = tab.creating,
                        remoteMissing = tab.remoteMissing,
                        historyAvailable = historyAvailable,
                        moveEnabled = !dirty && !saving && !moving,
                        moveDisabledMessage = moveDisabledMessage,
                        previewMode = previewMode,
                        sourceMode = sourceMode,
                        documentMenu = documentMenu,
                        onToggleSource = toggleSourceMode,
                        onTogglePreview = togglePreviewMode,
                        onShowHistory = { historyDialog = true; onShowHistory() },
                        onShowDocumentMenu = { documentMenu = true },
                        onDismissDocumentMenu = { documentMenu = false },
                        onMove = { documentMenu = false; requestMove() },
                        onDelete = { documentMenu = false; deleteDialog = true },
                        onShareToChat = if (shareToChat != null && !tab.creating && !tab.remoteMissing) {
                            { documentMenu = false; showSharePicker = true }
                        } else null,
                    )
                    if (canEdit) DocumentSaveAction(
                        saving = saving,
                        enabled = title.isNotBlank() && !saving && !moving &&
                            (dirty || tab.creating || tab.remoteMissing),
                        compact = false,
                        saveAsNew = tab.remoteMissing,
                        onSave = saveDocument,
                    )
                    commentsTrigger()
                }
            }
        }
        if (moving) {
            Text(
                text = "位置或名称变更正在确认；内容草稿已保留，确认后请再次保存正文。",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .testTag("documents.editor.structurePending")
                    .padding(top = 8.dp),
            )
        }
        shareNotice?.let { notice ->
            Text(
                text = notice,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .testTag("documents.editor.shareNotice")
                    .padding(top = 8.dp),
            )
        }
        if (showSharePicker && shareToChat != null) {
            val conversations by shareToChat.conversations().collectAsState(emptyList())
            ChatPickerDialog(
                conversations = conversations,
                title = "分享到会话",
                onPick = { conversation ->
                    showSharePicker = false
                    val documentId = tab.documentId
                    if (documentId != null) {
                        val ref = OfficeRefBody(
                            refType = OfficeRefBody.REF_TYPE_DOCUMENT,
                            spaceId = tab.spaceId,
                            targetId = documentId,
                            title = title.ifBlank { "未命名文档" },
                            subtitle = "文档",
                        )
                        val admitted = shareToChat.send(conversation.chatId, ref)
                        shareNotice = if (admitted) {
                            "已分享到「${conversationLabel(conversation)}」"
                        } else {
                            "分享未完成，请稍后重试"
                        }
                    }
                },
                onDismiss = { showSharePicker = false },
            )
        }
        if (tab.remoteMissing) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().testTag("documents.editor.remote-missing"),
            ) {
                Text(
                    "原文档已被删除。本机草稿仍可编辑；“另存为新文档”会在当前空间根目录创建新页面。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (canEdit && !previewMode && !sourceMode) {
                // 插入图片/文件并入工具栏行，不再单独占一行（内测 T034）
                val assetActions = if (embeddedAssetImportEnabled && embeddedAssetImports != null) {
                    buildList {
                        add(DocumentToolbarAction(
                            label = "插入图片",
                            testTag = "documents.asset.pick.image",
                            icon = Icons.Filled.Image,
                            onClick = { embeddedAssetImports.select(EmbeddedAssetPresentation.IMAGE) },
                        ))
                        add(DocumentToolbarAction(
                            label = "插入文件",
                            testTag = "documents.asset.pick.file",
                            icon = Icons.Filled.AttachFile,
                            onClick = { embeddedAssetImports.select(EmbeddedAssetPresentation.FILE) },
                        ))
                        onPasteEmbeddedAsset?.let { paste ->
                            add(DocumentToolbarAction(
                                label = "粘贴",
                                testTag = "documents.asset.paste",
                                icon = Icons.Filled.ContentPaste,
                                onClick = { paste() },
                            ))
                        }
                    }
                } else {
                    emptyList()
                }
                DocumentBlockFormattingToolbar(
                    controller = blockController,
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    extraActions = assetActions,
                )
            } else if (destructiveOperationPending || !(previewMode && mobileSingleDocumentMode)) {
                // 移动端预览屏寸寸金："Markdown 预览"标签行不渲染（内测反馈）；
                // 只读告警与桌面/源码模式的模式说明保留。
                Text(
                    if (destructiveOperationPending) {
                        "删除或归档结果待确认，当前只读"
                    } else if (previewMode || !canEdit) {
                        "Markdown 预览"
                    } else {
                        "Markdown 源码"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
        PendingAssetRows(
            jobs = referencedAssetJobs,
            testTagPrefix = "documents",
            onRetry = { session.retryPendingAsset(it, embeddedAssetImports) },
            onDiscard = { session.discardPendingAsset(it, embeddedAssetImports) },
        )
        session.embeddedAssetError?.let { error ->
            Text(
                error.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("documents.asset.error"),
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            Modifier.fillMaxWidth().weight(1f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (previewMode || !canEdit) {
                if (currentMarkdown.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("文档内容为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    DocumentMarkdownPreview(
                        markdown = currentMarkdown,
                        assets = session.currentAssetManifest(currentMarkdown),
                        modifier = Modifier.fillMaxSize(),
                        onUrlClick = { url -> normalizeRichTextLink(url)?.let { runCatching { uriHandler.openUri(it) } } },
                        onMentionClick = { uid ->
                            if (uid.isNotBlank()) documentMentionSupport.onMentionProfileOpen(uid)
                        },
                        embeddedAssetContent = embeddedAssetContent,
                    )
                }
            } else if (sourceMode) {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = sourceMarkdown,
                        onValueChange = { session.sourceMarkdown = it },
                        modifier = Modifier.fillMaxSize()
                            .withEmbeddedAssetPasteShortcut()
                            .testTag("documents.editor.source.body")
                            .padding(18.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = androidx.compose.material3.LocalContentColor.current,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                    if (sourceMarkdown.isEmpty()) Text(
                        "输入 Markdown 正文…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            } else {
                DocumentBlockEditor(
                    documentKey = editorKey,
                    initialMarkdown = blockMarkdown,
                    controller = blockController,
                    onMarkdownChange = { session.blockMarkdown = it },
                    assets = session.currentAssetManifest(blockMarkdown),
                    embeddedAssetContent = embeddedAssetContent,
                    modifier = Modifier.fillMaxSize()
                        .withEmbeddedAssetPasteShortcut()
                        .testTag("documents.editor.body"),
                )
            }
        }
    }
    }
    }


/** 会话展示名兜底：与 ChatPickerDialog 保持一致的标签推断。 */
private fun conversationLabel(conversation: com.virjar.tk.protocol.model.Conversation): String =
    conversation.chatName?.trim()?.takeIf(String::isNotEmpty)
        ?: conversation.peerUid?.take(8)
        ?: conversation.chatId.take(8)

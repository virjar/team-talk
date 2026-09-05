package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.virjar.tk.app.navigation.feature.document.DocumentDraftCaptureOwner
import com.virjar.tk.app.navigation.feature.document.DocumentDraftLifecycleBridge
import com.virjar.tk.app.navigation.feature.document.DocumentDraftUpdate
import com.virjar.tk.app.navigation.feature.document.DocumentTabState
import com.virjar.tk.protocol.body.EmbeddedAssetPresentation
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEventSink
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSnapshot
import com.virjar.tk.app.ui.bridge.LocalEmbeddedAssetImportGateway
import com.virjar.tk.app.ui.bridge.LocalEmbeddedAssetMediaConfig
import com.virjar.tk.app.ui.bridge.consumeEmbeddedAssetPasteShortcut
import com.virjar.tk.app.ui.component.FileCardWithDownload
import com.virjar.tk.app.ui.component.ImageThumbCard
import com.virjar.tk.app.ui.component.rich.DocumentBlockEditor
import com.virjar.tk.app.ui.component.rich.DocumentBlockFormattingToolbar
import com.virjar.tk.app.ui.component.rich.DocumentMarkdownPreview
import com.virjar.tk.app.ui.component.rich.EmbeddedAssetCommitBlocker
import com.virjar.tk.app.ui.component.rich.EmbeddedAssetMarkdownContent
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.admitEmbeddedAssetCommit
import com.virjar.tk.app.ui.component.rich.embeddedAssetMarkdownReferences
import com.virjar.tk.app.ui.component.rich.projectEmbeddedAssetManifest
import com.virjar.tk.app.ui.component.rich.referencedPendingAssetJobs
import com.virjar.tk.app.ui.component.rich.normalizeRichTextLink
import com.virjar.tk.app.ui.component.rich.rememberDocumentBlockEditorController

internal data class DocumentEditorDraftSnapshot(
    val title: String,
    val markdown: String,
    val dirty: Boolean,
    val assets: List<EmbeddedAsset> = emptyList(),
)

/**
 * 提交错误有不同的生命周期。上传等待是暂时的，可以被后续的导入帧调和；畸形的
 * Markdown/sidecar 必须保持可见，直到新的保存再次校验编辑后的内容。
 */
internal enum class DocumentEmbeddedAssetCommitError(val message: String) {
    UPLOAD_PENDING("附件仍在上传，请等待完成后再保存"),
    RETRY_UNAVAILABLE("本地附件已不可用于重试，请移除后重新选择"),
    INVALID_CONTENT("文档中的内嵌资产引用与清单不一致"),
}

internal fun admitDocumentEmbeddedAssetCommit(
    markdown: String,
    availableAssets: List<EmbeddedAsset>,
    pendingJobs: List<PendingAssetJob>,
) = run {
    val assets = runCatching { projectEmbeddedAssetManifest(markdown, availableAssets) }
        .getOrDefault(emptyList())
    val referencedIds = runCatching {
        embeddedAssetMarkdownReferences(markdown).mapNotNull { it.assetId }.toSet()
    }.getOrDefault(emptySet())
    admitEmbeddedAssetCommit(
        markdown = markdown,
        manifestAssetIds = assets.map(EmbeddedAsset::assetId),
        pendingJobs = pendingJobs.filter { it.assetId in referencedIds },
    )
}

/** 只有暂时性的保存屏障才有资格自动调和。 */
internal fun reconcileDocumentEmbeddedAssetCommitError(
    currentError: DocumentEmbeddedAssetCommitError?,
    markdown: String,
    availableAssets: List<EmbeddedAsset>,
    pendingJobs: List<PendingAssetJob>,
): DocumentEmbeddedAssetCommitError? {
    if (
        currentError != DocumentEmbeddedAssetCommitError.UPLOAD_PENDING &&
        currentError != DocumentEmbeddedAssetCommitError.RETRY_UNAVAILABLE
    ) return currentError
    val admission = admitDocumentEmbeddedAssetCommit(markdown, availableAssets, pendingJobs)
    return when {
        EmbeddedAssetCommitBlocker.JOB_NOT_READY in admission.blockers -> currentError
        admission.canCommit -> null
        else -> DocumentEmbeddedAssetCommitError.INVALID_CONTENT
    }
}

/** 每个编辑器实例的稳定句柄；旧标签页绝不能读取新标签页的捕获 lambda。 */
internal class DocumentDraftCaptureHandle(
    var action: () -> DocumentEditorDraftSnapshot,
) {
    fun capture(): DocumentEditorDraftSnapshot = action()
}

/**
 * 移动端单文档导航主要是阅读面：已有文档以预览打开，而新建草稿必须立即可编辑。
 * Desktop 保持其编辑默认，且没有编辑权限的查看者在所有平台都始终使用预览。
 */
internal fun shouldStartDocumentInPreview(
    canEdit: Boolean,
    mobileSingleDocumentMode: Boolean,
    creating: Boolean,
): Boolean = !canEdit || (mobileSingleDocumentMode && !creating)

@Composable
internal fun DocumentTabEditor(
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
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val editorKey = "${tab.instanceId}:${tab.recoveryId}:${tab.tabId}:${tab.revision ?: 0}"
    val embeddedAssetOwnerKey = "document:${tab.instanceId}:${tab.recoveryId}"
    val blockController = rememberDocumentBlockEditorController(editorKey)
    val baselineTitle = remember(editorKey) { tab.savedTitle }
    var title by remember(editorKey) { mutableStateOf(tab.draftTitle) }
    val baselineMarkdown = remember(editorKey) { tab.savedMarkdown }
    val baselineAssets = remember(editorKey) { tab.savedAssets }
    val embeddedAssetImports = LocalEmbeddedAssetImportGateway.current
    val embeddedAssetMedia = LocalEmbeddedAssetMediaConfig.current
    val embeddedAssetContent: EmbeddedAssetMarkdownContent? = embeddedAssetMedia?.let { media ->
        { asset, presentation, assetModifier ->
            when (presentation) {
                EmbeddedAssetPresentation.IMAGE -> ImageThumbCard(
                    attachment = asset.thumbnail ?: asset.attachment,
                    imageContent = media.imageContent,
                    imgWidth = asset.width,
                    imgHeight = asset.height,
                    onClick = null,
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
    var embeddedAssetSnapshot by remember(editorKey) {
        mutableStateOf(EmbeddedAssetImportSnapshot(assets = tab.draftAssets))
    }
    var deferredEmbeddedAssetEvents by remember(editorKey) {
        mutableStateOf<List<EmbeddedAssetImportEvent>>(emptyList())
    }
    var embeddedAssetError by remember(editorKey) {
        mutableStateOf<DocumentEmbeddedAssetCommitError?>(null)
    }
    var blockMarkdown by remember(editorKey) { mutableStateOf(tab.draftMarkdown) }
    var sourceMarkdown by remember(editorKey) { mutableStateOf(tab.draftMarkdown) }
    var sourceMode by remember(editorKey) { mutableStateOf(false) }
    var editorReady by remember(editorKey) { mutableStateOf(false) }
    var dirty by remember(editorKey) { mutableStateOf(tab.dirty || tab.creating) }
    var previewMode by remember(editorKey, canEdit, mobileSingleDocumentMode, tab.creating) {
        mutableStateOf(
            shouldStartDocumentInPreview(
                canEdit = canEdit,
                mobileSingleDocumentMode = mobileSingleDocumentMode,
                creating = tab.creating,
            )
        )
    }
    var historyDialog by remember(editorKey) { mutableStateOf(false) }
    var deleteDialog by remember(editorKey) { mutableStateOf(false) }
    var documentMenu by remember(editorKey) { mutableStateOf(false) }

    LaunchedEffect(editorKey) {
        editorReady = false
        blockMarkdown = tab.draftMarkdown
        sourceMarkdown = tab.draftMarkdown
        sourceMode = false
        // Block codec 会原样保留所有未编辑源码。等待画布挂载后再开始同步草稿，
        // 避免初始化期间的子编辑器状态被误判为用户输入。
        withFrameNanos { }
        withFrameNanos { }
        editorReady = true
    }
    val currentMarkdown = if (editorReady) {
        if (sourceMode) sourceMarkdown else blockMarkdown
    } else tab.draftMarkdown
    fun currentAssetManifest(markdown: String): List<EmbeddedAsset> = runCatching {
        projectEmbeddedAssetManifest(markdown, embeddedAssetSnapshot.assets)
    }.getOrDefault(emptyList())

    LaunchedEffect(editorReady, title, currentMarkdown, sourceMode, embeddedAssetSnapshot.assets) {
        if (!editorReady) return@LaunchedEffect
        val assets = currentAssetManifest(currentMarkdown)
        dirty = tab.creating || title != baselineTitle || currentMarkdown != baselineMarkdown ||
            assets != baselineAssets
        onUpdateDraft(
            DocumentDraftUpdate(
                tab.tabId,
                tab.instanceId,
                tab.revision,
                title,
                currentMarkdown,
                assets,
            ),
        )
    }

    fun latestVisualMarkdown(): String = blockController.snapshotMarkdown(blockMarkdown)
    fun publishDraft(markdown: String): DocumentEditorDraftSnapshot {
        blockMarkdown = markdown
        val assets = currentAssetManifest(markdown)
        dirty = tab.creating || title != baselineTitle || markdown != baselineMarkdown ||
            assets != baselineAssets
        onUpdateDraft(
            DocumentDraftUpdate(tab.tabId, tab.instanceId, tab.revision, title, markdown, assets),
        )
        return DocumentEditorDraftSnapshot(title, markdown, dirty, assets)
    }
    fun captureLatestDraft(): DocumentEditorDraftSnapshot = publishDraft(
        if (sourceMode) sourceMarkdown else latestVisualMarkdown()
    )
    fun discardPendingAsset(job: PendingAssetJob) = discardDocumentPendingAsset(
        job = job,
        markdown = if (sourceMode) sourceMarkdown else latestVisualMarkdown(),
        updateEditor = { updated ->
            sourceMarkdown = updated
            sourceMode = true
        },
        publishDraft = { publishDraft(it) },
        reconcileError = { updated ->
            embeddedAssetError = reconcileDocumentEmbeddedAssetCommitError(
                embeddedAssetError, updated, embeddedAssetSnapshot.assets, embeddedAssetSnapshot.jobs,
            )
        },
        cancelUpload = { embeddedAssetImports?.cancel(it) },
        reportInvalidContent = { embeddedAssetError = DocumentEmbeddedAssetCommitError.INVALID_CONTENT },
    )
    fun retryPendingAsset(job: PendingAssetJob) {
        if (embeddedAssetImports?.retry(job.jobId) == true) {
            if (embeddedAssetError == DocumentEmbeddedAssetCommitError.RETRY_UNAVAILABLE) {
                embeddedAssetError = null
            }
        } else {
            embeddedAssetError = DocumentEmbeddedAssetCommitError.RETRY_UNAVAILABLE
        }
    }
    fun placeEmbeddedAssetReference(
        event: EmbeddedAssetImportEvent,
        forceBlockBoundary: Boolean = false,
    ): Boolean {
        val markdown = if (sourceMode) sourceMarkdown else latestVisualMarkdown()
        when (
            val placement = placeDocumentEmbeddedAssetReference(
                event = event,
                currentMarkdown = markdown,
                sourceMode = sourceMode,
                previewMode = previewMode,
                forceBlockBoundary = forceBlockBoundary,
            )
                ?: return true
        ) {
            is DocumentEmbeddedAssetPlacement.VisualSelection -> {
                if (!blockController.insertEmbeddedAsset(placement.assetId, placement.syntax)) return false
                publishDraft(latestVisualMarkdown())
            }
            is DocumentEmbeddedAssetPlacement.BoundaryAppend -> {
                if (placement.resultingSourceMode) sourceMarkdown = placement.markdown
                sourceMode = placement.resultingSourceMode
                publishDraft(placement.markdown)
            }
        }
        return true
    }
    fun applyEmbeddedAssetImportEvent(
        event: EmbeddedAssetImportEvent,
        forceBlockBoundary: Boolean = false,
    ): Boolean {
        // READY 会改变 assets 的 remember-key 并重建块列表。当视觉编辑器确实已挂载时，
        // 同步物化它，使替换列表不会从仍在 250ms 合并窗口里等待的 projection 开始。
        // 预览与边界兜底已经拥有权威的 blockMarkdown，绝不能读取过期的 UI。
        val updatedSnapshot = captureVisualDraftThenReduceDocumentImport(
            event = event,
            sourceMode = sourceMode,
            previewMode = previewMode,
            forceBlockBoundary = forceBlockBoundary,
            visualActionsBound = blockController.embeddedAssetActionsBound,
            snapshot = embeddedAssetSnapshot,
            captureVisualDraft = { publishDraft(latestVisualMarkdown()) },
        )
        embeddedAssetSnapshot = updatedSnapshot
        if (!placeEmbeddedAssetReference(event, forceBlockBoundary)) return false
        val markdown = if (sourceMode) sourceMarkdown else blockMarkdown
        // 进度帧可能非常频繁，且不能让被阻塞的提交变为有效。
        // 只在 READY 描述符进入 sidecar 快照之后才调和。
        if (event is EmbeddedAssetImportEvent.Ready) {
            embeddedAssetError = reconcileDocumentEmbeddedAssetCommitError(
                currentError = embeddedAssetError,
                markdown = markdown,
                availableAssets = updatedSnapshot.assets,
                pendingJobs = updatedSnapshot.jobs,
            )
        }
        onUpdateDraft(
            DocumentDraftUpdate(
                tab.tabId,
                tab.instanceId,
                tab.revision,
                title,
                markdown,
                currentAssetManifest(markdown),
            ),
        )
        return true
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
        editorKey,
        sourceMode,
        previewMode,
        blockController.embeddedAssetActionsBound,
        deferredEmbeddedAssetEvents,
    ) {
        if (!canDrainDocumentImportReplay(
                sourceMode = sourceMode,
                previewMode = previewMode,
                visualActionsBound = blockController.embeddedAssetActionsBound,
                hasPendingEvents = deferredEmbeddedAssetEvents.isNotEmpty(),
            )
        ) {
            return@LaunchedEffect
        }
        val pending = deferredEmbeddedAssetEvents
        val remaining = drainDocumentImportReplayInOrder(pending, ::applyEmbeddedAssetImportEvent)
        if (remaining.size != pending.size) deferredEmbeddedAssetEvents = remaining
    }
    DisposableEffect(editorKey, embeddedAssetImports, embeddedAssetImportEnabled) {
        // 预览仍拥有它在可编辑期间启动的上传，因此 READY 可以解析 sidecar 与暂时性的
        // 保存错误。网关独立拒绝新的导入。
        val registration = embeddedAssetImports?.bind(
            ownerKey = embeddedAssetOwnerKey,
            sink = EmbeddedAssetImportEventSink { event ->
                if (shouldDeferDocumentImportEvent(
                        event = event,
                        sourceMode = sourceMode,
                        previewMode = previewMode,
                        visualActionsBound = blockController.embeddedAssetActionsBound,
                        hasDeferredPredecessor = deferredEmbeddedAssetEvents.isNotEmpty(),
                    )
                ) {
                    deferredEmbeddedAssetEvents = deferredEmbeddedAssetEvents + event
                } else if (!applyEmbeddedAssetImportEvent(event)) {
                    deferredEmbeddedAssetEvents = deferredEmbeddedAssetEvents + event
                }
            },
            acceptNewImports = embeddedAssetImportEnabled,
        )
        onDispose {
            registration?.close()
            // 网关已把这些帧交给该 owner。在预览切换或标签页退役丢弃编辑器本地
            // 光标队列之前物化它们。
            val pending = deferredEmbeddedAssetEvents
            deferredEmbeddedAssetEvents = drainDocumentImportReplayInOrder(pending) { event ->
                applyEmbeddedAssetImportEvent(event, forceBlockBoundary = true)
            }
        }
    }
    val draftCaptureHandle = remember(editorKey) {
        DocumentDraftCaptureHandle { captureLatestDraft() }
    }
    SideEffect { draftCaptureHandle.action = { captureLatestDraft() } }
    val stableDraftCapture = remember(editorKey) {
        { draftCaptureHandle.capture() }
    }
    DisposableEffect(editorKey, draftLifecycleBridge) {
        val lifecycleRegistration = draftLifecycleBridge.register(
            owner = DocumentDraftCaptureOwner.capture(tab),
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

    val toggleSourceMode = {
        if (sourceMode) {
            blockMarkdown = sourceMarkdown
            sourceMode = false
        } else {
            val latest = latestVisualMarkdown()
            publishDraft(latest)
            sourceMarkdown = latest
            sourceMode = true
        }
    }
    val togglePreviewMode = {
        if (!previewMode) {
            val latest = if (sourceMode) sourceMarkdown else latestVisualMarkdown()
            if (!sourceMode) publishDraft(latest)
        }
        previewMode = !previewMode
    }
    val saveDocument: () -> Unit = saveDocument@{
        val latest = if (sourceMode) sourceMarkdown else latestVisualMarkdown()
        val admission = admitDocumentEmbeddedAssetCommit(
            markdown = latest,
            availableAssets = embeddedAssetSnapshot.assets,
            pendingJobs = embeddedAssetSnapshot.jobs,
        )
        if (!admission.canCommit) {
            embeddedAssetError = if (EmbeddedAssetCommitBlocker.JOB_NOT_READY in admission.blockers) {
                DocumentEmbeddedAssetCommitError.UPLOAD_PENDING
            } else {
                DocumentEmbeddedAssetCommitError.INVALID_CONTENT
            }
            return@saveDocument
        }
        embeddedAssetError = null
        publishDraft(latest)
        onSave()
    }
    val requestMove = {
        val latest = captureLatestDraft()
        if (!latest.dirty && !tab.creating && !saving && !moving) onRequestMove(tab.instanceId)
    }
    val moveDisabledMessage = when {
        dirty -> "请先保存当前修改"
        saving -> "正在保存"
        moving -> "位置或名称变更等待确认"
        else -> null
    }
    val referencedAssetJobs = remember(currentMarkdown, embeddedAssetSnapshot.jobs) {
        referencedPendingAssetJobs(currentMarkdown, embeddedAssetSnapshot.jobs)
    }

    Column(modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compactHeader = maxWidth < 620.dp
            if (compactHeader) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DocumentTitleBlock(
                            title = title,
                            onTitleChange = { title = it },
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
                            )
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DocumentTitleBlock(
                        title = title,
                        onTitleChange = { title = it },
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
                    )
                    if (canEdit) DocumentSaveAction(
                        saving = saving,
                        enabled = title.isNotBlank() && !saving && !moving &&
                            (dirty || tab.creating || tab.remoteMissing),
                        compact = false,
                        saveAsNew = tab.remoteMissing,
                        onSave = saveDocument,
                    )
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
                DocumentBlockFormattingToolbar(
                    controller = blockController,
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                )
            } else {
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
        if (embeddedAssetImportEnabled && embeddedAssetImports != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { embeddedAssetImports.select(EmbeddedAssetPresentation.IMAGE) },
                    modifier = Modifier.testTag("documents.asset.pick.image"),
                ) { Text("插入图片") }
                TextButton(
                    onClick = { embeddedAssetImports.select(EmbeddedAssetPresentation.FILE) },
                    modifier = Modifier.testTag("documents.asset.pick.file"),
                ) { Text("插入文件") }
                onPasteEmbeddedAsset?.let { paste ->
                    TextButton(
                        onClick = { paste() },
                        modifier = Modifier.testTag("documents.asset.paste"),
                    ) { Text("粘贴") }
                }
            }
        }
        PendingAssetRows(
            jobs = referencedAssetJobs,
            testTagPrefix = "documents",
            onRetry = ::retryPendingAsset,
            onDiscard = ::discardPendingAsset,
        )
        embeddedAssetError?.let { error ->
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
                        assets = currentAssetManifest(currentMarkdown),
                        modifier = Modifier.fillMaxSize(),
                        onUrlClick = { url -> normalizeRichTextLink(url)?.let { runCatching { uriHandler.openUri(it) } } },
                        embeddedAssetContent = embeddedAssetContent,
                    )
                }
            } else if (sourceMode) {
                Box(Modifier.fillMaxSize()) {
                    BasicTextField(
                        value = sourceMarkdown,
                        onValueChange = { sourceMarkdown = it },
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
                    onMarkdownChange = { blockMarkdown = it },
                    assets = currentAssetManifest(blockMarkdown),
                    embeddedAssetContent = embeddedAssetContent,
                    modifier = Modifier.fillMaxSize()
                        .withEmbeddedAssetPasteShortcut()
                        .testTag("documents.editor.body"),
                )
            }
        }
    }
}

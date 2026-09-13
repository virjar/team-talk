package com.virjar.tk.app.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.virjar.tk.app.navigation.feature.document.DocumentDraftCaptureOwner
import com.virjar.tk.app.navigation.feature.document.DocumentDraftUpdate
import com.virjar.tk.app.navigation.feature.document.DocumentTabState
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportGateway
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSnapshot
import com.virjar.tk.app.ui.component.rich.DocumentBlockEditorController
import com.virjar.tk.app.ui.component.rich.EmbeddedAssetCommitBlocker
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.admitEmbeddedAssetCommit
import com.virjar.tk.app.ui.component.rich.embeddedAssetMarkdownReferences
import com.virjar.tk.app.ui.component.rich.projectEmbeddedAssetManifest
import com.virjar.tk.protocol.model.EmbeddedAsset

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

/**
 * 一个确切编辑器基线的内容和导入状态。Composable 创建并管理块 controller 的挂载；
 * 此处只借用它的同步快照和插入动作，不接管焦点或平台生命周期。
 */
internal class DocumentEditorSession(
    tab: DocumentTabState,
    private val blockController: DocumentBlockEditorController,
    onUpdateDraft: (DocumentDraftUpdate) -> Unit,
) {
    val owner = DocumentDraftCaptureOwner.capture(tab)
    val embeddedAssetOwnerKey = "document:${tab.instanceId}:${tab.recoveryId}"
    private val baselineTitle = tab.savedTitle
    private val baselineMarkdown = tab.savedMarkdown
    private val baselineAssets = tab.savedAssets
    private val initialMarkdown = tab.draftMarkdown
    private val creating = tab.creating
    private var publishUpdate = onUpdateDraft

    var title by mutableStateOf(tab.draftTitle)
    var blockMarkdown by mutableStateOf(tab.draftMarkdown)
    var sourceMarkdown by mutableStateOf(tab.draftMarkdown)
    var sourceMode by mutableStateOf(false)
        private set
    var editorReady by mutableStateOf(false)
        private set
    var dirty by mutableStateOf(tab.dirty || tab.creating)
        private set
    var embeddedAssetSnapshot by mutableStateOf(EmbeddedAssetImportSnapshot(assets = tab.draftAssets))
        private set
    var deferredEmbeddedAssetEvents by mutableStateOf<List<EmbeddedAssetImportEvent>>(emptyList())
        private set
    var embeddedAssetError by mutableStateOf<DocumentEmbeddedAssetCommitError?>(null)
        private set

    val currentMarkdown: String
        get() = if (!editorReady) initialMarkdown else if (sourceMode) sourceMarkdown else blockMarkdown

    /** 每个 session 保留自己的发布动作，旧编辑器退役时不会读取新标签的闭包。 */
    fun updateDraftPublisher(publisher: (DocumentDraftUpdate) -> Unit) {
        publishUpdate = publisher
    }

    fun beginInitialization() {
        editorReady = false
        blockMarkdown = initialMarkdown
        sourceMarkdown = initialMarkdown
        sourceMode = false
    }

    fun finishInitialization() {
        editorReady = true
    }

    fun currentAssetManifest(markdown: String): List<EmbeddedAsset> = runCatching {
        projectEmbeddedAssetManifest(markdown, embeddedAssetSnapshot.assets)
    }.getOrDefault(emptyList())

    fun publishCurrentDraftIfReady() {
        if (editorReady) publishSnapshot(currentMarkdown)
    }

    private fun publishSnapshot(markdown: String): DocumentEditorDraftSnapshot {
        val assets = currentAssetManifest(markdown)
        dirty = creating || title != baselineTitle || markdown != baselineMarkdown || assets != baselineAssets
        publishUpdate(
            DocumentDraftUpdate(owner.tabId, owner.instanceId, owner.revision, title, markdown, assets),
        )
        return DocumentEditorDraftSnapshot(title, markdown, dirty, assets)
    }

    private fun latestVisualMarkdown(): String {
        if (blockController.embeddedAssetActionsBound) return blockController.snapshotMarkdown(blockMarkdown)
        // clear 保存的最终输入只移交一次；重复捕获不能用这个旧帧覆盖之后追加的附件。
        blockController.takeRetiredMarkdown()?.let { blockMarkdown = it }
        return blockMarkdown
    }

    private fun publishDraft(markdown: String): DocumentEditorDraftSnapshot {
        blockMarkdown = markdown
        return publishSnapshot(markdown)
    }

    fun captureLatestDraft(): DocumentEditorDraftSnapshot {
        blockMarkdown = if (sourceMode) sourceMarkdown else latestVisualMarkdown()
        // 已绑定的视觉编辑器仍按光标放置；退出或尚未挂载时在正文边界物化收到的帧。
        drainEmbeddedAssetImports(
            previewMode = false,
            forceBlockBoundary = !blockController.embeddedAssetActionsBound,
        )
        return publishSnapshot(if (sourceMode) sourceMarkdown else blockMarkdown)
    }

    fun discardPendingAsset(job: PendingAssetJob, imports: EmbeddedAssetImportGateway?) =
        discardDocumentPendingAsset(
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
            cancelUpload = { imports?.cancel(it) },
            reportInvalidContent = { embeddedAssetError = DocumentEmbeddedAssetCommitError.INVALID_CONTENT },
        )

    fun retryPendingAsset(job: PendingAssetJob, imports: EmbeddedAssetImportGateway?) {
        if (imports?.retry(job.jobId) == true) {
            if (embeddedAssetError == DocumentEmbeddedAssetCommitError.RETRY_UNAVAILABLE) {
                embeddedAssetError = null
            }
        } else {
            embeddedAssetError = DocumentEmbeddedAssetCommitError.RETRY_UNAVAILABLE
        }
    }

    private fun placeEmbeddedAssetReference(
        event: EmbeddedAssetImportEvent,
        previewMode: Boolean,
        forceBlockBoundary: Boolean,
    ): Boolean {
        val markdown = when {
            sourceMode -> sourceMarkdown
            previewMode || forceBlockBoundary -> blockMarkdown
            else -> latestVisualMarkdown()
        }
        when (
            val placement = placeDocumentEmbeddedAssetReference(
                event = event,
                currentMarkdown = markdown,
                sourceMode = sourceMode,
                previewMode = previewMode,
                forceBlockBoundary = forceBlockBoundary,
            ) ?: return true
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

    private fun applyEmbeddedAssetImportEvent(
        event: EmbeddedAssetImportEvent,
        previewMode: Boolean,
        forceBlockBoundary: Boolean = false,
    ): Boolean {
        if (!sourceMode && !blockController.embeddedAssetActionsBound) latestVisualMarkdown()
        // READY 改变 sidecar 后会重建视觉块；先物化仍在 250ms 合并窗口内的输入。
        // 预览和退役时的边界兜底已有权威正文，不读取已卸载的视觉编辑器。
        embeddedAssetSnapshot = captureVisualDraftThenReduceDocumentImport(
            event = event,
            sourceMode = sourceMode,
            previewMode = previewMode,
            forceBlockBoundary = forceBlockBoundary,
            visualActionsBound = blockController.embeddedAssetActionsBound,
            snapshot = embeddedAssetSnapshot,
            captureVisualDraft = { publishDraft(latestVisualMarkdown()) },
        )
        if (!placeEmbeddedAssetReference(event, previewMode, forceBlockBoundary)) return false
        val markdown = if (sourceMode) sourceMarkdown else blockMarkdown
        // 进度不能解除保存屏障；只有 READY 的描述符进入 sidecar 后才调和。
        if (event is EmbeddedAssetImportEvent.Ready) {
            embeddedAssetError = reconcileDocumentEmbeddedAssetCommitError(
                embeddedAssetError, markdown, embeddedAssetSnapshot.assets, embeddedAssetSnapshot.jobs,
            )
        }
        publishSnapshot(markdown)
        return true
    }

    fun acceptEmbeddedAssetImport(event: EmbeddedAssetImportEvent, previewMode: Boolean) {
        if (shouldDeferDocumentImportEvent(
                event = event,
                sourceMode = sourceMode,
                previewMode = previewMode,
                visualActionsBound = blockController.embeddedAssetActionsBound,
                hasDeferredPredecessor = deferredEmbeddedAssetEvents.isNotEmpty(),
            ) || !applyEmbeddedAssetImportEvent(event, previewMode)
        ) {
            deferredEmbeddedAssetEvents = deferredEmbeddedAssetEvents + event
        }
    }

    fun drainEmbeddedAssetImports(previewMode: Boolean, forceBlockBoundary: Boolean = false) {
        if (!forceBlockBoundary && !canDrainDocumentImportReplay(
                sourceMode = sourceMode,
                previewMode = previewMode,
                visualActionsBound = blockController.embeddedAssetActionsBound,
                hasPendingEvents = deferredEmbeddedAssetEvents.isNotEmpty(),
            )
        ) return
        deferredEmbeddedAssetEvents = drainDocumentImportReplayInOrder(deferredEmbeddedAssetEvents) { event ->
            applyEmbeddedAssetImportEvent(event, previewMode, forceBlockBoundary)
        }
    }

    fun toggleSourceMode() {
        if (sourceMode) {
            // 旧视觉帧早于这轮源码编辑；重新挂载前也不能让它覆盖已编辑的源码。
            blockController.takeRetiredMarkdown()
            blockMarkdown = sourceMarkdown
            sourceMode = false
        } else {
            val latest = latestVisualMarkdown()
            publishDraft(latest)
            sourceMarkdown = latest
            sourceMode = true
        }
    }

    /** 预览默认值属于平台展示策略；进入预览前仍要同步捕获视觉稿。 */
    fun prepareForPreview() {
        if (!sourceMode) captureLatestDraft()
    }

    fun prepareSave(): Boolean {
        val latest = captureLatestDraft().markdown
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
            return false
        }
        embeddedAssetError = null
        return true
    }
}

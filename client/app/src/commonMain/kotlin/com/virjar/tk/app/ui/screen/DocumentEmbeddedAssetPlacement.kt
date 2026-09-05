package com.virjar.tk.app.ui.screen

import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSnapshot
import com.virjar.tk.app.ui.bridge.markdownPlacementOrNull
import com.virjar.tk.app.ui.bridge.reduce
import com.virjar.tk.app.ui.component.rich.embeddedAssetMarkdown
import com.virjar.tk.app.ui.component.rich.embeddedAssetMarkdownReferences

/** 平台的拖放/粘贴与选择器上传只有在编辑器可写时才能修改草稿。 */
internal fun documentEmbeddedAssetImportEnabled(
    canEdit: Boolean,
    previewMode: Boolean,
): Boolean = canEdit && !previewMode

internal sealed class DocumentEmbeddedAssetPlacement(
    val resultingSourceMode: Boolean,
) {
    class VisualSelection(
        val assetId: String,
        val syntax: String,
    ) : DocumentEmbeddedAssetPlacement(resultingSourceMode = false)

    class BoundaryAppend(
        val markdown: String,
        resultingSourceMode: Boolean,
    ) : DocumentEmbeddedAssetPlacement(resultingSourceMode)
}

internal fun placeDocumentEmbeddedAssetReference(
    event: EmbeddedAssetImportEvent,
    currentMarkdown: String,
    sourceMode: Boolean,
    previewMode: Boolean = false,
    forceBlockBoundary: Boolean = false,
): DocumentEmbeddedAssetPlacement? {
    // READY 只解析 sidecar。它绝不能复活用户已移除的语法。
    val placement = event.markdownPlacementOrNull() ?: return null
    val alreadyPlaced = runCatching {
        embeddedAssetMarkdownReferences(currentMarkdown).any { it.assetId == event.job.assetId }
    }.getOrDefault(false)
    if (alreadyPlaced) return null
    val syntax = embeddedAssetMarkdown(event.job.assetId, placement.presentation, placement.label)
    if (!sourceMode && !previewMode && !forceBlockBoundary) {
        return DocumentEmbeddedAssetPlacement.VisualSelection(event.job.assetId, syntax)
    }
    val separator = when {
        currentMarkdown.isEmpty() -> ""
        currentMarkdown.endsWith("\n\n") -> ""
        currentMarkdown.endsWith('\n') -> "\n"
        else -> "\n\n"
    }
    return DocumentEmbeddedAssetPlacement.BoundaryAppend(
        markdown = currentMarkdown + separator + syntax,
        resultingSourceMode = sourceMode,
    )
}

/**
 * 排空临时的编辑器本地重放队列，且不越过第一个不可用的条目。
 * 返回的后缀可以在视觉块 controller 完成绑定后重试。
 */
internal fun <T> drainDocumentImportReplayInOrder(
    pending: List<T>,
    consume: (T) -> Boolean,
): List<T> {
    pending.forEachIndexed { index, item ->
        if (!consume(item)) return pending.subList(index, pending.size).toList()
    }
    return emptyList()
}

internal fun shouldCaptureVisualDraftBeforeImportReduction(
    event: EmbeddedAssetImportEvent,
    sourceMode: Boolean,
    previewMode: Boolean,
    forceBlockBoundary: Boolean,
    visualActionsBound: Boolean,
): Boolean = event is EmbeddedAssetImportEvent.Ready && !sourceMode && !previewMode &&
    !forceBlockBoundary && visualActionsBound

internal fun captureVisualDraftThenReduceDocumentImport(
    event: EmbeddedAssetImportEvent,
    sourceMode: Boolean,
    previewMode: Boolean,
    forceBlockBoundary: Boolean,
    visualActionsBound: Boolean,
    snapshot: EmbeddedAssetImportSnapshot,
    captureVisualDraft: () -> Unit,
): EmbeddedAssetImportSnapshot {
    if (
        shouldCaptureVisualDraftBeforeImportReduction(
            event = event,
            sourceMode = sourceMode,
            previewMode = previewMode,
            forceBlockBoundary = forceBlockBoundary,
            visualActionsBound = visualActionsBound,
        )
    ) {
        captureVisualDraft()
    }
    return snapshot.reduce(event)
}

internal fun shouldDeferDocumentImportEvent(
    event: EmbeddedAssetImportEvent,
    sourceMode: Boolean,
    previewMode: Boolean,
    visualActionsBound: Boolean,
    hasDeferredPredecessor: Boolean,
): Boolean = hasDeferredPredecessor || (
    !sourceMode && !previewMode && event.markdownPlacementOrNull() != null && !visualActionsBound
)

internal fun canDrainDocumentImportReplay(
    sourceMode: Boolean,
    previewMode: Boolean,
    visualActionsBound: Boolean,
    hasPendingEvents: Boolean,
): Boolean = hasPendingEvents && (sourceMode || previewMode || visualActionsBound)

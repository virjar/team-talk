package com.virjar.tk.app.ui.screen

import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.removeEmbeddedAssetReferences

/** 让文档草稿发布先于尽力而为的平台上传取消。 */
internal fun discardDocumentPendingAsset(
    job: PendingAssetJob,
    markdown: String,
    updateEditor: (String) -> Unit,
    publishDraft: (String) -> Unit,
    reconcileError: (String) -> Unit,
    cancelUpload: (String) -> Unit,
    reportInvalidContent: () -> Unit,
) {
    val updated = runCatching {
        removeEmbeddedAssetReferences(markdown, job.assetId)
    }.getOrElse {
        reportInvalidContent()
        return
    }
    if (updated != markdown) updateEditor(updated)
    publishDraft(updated)
    reconcileError(updated)
    cancelUpload(job.jobId)
}

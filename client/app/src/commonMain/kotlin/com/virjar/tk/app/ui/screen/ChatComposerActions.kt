package com.virjar.tk.app.ui.screen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportGateway
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.canUseChatVisualEditor
import com.virjar.tk.app.ui.component.rich.removeEmbeddedAssetReferences

/** 应用一次模式切换，而不让 [ChatScreen] 拥有编辑器转换细节。 */
internal fun changeChatComposerMode(
    target: ChatComposerMode,
    voiceMode: Boolean,
    currentMode: ChatComposerMode,
    markdown: String,
    sourceInput: TextFieldValue,
    enterVisualMarkdown: (String) -> Unit,
    updateSourceInput: (TextFieldValue) -> Unit,
    updateMode: (ChatComposerMode) -> Unit,
    requestFocusRestore: () -> Unit,
    hideEmoji: () -> Unit,
) {
    if (voiceMode || target == currentMode) return
    when (target) {
        ChatComposerMode.VISUAL -> {
            if (!canUseChatVisualEditor(markdown)) return
            enterVisualMarkdown(markdown)
            requestFocusRestore()
        }
        ChatComposerMode.MARKDOWN -> {
            updateSourceInput(TextFieldValue(markdown, TextRange(markdown.length)))
            updateMode(ChatComposerMode.MARKDOWN)
            requestFocusRestore()
        }
        ChatComposerMode.PREVIEW -> {
            updateSourceInput(
                TextFieldValue(
                    markdown,
                    TextRange(
                        sourceInput.selection.start.coerceIn(0, markdown.length),
                        sourceInput.selection.end.coerceIn(0, markdown.length),
                    ),
                ),
            )
            updateMode(ChatComposerMode.PREVIEW)
            hideEmoji()
        }
    }
}

/** 先移除权威引用，再停止其尽力而为的平台上传。 */
internal fun discardChatPendingAsset(
    job: PendingAssetJob,
    markdown: String,
    sourceInput: TextFieldValue,
    editingSessionActive: Boolean,
    updateEditor: (TextFieldValue) -> Unit,
    persistSessionContext: () -> Unit,
    persistOrdinaryDraft: (String) -> Unit,
    publishUserTextChange: () -> Unit,
    cancelUpload: (String) -> Unit,
    reportError: (String) -> Unit,
) {
    val updatedInput = runCatching {
        if (sourceInput.text == markdown) {
            sourceInput.removeEmbeddedAssetReferences(job.assetId)
        } else {
            val updated = removeEmbeddedAssetReferences(markdown, job.assetId)
            TextFieldValue(updated, TextRange(updated.length))
        }
    }.getOrElse {
        reportError("无法从草稿移除附件，请检查 Markdown 内容")
        return
    }
    if (updatedInput.text == markdown) {
        persistSessionContext()
        if (!editingSessionActive) persistOrdinaryDraft(markdown)
        cancelUpload(job.jobId)
        return
    }

    updateEditor(updatedInput)
    persistSessionContext()
    if (!editingSessionActive) persistOrdinaryDraft(updatedInput.text)
    publishUserTextChange()
    cancelUpload(job.jobId)
}

/** 让平台重试查找及其面向用户的兜底留在已经很拥挤的面板之外。 */
internal fun retryChatPendingAsset(
    job: PendingAssetJob,
    imports: EmbeddedAssetImportGateway?,
    reportError: (String) -> Unit,
) {
    if (imports?.retry(job.jobId) != true) {
        reportError("本地附件已不可用于重试，请移除后重新选择")
    }
}

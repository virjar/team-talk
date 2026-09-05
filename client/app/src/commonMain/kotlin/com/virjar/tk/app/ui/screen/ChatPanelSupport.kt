package com.virjar.tk.app.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mohamedrejeb.richeditor.model.RichTextState
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.body.RichTextBody
import com.virjar.tk.protocol.body.buildRichTextBody
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.telemetry.ClientUiAction
import com.virjar.tk.app.telemetry.ClientUiPage
import com.virjar.tk.app.telemetry.FeedbackOrigin
import com.virjar.tk.app.telemetry.UserFeedbackCode
import com.virjar.tk.app.telemetry.UserFeedbackReporter
import com.virjar.tk.app.ui.AdmittedFileDownloadController
import com.virjar.tk.app.ui.AdmittedVoicePlaybackController
import com.virjar.tk.app.ui.UiActionAdmission
import com.virjar.tk.app.ui.bridge.ChatMediaConfig
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportEvent
import com.virjar.tk.app.ui.bridge.EmbeddedAssetImportSnapshot
import com.virjar.tk.app.ui.bridge.markdownPlacementOrNull
import com.virjar.tk.app.ui.component.VoicePlaybackController
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.app.ui.component.rich.ChatVisualMarkdownBaseline
import com.virjar.tk.app.ui.component.rich.EmbeddedAssetCommitBlocker
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import com.virjar.tk.app.ui.component.rich.admitEmbeddedAssetCommit
import com.virjar.tk.app.ui.component.rich.embeddedAssetMarkdown
import com.virjar.tk.app.ui.component.rich.embeddedAssetMarkdownReferences
import com.virjar.tk.app.ui.component.rich.projectEmbeddedAssetManifest
import com.virjar.tk.app.ui.component.rich.replaceComposerRange
import com.virjar.tk.app.viewmodel.ChatViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@Composable
internal fun rememberAdmittedChatMedia(
    media: ChatMediaConfig,
    actionAdmission: UiActionAdmission,
): ChatMediaConfig = remember(media, actionAdmission) {
    media.copy(
        onPickVideo = media.onPickVideo?.let { actionAdmission.guard(it) },
        onVoiceRecord = media.onVoiceRecord?.let { actionAdmission.guard(it) },
        onVoiceModeEntered = media.onVoiceModeEntered?.let { actionAdmission.guard(it) },
        onVoiceRecordCancel = media.onVoiceRecordCancel?.let { actionAdmission.guard(it) },
        onMediaClick = media.onMediaClick?.let { actionAdmission.guard(it) },
        onEmbeddedMediaClick = media.onEmbeddedMediaClick?.let { click ->
            { message, asset -> actionAdmission.runIfOpen { click(message, asset) } }
        },
        onMentionClick = media.onMentionClick?.let { actionAdmission.guard(it) },
        onUrlClick = media.onUrlClick?.let { actionAdmission.guard(it) },
        onPasteEmbeddedAsset = media.onPasteEmbeddedAsset?.let { paste ->
            {
                var consumed = false
                actionAdmission.runIfOpen { consumed = paste() }
                consumed
            }
        },
        fileDownloads = AdmittedFileDownloadController(media.fileDownloads, actionAdmission),
    )
}

@Composable
internal fun rememberAdmittedVoicePlayback(
    voicePlayback: VoicePlaybackController,
    actionAdmission: UiActionAdmission,
): VoicePlaybackController = remember(voicePlayback, actionAdmission) {
    AdmittedVoicePlaybackController(voicePlayback, actionAdmission)
}

@Composable
internal fun ChatReadEffects(
    viewModel: ChatViewModel,
    chatId: String,
    messages: List<Message>,
    readReceiptsEnabled: Boolean,
    messageListState: LazyListState,
    hasMore: Boolean,
    loading: Boolean,
    suppressHistoryPaging: Boolean = false,
    actionAdmission: UiActionAdmission,
) {
    val latestVisibleServerSeq = messages.maxOfOrNull(Message::serverSeq)?.coerceAtLeast(0L) ?: 0L
    val visibleReadTarget = visibleChatReadTarget(readReceiptsEnabled, latestVisibleServerSeq)
    LaunchedEffect(viewModel, chatId, visibleReadTarget) {
        visibleReadTarget?.let { target ->
            actionAdmission.runIfOpen { viewModel.markRead(target) }
        }
    }
    LaunchedEffect(messageListState, messages.size, hasMore, loading, suppressHistoryPaging) {
        snapshotFlow { messageListState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1 }
            .map { lastVisible ->
                !loading && messages.isNotEmpty() && hasMore &&
                    !suppressHistoryPaging &&
                    lastVisible >= (messages.lastIndex - 1).coerceAtLeast(0)
            }
            .distinctUntilChanged()
            .filter { it }
            .collect { actionAdmission.runIfOpen(viewModel::loadOlder) }
    }
}

@Composable
internal fun ChatFeedbackEffect(
    error: String?,
    feedbackReporter: UserFeedbackReporter,
    snackbarHostState: SnackbarHostState,
    actionAdmission: UiActionAdmission,
    clearError: () -> Unit,
) {
    LaunchedEffect(error) {
        error?.let {
            val visibleMessage = feedbackReporter.displayed(
                feedbackCode = UserFeedbackCode.forDisplayedMessage(error),
                page = ClientUiPage.CHAT,
                action = ClientUiAction.SHOW_FEEDBACK,
                origin = FeedbackOrigin.SNACKBAR,
            )
            snackbarHostState.showSnackbar(visibleMessage, duration = SnackbarDuration.Short)
            actionAdmission.runIfOpen(clearError)
        }
    }
}

@Composable
internal fun ChatFileDownloadEffect(
    messages: List<Message>,
    media: ChatMediaConfig,
    actionAdmission: UiActionAdmission,
) {
    val fileDownloads = media.fileDownloads
    LaunchedEffect(fileDownloads, messages) {
        val attachments = messages.asSequence()
            .filter { it.sendStatus != Message.SEND_STATUS_UPLOADING }
            .mapNotNull { (it.body as? FileBody)?.attachment }
            .filter { it.path.isNotBlank() }
            .distinctBy { it.path }
            .toList()
        actionAdmission.runIfOpen { attachments.forEach(fileDownloads::ensure) }
        snapshotFlow { attachments.map { attachment -> fileDownloads.states[attachment.path] } }
            .collect { states ->
                actionAdmission.runIfOpen {
                    attachments.forEachIndexed { index, attachment ->
                        if (fileDownloads.automaticDownloadLedger.claim(attachment, states[index])) {
                            fileDownloads.download(attachment)
                        }
                    }
                }
            }
    }
}

internal sealed class ChatEmbeddedAssetPlacement(
    val resultingMode: ChatComposerMode,
) {
    /** 在富文本编辑器当前视觉选区插入规范的资源 Markdown。 */
    class VisualSelection(val syntax: String) : ChatEmbeddedAssetPlacement(ChatComposerMode.VISUAL)

    /** 发布完整的源码编辑器值，并继续留在 Markdown 模式。 */
    class SourceInput(val sourceInput: TextFieldValue) :
        ChatEmbeddedAssetPlacement(ChatComposerMode.MARKDOWN)
}

internal fun applyChatEmbeddedAssetPlacement(
    placement: ChatEmbeddedAssetPlacement,
    richState: RichTextState,
    updateSourceInput: (TextFieldValue) -> Unit,
): ChatComposerMode {
    when (placement) {
        is ChatEmbeddedAssetPlacement.VisualSelection ->
            richState.insertMarkdownAfterSelection(placement.syntax)
        is ChatEmbeddedAssetPlacement.SourceInput -> updateSourceInput(placement.sourceInput)
    }
    return placement.resultingMode
}

internal fun placeChatEmbeddedAssetReference(
    event: EmbeddedAssetImportEvent,
    currentMarkdown: String,
    sourceInput: TextFieldValue,
    composerMode: ChatComposerMode,
): ChatEmbeddedAssetPlacement? {
    val placement = event.markdownPlacementOrNull() ?: return null
    val alreadyPlaced = runCatching {
        embeddedAssetMarkdownReferences(currentMarkdown).any { it.assetId == event.job.assetId }
    }.getOrDefault(false)
    if (alreadyPlaced) return null

    val syntax = embeddedAssetMarkdown(
        assetId = event.job.assetId,
        presentation = placement.presentation,
        label = placement.label,
    )
    return when (composerMode) {
        ChatComposerMode.VISUAL -> ChatEmbeddedAssetPlacement.VisualSelection(syntax)
        ChatComposerMode.MARKDOWN -> ChatEmbeddedAssetPlacement.SourceInput(
            sourceInput.replaceComposerRange(
                sourceInput.selection.min,
                sourceInput.selection.max,
                syntax,
            ),
        )
        ChatComposerMode.PREVIEW -> {
            // 预览模式没有光标。保留其既有行为：在可读的块边界处追加并回到源码模式，
            // 使进行中的选择器结果绝不会被吞掉。
            val separator = when {
                currentMarkdown.isEmpty() -> ""
                currentMarkdown.endsWith("\n\n") -> ""
                currentMarkdown.endsWith('\n') -> "\n"
                else -> "\n\n"
            }
            val updated = currentMarkdown + separator + syntax
            ChatEmbeddedAssetPlacement.SourceInput(TextFieldValue(updated, TextRange(updated.length)))
        }
    }
}

internal fun canonicalizeChatMessageOrReport(
    message: Message,
    reportError: (String) -> Unit,
): Message? = try {
    canonicalizeChatMessageForSend(message)
} catch (error: IllegalArgumentException) {
    reportError("发送失败: ${error.message ?: "消息内容不合法"}")
    null
}

internal fun admitChatEmbeddedAssetsOrReport(
    markdown: String,
    snapshot: EmbeddedAssetImportSnapshot,
    reportError: (String) -> Unit,
): List<EmbeddedAsset>? {
    val references = try {
        embeddedAssetMarkdownReferences(markdown)
    } catch (error: IllegalArgumentException) {
        reportError("发送失败: ${error.message ?: "内嵌资产引用不合法"}")
        return null
    }
    val referencedIds = references.mapNotNull { it.assetId }.toSet()
    val manifest = projectEmbeddedAssetManifest(markdown, snapshot.assets)
    val admission = admitEmbeddedAssetCommit(
        markdown = markdown,
        manifestAssetIds = manifest.map(EmbeddedAsset::assetId),
        pendingJobs = snapshot.jobs.filter { it.assetId in referencedIds },
    )
    if (!admission.canCommit) {
        val referencedJobs = snapshot.jobs.filter { it.assetId in referencedIds }
        val uploadStopped = referencedJobs.any {
            it.state == PendingAssetJobState.FAILED || it.state == PendingAssetJobState.CANCELLED
        }
        val waiting = !uploadStopped &&
            admission.blockers.any { it == EmbeddedAssetCommitBlocker.JOB_NOT_READY }
        reportError(
            when {
                uploadStopped -> UserFeedbackCode.MEDIA_UPLOAD_FAILED.publicMessage
                waiting -> UserFeedbackCode.CHAT_ASSET_UPLOAD_PENDING.publicMessage
                else -> "发送失败: 内嵌资产引用与清单不一致"
            },
        )
        return null
    }
    return manifest
}

internal fun buildChatRichTextBodyOrReport(
    markdown: String,
    assets: List<EmbeddedAsset>,
    reportError: (String) -> Unit,
): RichTextBody? = try {
    buildRichTextBody(markdown, assets)
} catch (error: IllegalArgumentException) {
    reportError("发送失败: ${error.message ?: "富文本内容不合法"}")
    null
}

/**
 * 选择编辑器最终渲染帧所代表的持久普通草稿。
 *
 * 编辑已发送消息会临时替换可见的编辑器正文，因此其已保存的
 * [SavedChatEditingSession.suspendedMarkdown] 保持权威。在富文本编辑器水合之前，
 * 可保存的源码镜像才是唯一完整帧。[visualMarkdown] 保持惰性，使两个分支都不会
 * 意外读取部分销毁的富文本编辑器。
 */
internal fun finalChatDraftSnapshot(
    editingSession: SavedChatEditingSession,
    composerReady: Boolean,
    preHydrationSource: String,
    composerMode: ChatComposerMode,
    visualMarkdown: () -> String,
    sourceMarkdown: String,
): String = when {
    editingSession.editingClientMsgId.isNotEmpty() -> editingSession.suspendedMarkdown
    !composerReady -> preHydrationSource
    composerMode == ChatComposerMode.VISUAL -> visualMarkdown()
    else -> sourceMarkdown
}

internal val ChatVisualMarkdownBaselineSaver = listSaver<ChatVisualMarkdownBaseline, Any>(
    save = { baseline -> listOf(baseline.originalMarkdown, baseline.normalizedMarkdown) },
    restore = { values ->
        ChatVisualMarkdownBaseline(
            originalMarkdown = values[0] as String,
            normalizedMarkdown = values[1] as String,
        )
    },
)

/**
 * 组装会话级正文展示上下文：发送者解析、正文导航、媒体交互与渲染槽收敛为单一对象，
 * 整个会话内随输入保持稳定，由 [ChatPanel] 构造一次并下传消息列表与气泡。
 */
@Composable
internal fun rememberMessageContentContext(
    resolveSender: ((uid: String) -> User?)?,
    admittedVoicePlayback: VoicePlaybackController,
    admittedMedia: ChatMediaConfig,
): MessageContentContext = remember(resolveSender, admittedVoicePlayback, admittedMedia) {
    MessageContentContext(
        resolveSender = resolveSender,
        voicePlayback = admittedVoicePlayback,
        onMentionClick = admittedMedia.onMentionClick,
        onUrlClick = admittedMedia.onUrlClick,
        onMediaClick = admittedMedia.onMediaClick,
        onEmbeddedMediaClick = admittedMedia.onEmbeddedMediaClick,
        imageContent = admittedMedia.imageContent,
    )
}

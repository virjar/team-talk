package com.virjar.tk.app.ui.screen

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mohamedrejeb.richeditor.model.RichTextState
import com.virjar.tk.protocol.body.MarkdownAssetPolicy
import com.virjar.tk.protocol.body.MessageBodyPolicy
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.protocol.model.User
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal fun visibleChatReadTarget(
    readReceiptsEnabled: Boolean,
    latestVisibleServerSeq: Long,
): Long? = latestVisibleServerSeq.takeIf { readReceiptsEnabled && it > 0L }

/** 清理聊天正文时同步丢弃撤销/重做历史，避免撤销恢复已发送或已取消的内容。 */
internal fun resetChatComposerState(state: RichTextState) {
    state.clear()
    state.history.clear()
}

/** UI 发送前与 SDK/服务端执行同一份消息类型、正文和资源预算校验。 */
internal fun canonicalizeChatMessageForSend(message: Message): Message =
    MessageBodyPolicy.canonicalize(message)

/**
 * 持久会话草稿后端目前只存储一个 Markdown 字符串。在它能够原子持久化带作用域的
 * 描述符 sidecar 之前，绝不要把裸内部 URI 镜像进 SQLite 或跨设备草稿流。完整正文在
 * 已认证客户端会话的整个生命周期内仍可从 ChatComposerContextStore 获得。
 */
internal fun durableChatDraftMirrorPayload(markdown: String): String =
    runCatching {
        markdown.takeIf { MarkdownAssetPolicy.references(it).isEmpty() }.orEmpty()
    }.getOrDefault("")

/**
 * 输入框的普通草稿同步。缓存观察值判断能否替换正文，发布值只控制防抖/离开补写的去重。
 * 两者不能合并：onDraftChange 只是异步入队，不代表 LocalCache 已完成写入。
 * 只随现有 UI context 在导航间续存，不新增持久状态或复制到 Activity Bundle。
 */
internal class ChatDraftSync(cachedDraft: String?, restored: ChatComposerContext?) {
    var previousCachedDraft: String? = if (restored == null) cachedDraft else restored.previousCachedDraft
        private set
    var lastPublishedDraft: String = restored?.lastPublishedDraft ?: cachedDraft.orEmpty()
        private set

    /** null 表示不替换；空字符串表示明确清空。回复、消息编辑、富资产仍由本机上下文拥有。 */
    fun receive(cachedDraft: String?, currentMarkdown: String, hasLocalContext: Boolean): String? {
        if (cachedDraft == null || cachedDraft == previousCachedDraft) return null
        val replace = !hasLocalContext && currentMarkdown == previousCachedDraft.orEmpty()
        previousCachedDraft = cachedDraft
        if (!replace) return null
        lastPublishedDraft = cachedDraft
        return cachedDraft
    }

    /** 发送后的清空必须提交，即使用户在防抖完成前已发送正文。 */
    fun publish(markdown: String, onDraftChange: ((String) -> Unit)?, force: Boolean = false) {
        if (!force && markdown == lastPublishedDraft) return
        onDraftChange?.invoke(durableChatDraftMirrorPayload(markdown))
        lastPublishedDraft = markdown
    }
}

/**
 * Compose 只在点击回调返回之后才重新绑定导入 owner。当挂起的草稿仍引用未完成的上传时，
 * 不要进入临时编辑上下文：否则该 apply 间隙里的 READY 帧会被旧 sink 消费，
 * 并被编辑水合覆盖。
 */
internal fun hasReferencedIncompleteEmbeddedAssetJob(
    markdown: String,
    jobs: List<PendingAssetJob>,
): Boolean = runCatching {
    val referencedIds = MarkdownAssetPolicy.references(markdown)
        .mapNotNull { it.assetId }
        .toSet()
    jobs.any { job ->
        job.assetId in referencedIds &&
            job.state != PendingAssetJobState.READY &&
            job.state != PendingAssetJobState.CANCELLED
    }
}.getOrDefault(true)

/** 回复协议只引用服务端序号；本地临时 clientMsgId 不得进入 replyToMsgId。 */
internal fun Message.confirmedReplyToMsgIdOrNull(): String? =
    serverSeq.takeIf { it > 0L }?.toString()

/** Activity 重建只保存稳定标识；Message 实例始终来自当前会话的 messages 流。 */
internal data class SavedChatReplyTarget(val clientMsgId: String = "") {
    internal fun bind(messages: List<Message>): Message? =
        clientMsgId.takeIf(String::isNotEmpty)?.let { targetId ->
            messages.firstOrNull { it.clientMsgId == targetId }
        }
}

internal val SavedChatReplyTargetSaver = listSaver<SavedChatReplyTarget, String>(
    save = { target -> listOf(target.clientMsgId) },
    restore = { values -> SavedChatReplyTarget(values.firstOrNull().orEmpty()) },
)

/**
 * 编辑已发消息时的可恢复会话。这里只保存平台 Saver 支持的稳定值；目标消息和回复消息
 * 均用 clientMsgId 在当前消息流中重新绑定，Activity 重建不会把被编辑正文误当普通草稿。
 */
internal data class SavedChatEditingSession(
    val editingClientMsgId: String = "",
    val targetLoaded: Boolean = false,
    /**
     * 仅对本次编辑尝试稳定。普通草稿与编辑尝试启动的内嵌资源上传必须使用不同的 owner，
     * 否则迟到的 READY 帧可能被错误的编辑器上下文消费，使挂起的草稿缺少其描述符。
     */
    val assetImportOwnerId: String = "",
    val suspendedMarkdown: String = "",
    val suspendedMode: ChatComposerMode = ChatComposerMode.VISUAL,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val replyingClientMsgId: String = "",
    val suspendedAssets: List<EmbeddedAsset> = emptyList(),
)

internal fun chatEmbeddedAssetImportOwnerKey(
    chatId: String,
    editingSession: SavedChatEditingSession,
): String = if (editingSession.editingClientMsgId.isEmpty()) {
    "chat:$chatId:draft"
} else {
    val editAttemptId = editingSession.assetImportOwnerId.ifEmpty {
        // 对内部畸形的恢复状态失败关闭（fail closed），且不会回退到普通草稿 owner。
        // 新的编辑尝试总是携带随机 owner id。
        "invalid-${editingSession.editingClientMsgId}"
    }
    "chat:$chatId:edit:$editAttemptId"
}

/** 在限制每个内联 SavedState 字符串的同时，保持常规旋转恢复的便利性。 */
internal const val MAX_CHAT_SAVED_STATE_INLINE_TEXT_LENGTH = 8_192

internal fun chatSourceInputSaver(chatId: String, store: ChatComposerContextStore) =
    listSaver<TextFieldValue, Any>(
        save = { value ->
            val inline = value.text.length <= MAX_CHAT_SAVED_STATE_INLINE_TEXT_LENGTH
            val textOrToken = if (inline) {
                store.discardText(chatId, RetainedTextSlot.SOURCE_INPUT)
                value.text
            } else {
                store.retainText(chatId, RetainedTextSlot.SOURCE_INPUT, value.text)
            }
            listOf(inline, textOrToken, value.selection.start, value.selection.end)
        },
        restore = { values ->
            val inline = values[0] as Boolean
            val textOrToken = values[1] as String
            val text = if (inline) {
                textOrToken
            } else {
                store.restoreText(chatId, RetainedTextSlot.SOURCE_INPUT, textOrToken)
                    ?: store.restore(chatId)?.markdown.orEmpty()
            }
            TextFieldValue(
                text = text,
                selection = TextRange(
                    (values[2] as Int).coerceIn(0, text.length),
                    (values[3] as Int).coerceIn(0, text.length),
                ),
            )
        },
    )

internal fun savedChatEditingSessionSaver(chatId: String, store: ChatComposerContextStore) =
    listSaver<SavedChatEditingSession, Any>(
        save = { session ->
            val inline = session.suspendedMarkdown.length <= MAX_CHAT_SAVED_STATE_INLINE_TEXT_LENGTH
            val textOrToken = if (inline) {
                store.discardText(chatId, RetainedTextSlot.SUSPENDED_DRAFT)
                session.suspendedMarkdown
            } else {
                store.retainText(chatId, RetainedTextSlot.SUSPENDED_DRAFT, session.suspendedMarkdown)
            }
            val assetsPayload = ChatEmbeddedAssetSaverJson.encodeToString(
                ListSerializer(EmbeddedAsset.serializer()),
                session.suspendedAssets,
            )
            val assetsInline = assetsPayload.length <= MAX_CHAT_SAVED_STATE_INLINE_TEXT_LENGTH
            val assetsOrToken = if (assetsInline) {
                store.discardText(chatId, RetainedTextSlot.SUSPENDED_ASSETS)
                assetsPayload
            } else {
                store.retainText(chatId, RetainedTextSlot.SUSPENDED_ASSETS, assetsPayload)
            }
            listOf(
                session.editingClientMsgId,
                session.targetLoaded,
                session.assetImportOwnerId,
                inline,
                textOrToken,
                session.suspendedMode.name,
                session.selectionStart,
                session.selectionEnd,
                session.replyingClientMsgId,
                assetsInline,
                assetsOrToken,
            )
        },
        restore = { values ->
            val editingClientMsgId = values[0] as String
            val inline = values[3] as Boolean
            val textOrToken = values[4] as String
            val suspendedMarkdown = if (inline) {
                textOrToken
            } else {
                store.restoreText(chatId, RetainedTextSlot.SUSPENDED_DRAFT, textOrToken)
                    ?: store.restore(chatId)
                        ?.editingSession
                        ?.takeIf { it.editingClientMsgId == editingClientMsgId }
                        ?.suspendedMarkdown
                        .orEmpty()
            }
            val assetsInline = values[9] as Boolean
            val assetsOrToken = values[10] as String
            val assetsPayload = if (assetsInline) {
                assetsOrToken
            } else {
                store.restoreText(chatId, RetainedTextSlot.SUSPENDED_ASSETS, assetsOrToken)
            }
            val suspendedAssets = assetsPayload
                ?.let { payload ->
                    runCatching {
                        ChatEmbeddedAssetSaverJson.decodeFromString(
                            ListSerializer(EmbeddedAsset.serializer()),
                            payload,
                        )
                    }.getOrNull()
                }
                ?: store.restore(chatId)
                    ?.editingSession
                    ?.takeIf { it.editingClientMsgId == editingClientMsgId }
                    ?.suspendedAssets
                    .orEmpty()
            SavedChatEditingSession(
                editingClientMsgId = editingClientMsgId,
                targetLoaded = values[1] as Boolean,
                assetImportOwnerId = values[2] as String,
                suspendedMarkdown = suspendedMarkdown,
                suspendedMode = ChatComposerMode.entries.firstOrNull { it.name == values[5] as String }
                    ?: ChatComposerMode.VISUAL,
                selectionStart = (values[6] as Int).coerceIn(0, suspendedMarkdown.length),
                selectionEnd = (values[7] as Int).coerceIn(0, suspendedMarkdown.length),
                replyingClientMsgId = values[8] as String,
                suspendedAssets = suspendedAssets,
            )
        },
    )

private val ChatEmbeddedAssetSaverJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
}

/**
 * 格式化聊天时间：当天显示 HH:mm，非当天显示 MM-dd HH:mm。
 */
internal fun formatChatTime(timestamp: Long): String {
    val now = Date()
    val msg = Date(timestamp)
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val isToday = dayFmt.format(now) == dayFmt.format(msg)
    return if (isToday) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(msg)
    } else {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(msg)
    }
}

/**
 * 解析发送者显示名。
 * fallback 链：User.name → User.username → uid.take(8)
 */
internal fun resolveDisplayName(uid: String, resolveSender: ((uid: String) -> User?)?): String {
    return resolveDisplayNameOrNull(uid, resolveSender) ?: uid.take(8)
}

/** 当前聊天的发送者通讯录尚未解析该 uid 时返回 null。 */
internal fun resolveDisplayNameOrNull(uid: String, resolveSender: ((uid: String) -> User?)?): String? {
    val user = resolveSender?.invoke(uid)
    return user?.name?.trim()?.takeIf(String::isNotEmpty)
        ?: user?.username?.trim()?.takeIf(String::isNotEmpty)
}

internal const val CHAT_TYPING_TEST_TAG = "chat.typing"

internal fun chatTypingLabel(uid: String, resolveSender: ((uid: String) -> User?)?): String =
    "${resolveDisplayName(uid, resolveSender)} 正在输入…"

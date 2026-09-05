package com.virjar.tk.app.ui.screen

import com.virjar.tk.protocol.model.EmbeddedAsset
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.app.ui.component.rich.ChatVisualMarkdownBaseline
import kotlinx.coroutines.CancellationException

/**
 * 从当前组合中的聊天编辑器到 AppDataState 退役之间的会话级 bridge。
 *
 * 编辑器拥有最新的富文本/源码帧，而 AppDataState 拥有同步的本地草稿写入。退役会在销毁
 * 导航之前原子地认领每个已注册的捕获；随后 Compose onDispose 会观察到已退役的 bridge，
 * 无法再借用 ClientSession。
 */
class ChatDraftLifecycleBridge {
    internal class Registration internal constructor(internal val id: Long)

    private data class Entry(
        val registration: Registration,
        val captureAndPublish: () -> Unit,
    )

    private val lock = Any()
    private var nextRegistrationId = 0L
    private var phase = ChatDraftLifecyclePhase.OPEN
    private val entries = linkedMapOf<Long, Entry>()

    internal fun register(captureAndPublish: () -> Unit): Registration = synchronized(lock) {
        check(nextRegistrationId < Long.MAX_VALUE) { "Chat draft registration sequence exhausted" }
        val registration = Registration(++nextRegistrationId)
        if (phase == ChatDraftLifecyclePhase.OPEN) {
            check(entries.size < MAX_LIVE_EDITORS) {
                "Chat draft lifecycle retained too many live editors"
            }
            entries[registration.id] = Entry(registration, captureAndPublish)
        }
        registration
    }

    /** 正常导航只认领自己的编辑器；owner 安全的过期 dispose 无法清除另一个。 */
    internal fun captureAndUnregister(registration: Registration) = synchronized(lock) {
        if (phase != ChatDraftLifecyclePhase.OPEN) return@synchronized
        // 保持租约直到同步本地草稿写入返回。退役在此 monitor 上等待，
        // 因此它无法在认领与发布之间静默 ClientSession。
        entries.remove(registration.id)
            ?.takeIf { it.registration === registration }
            ?.captureAndPublish
            ?.invoke()
    }

    /** 所有普通/防抖的草稿发布共享同一退役准入。 */
    internal fun publishIfOpen(action: () -> Unit): Boolean = synchronized(lock) {
        if (phase == ChatDraftLifecyclePhase.CLOSED) return@synchronized false
        // 在 CLOSING 期间，此 monitor 由 captureAndRetire 持有；只有它的可重入终结器
        // 能进入这里。并发的延迟防抖会等待，然后观察到 CLOSED。
        action()
        true
    }

    /** 会话退役一次性认领所有挂接的编辑器，并拒绝此后的一切销毁。 */
    internal fun captureAndRetire() {
        var terminalFailure: Throwable? = null
        synchronized(lock) {
            if (phase != ChatDraftLifecyclePhase.OPEN) return
            phase = ChatDraftLifecyclePhase.CLOSING
            val actions = entries.values.map(Entry::captureAndPublish)
            try {
                actions.forEach { action ->
                    try {
                        action()
                    } catch (failure: Throwable) {
                        terminalFailure = mergeChatDraftCaptureFailures(terminalFailure, failure)
                    }
                }
            } finally {
                entries.clear()
                phase = ChatDraftLifecyclePhase.CLOSED
            }
        }
        terminalFailure?.let { throw it }
    }

    private companion object {
        /** Compose 替换可能短暂地同时保留一个退出的和一个进入的编辑器。 */
        const val MAX_LIVE_EDITORS = 2
    }
}

private enum class ChatDraftLifecyclePhase { OPEN, CLOSING, CLOSED }

internal class ChatDraftCaptureHandle(var action: () -> Unit) {
    fun capture() = action()
}

private fun mergeChatDraftCaptureFailures(primary: Throwable?, additional: Throwable): Throwable {
    if (primary == null || primary === additional) return additional
    val primaryFatal = primary is CancellationException || primary !is Exception
    val additionalFatal = additional is CancellationException || additional !is Exception
    return if (!primaryFatal && additionalFatal) {
        additional.addSuppressed(primary)
        additional
    } else {
        primary.addSuppressed(additional)
        primary
    }
}

/**
 * 会话持有的聊天编辑器 UI 上下文内存续存 store。
 *
 * 普通草稿仍由 ConversationRepository 持久化。该 store 只在已登录会话存活期间保留草稿模型
 * 无法表达的更丰富交互上下文（回复/编辑目标、编辑模式、光标以及无损视觉 Markdown 基线）。
 * 它刻意是 AppDataState 持有的实例，而不是进程级单例。
 */
class ChatComposerContextStore {
    private val contexts = mutableMapOf<String, ChatComposerContext>()
    private val recency = mutableListOf<String>()
    private val retainedTextTokens = mutableMapOf<RetainedTextKey, String>()
    private val retainedTexts = mutableMapOf<String, String>()
    private var nextRetainedTextId = 1L

    internal fun restore(chatId: String): ChatComposerContext? {
        val context = contexts[chatId] ?: return null
        markRecent(chatId)
        return context
    }

    internal fun save(chatId: String, context: ChatComposerContext) {
        if (chatId.isBlank()) return
        val normalized = context.normalized()
        if (normalized.isEmpty) {
            remove(chatId)
            return
        }
        contexts[chatId] = normalized
        markRecent(chatId)
        while (recency.size > MAX_RETAINED_CHATS) {
            val oldest = recency.removeAt(0)
            contexts.remove(oldest)
            removeRetainedTexts(oldest)
        }
    }

    /**
     * 较大的编辑器正文留在该会话持有的 store 中。Android SavedState 只接收返回的不透明
     * token，因此 100k 的 Markdown 正文绝不会进入 Activity Bundle。
     */
    internal fun retainText(chatId: String, slot: RetainedTextSlot, text: String): String {
        if (chatId.isBlank()) return ""
        val key = RetainedTextKey(chatId, slot)
        val token = retainedTextTokens.getOrPut(key) { "composer-text-${nextRetainedTextId++}" }
        retainedTexts[token] = text
        markRecent(chatId)
        while (recency.size > MAX_RETAINED_CHATS) {
            val oldest = recency.removeAt(0)
            contexts.remove(oldest)
            removeRetainedTexts(oldest)
        }
        return token
    }

    internal fun restoreText(chatId: String, slot: RetainedTextSlot, token: String): String? {
        val key = RetainedTextKey(chatId, slot)
        if (retainedTextTokens[key] != token) return null
        markRecent(chatId)
        return retainedTexts[token]
    }

    internal fun discardText(chatId: String, slot: RetainedTextSlot) {
        retainedTextTokens.remove(RetainedTextKey(chatId, slot))?.let(retainedTexts::remove)
    }

    internal fun remove(chatId: String) {
        contexts.remove(chatId)
        recency.remove(chatId)
        removeRetainedTexts(chatId)
    }

    internal fun clear() {
        contexts.clear()
        recency.clear()
        retainedTextTokens.clear()
        retainedTexts.clear()
    }

    private fun markRecent(chatId: String) {
        recency.remove(chatId)
        recency += chatId
    }

    private fun removeRetainedTexts(chatId: String) {
        retainedTextTokens.keys
            .filter { key -> key.chatId == chatId }
            .forEach { key -> retainedTextTokens.remove(key)?.let(retainedTexts::remove) }
    }

    private companion object {
        /** 限制访问大量聊天的会话规模，同时持久保留普通草稿。 */
        const val MAX_RETAINED_CHATS = 64
    }
}

internal enum class RetainedTextSlot {
    SOURCE_INPUT,
    SUSPENDED_DRAFT,
    SUSPENDED_ASSETS,
}

private data class RetainedTextKey(val chatId: String, val slot: RetainedTextSlot)

/** 不可变快照；绝不保留 Message、RichTextState 或其他平台对象。 */
internal data class ChatComposerContext(
    val replyTarget: SavedChatReplyTarget = SavedChatReplyTarget(),
    val editingSession: SavedChatEditingSession = SavedChatEditingSession(),
    val markdown: String = "",
    /** 捕获此正文时最后观察到的缓存值；导航恢复仍需识别外部更新与尚未落盘的本机输入。 */
    val previousCachedDraft: String? = null,
    /** 最后接受或提交的完整普通草稿，只用于去重，不代表异步落盘已经完成。 */
    val lastPublishedDraft: String = "",
    /** 仅已上传的描述符；OS 本地的选择与上传句柄绝不进入该 store。 */
    val assets: List<EmbeddedAsset> = emptyList(),
    val mode: ChatComposerMode = ChatComposerMode.VISUAL,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val visualBaseline: ChatVisualMarkdownBaseline = ChatVisualMarkdownBaseline("", ""),
) {
    internal val isEmpty: Boolean
        get() = replyTarget.clientMsgId.isEmpty() &&
            editingSession.editingClientMsgId.isEmpty() &&
            markdown.isEmpty() &&
            previousCachedDraft.isNullOrEmpty() &&
            lastPublishedDraft.isEmpty() &&
            assets.isEmpty() &&
            mode == ChatComposerMode.VISUAL &&
            selectionStart == 0 && selectionEnd == 0 &&
            visualBaseline.originalMarkdown.isEmpty() &&
            visualBaseline.normalizedMarkdown.isEmpty()

    internal fun normalized(): ChatComposerContext {
        val start = selectionStart.coerceIn(0, markdown.length)
        val end = selectionEnd.coerceIn(0, markdown.length)
        return copy(selectionStart = start, selectionEnd = end)
    }
}

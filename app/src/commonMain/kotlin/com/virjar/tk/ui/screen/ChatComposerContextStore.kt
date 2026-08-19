package com.virjar.tk.ui.screen

import com.virjar.tk.ui.component.rich.ChatComposerMode
import com.virjar.tk.ui.component.rich.ChatVisualMarkdownBaseline

/**
 * A session-owned, in-memory continuation store for chat composer UI context.
 *
 * Ordinary drafts are still persisted by ConversationRepository. This store only keeps the
 * richer interaction context that the draft model cannot represent (reply/edit target, editing
 * mode, caret and the lossless visual Markdown baseline) while a signed-in session is alive.
 * It is deliberately an instance owned by AppDataState rather than a process-global singleton.
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
     * Large composer bodies stay in this session-owned store. Android SavedState receives only
     * the returned opaque token, so a 100k Markdown body never enters the Activity Bundle.
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

    internal fun retainedChatIds(): List<String> = recency.toList()

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
        /** Bounds a session that visits many chats while retaining normal drafts durably. */
        const val MAX_RETAINED_CHATS = 64
    }
}

internal enum class RetainedTextSlot {
    SOURCE_INPUT,
    SUSPENDED_DRAFT,
}

private data class RetainedTextKey(val chatId: String, val slot: RetainedTextSlot)

/** Immutable snapshot; never retains Message, RichTextState or another platform object. */
internal data class ChatComposerContext(
    val replyTarget: SavedChatReplyTarget = SavedChatReplyTarget(),
    val editingSession: SavedChatEditingSession = SavedChatEditingSession(),
    val markdown: String = "",
    val mode: ChatComposerMode = ChatComposerMode.VISUAL,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val visualBaseline: ChatVisualMarkdownBaseline = ChatVisualMarkdownBaseline("", ""),
) {
    internal val isEmpty: Boolean
        get() = replyTarget.clientMsgId.isEmpty() &&
            editingSession.editingClientMsgId.isEmpty() &&
            markdown.isEmpty() &&
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

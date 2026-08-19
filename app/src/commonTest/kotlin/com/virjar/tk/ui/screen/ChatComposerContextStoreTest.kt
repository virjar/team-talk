package com.virjar.tk.ui.screen

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.virjar.tk.ui.component.rich.ChatComposerMode
import com.virjar.tk.ui.component.rich.ChatVisualMarkdownBaseline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatComposerContextStoreTest {

    @Test
    fun `same session restores independent reply and source editing contexts across chats`() {
        val store = ChatComposerContextStore()
        val chatA = ChatComposerContext(
            replyTarget = SavedChatReplyTarget(),
            editingSession = SavedChatEditingSession(
                editingClientMsgId = "message-a",
                targetLoaded = true,
                suspendedMarkdown = "ordinary **draft**",
                suspendedMode = ChatComposerMode.VISUAL,
                selectionStart = 9,
                selectionEnd = 9,
                replyingClientMsgId = "reply-before-edit",
            ),
            markdown = "# edited source\n\n| A | B |\n| - | - |\n| 1 | 2 |",
            mode = ChatComposerMode.MARKDOWN,
            selectionStart = 3,
            selectionEnd = 16,
            visualBaseline = ChatVisualMarkdownBaseline("original-a", "normalized-a"),
        )
        val chatB = ChatComposerContext(
            replyTarget = SavedChatReplyTarget("reply-b"),
            markdown = "message b",
            mode = ChatComposerMode.PREVIEW,
            selectionStart = 4,
            selectionEnd = 4,
        )

        store.save("chat-a", chatA)
        store.save("chat-b", chatB)

        assertEquals(chatB, store.restore("chat-b"))
        assertEquals(chatA, store.restore("chat-a"))
        assertEquals(listOf("chat-b", "chat-a"), store.retainedChatIds())
    }

    @Test
    fun `successful send removes the now empty composer context`() {
        val store = ChatComposerContextStore()
        store.save(
            "chat-a",
            ChatComposerContext(
                replyTarget = SavedChatReplyTarget("reply-a"),
                markdown = "send me",
                mode = ChatComposerMode.MARKDOWN,
                selectionStart = 7,
                selectionEnd = 7,
            ),
        )

        store.save("chat-a", ChatComposerContext())

        assertNull(store.restore("chat-a"))
        assertEquals(emptyList(), store.retainedChatIds())
    }

    @Test
    fun `editing saver keeps suspended draft reply mode and selection for activity restore`() {
        val store = ChatComposerContextStore()
        val original = SavedChatEditingSession(
            editingClientMsgId = "editing-message",
            targetLoaded = true,
            suspendedMarkdown = "```kotlin\nval answer = 42\n```",
            suspendedMode = ChatComposerMode.MARKDOWN,
            selectionStart = 7,
            selectionEnd = 18,
            replyingClientMsgId = "reply-target",
        )
        val saver = savedChatEditingSessionSaver("chat-a", store)

        val saved = with(saver) { SaveEverythingScope.save(original) }

        assertEquals(original, saver.restore(saved!!))
    }

    @Test
    fun `large source and suspended draft use lightweight saved state tokens`() {
        val store = ChatComposerContextStore()
        val largeSource = "source-body-" + "x".repeat(MAX_CHAT_SAVED_STATE_INLINE_TEXT_LENGTH + 20_000)
        val largeDraft = "suspended-body-" + "y".repeat(MAX_CHAT_SAVED_STATE_INLINE_TEXT_LENGTH + 20_000)
        val sourceSaver = chatSourceInputSaver("chat-a", store)
        val editingSaver = savedChatEditingSessionSaver("chat-a", store)
        val source = TextFieldValue(largeSource, TextRange(15, largeSource.length))
        val editing = SavedChatEditingSession(
            editingClientMsgId = "editing-message",
            targetLoaded = true,
            suspendedMarkdown = largeDraft,
            suspendedMode = ChatComposerMode.MARKDOWN,
            selectionStart = 10,
            selectionEnd = largeDraft.length,
        )

        val savedSource = with(sourceSaver) { SaveEverythingScope.save(source) }!!
        val savedEditing = with(editingSaver) { SaveEverythingScope.save(editing) }!!

        assertTrue(!savedSource.toString().contains("source-body-"))
        assertTrue(!savedEditing.toString().contains("suspended-body-"))
        assertEquals(source, sourceSaver.restore(savedSource))
        assertEquals(editing, editingSaver.restore(savedEditing))
    }

    @Test
    fun `short source remains inline for normal rotation recovery`() {
        val store = ChatComposerContextStore()
        val saver = chatSourceInputSaver("chat-a", store)
        val source = TextFieldValue("short **draft**", TextRange(3, 9))

        val saved = with(saver) { SaveEverythingScope.save(source) }!!

        assertTrue(saved.toString().contains(source.text))
        store.clear()
        assertEquals(source, saver.restore(saved))
    }

    @Test
    fun `destroy style clear removes every chat context`() {
        val store = ChatComposerContextStore()
        store.save("chat-a", ChatComposerContext(markdown = "a"))
        store.save("chat-b", ChatComposerContext(markdown = "b"))

        store.clear()

        assertNull(store.restore("chat-a"))
        assertNull(store.restore("chat-b"))
    }

    private object SaveEverythingScope : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }
}

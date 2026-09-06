package com.virjar.tk.app.ui.screen

import androidx.compose.ui.text.input.TextFieldValue
import com.mohamedrejeb.richeditor.model.RichTextState
import com.virjar.tk.app.ui.component.rich.ChatComposerMode
import com.virjar.tk.app.ui.component.rich.PendingAssetJob
import com.virjar.tk.app.ui.component.rich.PendingAssetJobState
import com.virjar.tk.app.ui.component.rich.RichEditorMarkdownCapability
import com.virjar.tk.app.ui.component.rich.canUseChatVisualEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ChatComposerImageModeTest {
    private val firstId = "11111111-1111-4111-8111-111111111111"
    private val first = "![剪贴板图片.png](teamtalk-asset://asset/$firstId)"
    private val second = "![my\\_photo.png](teamtalk-asset://asset/22222222-2222-4222-8222-222222222222)"

    @Test
    fun `controlled image source enters visual mode and removing one image keeps the remainder editable`() {
        val markdown = "图片验收 A $first$second 后文"
        val state = RichTextState()
        var mode = ChatComposerMode.MARKDOWN
        changeChatComposerMode(
            target = ChatComposerMode.VISUAL,
            voiceMode = false,
            currentMode = mode,
            markdown = markdown,
            sourceInput = TextFieldValue(markdown),
            enterVisualMarkdown = { state.setMarkdown(it); mode = ChatComposerMode.VISUAL },
            updateSourceInput = {},
            updateMode = { mode = it },
            requestFocusRestore = {},
            hideEmoji = {},
        )
        assertEquals(ChatComposerMode.VISUAL, mode)
        assertEquals(markdown, state.toMarkdown())

        discardChatPendingAsset(
            job = PendingAssetJob("job-1", firstId, PendingAssetJobState.READY, 1f),
            markdown = state.toMarkdown(),
            sourceInput = TextFieldValue(markdown),
            editingSessionActive = false,
            updateEditor = { updated ->
                if (mode == ChatComposerMode.VISUAL && canUseChatVisualEditor(updated.text)) {
                    state.setMarkdown(updated.text)
                } else {
                    mode = ChatComposerMode.MARKDOWN
                }
            },
            persistSessionContext = {},
            persistOrdinaryDraft = {},
            publishUserTextChange = {},
            cancelUpload = {},
            reportError = { fail(it) },
        )
        assertEquals(ChatComposerMode.VISUAL, mode)
        assertEquals("图片验收 A $second 后文", state.toMarkdown())
    }

    @Test
    fun `filename underscore remains eligible after visual serialization`() {
        val raw = second.replace("\\_", "_")
        assertTrue(canUseChatVisualEditor(raw))
        val state = RichTextState().apply { setMarkdown(raw) }
        assertEquals(second, state.toMarkdown())
        assertTrue(canUseChatVisualEditor(state.toMarkdown()))
        state.setMarkdown(state.toMarkdown())
        assertEquals(second, state.toMarkdown())
    }

    @Test
    fun `image exception does not admit external images complex alt titles or advanced markdown`() {
        assertTrue(canUseChatVisualEditor(first))
        assertTrue(RichEditorMarkdownCapability.inspect(first).requiresSourceMode)
        listOf(
            "![图片](https://example.org/image.png)",
            "![**加粗**](teamtalk-asset://asset/$firstId)",
            "![含\\[括号\\]](teamtalk-asset://asset/$firstId)",
            "![图片](teamtalk-asset://asset/$firstId \"title\")",
            "![图片](teamtalk-asset://asset/not-a-uuid)",
            "> 引用\n$first",
            "```kotlin\nval x = 1\n```\n$first",
        ).forEach { assertFalse(canUseChatVisualEditor(it), it) }
    }
}

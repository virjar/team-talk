package com.virjar.tk.ui.component.rich

import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentBlockEditorStateTest {

    @Test
    fun `编辑器销毁后仍可同步取得最后一个草稿快照`() {
        val controller = DocumentBlockEditorController()
        controller.bindActions(
            insert = {},
            append = {},
            snapshot = { fallback -> "latest:$fallback" },
        )

        assertEquals("latest:draft", controller.snapshotMarkdown("draft"))
        controller.clear()

        // 子画布可能先于父标签销毁；父标签仍要在 onDispose 中发布最后一次输入。
        assertEquals("latest:draft", controller.snapshotMarkdown("draft"))
    }

    @Test
    fun `重新排列干净块只改间距而不改写原始正文`() {
        val source = "  ```` kotlin title=\"原样\"\nval raw = \"```\"\n  ````"
        val code = assertIs<DocumentCodeFenceBlock>(DocumentMarkdownBlockCodec.parse(source).single())

        val moved = assertIs<DocumentCodeFenceBlock>(
            code.withDocumentLayout(leadingMarkdown = "\n\n", trailingMarkdown = "\n")
        )

        assertFalse(moved.dirty)
        assertEquals("\n\n${source}\n", DocumentMarkdownBlockCodec.encode(listOf(moved)))
        assertEquals(code.infoString, moved.infoString)
        assertEquals(code.code, moved.code)
    }

    @Test
    fun `待激活富文本仅在目标会话就绪后消费一次并聚焦`() {
        val controller = DocumentBlockEditorController()
        val state = RichTextState()
        var focusRequests = 0

        controller.requestRichActivation("inserted-quote")

        assertEquals("inserted-quote", controller.pendingActivationKey)
        assertEquals("inserted-quote", controller.pendingFocusKey)
        assertFalse(
            controller.consumePendingRichActivation("another-block", state) { focusRequests++ }
        )
        assertTrue(
            controller.consumePendingRichActivation("inserted-quote", state) { focusRequests++ }
        )
        assertNull(controller.pendingActivationKey)
        assertNull(controller.pendingFocusKey)
        assertSame(state, controller.activeRichState)
        assertEquals(1, focusRequests)

        assertFalse(
            controller.consumePendingRichActivation("inserted-quote", state) { focusRequests++ }
        )
        assertEquals(1, focusRequests)
    }

    @Test
    fun `待激活块离开组合或用户切换块后不会在重入时抢焦点`() {
        val controller = DocumentBlockEditorController()
        val state = RichTextState()
        var focusRequests = 0

        controller.requestRichActivation("lazy-block")
        controller.deactivate("lazy-block")
        assertNull(controller.pendingActivationKey)
        assertFalse(
            controller.consumePendingRichActivation("lazy-block", state) { focusRequests++ }
        )

        controller.requestRichActivation("lazy-block")
        controller.activate("user-selected-block")
        assertNull(controller.pendingActivationKey)
        assertNull(controller.pendingFocusKey)
        assertFalse(
            controller.consumePendingRichActivation("lazy-block", state) { focusRequests++ }
        )
        assertEquals(0, focusRequests)
    }

}

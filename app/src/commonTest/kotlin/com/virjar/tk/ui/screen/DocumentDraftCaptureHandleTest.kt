package com.virjar.tk.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentDraftCaptureHandleTest {

    @Test
    fun `旧标签的稳定快照句柄不会读取新标签动作`() {
        val oldHandle = DocumentDraftCaptureHandle {
            DocumentEditorDraftSnapshot("A", "A 的最后输入", dirty = true)
        }
        val oldStableCapture = oldHandle::capture

        val newHandle = DocumentDraftCaptureHandle {
            DocumentEditorDraftSnapshot("B", "B 的正文", dirty = false)
        }
        newHandle.action = { DocumentEditorDraftSnapshot("B2", "B 的新正文", dirty = true) }

        assertEquals("A 的最后输入", oldStableCapture().markdown)
        assertEquals("B 的新正文", newHandle.capture().markdown)
    }
}

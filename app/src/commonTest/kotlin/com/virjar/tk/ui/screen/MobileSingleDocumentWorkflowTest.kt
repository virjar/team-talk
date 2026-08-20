package com.virjar.tk.ui.screen

import com.virjar.tk.navigation.feature.DocumentTabState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileSingleDocumentWorkflowTest {

    @Test
    fun `移动端已有文档默认预览而新建草稿直接编辑`() {
        assertTrue(
            shouldStartDocumentInPreview(
                canEdit = true,
                mobileSingleDocumentMode = true,
                creating = false,
            )
        )
        assertFalse(
            shouldStartDocumentInPreview(
                canEdit = true,
                mobileSingleDocumentMode = true,
                creating = true,
            )
        )
    }

    @Test
    fun `桌面端可编辑文档仍默认编辑而无编辑权限始终预览`() {
        assertFalse(
            shouldStartDocumentInPreview(
                canEdit = true,
                mobileSingleDocumentMode = false,
                creating = false,
            )
        )
        assertTrue(
            shouldStartDocumentInPreview(
                canEdit = false,
                mobileSingleDocumentMode = false,
                creating = false,
            )
        )
        assertTrue(
            shouldStartDocumentInPreview(
                canEdit = false,
                mobileSingleDocumentMode = true,
                creating = true,
            )
        )
    }

    @Test
    fun `刚输入正文立刻返回会同步捕获并要求确认`() {
        val staleTab = tab(documentId = "doc-a", dirty = false, draftMarkdown = "旧正文")

        val decision = prepareMobileSingleDocumentTransition(
            currentTab = staleTab,
            captureDraft = {
                DocumentEditorDraftSnapshot(
                    title = "即时标题",
                    markdown = "尚未进入 Compose 下一帧的正文",
                    dirty = true,
                )
            },
            targetDocumentId = null,
        )

        assertEquals(MobileSingleDocumentTransition.CONFIRM_DISCARD, decision.transition)
        assertEquals("即时标题", decision.currentTab?.draftTitle)
        assertEquals("尚未进入 Compose 下一帧的正文", decision.currentTab?.draftMarkdown)
    }

    @Test
    fun `刚输入正文立刻打开另一篇会同步捕获并要求确认`() {
        val staleTab = tab(documentId = "doc-a", dirty = false, draftMarkdown = "旧正文")

        val decision = prepareMobileSingleDocumentTransition(
            currentTab = staleTab,
            captureDraft = {
                DocumentEditorDraftSnapshot(
                    title = "文档 A",
                    markdown = "最后一个字符也必须保留",
                    dirty = true,
                )
            },
            targetDocumentId = "doc-b",
        )

        assertEquals(MobileSingleDocumentTransition.CONFIRM_DISCARD, decision.transition)
        assertEquals("最后一个字符也必须保留", decision.currentTab?.draftMarkdown)
    }

    @Test
    fun `新建草稿即使快照未标脏也必须确认`() {
        val draft = tab(documentId = null, dirty = false, creating = true)

        val decision = prepareMobileSingleDocumentTransition(
            currentTab = draft,
            captureDraft = { DocumentEditorDraftSnapshot("无标题文档", "", dirty = false) },
            targetDocumentId = "doc-b",
        )

        assertEquals(MobileSingleDocumentTransition.CONFIRM_DISCARD, decision.transition)
        assertEquals(true, decision.currentTab?.dirty)
    }

    @Test
    fun `重新打开当前文档不关闭也不确认`() {
        val current = tab(documentId = "doc-a", dirty = true)

        val decision = prepareMobileSingleDocumentTransition(
            currentTab = current,
            captureDraft = { DocumentEditorDraftSnapshot("A", "本地草稿", dirty = true) },
            targetDocumentId = "doc-a",
        )

        assertEquals(MobileSingleDocumentTransition.RESUME_CURRENT, decision.transition)
        assertEquals("本地草稿", decision.currentTab?.draftMarkdown)
    }

    @Test
    fun `无当前文档可直接打开而干净文档可直接替换`() {
        val direct = prepareMobileSingleDocumentTransition(null, null, targetDocumentId = "doc-a")
        val replace = prepareMobileSingleDocumentTransition(
            currentTab = tab(documentId = "doc-a", dirty = false),
            captureDraft = { DocumentEditorDraftSnapshot("A", "正文", dirty = false) },
            targetDocumentId = "doc-b",
        )

        assertEquals(MobileSingleDocumentTransition.OPEN_DIRECTLY, direct.transition)
        assertNull(direct.currentTab)
        assertEquals(MobileSingleDocumentTransition.CLOSE_AND_CONTINUE, replace.transition)
    }

    @Test
    fun `首页最近文档异步到达后进入编辑器`() {
        assertEquals(false, shouldShowMobileSingleDocumentEditor(activeTab = null, selectedSpaceId = "space-a"))
        assertEquals(
            true,
            shouldShowMobileSingleDocumentEditor(
                activeTab = tab(documentId = "doc-a", dirty = false),
                selectedSpaceId = "space-a",
            ),
        )
        assertEquals(
            false,
            shouldShowMobileSingleDocumentEditor(
                activeTab = tab(documentId = "doc-other", dirty = false).copy(spaceId = "space-b"),
                selectedSpaceId = "space-a",
            ),
        )
    }

    @Test
    fun `Android 一级导航等待文档工作台放行`() {
        val coordinator = MobileDocumentExitCoordinator()
        var pendingExit: (() -> Unit)? = null
        var exited = false
        val guard: ((() -> Unit) -> Unit) = { onExit -> pendingExit = onExit }
        coordinator.attach(guard)

        coordinator.requestExit { exited = true }
        assertFalse(exited)

        requireNotNull(pendingExit).invoke()
        assertTrue(exited)

        coordinator.detach(guard)
        exited = false
        coordinator.requestExit { exited = true }
        assertTrue(exited)
    }

    private fun tab(
        documentId: String?,
        dirty: Boolean,
        creating: Boolean = false,
        draftMarkdown: String = "正文",
    ) = DocumentTabState(
        tabId = documentId ?: "draft-1",
        instanceId = 1,
        documentId = documentId,
        spaceId = "space-a",
        parentId = "folder-a",
        ancestorIds = listOf("folder-a"),
        savedTitle = if (creating) "" else "A",
        savedMarkdown = if (creating) "" else "正文",
        draftTitle = if (creating) "无标题文档" else "A",
        draftMarkdown = draftMarkdown,
        revision = if (creating) null else 1,
        dirty = dirty,
        creating = creating,
    )
}

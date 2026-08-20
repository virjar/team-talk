package com.virjar.tk.navigation.feature

import com.virjar.tk.model.Document
import com.virjar.tk.model.DocumentNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentWorkspaceStateTest {

    @Test
    fun `new navigation invalidates every older target`() {
        val navigation = GenerationGate()

        val first = navigation.next()
        val second = navigation.next()

        assertFalse(navigation.isCurrent(first))
        assertTrue(navigation.isCurrent(second))
    }

    @Test
    fun `closing active tab picks latest replacement only from same space`() {
        val remaining = listOf(
            tab("other-old", "space-b"),
            tab("same-old", "space-a"),
            tab("other-new", "space-b"),
            tab("same-new", "space-a"),
        )

        assertEquals("same-new", replacementDocumentTab(remaining, "space-a")?.tabId)
        assertNull(replacementDocumentTab(remaining, "space-missing"))
    }

    @Test
    fun `closing last document keeps its parent folder selected`() {
        val closing = tab("doc-a", "space-a").copy(parentId = "folder-deep")
        val replacement = tab("doc-b", "space-a").copy(parentId = "folder-other")

        assertEquals("folder-deep", selectedFolderAfterClosingDocumentTab(closing, replacement = null))
        assertEquals("folder-other", selectedFolderAfterClosingDocumentTab(closing, replacement))
    }

    @Test
    fun `delete request keeps A identity after active tab switches to B`() {
        val tabA = tab("doc-a", "space-a").copy(
            instanceId = 41L,
            parentId = "folder-a",
            revision = 7L,
        )
        val request = requireNotNull(DocumentDeleteRequest.capture(tabA))
        val activeTabAfterLaunch = tab("doc-b", "space-a").copy(instanceId = 42L, revision = 9L)

        assertEquals(41L, request.instanceId)
        assertEquals("doc-a", request.documentId)
        assertEquals(7L, request.revision)
        assertEquals("doc-b", activeTabAfterLaunch.documentId)
    }

    @Test
    fun `delete success invalidates a document reopened while request is pending`() {
        val original = tab("doc-a", "space-a").copy(instanceId = 41L, revision = 7L)
        val request = requireNotNull(DocumentDeleteRequest.capture(original))
        val reopened = tab("doc-a-reopened-tab", "space-a").copy(
            instanceId = 43L,
            documentId = "doc-a",
            revision = 7L,
        )
        val unrelated = tab("doc-b", "space-a").copy(instanceId = 42L, revision = 9L)

        assertEquals(
            listOf("doc-a-reopened-tab"),
            documentTabIdsInvalidatedByDelete(listOf(reopened, unrelated), request),
        )
    }

    @Test
    fun `instance identity resolves migrated draft tab id`() {
        val migrated = tab("server-doc-a", "space-a").copy(instanceId = 41L)
        val other = tab("doc-b", "space-a").copy(instanceId = 42L)

        assertEquals("server-doc-a", documentTabIdByInstance(listOf(migrated, other), instanceId = 41L))
        assertEquals("doc-b", documentTabIdByInstance(listOf(migrated, other), instanceId = 42L))
        assertNull(documentTabIdByInstance(listOf(migrated, other), instanceId = 404L))
    }

    @Test
    fun `discard confirmation before create response closes draft and late response cannot restore it`() {
        val draft = tab("draft-1", "space-a").copy(
            instanceId = 41L,
            documentId = null,
            revision = null,
            creating = true,
            dirty = true,
        )
        val saveRequest = DocumentTabRequest.capture(draft)
        val storedConfirmationInstanceId = draft.instanceId
        val closeId = documentTabIdByInstance(listOf(draft), storedConfirmationInstanceId)
        val afterDiscard = listOf(draft).filterNot { it.tabId == closeId }

        assertTrue(afterDiscard.isEmpty())
        assertNull(
            mergeDocumentMutationResponse(
                afterDiscard,
                saveRequest,
                document("doc-created", "已保存", "正文", revision = 1),
            ),
        )
    }

    @Test
    fun `discard confirmation after create response resolves migrated tab id by instance`() {
        val draft = tab("draft-1", "space-a").copy(
            instanceId = 41L,
            documentId = null,
            revision = null,
            creating = true,
            dirty = true,
        )
        val saveRequest = DocumentTabRequest.capture(draft)
        val migrated = requireNotNull(
            mergeDocumentMutationResponse(
                listOf(draft),
                saveRequest,
                document("doc-created", "已保存", "正文", revision = 1),
            ),
        ).tabs

        assertEquals("doc-created", documentTabIdByInstance(migrated, instanceId = 41L))
    }

    @Test
    fun `folder target path is rebuilt from root to selected folder`() {
        val root = folder("root", parentId = null)
        val chapter = folder("chapter", parentId = root.nodeId)
        val section = folder("section", parentId = chapter.nodeId)
        val tree = mapOf(
            null to listOf(root),
            root.nodeId to listOf(chapter),
            chapter.nodeId to listOf(section),
        )

        assertEquals(listOf("root", "chapter", "section"), folderAncestorIds("section", tree))
        assertEquals(emptyList(), folderAncestorIds(null, tree))
        assertEquals(emptyList(), folderAncestorIds("unknown", tree))
    }

    @Test
    fun `save response advances baseline but preserves edits made while request is pending`() = runTest {
        var tabs = listOf(
            tab("doc-a", "space-a").copy(
                savedTitle = "旧标题",
                savedMarkdown = "旧正文",
                draftTitle = "请求标题",
                draftMarkdown = "请求正文",
                dirty = true,
                editGeneration = 4,
            ),
        )
        val request = DocumentTabRequest.capture(tabs.single())
        val response = CompletableDeferred<Document>()

        val mergeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val saved = response.await()
            mergeDocumentMutationResponse(tabs, request, saved)?.let { tabs = it.tabs }
        }
        tabs = tabs.map {
            it.copy(
                draftTitle = "请求后继续编辑",
                draftMarkdown = "不能被响应覆盖",
                dirty = true,
                editGeneration = 5,
            )
        }
        response.complete(
            document(
                id = "doc-a",
                title = "请求标题",
                markdown = "请求正文",
                revision = 2,
            ),
        )
        mergeJob.join()

        val merged = tabs.single()
        assertEquals("请求标题", merged.savedTitle)
        assertEquals("请求正文", merged.savedMarkdown)
        assertEquals(2L, merged.revision)
        assertEquals("请求后继续编辑", merged.draftTitle)
        assertEquals("不能被响应覆盖", merged.draftMarkdown)
        assertEquals(5L, merged.editGeneration)
        assertTrue(merged.dirty)
    }

    @Test
    fun `create response migrates draft id without discarding later edits`() = runTest {
        var tabs = listOf(
            tab("draft-1", "space-a").copy(
                documentId = null,
                revision = null,
                savedTitle = "",
                savedMarkdown = "",
                draftTitle = "首次保存",
                draftMarkdown = "首次正文",
                dirty = true,
                creating = true,
                editGeneration = 1,
            ),
        )
        val request = DocumentTabRequest.capture(tabs.single())
        val response = CompletableDeferred<Document>()

        val mergeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val saved = response.await()
            mergeDocumentMutationResponse(tabs, request, saved)?.let { tabs = it.tabs }
        }
        tabs = tabs.map {
            it.copy(
                draftTitle = "创建期间的新标题",
                draftMarkdown = "创建期间的新正文",
                editGeneration = 2,
            )
        }
        response.complete(
            document(
                id = "doc-created",
                title = "首次保存",
                markdown = "首次正文",
                revision = 1,
            ),
        )
        mergeJob.join()

        val merged = tabs.single()
        assertEquals("doc-created", merged.tabId)
        assertEquals("doc-created", merged.documentId)
        assertFalse(merged.creating)
        assertEquals("首次保存", merged.savedTitle)
        assertEquals("创建期间的新标题", merged.draftTitle)
        assertEquals("创建期间的新正文", merged.draftMarkdown)
        assertTrue(merged.dirty)
    }

    @Test
    fun `late response never recreates a tab closed while saving`() = runTest {
        var tabs = listOf(tab("doc-a", "space-a").copy(dirty = true, editGeneration = 1))
        val request = DocumentTabRequest.capture(tabs.single())
        val response = CompletableDeferred<Document>()
        var merge: DocumentTabMerge? = null

        val mergeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val saved = response.await()
            merge = mergeDocumentMutationResponse(tabs, request, saved)
            merge?.let { tabs = it.tabs }
        }
        tabs = emptyList()
        response.complete(document("doc-a", "已保存", "正文", revision = 2))
        mergeJob.join()

        assertNull(merge)
        assertTrue(tabs.isEmpty())
    }

    @Test
    fun `save response cannot target a reopened instance of the same document`() {
        val original = tab("doc-a", "space-a").copy(
            instanceId = 10,
            dirty = true,
            editGeneration = 3,
        )
        val request = DocumentTabRequest.capture(original)
        val reopened = original.copy(
            instanceId = 11,
            draftTitle = "重新打开后的草稿",
            draftMarkdown = "不能被旧响应覆盖",
        )

        assertNull(
            mergeDocumentMutationResponse(
                tabs = listOf(reopened),
                request = request,
                saved = document("doc-a", "旧请求标题", "旧请求正文", revision = 2),
            ),
        )
    }

    @Test
    fun `response from an older server revision cannot roll a tab back`() {
        val original = tab("doc-a", "space-a").copy(revision = 1, dirty = true, editGeneration = 1)
        val request = DocumentTabRequest.capture(original)
        val alreadyAdvanced = original.copy(
            savedTitle = "更新版本",
            savedMarkdown = "更新正文",
            revision = 2,
            dirty = false,
        )

        assertNull(
            mergeDocumentMutationResponse(
                listOf(alreadyAdvanced),
                request,
                document("doc-a", "迟到版本", "迟到正文", revision = 2),
            ),
        )
    }

    @Test
    fun `history request gate rejects delayed document A after document B becomes current`() = runTest {
        val gate = LatestRequestGate<DocumentRequestTarget>()
        val targetA = DocumentRequestTarget("doc-a", "doc-a", "space-a")
        val targetB = DocumentRequestTarget("doc-b", "doc-b", "space-a")
        val delayedA = CompletableDeferred<String>()
        val tokenA = gate.begin(targetA)
        var visibleResult: String? = null

        val requestJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val result = delayedA.await()
            if (gate.isCurrent(tokenA)) visibleResult = result
        }
        val tokenB = gate.begin(targetB)
        delayedA.complete("A 的历史")
        requestJob.join()

        assertNull(visibleResult)
        assertTrue(gate.isCurrent(tokenB))
    }

    @Test
    fun `closing history invalidates an in flight revision preview`() = runTest {
        val gate = LatestRequestGate<DocumentRequestTarget>()
        val target = DocumentRequestTarget("doc-a", "doc-a", "space-a")
        val delayedPreview = CompletableDeferred<String>()
        val token = gate.begin(target)
        var preview: String? = null

        val requestJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val result = delayedPreview.await()
            if (gate.isCurrent(token)) preview = result
        }
        gate.invalidate()
        delayedPreview.complete("旧版本正文")
        requestJob.join()

        assertNull(preview)
    }

    private fun tab(id: String, spaceId: String) = DocumentTabState(
        tabId = id,
        instanceId = 1,
        documentId = id,
        spaceId = spaceId,
        parentId = null,
        ancestorIds = emptyList(),
        savedTitle = id,
        savedMarkdown = "",
        draftTitle = id,
        draftMarkdown = "",
        revision = 1,
    )

    private fun folder(id: String, parentId: String?) = DocumentNode(
        nodeId = id,
        spaceId = "space-a",
        parentId = parentId,
        nodeType = DocumentNode.TYPE_FOLDER,
        name = id,
        revision = 1,
        createdBy = "u1",
        createdAt = 1,
        updatedBy = "u1",
        updatedAt = 1,
    )

    private fun document(
        id: String,
        title: String,
        markdown: String,
        revision: Long,
    ) = Document(
        documentId = id,
        spaceId = "space-a",
        parentId = null,
        title = title,
        markdown = markdown,
        revision = revision,
        createdBy = "u1",
        createdAt = 1,
        updatedBy = "u1",
        updatedAt = revision,
    )
}

package com.virjar.tk.app.navigation.feature.document

import com.virjar.tk.app.navigation.feature.LatestRequestGate

import com.virjar.tk.shared.client.PendingDocumentMoveCommand
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.DocumentSpaceGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentWorkspaceStateTest {

    @Test
    fun `completed operation fences an older pending snapshot`() {
        val command = PendingDocumentMoveCommand.create(
            operationId = "00000000-0000-4000-8000-000000000101",
            spaceId = "00000000-0000-4000-8000-000000000102",
            nodeId = "00000000-0000-4000-8000-000000000103",
            oldParentId = null,
            targetParentId = "00000000-0000-4000-8000-000000000104",
            name = "新名称",
            expectedRevision = 7L,
            issuedAt = 1L,
        )

        assertEquals(
            emptyMap(),
            restoredPendingDocumentMoves(listOf(command), setOf(command.operationId)),
        )
        assertEquals(
            mapOf(command.operationId to command.nodeId),
            restoredPendingDocumentMoves(listOf(command), emptySet()),
        )
        assertEquals(
            emptyMap(),
            pendingDocumentMovesAfterSubmission(
                current = emptyMap(),
                command = command,
                recoveredOperationIds = setOf(command.operationId),
            ),
            "a foreground Pending continuation must not resurrect an already completed command",
        )
        assertEquals(
            mapOf(command.operationId to command.nodeId),
            pendingDocumentMovesAfterSubmission(
                current = emptyMap(),
                command = command,
                recoveredOperationIds = emptySet(),
            ),
        )
    }

    @Test
    fun `failed listSpaces cannot hide a persisted dirty draft`() = runTest {
        val dirty = tab("7a548497-744b-4d0d-9c5f-f542a309f738", "space-offline").copy(
            instanceId = 41L,
            parentId = "page-a",
            draftTitle = "断网前标题",
            draftMarkdown = "本机未保存正文",
            dirty = true,
        )
        val clean = tab("clean-doc", "space-offline").copy(instanceId = 42L)
        val snapshot = DocumentWorkspaceDraftSnapshot(
            tabs = listOf(dirty, clean),
            // 干净的 activity tab 在恢复期间被刻意过滤。剩余的 dirty 正文
            // 仍然需要一个确定性的可编辑 fallback。
            activeTabInstanceId = clean.instanceId,
            selectedSpaceId = null,
        )
        val listSpaces = CompletableDeferred<List<DocumentSpace>>()
        val offlineFailure = IllegalStateException("listSpaces offline")
        var publication: OfflineDraftPublication? = null
        var observedFailure: Throwable? = null

        val opening = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                publishPersistedDraftBeforeRemoteLoad(
                    rawSnapshot = snapshot,
                    publish = { publication = offlineDraftPublication(it) },
                    loadRemote = { listSpaces.await() },
                )
            } catch (failure: Throwable) {
                observedFailure = failure
            }
        }

        val published = requireNotNull(publication)
        assertTrue(opening.isActive)
        assertEquals(listOf(dirty.copy(pathResolved = false)), published.tabs)
        assertEquals(dirty.spaceId, published.selectedSpaceId)
        assertEquals(dirty.tabId, published.activeTabId)
        assertEquals(DocumentSpace.ROLE_EDITOR, published.spaces.single().myRole)
        assertTrue(published.spaces.single().name.startsWith("离线草稿"))
        assertTrue(requireNotNull(published.spaces.single().description).contains("未缓存"))
        with(published.spaces.single()) {
            assertEquals(OFFLINE_DOCUMENT_DRAFT_PRINCIPAL_ID, createdBy)
            assertEquals(DocumentSpaceGrant.PRINCIPAL_USER, ownerPrincipalType)
            assertEquals(OFFLINE_DOCUMENT_DRAFT_PRINCIPAL_ID, ownerPrincipalId)
            assertEquals(ownerPrincipalId, stewardUid)
            assertEquals(1L, custodyRevision)
        }

        listSpaces.completeExceptionally(offlineFailure)
        opening.join()
        assertEquals(offlineFailure::class, observedFailure?.let { it::class })
        assertEquals(offlineFailure.message, observedFailure?.message)
    }

    @Test
    fun `offline unresolved draft publishes its body without publishing a stale directory`() {
        val dirty = tab("7a548497-744b-4d0d-9c5f-f542a309f738", "space-offline").copy(
            instanceId = 41L,
            parentId = "stale-parent",
            ancestorIds = listOf("stale-root", "stale-parent"),
            pathResolved = false,
            draftMarkdown = "断网时仍必须可编辑",
            dirty = true,
        )
        val publication = offlineDraftPublication(
            DocumentWorkspaceDraftSnapshot(
                tabs = listOf(dirty),
                activeTabInstanceId = dirty.instanceId,
                selectedSpaceId = dirty.spaceId,
            ),
        )

        assertSame(dirty, publication.tabs.single())
        assertEquals(dirty.tabId, publication.activeTabId)
    }

    @Test
    fun `space projection removal preserves dirty body as explicit local orphan`() {
        val dirty = tab("doc-a", "space-a").copy(
            instanceId = 41L,
            parentId = "parent-a",
            ancestorIds = listOf("parent-a"),
            draftMarkdown = "未保存正文",
            dirty = true,
            revision = 7L,
        )

        val projection = removeUnavailableDocumentSpaceProjection(
            spaceId = "space-a",
            tabs = listOf(dirty),
            spaces = listOf(documentSpace("space-a")),
            offlineDraftSpaceIds = emptySet(),
            selectedSpaceId = "space-a",
            activeTabId = dirty.tabId,
            selectedParentNodeId = dirty.parentId,
        )

        val retained = projection.tabs.single()
        assertEquals("未保存正文", retained.draftMarkdown)
        assertEquals(7L, retained.revision, "space 403 must not change the draft CAS baseline")
        assertTrue(retained.dirty)
        assertFalse(retained.pathResolved)
        assertFalse(retained.remoteMissing, "space ACL loss is not a document-level 404")
        assertTrue(projection.orphanRetained)
        assertEquals(setOf("space-a"), projection.offlineDraftSpaceIds)
        assertEquals("space-a", projection.selectedSpaceId)
        assertEquals(dirty.tabId, projection.activeTabId)
        assertNull(projection.selectedParentNodeId)
        assertTrue(requireNotNull(projection.spaces.single().description).contains("访问权已失效"))
        with(projection.spaces.single()) {
            assertEquals(OFFLINE_DOCUMENT_DRAFT_PRINCIPAL_ID, createdBy)
            assertEquals(DocumentSpaceGrant.PRINCIPAL_USER, ownerPrincipalType)
            assertEquals(OFFLINE_DOCUMENT_DRAFT_PRINCIPAL_ID, ownerPrincipalId)
            assertEquals(ownerPrincipalId, stewardUid)
            assertEquals(1L, custodyRevision)
        }
    }

    @Test
    fun `space projection removal drops clean cache and remote workset row`() {
        val clean = tab("doc-a", "space-a").copy(instanceId = 41L)
        val other = tab("doc-b", "space-b").copy(instanceId = 42L)

        val projection = removeUnavailableDocumentSpaceProjection(
            spaceId = "space-a",
            tabs = listOf(clean, other),
            spaces = listOf(documentSpace("space-a"), documentSpace("space-b")),
            offlineDraftSpaceIds = emptySet(),
            selectedSpaceId = "space-a",
            activeTabId = clean.tabId,
            selectedParentNodeId = null,
        )

        assertEquals(listOf(other), projection.tabs)
        assertEquals(listOf("space-b"), projection.spaces.map(DocumentSpace::spaceId))
        assertTrue(projection.offlineDraftSpaceIds.isEmpty())
        assertNull(projection.selectedSpaceId)
        assertNull(projection.activeTabId)
        assertFalse(projection.orphanRetained)
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
                document("draft-1", "已保存", "正文", revision = 1),
            ),
        )
    }

    @Test
    fun `discard confirmation after create response resolves stable resource id by instance`() {
        val draft = tab("draft-1", "space-a").copy(
            instanceId = 41L,
            documentId = null,
            revision = null,
            creating = true,
            dirty = true,
        )
        val saveRequest = DocumentTabRequest.capture(draft)
        val savedTabs = requireNotNull(
            mergeDocumentMutationResponse(
                listOf(draft),
                saveRequest,
                document("draft-1", "已保存", "正文", revision = 1),
            ),
        ).tabs

        assertEquals("draft-1", documentTabIdByInstance(savedTabs, instanceId = 41L))
    }

    @Test
    fun `page target path is rebuilt from root to selected parent page`() {
        val root = node("root", parentId = null, hasChildren = true)
        val chapter = node("chapter", parentId = root.nodeId, hasChildren = true)
        val section = node("section", parentId = chapter.nodeId)
        val tree = mapOf(
            null to listOf(root),
            root.nodeId to listOf(chapter),
            chapter.nodeId to listOf(section),
        )

        assertEquals(listOf("root", "chapter", "section"), nodeAncestorIds("section", tree))
        assertEquals(emptyList(), nodeAncestorIds(null, tree))
        assertEquals(emptyList(), nodeAncestorIds("unknown", tree))
    }

    @Test
    fun `visible tree opens every node as content while expansion only controls descendants`() {
        val root = node("root", parentId = null, hasChildren = true)
        val child = node("child", parentId = root.nodeId, hasChildren = true)
        val leaf = node("leaf", parentId = child.nodeId)
        val tree = mapOf(
            null to listOf(root),
            root.nodeId to listOf(child),
            child.nodeId to listOf(leaf),
        )

        assertEquals(listOf("root"), visibleDocumentTreeRows(tree, emptySet()).map { it.node.nodeId })
        val firstLevel = visibleDocumentTreeRows(tree, setOf(root.nodeId))
        assertEquals(listOf("root", "child"), firstLevel.map { it.node.nodeId })
        assertTrue(firstLevel.first().hasChildren)
        assertEquals(listOf(0, 1), firstLevel.map(DocumentTreeRow::depth))
        assertEquals(
            listOf("root", "child", "leaf"),
            visibleDocumentTreeRows(tree, setOf(root.nodeId, child.nodeId)).map { it.node.nodeId },
        )
    }

    @Test
    fun `path spine preserves known siblings and marks only incomplete edges partial`() {
        val root = node("root", parentId = null, hasChildren = true)
        val rootSibling = node("root-sibling", parentId = null)
        val childSibling = node("child-sibling", parentId = root.nodeId)
        val target = node("target", parentId = root.nodeId)
        val projection = mergeDocumentPathSpineIntoTree(
            treeChildren = mapOf(
                null to listOf(rootSibling, root),
                root.nodeId to listOf(childSibling),
            ),
            partialBranchParentIds = emptySet(),
            spine = DocumentPathSpine(listOf(root, target)),
        )

        assertEquals(listOf(root, rootSibling), projection.treeChildren[null])
        assertEquals(listOf(childSibling, target), projection.treeChildren[root.nodeId])
        assertEquals(setOf(root.nodeId), projection.partialBranchParentIds)
    }

    @Test
    fun `path spine rename preserves immutable sibling order`() {
        val sibling = node("sibling", parentId = null).copy(name = "Middle", createdAt = 1)
        val before = node("renamed", parentId = null).copy(name = "Zulu", revision = 1, createdAt = 2)
        val renamed = before.copy(name = "Alpha", revision = 2)

        val projection = mergeDocumentPathSpineIntoTree(
            treeChildren = mapOf(null to listOf(sibling, before)),
            partialBranchParentIds = emptySet(),
            spine = DocumentPathSpine(listOf(renamed)),
        )

        assertEquals(listOf(sibling, renamed), projection.treeChildren[null])
    }

    @Test
    fun `path spine breaks equal-created-at ties by node id`() {
        val laterId = node("node-b", parentId = null).copy(name = "Alpha", createdAt = 5)
        val earlierId = node("node-a", parentId = null).copy(name = "Zulu", createdAt = 5)

        val projection = mergeDocumentPathSpineIntoTree(
            treeChildren = mapOf(null to listOf(laterId)),
            partialBranchParentIds = emptySet(),
            spine = DocumentPathSpine(listOf(earlierId)),
        )

        assertEquals(listOf(earlierId, laterId), projection.treeChildren[null])
    }

    @Test
    fun `root-only spine is visible but never claims a complete root branch`() {
        val root = node("root", parentId = null)

        val projection = mergeDocumentPathSpineIntoTree(
            treeChildren = emptyMap(),
            partialBranchParentIds = emptySet(),
            spine = DocumentPathSpine(listOf(root)),
        )

        assertEquals(listOf(root), projection.treeChildren[null])
        assertEquals(setOf<String?>(null), projection.partialBranchParentIds)
    }

    @Test
    fun `loaded empty children override stale hasChildren summary`() {
        val staleParent = node("parent", parentId = null, hasChildren = true)

        val row = visibleDocumentTreeRows(
            treeChildren = mapOf(null to listOf(staleParent), staleParent.nodeId to emptyList()),
            expandedNodeIds = setOf(staleParent.nodeId),
        ).single()

        assertFalse(row.hasChildren)
    }

    @Test
    fun `new positive hasChildren hint invalidates an older empty branch and its expansion`() {
        val cachedParent = node("parent", parentId = null, hasChildren = false)
        val previous: Map<String?, List<DocumentNode>> = mapOf(
            null to listOf(cachedParent),
            cachedParent.nodeId to emptyList(),
        )

        // 子成员关系不递增 parent 的内容 revision。因此跨设备创建子节点
        // 可以返回相同的 revision，并带有一个新变为正向的展开提示。
        val published = publishDocumentTreeBranch(
            treeChildren = previous,
            spaceId = "space-a",
            parentId = null,
            children = listOf(cachedParent.copy(hasChildren = true)),
        )

        assertFalse(published.containsKey(cachedParent.nodeId))
        assertEquals(
            emptySet(),
            reconcileExpandedDocumentTreeBranches(
                expandedNodeIds = setOf(cachedParent.nodeId),
                previousTreeChildren = previous,
                publishedTreeChildren = published,
            ),
        )
        assertTrue(visibleDocumentTreeRows(published, emptySet()).single().hasChildren)
    }

    @Test
    fun `authoritative branch publication moves one node identity out of every old branch`() {
        val movedOld = node("moved", parentId = "old-parent")
        val oldSibling = node("old-sibling", parentId = "old-parent")
        val newSibling = node("new-sibling", parentId = "new-parent")
        val movedNew = movedOld.copy(parentId = "new-parent", revision = movedOld.revision + 1)
        val tree: Map<String?, List<DocumentNode>> = mapOf(
            "old-parent" to listOf(movedOld, oldSibling),
            "new-parent" to listOf(newSibling),
        )

        val published = publishDocumentTreeBranch(
            treeChildren = tree,
            spaceId = "space-a",
            parentId = "new-parent",
            children = listOf(newSibling, movedNew),
        )

        assertEquals(listOf(oldSibling), published["old-parent"])
        assertEquals(listOf(newSibling, movedNew), published["new-parent"])
        assertEquals(1, published.values.flatten().count { it.nodeId == movedNew.nodeId })
    }

    @Test
    fun `late lower revision old-parent response cannot resurrect a moved node`() {
        val newer = node("moved", parentId = "new-parent").copy(revision = 8)
        val afterNewParent = publishDocumentTreeBranch(
            treeChildren = emptyMap(),
            spaceId = "space-a",
            parentId = "new-parent",
            children = listOf(newer),
        )

        val afterLateOldParent = publishDocumentTreeBranch(
            treeChildren = afterNewParent,
            spaceId = "space-a",
            parentId = "old-parent",
            children = listOf(newer.copy(parentId = "old-parent", revision = 7)),
        )

        assertEquals(listOf(newer), afterLateOldParent["new-parent"])
        assertEquals(emptyList(), afterLateOldParent["old-parent"])
        assertEquals(1, afterLateOldParent.values.flatten().count { it.nodeId == newer.nodeId })
    }

    @Test
    fun `same revision under different parents fails closed`() {
        val cached = node("moved", parentId = "old-parent").copy(revision = 8)

        assertFailsWith<IllegalStateException> {
            publishDocumentTreeBranch(
                treeChildren = mapOf("old-parent" to listOf(cached)),
                spaceId = "space-a",
                parentId = "new-parent",
                children = listOf(cached.copy(parentId = "new-parent")),
            )
        }
    }

    @Test
    fun `full document response evicts stale identity and plans new parent before old parent`() {
        val stale = node("doc-a", parentId = "old-parent")
        val tree: Map<String?, List<DocumentNode>> = mapOf(
            "old-parent" to listOf(stale, node("sibling", parentId = "old-parent")),
            "new-parent" to emptyList(),
        )
        val remote = document(
            id = "doc-a",
            title = "moved",
            markdown = "body",
            revision = stale.revision + 1,
            parentId = "new-parent",
            ancestorIds = listOf("root", "new-parent"),
        )

        val plan = planDocumentTreeRefresh(
            treeChildren = tree,
            document = remote,
            previousParentIds = setOf("captured-parent"),
        )

        assertEquals(
            listOf<String?>("new-parent", "captured-parent", "old-parent"),
            plan.parentIdsToRefresh,
        )
        assertTrue(plan.treeChildren.values.flatten().none { it.nodeId == remote.documentId })
        assertEquals(listOf("sibling"), plan.treeChildren["old-parent"]?.map { it.nodeId })
    }

    @Test
    fun `late reveal failure only revokes the exact captured path epoch`() {
        val old = tab("doc-a", "space-a").copy(
            instanceId = 41,
            parentId = "old-parent",
            ancestorIds = listOf("old-parent"),
            revision = 7,
            pathResolved = true,
            draftMarkdown = "must survive",
            dirty = true,
        )
        val stamp = requireNotNull(DocumentPathStamp.capture(old))
        val newer = old.copy(
            parentId = "new-parent",
            ancestorIds = listOf("new-parent"),
            revision = 8,
        )
        val newerTabs = listOf(newer)

        assertSame(newerTabs, invalidateDocumentPathStamp(newerTabs, stamp))

        val invalidated = invalidateDocumentPathStamp(listOf(old), stamp).single()
        assertFalse(invalidated.pathResolved)
        assertEquals("must survive", invalidated.draftMarkdown)
        assertEquals(old.parentId, invalidated.parentId)
        assertEquals(old.ancestorIds, invalidated.ancestorIds)
    }

    @Test
    fun `loaded path verification requires the target even for a root document`() {
        val rootTab = tab("doc-root", "space-a").copy(
            instanceId = 41,
            parentId = null,
            ancestorIds = emptyList(),
            pathResolved = true,
        )
        val stamp = requireNotNull(DocumentPathStamp.capture(rootTab))

        assertFalse(loadedDocumentPathMatches(mapOf(null to emptyList()), stamp))
        assertTrue(
            loadedDocumentPathMatches(
                treeChildren = mapOf(null to listOf(node("doc-root", parentId = null))),
                stamp = stamp,
            ),
        )
    }

    @Test
    fun `loaded path verification rejects a target cached only under its old parent`() {
        val tab = tab("doc-a", "space-a").copy(
            instanceId = 41,
            parentId = "new-parent",
            ancestorIds = listOf("root", "new-parent"),
            pathResolved = true,
        )
        val stamp = requireNotNull(DocumentPathStamp.capture(tab))
        val tree = mapOf(
            null to listOf(node("root", null, hasChildren = true)),
            "root" to listOf(node("new-parent", "root", hasChildren = true)),
            "new-parent" to emptyList(),
            "old-parent" to listOf(node("doc-a", "old-parent")),
        )

        assertFalse(loadedDocumentPathMatches(tree, stamp))
    }

    @Test
    fun `loaded path verification rejects duplicate target identities across branches`() {
        val tab = tab("doc-a", "space-a").copy(
            instanceId = 41,
            parentId = "new-parent",
            ancestorIds = listOf("new-parent"),
            pathResolved = true,
        )
        val stamp = requireNotNull(DocumentPathStamp.capture(tab))
        val tree = mapOf(
            null to listOf(node("new-parent", null, hasChildren = true)),
            "new-parent" to listOf(node("doc-a", "new-parent")),
            "old-parent" to listOf(node("doc-a", "old-parent")),
        )

        assertFalse(loadedDocumentPathMatches(tree, stamp))
    }

    @Test
    fun `editing a draft reuses the cached tree projection`() {
        val root = node("root", parentId = null, hasChildren = true)
        val child = node("child", parentId = root.nodeId)
        val tree = mapOf(null to listOf(root), root.nodeId to listOf(child))
        val expanded = setOf(root.nodeId)
        val projection = DocumentTreeRowsProjection()
        val firstRows = projection.rows(tree, expanded)

        val editedTab = tab("doc-a", "space-a")
        val editedTabs = updateDocumentDraftTabs(
            tabs = listOf(editedTab),
            update = DocumentDraftUpdate(
                tabId = editedTab.tabId,
                instanceId = editedTab.instanceId,
                revision = editedTab.revision,
                title = "仅编辑正文状态",
                markdown = "树快照没有变化",
            ),
        )
        val rowsAfterDraftEdit = projection.rows(tree, expanded)

        assertEquals("仅编辑正文状态", editedTabs.single().draftTitle)
        assertSame(firstRows, rowsAfterDraftEdit)
        assertFalse(firstRows === projection.rows(tree, emptySet()))
    }

    @Test
    fun `stale editor disposal cannot overwrite a newer revision baseline`() {
        val current = tab("doc-a", "space-a").copy(
            instanceId = 41,
            revision = 8,
            savedTitle = "已保存标题",
            savedMarkdown = "已保存正文",
            draftTitle = "已保存标题",
            draftMarkdown = "已保存正文",
            dirty = false,
        )

        val stale = updateDocumentDraftTabs(
            listOf(current),
            DocumentDraftUpdate(
                tabId = current.tabId,
                instanceId = current.instanceId,
                revision = 7,
                title = "旧编辑器标题",
                markdown = "旧编辑器正文",
            ),
        )
        val sameContent = updateDocumentDraftTabs(
            listOf(current),
            DocumentDraftUpdate(
                tabId = current.tabId,
                instanceId = current.instanceId,
                revision = 8,
                title = current.savedTitle,
                markdown = current.savedMarkdown,
            ),
        )

        assertSame(current, stale.single())
        assertFalse(sameContent.single().dirty)
    }

    @Test
    fun `create acknowledgement advances frozen baseline while preserving later edits`() {
        val creating = DocumentTabState(
            tabId = "00000000-0000-0000-0000-000000000041",
            instanceId = 41,
            documentId = null,
            spaceId = "00000000-0000-0000-0000-000000000042",
            parentId = null,
            ancestorIds = emptyList(),
            savedTitle = "",
            savedMarkdown = "",
            draftTitle = "首次标题",
            draftMarkdown = "首次正文",
            revision = null,
            dirty = true,
            creating = true,
            editGeneration = 3,
        )
        val request = DocumentTabRequest.capture(creating, editGeneration = 3)
        val edited = creating.copy(
            draftTitle = "后来标题",
            draftMarkdown = "后来正文",
            editGeneration = 4,
        )
        val saved = document(
            id = creating.tabId,
            title = "首次标题",
            markdown = "首次正文",
            revision = 1,
            parentId = null,
            ancestorIds = emptyList(),
        ).copy(spaceId = creating.spaceId)

        val merged = requireNotNull(mergeDocumentMutationResponse(listOf(edited), request, saved)).tab

        assertEquals("首次标题", merged.savedTitle)
        assertEquals("首次正文", merged.savedMarkdown)
        assertEquals("后来标题", merged.draftTitle)
        assertEquals("后来正文", merged.draftMarkdown)
        assertEquals(edited.recoveryId, merged.recoveryId)
        assertTrue(merged.dirty)
        assertFalse(merged.creating)
    }

    @Test
    fun `durable cleanup merge preserves unrelated tab additions closures and edits`() {
        val saving = tab("doc-a", "space-a").copy(
            instanceId = 41,
            recoveryId = "recovery-a",
            savedTitle = "old title",
            savedMarkdown = "old body",
            draftTitle = "saved title",
            draftMarkdown = "saved body",
            revision = 7,
            dirty = true,
            editGeneration = 3,
        )
        val editedUnrelated = tab("doc-b", "space-a").copy(
            instanceId = 42,
            draftTitle = "edited while cleanup waited",
            draftMarkdown = "unrelated edit must survive",
            dirty = true,
            editGeneration = 9,
        )
        val closedUnrelated = tab("doc-c", "space-a").copy(instanceId = 43)
        val addedUnrelated = tab("doc-d", "space-b").copy(
            instanceId = 44,
            draftMarkdown = "new tab",
            dirty = true,
        )
        val request = DocumentTabRequest.capture(saving)
        val preCleanupMerge = requireNotNull(
            mergeDocumentMutationResponse(
                tabs = listOf(saving, editedUnrelated.copy(draftTitle = "old unrelated state"), closedUnrelated),
                request = request,
                saved = document(
                    id = saving.tabId,
                    title = saving.draftTitle,
                    markdown = saving.draftMarkdown,
                    revision = 8,
                ),
            ),
        )

        val merged = requireNotNull(
            mergeDocumentMutationAfterDurableCleanup(
                latestTabs = listOf(editedUnrelated, saving, addedUnrelated),
                merge = preCleanupMerge,
                deferredUpdate = null,
                rotateRecoveryIdentity = false,
            ),
        )

        assertEquals(listOf("doc-b", "doc-a", "doc-d"), merged.tabs.map { it.tabId })
        assertSame(editedUnrelated, merged.tabs[0])
        assertSame(addedUnrelated, merged.tabs[2])
        assertTrue(merged.tabs.none { it.tabId == closedUnrelated.tabId })
        assertEquals(8L, merged.tab.revision)
        assertEquals("saved body", merged.tab.savedMarkdown)
        assertFalse(merged.tab.dirty)
    }

    @Test
    fun `durable cleanup replays complete editor frame on acknowledged revision`() {
        val saving = tab("doc-a", "space-a").copy(
            instanceId = 41,
            recoveryId = "recovery-a",
            savedTitle = "old title",
            savedMarkdown = "old body",
            draftTitle = "request title",
            draftMarkdown = "request body",
            revision = 7,
            dirty = true,
            editGeneration = 3,
        )
        val request = DocumentTabRequest.capture(saving)
        val preCleanupMerge = requireNotNull(
            mergeDocumentMutationResponse(
                tabs = listOf(saving),
                request = request,
                saved = document(
                    id = saving.tabId,
                    title = saving.draftTitle,
                    markdown = saving.draftMarkdown,
                    revision = 8,
                ),
            ),
        )
        val deferredFrame = DocumentDraftUpdate(
            tabId = saving.tabId,
            instanceId = saving.instanceId,
            revision = saving.revision,
            title = "title typed during cleanup",
            markdown = "body typed during cleanup",
        )

        val merged = requireNotNull(
            mergeDocumentMutationAfterDurableCleanup(
                latestTabs = listOf(saving),
                merge = preCleanupMerge,
                deferredUpdate = deferredFrame,
                rotateRecoveryIdentity = false,
            ),
        ).tab

        assertEquals(8L, merged.revision)
        assertEquals("request title", merged.savedTitle)
        assertEquals("request body", merged.savedMarkdown)
        assertEquals("title typed during cleanup", merged.draftTitle)
        assertEquals("body typed during cleanup", merged.draftMarkdown)
        assertEquals(4L, merged.editGeneration)
        assertEquals(saving.recoveryId, merged.recoveryId)
        assertTrue(merged.dirty)
    }

    @Test
    fun `newer frame arriving during replacement flush rebases again including undo to clean`() {
        val saving = tab("doc-a", "space-a").copy(
            instanceId = 41,
            recoveryId = "recovery-a",
            savedTitle = "old",
            savedMarkdown = "old body",
            draftTitle = "request",
            draftMarkdown = "request body",
            revision = 7,
            dirty = true,
            editGeneration = 3,
        )
        val request = DocumentTabRequest.capture(saving)
        val acknowledged = requireNotNull(
            mergeDocumentMutationResponse(
                tabs = listOf(saving),
                request = request,
                saved = document(
                    id = saving.tabId,
                    title = saving.draftTitle,
                    markdown = saving.draftMarkdown,
                    revision = 8,
                ),
            ),
        )
        val first = requireNotNull(
            mergeDocumentMutationAfterDurableCleanup(
                latestTabs = listOf(saving),
                merge = acknowledged,
                deferredUpdate = DocumentDraftUpdate(
                    saving.tabId,
                    saving.instanceId,
                    saving.revision,
                    "during first flush",
                    "frame two",
                ),
                rotateRecoveryIdentity = true,
                freshRecoveryId = "replacement-recovery",
            ),
        )

        val second = requireNotNull(
            rebaseDeferredDocumentDraftUpdate(
                first,
                DocumentDraftUpdate(
                    saving.tabId,
                    saving.instanceId,
                    saving.revision,
                    "during second flush",
                    "frame three",
                ),
            ),
        )
        val undone = requireNotNull(
            rebaseDeferredDocumentDraftUpdate(
                second,
                DocumentDraftUpdate(
                    saving.tabId,
                    saving.instanceId,
                    saving.revision,
                    acknowledged.tab.savedTitle,
                    acknowledged.tab.savedMarkdown,
                ),
            ),
        ).tab

        assertEquals(8L, second.tab.revision)
        assertEquals("frame three", second.tab.draftMarkdown)
        assertEquals("replacement-recovery", second.tab.recoveryId)
        assertEquals(5L, second.tab.editGeneration)
        assertFalse(undone.dirty)
        assertEquals(6L, undone.editGeneration)
    }

    @Test
    fun `clean save after durable tombstone rotates recovery identity`() {
        val saving = tab("doc-a", "space-a").copy(
            instanceId = 41,
            recoveryId = "retired-recovery",
            draftTitle = "saved title",
            draftMarkdown = "saved body",
            revision = 7,
            dirty = true,
            editGeneration = 3,
        )
        val request = DocumentTabRequest.capture(saving)
        val preCleanupMerge = requireNotNull(
            mergeDocumentMutationResponse(
                tabs = listOf(saving),
                request = request,
                saved = document(
                    id = saving.tabId,
                    title = saving.draftTitle,
                    markdown = saving.draftMarkdown,
                    revision = 8,
                ),
            ),
        )

        val merged = requireNotNull(
            mergeDocumentMutationAfterDurableCleanup(
                latestTabs = listOf(saving),
                merge = preCleanupMerge,
                deferredUpdate = null,
                rotateRecoveryIdentity = true,
                freshRecoveryId = "fresh-recovery",
            ),
        ).tab

        assertFalse(merged.dirty)
        assertEquals("fresh-recovery", merged.recoveryId)
        assertNotEquals(saving.recoveryId, merged.recoveryId)
    }

    @Test
    fun `recovery rotation changes only the exact live tab`() {
        val exact = tab("doc-a", "space-a").copy(
            instanceId = 41,
            recoveryId = "retired-recovery",
            revision = 7,
            editGeneration = 3,
        )
        val reopenedSameDocument = exact.copy(
            instanceId = 42,
            recoveryId = "retired-recovery",
        )
        val unrelatedSameRecovery = tab("doc-b", "space-a").copy(
            instanceId = 43,
            recoveryId = "retired-recovery",
        )
        val request = DocumentTabRequest.capture(exact)
        val tabs = listOf(reopenedSameDocument, unrelatedSameRecovery, exact)

        val rotated = rotateDocumentTabRecoveryIdentity(
            tabs = tabs,
            request = request,
            retiredRecoveryId = exact.recoveryId,
            freshRecoveryId = "fresh-recovery",
        )

        assertSame(reopenedSameDocument, rotated[0])
        assertSame(unrelatedSameRecovery, rotated[1])
        assertEquals(exact.copy(recoveryId = "fresh-recovery"), rotated[2])
        assertEquals("fresh-recovery", rotated[2].recoveryId)

        val alreadyAdvanced = rotated
        assertSame(
            alreadyAdvanced,
            rotateDocumentTabRecoveryIdentity(
                tabs = alreadyAdvanced,
                request = request,
                retiredRecoveryId = exact.recoveryId,
                freshRecoveryId = "must-not-apply",
            ),
        )
    }

    @Test
    fun `late response for a branch cannot overwrite its newer request`() = runTest {
        val gate = DocumentBranchRequestGate()
        val delayedResponse = CompletableDeferred<List<String>>()
        val older = gate.begin("space-a", "parent-a")
        var committed: List<String>? = null

        val requestJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val response = delayedResponse.await()
            if (gate.isCurrent(older)) committed = response
            gate.finish(older)
        }
        val newer = gate.begin("space-a", "parent-a")
        delayedResponse.complete(listOf("stale-child"))
        requestJob.join()

        assertNull(committed)
        assertTrue(gate.isCurrent(newer))
        assertEquals(1, gate.inFlightCount)
        gate.finish(newer)
        assertEquals(0, gate.inFlightCount)
        assertFalse(gate.isCurrent(newer), "a finished response must not remain an admitted capability")
    }

    @Test
    fun `refresh or switch invalidates old branches without losing in flight accounting`() {
        val gate = DocumentBranchRequestGate()
        val oldRoot = gate.begin("space-a", null)
        val oldChild = gate.begin("space-a", "parent-a")
        val independent = gate.begin("space-b", null)
        assertTrue(gate.isCurrent(oldRoot))
        assertTrue(gate.isCurrent(oldChild))
        assertTrue(gate.isCurrent(independent))

        gate.invalidateAll()
        val currentRoot = gate.begin("space-b", null)

        assertFalse(gate.isCurrent(oldRoot))
        assertFalse(gate.isCurrent(oldChild))
        assertFalse(gate.isCurrent(independent))
        assertTrue(gate.isCurrent(currentRoot))
        assertEquals(4, gate.inFlightCount)
        listOf(oldRoot, oldChild, independent).forEach(gate::finish)
        assertEquals(1, gate.inFlightCount)
        gate.finish(currentRoot)
        assertEquals(0, gate.inFlightCount)
    }

    @Test
    fun `expected branch space cannot be retargeted after selection switches`() {
        assertEquals("space-a", resolveDocumentBranchSpace("space-a", expectedSpaceId = "space-a"))
        assertNull(resolveDocumentBranchSpace("space-b", expectedSpaceId = "space-a"))
        assertEquals("space-b", resolveDocumentBranchSpace("space-b", expectedSpaceId = null))
        assertNull(resolveDocumentBranchSpace(selectedSpaceId = null, expectedSpaceId = "space-a"))
    }

    @Test
    fun `delete invalidates parent and deleted child branches only`() {
        val gate = DocumentBranchRequestGate()
        val parent = gate.begin("space-a", "parent-a")
        val deletedChild = gate.begin("space-a", "doc-a")
        val sibling = gate.begin("space-a", "doc-b")

        gate.invalidate("space-a", "parent-a")
        gate.invalidate("space-a", "doc-a")

        assertFalse(gate.isCurrent(parent))
        assertFalse(gate.isCurrent(deletedChild))
        assertTrue(gate.isCurrent(sibling))
    }

    @Test
    fun `confirmed delete evicts the row and its lazy branch before projection reload`() {
        val deleted = node("doc-a", parentId = "parent-a")
        val sibling = node("doc-b", parentId = "parent-a")
        val tree: Map<String?, List<DocumentNode>> = mapOf(
            "parent-a" to listOf(deleted, sibling),
            deleted.nodeId to emptyList(),
            sibling.nodeId to emptyList(),
        )

        val converged = removeDeletedDocumentTreeIdentity(
            treeChildren = tree,
            spaceId = "space-a",
            documentId = deleted.nodeId,
        )

        assertEquals(listOf(sibling), converged["parent-a"])
        assertFalse(converged.containsKey(deleted.nodeId))
        assertEquals(emptyList(), converged[sibling.nodeId])
        assertTrue(converged.values.flatten().none { it.nodeId == deleted.nodeId })
    }

    @Test
    fun `new document target is explicitly top level or a child page`() {
        val root = node("root", parentId = null, hasChildren = true)
        val parent = node("parent", parentId = root.nodeId)
        val tree = mapOf(null to listOf(root), root.nodeId to listOf(parent))

        assertEquals(
            DocumentCreationLocation(parentId = null, ancestorIds = emptyList()),
            documentCreationLocation("space-a", parentId = null, treeChildren = tree),
        )
        assertEquals(
            DocumentCreationLocation(parentId = "parent", ancestorIds = listOf("root", "parent")),
            documentCreationLocation("space-a", parentId = "parent", treeChildren = tree),
        )
        assertNull(documentCreationLocation("space-a", parentId = "missing", treeChildren = tree))
    }

    @Test
    fun `manual refresh follows cross device move without overwriting a dirty draft`() {
        val dirty = tab("doc-a", "space-a").copy(
            parentId = "old-parent",
            ancestorIds = listOf("old-root", "old-parent"),
            savedTitle = "旧基线",
            savedMarkdown = "旧远端正文",
            draftTitle = "本地标题",
            draftMarkdown = "本地未保存正文",
            revision = 3,
            dirty = true,
            editGeneration = 8,
        )
        val movedRemote = document(
            id = "doc-a",
            title = "另一端标题",
            markdown = "另一端正文",
            revision = 4,
            parentId = "new-parent",
            ancestorIds = listOf("new-root", "new-section", "new-parent"),
        )

        val merged = requireNotNull(mergeDocumentRefresh(dirty, movedRemote))
        assertEquals("new-parent", merged.parentId)
        assertEquals(listOf("new-root", "new-section", "new-parent"), merged.ancestorIds)
        assertEquals("本地标题", merged.draftTitle)
        assertEquals("本地未保存正文", merged.draftMarkdown)
        assertEquals("旧基线", merged.savedTitle)
        assertEquals("旧远端正文", merged.savedMarkdown)
        assertEquals(3L, merged.revision)
        assertEquals(8L, merged.editGeneration)
        assertTrue(merged.dirty)
    }

    @Test
    fun `manual refresh adopts a newer remote snapshot for a clean moved document`() {
        val clean = tab("doc-a", "space-a").copy(
            parentId = "old-parent",
            ancestorIds = listOf("old-parent"),
            revision = 3,
            dirty = false,
            editGeneration = 5,
        )
        val movedRemote = document(
            id = "doc-a",
            title = "远端新标题",
            markdown = "远端新正文",
            revision = 4,
            parentId = "new-parent",
            ancestorIds = listOf("new-root", "new-parent"),
        )

        val merged = requireNotNull(mergeDocumentRefresh(clean, movedRemote))
        assertEquals("new-parent", merged.parentId)
        assertEquals(listOf("new-root", "new-parent"), merged.ancestorIds)
        assertEquals("远端新标题", merged.draftTitle)
        assertEquals("远端新正文", merged.draftMarkdown)
        assertEquals(4L, merged.revision)
        assertEquals(5L, merged.editGeneration)
        assertEquals(clean.recoveryId, merged.recoveryId)
        assertFalse(merged.dirty)
        assertNull(mergeDocumentRefresh(merged, movedRemote.copy(revision = 2)))
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
    fun `create response keeps client resource id without discarding later edits`() = runTest {
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
                id = "draft-1",
                title = "首次保存",
                markdown = "首次正文",
                revision = 1,
            ),
        )
        mergeJob.join()

        val merged = tabs.single()
        assertEquals("draft-1", merged.tabId)
        assertEquals("draft-1", merged.documentId)
        assertFalse(merged.creating)
        assertEquals("首次保存", merged.savedTitle)
        assertEquals("创建期间的新标题", merged.draftTitle)
        assertEquals("创建期间的新正文", merged.draftMarkdown)
        assertTrue(merged.dirty)
    }

    @Test
    fun `create response cannot rename the client owned document id`() {
        val draft = tab("draft-1", "space-a").copy(
            instanceId = 41L,
            documentId = null,
            revision = null,
            creating = true,
            dirty = true,
        )

        assertNull(
            mergeDocumentMutationResponse(
                tabs = listOf(draft),
                request = DocumentTabRequest.capture(draft),
                saved = document("different-server-id", "已保存", "正文", revision = 1),
            ),
        )
    }

    @Test
    fun `deferred tree completion re-resolves create result by instance intent`() = runTest {
        val resourceId = "7a548497-744b-4d0d-9c5f-f542a309f738"
        var tabs = listOf(
            tab(resourceId, "space-a").copy(
                instanceId = 41L,
                documentId = null,
                parentId = "old-parent",
                ancestorIds = listOf("old-parent"),
                revision = null,
                creating = true,
                dirty = true,
            ),
        )
        val createRequest = DocumentTabRequest.capture(tabs.single())
        val generation = 7L
        val intent = DocumentTabNavigationIntent.capture(tabs.single(), generation)
        val tree = CompletableDeferred<Unit>()
        val selectedSpaceId = "space-a"
        var currentGeneration = generation
        var resolvedAfterTree: DocumentTabState? = null

        val navigation = launch(start = CoroutineStart.UNDISPATCHED) {
            tree.await()
            resolvedAfterTree = intent.resolve(tabs) { expectedGeneration, expectedSpaceId ->
                expectedGeneration == currentGeneration && expectedSpaceId == selectedSpaceId
            }
        }
        tabs = requireNotNull(
            mergeDocumentMutationResponse(
                tabs = tabs,
                request = createRequest,
                saved = document(
                    id = resourceId,
                    title = "已保存",
                    markdown = "正文",
                    revision = 1,
                    parentId = "new-parent",
                    ancestorIds = listOf("root", "new-parent"),
                ),
            ),
        ).tabs
        tree.complete(Unit)
        navigation.join()

        assertSame(tabs.single(), resolvedAfterTree)
        assertEquals(resourceId, resolvedAfterTree?.tabId)
        assertEquals(resourceId, resolvedAfterTree?.documentId)
        assertEquals("new-parent", resolvedAfterTree?.parentId)

        currentGeneration++
        assertNull(
            intent.resolve(tabs) { expectedGeneration, expectedSpaceId ->
                expectedGeneration == currentGeneration && expectedSpaceId == selectedSpaceId
            },
        )
    }

    @Test
    fun `deferred reopen resolves the latest edit and rejects a replacement instance`() {
        val original = tab("doc-a", "space-a").copy(
            instanceId = 41L,
            draftMarkdown = "before request",
            editGeneration = 1L,
        )
        val intent = DocumentTabNavigationIntent.capture(original, generation = 7L)
        val edited = original.copy(
            draftMarkdown = "typed while request was pending",
            dirty = true,
            editGeneration = 2L,
        )

        assertSame(edited, intent.resolve(listOf(edited)) { generation, spaceId ->
            generation == 7L && spaceId == "space-a"
        })

        val reopened = edited.copy(instanceId = 42L, draftMarkdown = "replacement instance")
        assertNull(intent.resolve(listOf(reopened)) { _, _ -> true })
    }

    @Test
    fun `failed deferred tree still resolves a dirty tab in another space`() = runTest {
        val dirty = tab("7a548497-744b-4d0d-9c5f-f542a309f738", "space-b").copy(
            instanceId = 41L,
            dirty = true,
            pathResolved = false,
        )
        val generation = 9L
        val intent = DocumentTabNavigationIntent.capture(dirty, generation)
        val tree = CompletableDeferred<Unit>()
        val treeFailure = IllegalStateException("tree offline")
        var fallback: DocumentTabState? = null

        val navigation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                tree.await()
            } catch (_: IllegalStateException) {
                fallback = intent.resolveLocalDraftAfterDirectoryFailure(
                    listOf(dirty),
                ) { expectedGeneration, expectedSpaceId ->
                    expectedGeneration == generation && expectedSpaceId == dirty.spaceId
                }
            }
        }
        tree.completeExceptionally(treeFailure)
        navigation.join()

        assertSame(dirty, fallback)
        assertNull(fallback?.resolvedParentIdForNavigation())
        assertNull(
            intent.resolveLocalDraftAfterDirectoryFailure(listOf(dirty.copy(dirty = false))) { _, _ ->
                true
            },
        )
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

    @Test
    fun `move request requires a clean saved tab and captures destination identity`() {
        val clean = tab("doc-a", "space-a").copy(
            instanceId = 41,
            parentId = "old-parent",
            ancestorIds = listOf("root", "old-parent"),
            revision = 7,
        )

        val request = requireNotNull(
            DocumentMoveRequest.capture(
                clean,
                targetParentId = "new-parent",
            ),
        )

        assertEquals(41L, request.instanceId)
        assertEquals("old-parent", request.oldParentId)
        assertEquals("new-parent", request.targetParentId)
        assertNull(DocumentMoveRequest.capture(clean.copy(dirty = true), "new-parent"))
        assertNull(DocumentMoveRequest.capture(clean.copy(pathResolved = false), "new-parent"))
        assertNull(DocumentMoveRequest.capture(clean, "old-parent"))
    }

    @Test
    fun `rename request canonicalizes editor whitespace before strict acknowledgement matching`() {
        val original = tab("doc-a", "space-a").copy(
            instanceId = 41,
            savedTitle = "旧名称",
            draftTitle = " 新名称 ",
            revision = 7,
            dirty = true,
        )
        val request = requireNotNull(DocumentMoveRequest.captureRename(original, original.draftTitle))
        assertEquals("新名称", request.title)
        assertTrue(
            moveResult(
                node("doc-a", original.parentId).copy(name = "新名称", revision = 8),
                emptyList(),
            ).matchesDocumentMoveRequest(request),
        )
    }

    @Test
    fun `late move response cannot mutate switched revision or reopened instance`() {
        val original = tab("doc-a", "space-a").copy(instanceId = 41, revision = 7)
        val request = requireNotNull(DocumentMoveRequest.capture(original, "new-parent"))
        val moved = moveResult(
            node("doc-a", parentId = "new-parent").copy(revision = 8),
            listOf("new-parent"),
        )

        assertNull(mergeDocumentMoveResponse(listOf(original.copy(revision = 8)), request, moved))
        assertNull(mergeDocumentMoveResponse(listOf(original.copy(instanceId = 42)), request, moved))
    }

    @Test
    fun `move response rejects a superseding name or revision`() {
        val original = tab("doc-a", "space-a").copy(instanceId = 41, revision = 7)
        val request = requireNotNull(DocumentMoveRequest.capture(original, "new-parent"))

        assertNull(
            mergeDocumentMoveResponse(
                listOf(original),
                request,
                moveResult(
                    node("doc-a", "new-parent").copy(name = "later-name", revision = 8),
                    listOf("new-parent"),
                ),
            ),
        )
        assertNull(
            mergeDocumentMoveResponse(
                listOf(original),
                request,
                moveResult(
                    node("doc-a", "new-parent").copy(revision = 9),
                    listOf("new-parent"),
                ),
            ),
        )
    }

    @Test
    fun `move response updates parent path and revision while preserving active instance`() {
        val original = tab("doc-a", "space-a").copy(
            instanceId = 41,
            parentId = "old-parent",
            ancestorIds = listOf("old-parent"),
            revision = 7,
        )
        val request = requireNotNull(
            DocumentMoveRequest.capture(original, "new-parent"),
        )
        val moved = moveResult(
            node("doc-a", parentId = "new-parent").copy(name = "doc-a", revision = 8),
            listOf("root", "new-parent"),
        )

        val merged = requireNotNull(mergeDocumentMoveResponse(listOf(original), request, moved)).single()

        assertEquals(41L, merged.instanceId)
        assertEquals("new-parent", merged.parentId)
        assertEquals(listOf("root", "new-parent"), merged.ancestorIds)
        assertEquals(8L, merged.revision)
    }

    @Test
    fun `move response advances location without discarding edits made in flight`() {
        val original = tab("doc-a", "space-a").copy(instanceId = 41, revision = 7, editGeneration = 2)
        val request = requireNotNull(
            DocumentMoveRequest.capture(original, "new-parent"),
        )
        val edited = original.copy(
            draftTitle = "移动期间继续编辑",
            draftMarkdown = "必须保留",
            dirty = true,
            editGeneration = 3,
        )

        val merged = requireNotNull(
            mergeDocumentMoveResponse(
                listOf(edited),
                request,
                moveResult(
                    node("doc-a", "new-parent").copy(revision = 8),
                    listOf("new-parent"),
                ),
            ),
        ).single()

        assertEquals("new-parent", merged.parentId)
        assertEquals(8L, merged.revision)
        assertEquals("移动期间继续编辑", merged.draftTitle)
        assertEquals("必须保留", merged.draftMarkdown)
        assertTrue(merged.dirty)
    }

    @Test
    fun `recovered rename advances baseline without overwriting a newer editor title`() {
        val pending = tab("doc-a", "space-a").copy(
            instanceId = 41,
            savedTitle = "旧名称",
            draftTitle = "名称 B",
            revision = 7,
            dirty = true,
            editGeneration = 2,
        )
        val recoveredRequest = requireNotNull(
            DocumentMoveRequest.captureRename(pending, pending.draftTitle),
        ).copy(preserveDraftTitle = true)
        val edited = pending.copy(
            draftTitle = "名称 C",
            editGeneration = 3,
        )

        val merged = requireNotNull(
            mergeDocumentMoveResponse(
                listOf(edited),
                recoveredRequest,
                moveResult(
                    node("doc-a", null).copy(name = "名称 B", revision = 8),
                    emptyList(),
                ),
            ),
        ).single()

        assertEquals("名称 B", merged.savedTitle)
        assertEquals("名称 C", merged.draftTitle)
        assertEquals(8L, merged.revision)
        assertTrue(merged.dirty)
    }

    @Test
    fun `deferred move response replaces stale tree path with server ancestors`() = runTest {
        val staleTreePath = listOf("old-root", "new-parent")
        val original = tab("doc-a", "space-a").copy(
            instanceId = 41,
            parentId = "old-parent",
            ancestorIds = listOf("old-root", "old-parent"),
            revision = 7,
        )
        val request = requireNotNull(DocumentMoveRequest.capture(original, "new-parent"))
        var tabs = listOf(original)
        val response = CompletableDeferred<DocumentMoveResult>()
        val authoritativePath = listOf("fresh-root", "relocated-section", "new-parent")
        val mergeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            val result = response.await()
            mergeDocumentMoveResponse(tabs, request, result)?.let { tabs = it }
        }

        response.complete(
            moveResult(
                node("doc-a", "new-parent").copy(revision = 8),
                authoritativePath,
            ),
        )
        mergeJob.join()

        val merged = tabs.single()
        assertEquals(authoritativePath, merged.ancestorIds)
        assertFalse(merged.ancestorIds == staleTreePath)
    }

    @Test
    fun `moving a parent invalidates other same-space paths without inventing subtree membership`() {
        val parent = tab("parent", "space-a").copy(
            instanceId = 41,
            parentId = "old-section",
            ancestorIds = listOf("old-root", "old-section"),
            revision = 7,
        )
        val child = tab("child", "space-a").copy(
            instanceId = 42,
            parentId = "parent",
            ancestorIds = listOf("old-root", "old-section", "parent"),
            draftMarkdown = "子页面未保存正文",
            dirty = true,
        )
        val grandchild = tab("grandchild", "space-a").copy(
            instanceId = 43,
            parentId = "child",
            ancestorIds = listOf("old-root", "old-section", "parent", "child"),
        )
        val otherSpace = tab("other", "space-b").copy(
            instanceId = 44,
            ancestorIds = listOf("parent"),
        )
        val localCreate = tab("local-create", "space-a").copy(
            instanceId = 45,
            documentId = null,
            parentId = "parent",
            ancestorIds = listOf("old-root", "old-section", "parent"),
            revision = null,
            dirty = true,
            creating = true,
        )
        val request = requireNotNull(DocumentMoveRequest.capture(parent, "new-section"))

        val merged = requireNotNull(
            mergeDocumentMoveResponse(
                listOf(parent, child, grandchild, otherSpace, localCreate),
                request,
                moveResult(
                    node("parent", "new-section").copy(revision = 8),
                    listOf("new-root", "new-section"),
                ),
            ),
        )

        assertEquals(listOf("new-root", "new-section"), merged[0].ancestorIds)
        assertTrue(merged[0].pathResolved)
        assertEquals(listOf("old-root", "old-section", "parent"), merged[1].ancestorIds)
        assertFalse(merged[1].pathResolved)
        assertEquals("子页面未保存正文", merged[1].draftMarkdown)
        assertTrue(merged[1].dirty)
        assertEquals(
            listOf("old-root", "old-section", "parent", "child"),
            merged[2].ancestorIds,
        )
        assertFalse(merged[2].pathResolved)
        assertSame(otherSpace, merged[3])
        assertSame(localCreate, merged[4])
        assertTrue(merged[4].pathResolved)
    }

    @Test
    fun `move result rejects cyclic or duplicate authoritative ancestor paths`() {
        val original = tab("doc-a", "space-a").copy(instanceId = 41, revision = 7)
        val request = requireNotNull(DocumentMoveRequest.capture(original, "new-parent"))
        val node = node("doc-a", "new-parent").copy(revision = 8)

        assertNull(
            mergeDocumentMoveResponse(
                listOf(original),
                request,
                moveResult(node, listOf("doc-a", "new-parent")),
            ),
        )
        assertNull(
            mergeDocumentMoveResponse(
                listOf(original),
                request,
                moveResult(node, listOf("new-parent", "new-parent")),
            ),
        )
    }

    @Test
    fun `known descendants traverse loaded collapsed branches without loops`() {
        val root = node("root", null, hasChildren = true)
        val child = node("child", "root", hasChildren = true)
        val grandchild = node("grandchild", "child")
        val tree = mapOf(
            null to listOf(root),
            "root" to listOf(child),
            "child" to listOf(grandchild),
            "grandchild" to listOf(root),
        )

        assertEquals(setOf("child", "grandchild"), knownDocumentDescendantIds("root", tree))
    }

    @Test
    fun `editing a draft does not rescan known move descendants`() {
        val root = node("root", null, hasChildren = true)
        val child = node("child", "root")
        val tree = mapOf(null to listOf(root), "root" to listOf(child))
        val projection = DocumentKnownDescendantsProjection()
        val first = projection.descendants("root", tree)

        val editedTab = tab("root", "space-a")
        updateDocumentDraftTabs(
            tabs = listOf(editedTab),
            update = DocumentDraftUpdate(
                tabId = editedTab.tabId,
                instanceId = editedTab.instanceId,
                revision = editedTab.revision,
                title = "正文编辑不属于树缓存键",
                markdown = "changed",
            ),
        )

        assertSame(first, projection.descendants("root", tree))
    }

    @Test
    fun `conflict choices are fenced by edit generation and rebase does not write`() {
        val local = tab("doc-a", "space-a").copy(
            instanceId = 41,
            savedTitle = "旧标题",
            savedMarkdown = "旧正文",
            draftTitle = "我的标题",
            draftMarkdown = "我的正文",
            revision = 7,
            dirty = true,
            editGeneration = 3,
        )
        val request = DocumentTabRequest.capture(local)
        val ready = DocumentRevisionConflictState.Ready(
            request,
            document("doc-a", "服务器标题", "服务器正文", revision = 8),
        )

        val rebased = requireNotNull(rebaseDocumentConflictKeepingDraft(listOf(local), ready)).single()
        assertEquals(8L, rebased.revision)
        assertEquals("服务器标题", rebased.savedTitle)
        assertEquals("我的标题", rebased.draftTitle)
        assertEquals("我的正文", rebased.draftMarkdown)
        assertEquals(local.recoveryId, rebased.recoveryId)
        assertTrue(rebased.dirty)

        val adopted = requireNotNull(adoptDocumentConflictServerVersion(listOf(local), ready)).single()
        assertEquals("服务器标题", adopted.draftTitle)
        assertEquals("服务器正文", adopted.draftMarkdown)
        assertNotEquals(local.recoveryId, adopted.recoveryId)
        assertFalse(adopted.dirty)

        val editedLater = local.copy(draftMarkdown = "冲突读取期间继续输入", editGeneration = 4)
        assertNull(rebaseDocumentConflictKeepingDraft(listOf(editedLater), ready))
        assertNull(adoptDocumentConflictServerVersion(listOf(editedLater), ready))
    }

    @Test
    fun `remote deleted draft becomes a new idempotent create without retiring recovery early`() {
        val missing = tab(
            "37f76143-6234-4c4b-b7f8-420668c1029c",
            "144714d7-115f-4386-a959-7590a5489202",
        ).copy(
            instanceId = 41L,
            recoveryId = "4ce7403d-825e-4166-8929-63bb55d7837e",
            parentId = "stale-parent",
            ancestorIds = listOf("stale-root", "stale-parent"),
            pathResolved = false,
            remoteMissing = true,
            savedTitle = "旧服务端标题",
            savedMarkdown = "旧服务端正文",
            draftTitle = "保留的本机标题",
            draftMarkdown = "保留的本机正文",
            revision = 7L,
            dirty = true,
            editGeneration = 9L,
        )
        val newId = "82be6656-5c64-4f68-933e-9c96ba12a173"

        val prepared = requireNotNull(
            prepareRemoteMissingDocumentCreate(
                tab = missing,
                newDocumentId = newId,
                location = DocumentCreationLocation(parentId = null, ancestorIds = emptyList()),
            ),
        )

        assertEquals(newId, prepared.tabId)
        assertNull(prepared.documentId)
        assertNull(prepared.revision)
        assertNull(prepared.parentId)
        assertEquals(emptyList(), prepared.ancestorIds)
        assertTrue(prepared.pathResolved)
        assertFalse(prepared.remoteMissing)
        assertTrue(prepared.creating)
        assertTrue(prepared.dirty)
        assertEquals(missing.recoveryId, prepared.recoveryId)
        assertEquals(missing.draftTitle, prepared.draftTitle)
        assertEquals(missing.draftMarkdown, prepared.draftMarkdown)
        assertEquals("", prepared.savedTitle)
        assertEquals("", prepared.savedMarkdown)
        assertNull(DocumentRequestTarget.from(missing))
        assertNull(DocumentDeleteRequest.capture(missing))
        assertEquals(newId, PendingDocumentCreateCommand.capture(prepared)?.documentId)
    }

    @Test
    fun `only an existing dirty remote-missing tab may become a create`() {
        val location = DocumentCreationLocation(parentId = null, ancestorIds = emptyList())
        val newId = "82be6656-5c64-4f68-933e-9c96ba12a173"

        assertNull(prepareRemoteMissingDocumentCreate(tab("doc", "space-a"), newId, location))
        assertNull(
            prepareRemoteMissingDocumentCreate(
                tab("doc", "space-a").copy(remoteMissing = true, pathResolved = false),
                newId,
                location,
            ),
        )
        assertNull(
            prepareRemoteMissingDocumentCreate(
                tab("doc", "space-a").copy(
                    remoteMissing = true,
                    pathResolved = false,
                    dirty = true,
                ),
                "not-a-uuid",
                location,
            ),
        )
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

    private fun documentSpace(spaceId: String) = DocumentSpace(
        spaceId = spaceId,
        name = "空间-$spaceId",
        myRole = DocumentSpace.ROLE_EDITOR,
        createdBy = "owner",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun node(id: String, parentId: String?, hasChildren: Boolean = false) = DocumentNode(
        nodeId = id,
        spaceId = "space-a",
        parentId = parentId,
        name = id,
        hasChildren = hasChildren,
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
        parentId: String? = null,
        ancestorIds: List<String> = emptyList(),
    ) = Document(
        documentId = id,
        spaceId = "space-a",
        parentId = parentId,
        title = title,
        markdown = markdown,
        revision = revision,
        createdBy = "u1",
        createdAt = 1,
        updatedBy = "u1",
        updatedAt = revision,
        ancestorIds = ancestorIds,
    )

    private fun moveResult(node: DocumentNode, ancestorIds: List<String>) = DocumentMoveResult(
        node = node,
        ancestorIds = ancestorIds,
    )
}

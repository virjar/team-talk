package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentMoveResult
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentPathSpine
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.EmbeddedAsset
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDocumentProjectionStoreTest {
    @Test
    fun `complete branch derives sibling order from creation identity`() {
        newMemoryCache().useForTest { cache ->
            val older = node("node-z", null, false).copy(name = "Zulu", createdAt = 1L)
            val sameTimeEarlierId = node("node-a", null, false).copy(name = "Zulu", createdAt = 2L)
            val sameTimeLaterId = node("node-b", null, false).copy(name = "Alpha", createdAt = 2L)
            val lease = cache.beginDocumentBranchSnapshot(SPACE_ID, null)

            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    lease,
                    SPACE_ID,
                    null,
                    listOf(sameTimeLaterId, older, sameTimeEarlierId),
                ),
            )
            assertEquals(
                listOf(older, sameTimeEarlierId, sameTimeLaterId),
                cache.getDocumentNodes(SPACE_ID, null),
            )
        }
    }

    @Test
    fun `path spine rename keeps complete branch order across restart`() {
        val path = Files.createTempFile("team-talk-document-spine-order-", ".db")
        path.deleteIfExists()
        val middle = node("middle", null, false).copy(name = "Middle")
        val renamedBefore = node("renamed", null, false).copy(name = "Zulu")
        val renamedAfter = renamedBefore.copy(name = "Alpha", revision = 2L)
        try {
            newCache(path.absolutePathString(), create = true).useForTest { cache ->
                val spaces = cache.beginDocumentSpaceSnapshot()
                assertTrue(cache.applyDocumentSpaceSnapshot(spaces, listOf(space())))
                val rootBranch = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
                assertTrue(
                    cache.applyDocumentBranchSnapshot(
                        rootBranch,
                        SPACE_ID,
                        null,
                        listOf(middle, renamedBefore),
                    ),
                )
                val home = cache.beginDocumentHomeSnapshot(DocumentHomeCollection.RECENT)
                assertTrue(
                    cache.applyDocumentHomeSnapshot(
                        home,
                        DocumentHomeCollection.RECENT,
                        listOf(homeItem(renamedBefore.nodeId).copy(title = renamedBefore.name)),
                    ),
                )
                val pathLease = cache.beginDocumentPathSpineSnapshot(SPACE_ID, renamedAfter.nodeId)
                assertTrue(
                    cache.applyDocumentPathSpineSnapshot(
                        pathLease,
                        SPACE_ID,
                        renamedAfter.nodeId,
                        DocumentPathSpine(listOf(renamedAfter)),
                    ),
                )
                assertEquals(
                    listOf(middle, renamedAfter),
                    cache.getDocumentNodes(SPACE_ID, null),
                )
                assertEquals("Alpha", cache.getDocumentHome(DocumentHomeCollection.RECENT).single().title)
            }

            newCache(path.absolutePathString(), create = false).useForTest { cache ->
                assertTrue(cache.isDocumentBranchCached(SPACE_ID, null))
                assertEquals(
                    listOf(middle, renamedAfter),
                    cache.getDocumentNodes(SPACE_ID, null),
                )
                assertEquals("Alpha", cache.getDocumentHome(DocumentHomeCollection.RECENT).single().title)
            }
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun `partial path spine is durable but never marks or exposes an incomplete branch`() {
        val path = Files.createTempFile("team-talk-document-spine-", ".db")
        path.deleteIfExists()
        val sibling = node("sibling", null, false)
        val root = node("root", null, true)
        val middle = node("middle", "root", true)
        val target = node("target", "middle", false)
        val spine = DocumentPathSpine(listOf(root.copy(revision = 2L), middle, target))
        try {
            newCache(path.absolutePathString(), create = true).useForTest { cache ->
                val spaces = cache.beginDocumentSpaceSnapshot()
                assertTrue(cache.applyDocumentSpaceSnapshot(spaces, listOf(space())))
                val rootBranch = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
                assertTrue(
                    cache.applyDocumentBranchSnapshot(rootBranch, SPACE_ID, null, listOf(sibling, root)),
                )

                val staleChildBranch = cache.beginDocumentBranchSnapshot(SPACE_ID, root.nodeId)
                val pathLease = cache.beginDocumentPathSpineSnapshot(SPACE_ID, target.nodeId)
                assertTrue(
                    cache.applyDocumentPathSpineSnapshot(
                        pathLease,
                        SPACE_ID,
                        target.nodeId,
                        spine,
                    ),
                )

                assertEquals(spine, cache.getDocumentPathSpine(SPACE_ID, target.nodeId))
                assertTrue(cache.isDocumentBranchCached(SPACE_ID, null))
                assertEquals(
                    listOf("root", "sibling"),
                    cache.getDocumentNodes(SPACE_ID, null).map(DocumentNode::nodeId),
                )
                assertEquals(
                    2L,
                    cache.getDocumentNodes(SPACE_ID, null).first { it.nodeId == root.nodeId }.revision,
                )
                assertFalse(cache.isDocumentBranchCached(SPACE_ID, root.nodeId))
                assertEquals(emptyList(), cache.getDocumentNodes(SPACE_ID, root.nodeId))
                val bodyLease = cache.beginDocumentBodySnapshot(SPACE_ID, target.nodeId)
                assertTrue(
                    cache.applyDocumentBodySnapshot(
                        bodyLease,
                        document(target.nodeId, middle.nodeId, listOf(root.nodeId, middle.nodeId)),
                    ),
                )
                assertEquals(spine, cache.getDocumentPathSpine(SPACE_ID, target.nodeId))
                assertFalse(cache.isDocumentBranchCached(SPACE_ID, middle.nodeId))
                assertFalse(
                    cache.applyDocumentBranchSnapshot(
                        staleChildBranch,
                        SPACE_ID,
                        root.nodeId,
                        listOf(middle),
                    ),
                )
            }

            newCache(path.absolutePathString(), create = false).useForTest { cache ->
                assertEquals(spine, cache.getDocumentPathSpine(SPACE_ID, target.nodeId))
                assertFalse(cache.isDocumentBranchCached(SPACE_ID, root.nodeId))
                assertEquals(emptyList(), cache.getDocumentNodes(SPACE_ID, root.nodeId))

                val completeChild = cache.beginDocumentBranchSnapshot(SPACE_ID, root.nodeId)
                assertTrue(
                    cache.applyDocumentBranchSnapshot(
                        completeChild,
                        SPACE_ID,
                        root.nodeId,
                        listOf(middle),
                    ),
                )
                assertTrue(cache.isDocumentBranchCached(SPACE_ID, root.nodeId))
                assertEquals(listOf(middle), cache.getDocumentNodes(SPACE_ID, root.nodeId))

                val completeLeaf = cache.beginDocumentBranchSnapshot(SPACE_ID, middle.nodeId)
                assertTrue(
                    cache.applyDocumentBranchSnapshot(
                        completeLeaf,
                        SPACE_ID,
                        middle.nodeId,
                        emptyList(),
                    ),
                )
                assertNull(cache.getDocumentPathSpine(SPACE_ID, target.nodeId))
                assertTrue(cache.isDocumentBranchCached(SPACE_ID, middle.nodeId))
            }
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun `complete branch and relationship changes fence an older path spine lease`() {
        val cache = newMemoryCache()
        try {
            val root = node("root", null, true)
            val target = node("target", "root", false)
            val spine = DocumentPathSpine(listOf(root, target))
            val staleAfterBranch = cache.beginDocumentPathSpineSnapshot(SPACE_ID, target.nodeId)
            val rootBranch = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
            assertTrue(cache.applyDocumentBranchSnapshot(rootBranch, SPACE_ID, null, listOf(root)))
            assertFalse(
                cache.applyDocumentPathSpineSnapshot(
                    staleAfterBranch,
                    SPACE_ID,
                    target.nodeId,
                    spine,
                ),
            )

            val staleAfterBody = cache.beginDocumentPathSpineSnapshot(SPACE_ID, target.nodeId)
            val bodyLease = cache.beginDocumentBodySnapshot(SPACE_ID, root.nodeId)
            assertTrue(cache.applyDocumentBodySnapshot(bodyLease, document("root", null, emptyList())))
            assertFalse(
                cache.applyDocumentPathSpineSnapshot(
                    staleAfterBody,
                    SPACE_ID,
                    target.nodeId,
                    spine,
                ),
            )
        } finally {
            cache.close()
        }
    }

    @Test
    fun `path spine fences stale old-parent and target-child branch reads`() {
        val cache = newMemoryCache()
        try {
            val oldParent = node("old-parent", null, true)
            val newParent = node("new-parent", null, false)
            val moving = node("moving", oldParent.nodeId, false)
            val rootBranch = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    rootBranch,
                    SPACE_ID,
                    null,
                    listOf(oldParent, newParent),
                ),
            )
            val oldParentSeed = cache.beginDocumentBranchSnapshot(SPACE_ID, oldParent.nodeId)
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    oldParentSeed,
                    SPACE_ID,
                    oldParent.nodeId,
                    listOf(moving),
                ),
            )

            val staleOldParent = cache.beginDocumentBranchSnapshot(SPACE_ID, oldParent.nodeId)
            val staleTargetChildren = cache.beginDocumentBranchSnapshot(SPACE_ID, moving.nodeId)
            val spine = DocumentPathSpine(
                listOf(
                    newParent.copy(hasChildren = true),
                    moving.copy(parentId = newParent.nodeId, revision = 2L),
                ),
            )
            val pathLease = cache.beginDocumentPathSpineSnapshot(SPACE_ID, moving.nodeId)
            assertTrue(
                cache.applyDocumentPathSpineSnapshot(
                    pathLease,
                    SPACE_ID,
                    moving.nodeId,
                    spine,
                ),
            )

            assertFalse(
                cache.applyDocumentBranchSnapshot(
                    staleOldParent,
                    SPACE_ID,
                    oldParent.nodeId,
                    listOf(moving),
                ),
            )
            assertFalse(
                cache.applyDocumentBranchSnapshot(
                    staleTargetChildren,
                    SPACE_ID,
                    moving.nodeId,
                    listOf(node("stale-child", moving.nodeId, false)),
                ),
            )
            assertEquals(spine, cache.getDocumentPathSpine(SPACE_ID, moving.nodeId))
            assertFalse(cache.isDocumentBranchCached(SPACE_ID, oldParent.nodeId))
            assertFalse(cache.isDocumentBranchCached(SPACE_ID, moving.nodeId))
        } finally {
            cache.close()
        }
    }

    @Test
    fun `ordered tree body assets and empty snapshot markers survive restart`() {
        val path = Files.createTempFile("team-talk-document-projection-", ".db")
        path.deleteIfExists()
        try {
            newCache(path.absolutePathString(), create = true).useForTest { cache ->
                val spaces = cache.beginDocumentSpaceSnapshot()
                assertTrue(cache.applyDocumentSpaceSnapshot(spaces, listOf(space())))

                val root = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
                assertTrue(
                    cache.applyDocumentBranchSnapshot(
                        root,
                        SPACE_ID,
                        null,
                        listOf(node("overview", null, true), node("appendix", null, false)),
                    ),
                )
                val child = cache.beginDocumentBranchSnapshot(SPACE_ID, "overview")
                assertTrue(cache.applyDocumentBranchSnapshot(child, SPACE_ID, "overview", emptyList()))

                val asset = architectureAsset()
                val body = document("overview", null, emptyList()).copy(
                    markdown = "![architecture](${EmbeddedAsset.uri(asset.assetId)})",
                    revision = 2L,
                    assets = listOf(asset),
                )
                val bodyLease = cache.beginDocumentBodySnapshot(SPACE_ID, body.documentId)
                assertTrue(cache.applyDocumentBodySnapshot(bodyLease, body))

                val homeLease = cache.beginDocumentHomeSnapshot(DocumentHomeCollection.RECENT)
                assertTrue(
                    cache.applyDocumentHomeSnapshot(
                        homeLease,
                        DocumentHomeCollection.RECENT,
                        listOf(homeItem(body.documentId)),
                    ),
                )
            }

            newCache(path.absolutePathString(), create = false).useForTest { cache ->
                assertEquals(listOf(space()), cache.getDocumentSpaces())
                assertEquals(
                    listOf("appendix", "overview"),
                    cache.getDocumentNodes(SPACE_ID, null).map(DocumentNode::nodeId),
                )
                assertFalse(cache.getDocumentNodes(SPACE_ID, null).first().hasChildren)
                assertTrue(cache.isDocumentBranchCached(SPACE_ID, "overview"))
                assertEquals(emptyList(), cache.getDocumentNodes(SPACE_ID, "overview"))
                assertTrue(cache.isDocumentSpaceSnapshotCached())
                assertTrue(cache.isDocumentHomeSnapshotCached(DocumentHomeCollection.RECENT))
                assertFalse(cache.isDocumentHomeSnapshotCached(DocumentHomeCollection.RECENTLY_CREATED))

                val asset = architectureAsset()
                val expectedBody = document("overview", null, emptyList()).copy(
                    markdown = "![architecture](${EmbeddedAsset.uri(asset.assetId)})",
                    revision = 2L,
                    assets = listOf(asset),
                )
                assertEquals(expectedBody, cache.getDocumentBody(SPACE_ID, "overview"))
                val sameRevisionRefresh = cache.beginDocumentBodySnapshot(SPACE_ID, "overview")
                assertTrue(cache.applyDocumentBodySnapshot(sameRevisionRefresh, expectedBody))
                assertEquals(expectedBody, cache.getDocumentBody(SPACE_ID, "overview"))
                assertEquals(
                    listOf(homeItem("overview").copy(excerpt = "architecture")),
                    cache.getDocumentHome(DocumentHomeCollection.RECENT),
                )
            }
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun `latest projection request wins independently in each lane`() {
        newMemoryCache().useForTest { cache ->
            val oldBranch = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
            val newBranch = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
            assertFalse(
                cache.applyDocumentBranchSnapshot(
                    oldBranch,
                    SPACE_ID,
                    null,
                    listOf(node("old", null, false)),
                ),
            )
            assertTrue(cache.applyDocumentBranchSnapshot(newBranch, SPACE_ID, null, emptyList()))
            assertTrue(cache.isDocumentBranchCached(SPACE_ID, null))
            assertEquals(emptyList(), cache.getDocumentNodes(SPACE_ID, null))

            val oldBody = cache.beginDocumentBodySnapshot(SPACE_ID, "body")
            val newBody = cache.beginDocumentBodySnapshot(SPACE_ID, "body")
            assertFalse(cache.applyDocumentBodySnapshot(oldBody, document("body", null, emptyList())))
            val latestBody = document("body", null, emptyList()).copy(revision = 2L, markdown = "latest")
            assertTrue(cache.applyDocumentBodySnapshot(newBody, latestBody))
            assertEquals(latestBody, cache.getDocumentBody(SPACE_ID, "body"))

            val oldMutation = cache.beginDocumentSpaceMutationSnapshot(SPACE_ID)
            val newMutation = cache.beginDocumentSpaceMutationSnapshot(SPACE_ID)
            assertFalse(cache.applyDocumentSpaceMutation(oldMutation, space().copy(name = "old")))
            val latestSpace = space().copy(name = "latest", updatedAt = 3L)
            assertTrue(cache.applyDocumentSpaceMutation(newMutation, latestSpace))
            assertEquals(listOf(latestSpace), cache.getDocumentSpaces())
        }
    }

    @Test
    fun `higher revision wins across branches and equal revision conflict fails closed`() {
        newMemoryCache().useForTest { cache ->
            val oldParent = cache.beginDocumentBranchSnapshot(SPACE_ID, "old-parent")
            val newParent = cache.beginDocumentBranchSnapshot(SPACE_ID, "new-parent")
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    newParent,
                    SPACE_ID,
                    "new-parent",
                    listOf(node("moving", "new-parent", false).copy(revision = 2L)),
                ),
            )
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    oldParent,
                    SPACE_ID,
                    "old-parent",
                    listOf(node("moving", "old-parent", false)),
                ),
            )
            assertEquals(emptyList(), cache.getDocumentNodes(SPACE_ID, "old-parent"))
            assertEquals("moving", cache.getDocumentNodes(SPACE_ID, "new-parent").single().nodeId)

            val derivedChildren = cache.beginDocumentBranchSnapshot(SPACE_ID, "new-parent")
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    derivedChildren,
                    SPACE_ID,
                    "new-parent",
                    listOf(node("moving", "new-parent", true).copy(revision = 2L)),
                ),
            )
            assertTrue(cache.getDocumentNodes(SPACE_ID, "new-parent").single().hasChildren)

            val conflict = cache.beginDocumentBranchSnapshot(SPACE_ID, "old-parent")
            assertFailsWith<IllegalStateException> {
                cache.applyDocumentBranchSnapshot(
                    conflict,
                    SPACE_ID,
                    "old-parent",
                    listOf(node("moving", "old-parent", false).copy(revision = 2L)),
                )
            }
        }
    }

    @Test
    fun `body revision never rolls back and equal revision conflict fails closed`() {
        newMemoryCache().useForTest { cache ->
            val newer = document("body", null, emptyList()).copy(revision = 2L, markdown = "new")
            val newLease = cache.beginDocumentBodySnapshot(SPACE_ID, "body")
            assertTrue(cache.applyDocumentBodySnapshot(newLease, newer))

            val rollback = cache.beginDocumentBodySnapshot(SPACE_ID, "body")
            assertFalse(cache.applyDocumentBodySnapshot(rollback, document("body", null, emptyList())))
            assertEquals(newer, cache.getDocumentBody(SPACE_ID, "body"))

            val conflict = cache.beginDocumentBodySnapshot(SPACE_ID, "body")
            assertFailsWith<IllegalStateException> {
                cache.applyDocumentBodySnapshot(conflict, newer.copy(markdown = "conflict"))
            }
        }
    }

    @Test
    fun `space index capacity eviction retains independently bounded branch and body`() {
        newMemoryCache().useForTest { cache ->
            val protected = space("protected")
            val initial = List(LocalDocumentProjectionLimits.MAX_SPACES - 1) { space("space-$it") } + protected
            val spacesLease = cache.beginDocumentSpaceSnapshot()
            assertTrue(cache.applyDocumentSpaceSnapshot(spacesLease, initial))

            val branchLease = cache.beginDocumentBranchSnapshot(protected.spaceId, null)
            val protectedNode = node("protected-doc", null, false, protected.spaceId)
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    branchLease,
                    protected.spaceId,
                    null,
                    listOf(protectedNode),
                ),
            )
            val protectedBody = document("protected-doc", null, emptyList(), protected.spaceId)
            val bodyLease = cache.beginDocumentBodySnapshot(protected.spaceId, protectedBody.documentId)
            assertTrue(cache.applyDocumentBodySnapshot(bodyLease, protectedBody))

            val pageLease = cache.beginDocumentSpaceSnapshot()
            assertTrue(cache.applyDocumentSpacePage(pageLease, listOf(space("new-space")), isFirstPage = true))

            assertEquals(LocalDocumentProjectionLimits.MAX_SPACES, cache.getDocumentSpaces().size)
            assertFalse(cache.getDocumentSpaces().any { it.spaceId == protected.spaceId })
            assertTrue(cache.isDocumentBranchCached(protected.spaceId, null))
            assertEquals(protectedBody, cache.getDocumentBody(protected.spaceId, protectedBody.documentId))
        }
    }

    @Test
    fun `complete space snapshot removes evicted space data and fences earlier mutations`() {
        newMemoryCache().useForTest { cache ->
            val protected = space("protected")
            val initial = List(LocalDocumentProjectionLimits.MAX_SPACES - 1) { space("space-$it") } + protected
            val spacesLease = cache.beginDocumentSpaceSnapshot()
            assertTrue(cache.applyDocumentSpaceSnapshot(spacesLease, initial))

            val protectedBody = document("protected-doc", null, emptyList(), protected.spaceId)
            val branchLease = cache.beginDocumentBranchSnapshot(protected.spaceId, null)
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    branchLease,
                    protected.spaceId,
                    null,
                    listOf(node(protectedBody.documentId, null, false, protected.spaceId)),
                ),
            )
            val bodyLease = cache.beginDocumentBodySnapshot(protected.spaceId, protectedBody.documentId)
            assertTrue(cache.applyDocumentBodySnapshot(bodyLease, protectedBody))

            val partialPage = cache.beginDocumentSpaceSnapshot()
            val visible = space("new-space")
            assertTrue(cache.applyDocumentSpacePage(partialPage, listOf(visible), isFirstPage = true))
            assertFalse(cache.getDocumentSpaces().any { it.spaceId == protected.spaceId })
            assertEquals(protectedBody, cache.getDocumentBody(protected.spaceId, protectedBody.documentId))

            val lateSpaceMutation = cache.beginDocumentSpaceMutationSnapshot(protected.spaceId)
            val lateBodyMutation = cache.beginDocumentBodyMutationSnapshot(
                protected.spaceId,
                protectedBody.documentId,
            )
            val terminal = cache.beginDocumentSpaceSnapshot()
            assertTrue(cache.applyDocumentSpaceSnapshot(terminal, listOf(visible)))
            assertNull(cache.getDocumentBody(protected.spaceId, protectedBody.documentId))

            assertFalse(
                cache.applyDocumentSpaceMutation(
                    lateSpaceMutation,
                    protected.copy(name = "late", updatedAt = 3L),
                ),
            )
            assertFalse(
                cache.applyDocumentBodyMutation(
                    lateBodyMutation,
                    protectedBody.copy(revision = 2L, markdown = "late", updatedAt = 3L),
                ),
            )
            assertEquals(listOf(visible), cache.getDocumentSpaces())
            assertNull(cache.getDocumentBody(protected.spaceId, protectedBody.documentId))
        }
    }

    @Test
    fun `multi-page refresh purges omitted space tree body and home only at terminal`() {
        newMemoryCache().useForTest { cache ->
            val first = space("a-visible")
            val omitted = space()
            val last = space("z-visible")
            val seed = cache.beginDocumentSpaceSnapshot()
            assertTrue(cache.applyDocumentSpaceSnapshot(seed, listOf(first, omitted, last)))

            val omittedNode = node("omitted-doc", null, false)
            val branch = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
            assertTrue(cache.applyDocumentBranchSnapshot(branch, SPACE_ID, null, listOf(omittedNode)))
            val omittedBody = document(omittedNode.nodeId, null, emptyList())
            val body = cache.beginDocumentBodySnapshot(SPACE_ID, omittedBody.documentId)
            assertTrue(cache.applyDocumentBodySnapshot(body, omittedBody))
            val home = cache.beginDocumentHomeSnapshot(DocumentHomeCollection.RECENT)
            assertTrue(
                cache.applyDocumentHomeSnapshot(
                    home,
                    DocumentHomeCollection.RECENT,
                    listOf(homeItem(omittedBody.documentId)),
                ),
            )

            val cycle = cache.beginDocumentSpaceSnapshot()
            assertTrue(
                cache.applyDocumentSpaceRefreshPage(
                    cycle,
                    listOf(first),
                    isFirstPage = true,
                    isTerminal = false,
                ),
            )
            assertEquals(omittedBody, cache.getDocumentBody(SPACE_ID, omittedBody.documentId))
            assertTrue(cache.isDocumentBranchCached(SPACE_ID, null))
            assertEquals(1, cache.getDocumentHome(DocumentHomeCollection.RECENT).size)

            assertTrue(
                cache.applyDocumentSpaceRefreshPage(
                    cycle,
                    listOf(last),
                    isFirstPage = false,
                    isTerminal = true,
                ),
            )
            assertNull(cache.getDocumentBody(SPACE_ID, omittedBody.documentId))
            assertFalse(cache.isDocumentBranchCached(SPACE_ID, null))
            assertTrue(cache.getDocumentHome(DocumentHomeCollection.RECENT).isEmpty())
            assertEquals(listOf(first, last), cache.getDocumentSpaces())
        }
    }

    @Test
    fun `cancelled or superseded refresh cannot use omission as deletion`() {
        newMemoryCache().useForTest { cache ->
            val first = space("a-visible")
            val candidate = space()
            val seed = cache.beginDocumentSpaceSnapshot()
            assertTrue(cache.applyDocumentSpaceSnapshot(seed, listOf(first, candidate)))
            val candidateBody = document("candidate-body", null, emptyList())
            val body = cache.beginDocumentBodySnapshot(SPACE_ID, candidateBody.documentId)
            assertTrue(cache.applyDocumentBodySnapshot(body, candidateBody))

            val cancelled = cache.beginDocumentSpaceSnapshot()
            assertTrue(
                cache.applyDocumentSpaceRefreshPage(
                    cancelled,
                    listOf(first),
                    isFirstPage = true,
                    isTerminal = false,
                ),
            )
            assertTrue(cache.abandonProjectionSnapshot(cancelled))
            assertFalse(
                cache.applyDocumentSpaceRefreshPage(
                    cancelled,
                    emptyList(),
                    isFirstPage = false,
                    isTerminal = true,
                ),
            )
            assertEquals(candidateBody, cache.getDocumentBody(SPACE_ID, candidateBody.documentId))

            val stale = cache.beginDocumentSpaceSnapshot()
            val newer = cache.beginDocumentSpaceSnapshot()
            assertFalse(
                cache.applyDocumentSpaceRefreshPage(
                    stale,
                    emptyList(),
                    isFirstPage = true,
                    isTerminal = true,
                ),
            )
            assertEquals(candidateBody, cache.getDocumentBody(SPACE_ID, candidateBody.documentId))
            assertTrue(cache.abandonProjectionSnapshot(newer))
        }
    }

    @Test
    fun `committed space mutation fences terminal page from an older refresh`() {
        newMemoryCache().useForTest { cache ->
            val original = space()
            val retained = space("a-visible")
            val seed = cache.beginDocumentSpaceSnapshot()
            assertTrue(cache.applyDocumentSpaceSnapshot(seed, listOf(retained, original)))

            val refresh = cache.beginDocumentSpaceSnapshot()
            assertTrue(
                cache.applyDocumentSpaceRefreshPage(
                    refresh,
                    listOf(retained),
                    isFirstPage = true,
                    isTerminal = false,
                ),
            )
            val mutation = cache.beginDocumentSpaceMutationSnapshot(SPACE_ID)
            val updated = original.copy(name = "committed", updatedAt = 3L)
            assertTrue(cache.applyDocumentSpaceMutation(mutation, updated))

            assertFalse(
                cache.applyDocumentSpaceRefreshPage(
                    refresh,
                    emptyList(),
                    isFirstPage = false,
                    isTerminal = true,
                ),
            )
            assertEquals(updated, cache.getDocumentSpaces().single { it.spaceId == SPACE_ID })
        }
    }

    @Test
    fun `accepted body fences earlier old and new branch omissions`() {
        newMemoryCache().useForTest { cache ->
            val oldParent = "old-parent"
            val newParent = "new-parent"
            val rootLease = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    rootLease,
                    SPACE_ID,
                    null,
                    listOf(node(oldParent, null, true), node(newParent, null, true)),
                ),
            )
            val oldSeed = cache.beginDocumentBranchSnapshot(SPACE_ID, oldParent)
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    oldSeed,
                    SPACE_ID,
                    oldParent,
                    listOf(node("moving", oldParent, false)),
                ),
            )
            val newSeed = cache.beginDocumentBranchSnapshot(SPACE_ID, newParent)
            assertTrue(cache.applyDocumentBranchSnapshot(newSeed, SPACE_ID, newParent, emptyList()))

            val lateOld = cache.beginDocumentBranchSnapshot(SPACE_ID, oldParent)
            val lateNew = cache.beginDocumentBranchSnapshot(SPACE_ID, newParent)
            val moved = document("moving", newParent, listOf(newParent)).copy(revision = 2L)
            val bodyLease = cache.beginDocumentBodySnapshot(SPACE_ID, moved.documentId)
            assertTrue(cache.applyDocumentBodySnapshot(bodyLease, moved))

            assertFalse(cache.applyDocumentBranchSnapshot(lateOld, SPACE_ID, oldParent, emptyList()))
            assertFalse(cache.applyDocumentBranchSnapshot(lateNew, SPACE_ID, newParent, emptyList()))
            assertEquals(emptyList(), cache.getDocumentNodes(SPACE_ID, oldParent))
            assertEquals(listOf("moving"), cache.getDocumentNodes(SPACE_ID, newParent).map { it.nodeId })
            assertEquals(moved, cache.getDocumentBody(SPACE_ID, moved.documentId))
        }
    }

    @Test
    fun `move and delete atomically converge tree body descendants and home`() {
        newMemoryCache().useForTest { cache ->
            val parent = node("parent", null, true)
            val moving = node("moving", null, true)
            val rootLease = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
            assertTrue(cache.applyDocumentBranchSnapshot(rootLease, SPACE_ID, null, listOf(parent, moving)))
            val parentLease = cache.beginDocumentBranchSnapshot(SPACE_ID, parent.nodeId)
            val targetSibling = node("target-sibling", parent.nodeId, false).copy(createdAt = 2L)
            assertTrue(
                cache.applyDocumentBranchSnapshot(
                    parentLease,
                    SPACE_ID,
                    parent.nodeId,
                    listOf(targetSibling),
                ),
            )
            val child = node("child", moving.nodeId, false)
            val childLease = cache.beginDocumentBranchSnapshot(SPACE_ID, moving.nodeId)
            assertTrue(cache.applyDocumentBranchSnapshot(childLease, SPACE_ID, moving.nodeId, listOf(child)))
            val childBody = document(child.nodeId, moving.nodeId, listOf(moving.nodeId))
            val childBodyLease = cache.beginDocumentBodySnapshot(SPACE_ID, child.nodeId)
            assertTrue(cache.applyDocumentBodySnapshot(childBodyLease, childBody))

            val updated = document(moving.nodeId, null, emptyList()).copy(
                title = "移动页",
                markdown = "# 移动页\n正文",
                revision = 2L,
            )
            val updatedLease = cache.beginDocumentBodySnapshot(SPACE_ID, moving.nodeId)
            assertTrue(cache.applyDocumentBodySnapshot(updatedLease, updated))
            assertTrue(cache.getDocumentNodes(SPACE_ID, null).first { it.nodeId == moving.nodeId }.hasChildren)

            val movedNode = cache.getDocumentNodes(SPACE_ID, null).first { it.nodeId == moving.nodeId }.copy(
                parentId = parent.nodeId,
                revision = 3L,
                updatedAt = 3L,
            )
            val moveLease = cache.beginDocumentBodyMutationSnapshot(SPACE_ID, moving.nodeId)
            assertTrue(
                cache.applyDocumentMove(
                    moveLease,
                    DocumentMoveResult(movedNode, listOf(parent.nodeId)),
                ),
            )

            assertFalse(cache.getDocumentNodes(SPACE_ID, null).any { it.nodeId == moving.nodeId })
            assertEquals(
                listOf(moving.nodeId, targetSibling.nodeId),
                cache.getDocumentNodes(SPACE_ID, parent.nodeId).map { it.nodeId },
            )
            assertEquals(
                updated.copy(
                    parentId = parent.nodeId,
                    revision = 3L,
                    updatedAt = 3L,
                    ancestorIds = listOf(parent.nodeId),
                ),
                cache.getDocumentBody(SPACE_ID, moving.nodeId),
            )
            assertNull(cache.getDocumentBody(SPACE_ID, child.nodeId))

            val refreshedChild = childBody.copy(ancestorIds = listOf(parent.nodeId, moving.nodeId))
            val refreshedChildLease = cache.beginDocumentBodySnapshot(SPACE_ID, child.nodeId)
            assertTrue(cache.applyDocumentBodySnapshot(refreshedChildLease, refreshedChild))
            val homeLease = cache.beginDocumentHomeSnapshot(DocumentHomeCollection.RECENT)
            assertTrue(
                cache.applyDocumentHomeSnapshot(
                    homeLease,
                    DocumentHomeCollection.RECENT,
                    listOf(homeItem(child.nodeId)),
                ),
            )

            cache.purgeDocument(SPACE_ID, moving.nodeId)
            assertFalse(cache.isDocumentBranchCached(SPACE_ID, moving.nodeId))
            assertNull(cache.getDocumentBody(SPACE_ID, child.nodeId))
            assertFalse(cache.getDocumentNodes(SPACE_ID, parent.nodeId).any { it.nodeId == moving.nodeId })
            assertEquals(emptyList(), cache.getDocumentHome(DocumentHomeCollection.RECENT))
        }
    }

    @Test
    fun `body reads refresh LRU and home snapshots stay within hard bounds`() {
        newMemoryCache().useForTest { cache ->
            repeat(LocalDocumentProjectionLimits.MAX_BODIES) { index ->
                val body = document("doc-$index", null, emptyList())
                val lease = cache.beginDocumentBodySnapshot(SPACE_ID, body.documentId)
                assertTrue(cache.applyDocumentBodySnapshot(lease, body))
            }
            assertEquals("doc-0", cache.getDocumentBody(SPACE_ID, "doc-0")?.documentId)
            val newest = document("doc-${LocalDocumentProjectionLimits.MAX_BODIES}", null, emptyList())
            val newestLease = cache.beginDocumentBodySnapshot(SPACE_ID, newest.documentId)
            assertTrue(cache.applyDocumentBodySnapshot(newestLease, newest))

            assertEquals("doc-0", cache.getDocumentBody(SPACE_ID, "doc-0")?.documentId)
            assertNull(cache.getDocumentBody(SPACE_ID, "doc-1"))
            assertEquals(
                "doc-${LocalDocumentProjectionLimits.MAX_BODIES}",
                cache.getDocumentBody(
                    SPACE_ID,
                    "doc-${LocalDocumentProjectionLimits.MAX_BODIES}",
                )?.documentId,
            )

            val overflow = List(LocalDocumentProjectionLimits.MAX_HOME_ITEMS + 1) { homeItem("home-$it") }
            val lease = cache.beginDocumentHomeSnapshot(DocumentHomeCollection.RECENT)
            assertFailsWith<IllegalArgumentException> {
                cache.applyDocumentHomeSnapshot(lease, DocumentHomeCollection.RECENT, overflow)
            }
        }
    }

    @Test
    fun `server reset clears documents and close retires outstanding leases`() {
        val cache = newMemoryCache()
        val branch = cache.beginDocumentBranchSnapshot(SPACE_ID, null)
        assertTrue(cache.applyDocumentBranchSnapshot(branch, SPACE_ID, null, listOf(node("doc", null, false))))
        cache.resetServerProjection("00000000-0000-4000-8000-000000000001")
        assertFalse(cache.isDocumentBranchCached(SPACE_ID, null))
        assertFalse(cache.isDocumentSpaceSnapshotCached())

        val late = cache.beginDocumentBodySnapshot(SPACE_ID, "doc")
        cache.close()
        assertFalse(cache.applyDocumentBodySnapshot(late, document("doc", null, emptyList())))
        assertFailsWith<IllegalStateException> { cache.getDocumentSpaces() }
    }

    private fun newMemoryCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun newCache(path: String, create: Boolean): LocalCacheImpl {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        if (create) AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private inline fun LocalCacheImpl.useForTest(block: (LocalCacheImpl) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun architectureAsset() = EmbeddedAsset(
        assetId = "00000000-0000-4000-8000-000000000201",
        attachment = Attachment("owner/architecture.png", "architecture.png", "image/png", 12L),
        thumbnail = Attachment("owner/architecture-thumb.jpg", "thumb.jpg", "image/jpeg", 4L),
        width = 640,
        height = 480,
    )

    private fun space(spaceId: String = SPACE_ID) = DocumentSpace(
        spaceId = spaceId,
        name = "知识库-$spaceId",
        description = "团队资料",
        myRole = DocumentSpace.ROLE_EDITOR,
        createdBy = "owner",
        createdAt = 1L,
        updatedAt = 2L,
        policyRevision = 7L,
    )

    private fun node(
        id: String,
        parentId: String?,
        hasChildren: Boolean,
        spaceId: String = SPACE_ID,
    ) = DocumentNode(
        nodeId = id,
        spaceId = spaceId,
        parentId = parentId,
        hasChildren = hasChildren,
        name = id,
        excerpt = id,
        revision = 1L,
        createdBy = "owner",
        createdAt = 1L,
        updatedBy = "owner",
        updatedAt = 2L,
    )

    private fun document(
        id: String,
        parentId: String?,
        ancestors: List<String>,
        spaceId: String = SPACE_ID,
    ) = Document(
        documentId = id,
        spaceId = spaceId,
        parentId = parentId,
        title = id,
        markdown = "# $id",
        revision = 1L,
        createdBy = "owner",
        createdAt = 1L,
        updatedBy = "owner",
        updatedAt = 2L,
        ancestorIds = ancestors,
    )

    private fun homeItem(id: String) = DocumentHomeItem(
        documentId = id,
        spaceId = SPACE_ID,
        spaceName = "知识库",
        title = id,
        excerpt = "摘要",
        createdBy = "owner",
        creatorName = "Owner",
        createdAt = 1L,
        updatedAt = 2L,
        accessedAt = 3L,
    )

    private companion object {
        const val SPACE_ID = "space"
    }
}

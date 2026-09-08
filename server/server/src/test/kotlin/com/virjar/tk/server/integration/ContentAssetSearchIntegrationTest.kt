package com.virjar.tk.server.integration

import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.OrganizationUnit
import com.virjar.tk.server.domain.chat.ChatAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentAccessDeniedException
import com.virjar.tk.server.domain.document.DocumentService
import com.virjar.tk.server.domain.search.ContentAssetKey
import com.virjar.tk.server.domain.search.ContentAssetPending
import com.virjar.tk.server.infra.db.ContentSearchPending
import com.virjar.tk.server.infra.db.DocumentNodes
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.repository.ExposedContentAssetRepository
import com.virjar.tk.server.infra.search.ContentAssetSearchIndex
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/** Current PG authorization, durable projection recovery and bounded Lucene pagination together. */
class ContentAssetSearchIntegrationTest {
    companion object { @JvmField @RegisterExtension val ext = IntegrationTestExtension() }
    private val ctx get() = ext.env
    private val documents get() = ctx.documentService
    private val search get() = ctx.contentSearchService
    private val source get() = ExposedContentAssetRepository(ctx.database)
    private fun id() = UUID.randomUUID().toString()
    private fun documentRequest(keyword: String = "", spaceId: String = "", limit: Int = 20, cursor: String? = null) =
        ContentSearchRequest(ContentSearchRequest.KIND_DOCUMENT, keyword, spaceId, limit = limit, cursor = cursor)
    private fun fileRequest(keyword: String = "", chatId: String = "", type: Int = 0, limit: Int = 20, cursor: String? = null) =
        ContentSearchRequest(ContentSearchRequest.KIND_GROUP_FILE, keyword, chatId, type, limit, cursor)

    @Test
    fun `document search includes full body and reflects rename delete revoke and archive`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("asset-doc-owner"))
        val reader = ctx.registerUser(uniqueUsername("asset-doc-reader"))
        val outsider = ctx.registerUser(uniqueUsername("asset-doc-outsider"))
        val space = documents.createSpace(owner, "研发资料", null)
        val grant = documents.upsertGrant(owner, space.spaceId, 1, reader, DocumentSpace.ROLE_VIEWER, false, 1, id())
        val document = documents.createDocument(owner, space.spaceId, null, "接口手册", "前言 ".repeat(600) + " searchablequartz")
        val query = documentRequest("searchablequartz")
        val hit = search.search(reader, query).items.single()
        assertEquals(document.documentId, hit.targetId)
        assertEquals("研发资料", hit.scopeName)
        assertEquals(1L, hit.revision)
        assertTrue(hit.snippet.length <= 500)
        assertFalse(hit.snippet.contains("searchablequartz"), "Full body, not the 500-character excerpt, is indexed")
        assertNull(hit.mimeType)
        assertTrue(search.search(outsider, query).items.isEmpty())
        assertFailsWith<DocumentAccessDeniedException> { search.search(outsider, query.copy(scopeId = space.spaceId)) }

        documents.updateSpace(owner, space.spaceId, "当前资料名称", null)
        assertEquals("当前资料名称", search.search(reader, query).items.single().scopeName)
        val updated = documents.updateDocument(owner, space.spaceId, document.documentId, "currentbodygem", document.revision)
        assertTrue(search.search(reader, query).items.isEmpty())
        documents.moveNode(owner, space.spaceId, document.documentId, null, "最终手册", updated.revision, id(), System.currentTimeMillis())
        val renamed = search.search(reader, documentRequest("currentbodygem")).items.single()
        assertEquals("最终手册", renamed.title)
        assertEquals(3L, renamed.revision)
        val removed = documents.removeGrant(owner, space.spaceId, 1, reader, grant.policyRevision, id())
        assertTrue(search.search(reader, documentRequest("currentbodygem")).items.isEmpty())
        assertFailsWith<DocumentAccessDeniedException> { documents.getDocument(reader, space.spaceId, hit.targetId) }
        documents.upsertGrant(owner, space.spaceId, 1, reader, DocumentSpace.ROLE_VIEWER, false, removed.policyRevision, id())
        assertEquals(document.documentId, search.search(reader, documentRequest("最终手册")).items.single().targetId)
        documents.deleteNode(owner, space.spaceId, document.documentId, 3, id())
        assertTrue(search.search(reader, documentRequest("最终手册")).items.isEmpty())
        documents.createDocument(owner, space.spaceId, null, "归档资料", "archivedgem")
        assertEquals(1, search.search(reader, documentRequest("archivedgem")).items.size)
        documents.archiveSpace(owner, space.spaceId, id())
        assertTrue(search.search(reader, documentRequest("archivedgem")).items.isEmpty())
    }

    @Test
    fun `organization grants filter global results from current hierarchy without index updates`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("asset-org-owner"))
        val reader = ctx.registerUser(uniqueUsername("asset-org-reader"))
        val parent = OrganizationUnit(id(), name = "总部")
        val child = OrganizationUnit(id(), parentId = parent.unitId, name = "研发")
        ctx.seedOrganizationUnit(parent)
        ctx.seedOrganizationUnit(child)
        ctx.seedOrganizationMember(OrganizationMember(child.unitId, reader))
        val space = documents.createSpace(owner, "组织资料", null)
        val grant = documents.upsertGrant(owner, space.spaceId, 2, parent.unitId, DocumentSpace.ROLE_VIEWER, true, 1, id())
        val document = documents.createDocument(owner, space.spaceId, null, "资料", "departmentgem")
        assertEquals(document.documentId, search.search(reader, documentRequest("departmentgem")).items.single().targetId)
        documents.upsertGrant(owner, space.spaceId, 2, parent.unitId, DocumentSpace.ROLE_VIEWER, false, grant.policyRevision, id())
        assertTrue(search.search(reader, documentRequest("departmentgem")).items.isEmpty())
        assertTrue(pending(ContentAssetKey(1, document.documentId)).isEmpty(), "ACL refresh does not depend on reindexing document content")
    }

    @Test
    fun `group files filter type before paging and retain current download permissions`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("asset-file-owner"))
        val reader = ctx.registerUser(uniqueUsername("asset-file-reader"))
        val chat = ctx.chatService.createGroup("共享资料", null, owner, listOf(reader))
        repeat(12) { createFile(owner, chat.chatId, "Quarterly-$it.txt", "text/plain") }
        val images = (1..3).map { createFile(owner, chat.chatId, "Quarterly-$it.PNG", "image/png") }
        ctx.groupFileService.createFolder(owner, id(), id(), chat.chatId, null, "Quarterly-folder.PNG")
        val request = fileRequest("quarterly-", chat.chatId, ContentSearchRequest.FILE_TYPE_IMAGE, limit = 2)
        val first = search.search(reader, request)
        assertEquals(2, first.items.size)
        val second = search.search(reader, request.copy(cursor = assertNotNull(first.nextCursor)))
        assertEquals(images.map { it.entryId }.toSet(), (first.items + second.items).map { it.targetId }.toSet())
        assertEquals(3, (first.items + second.items).size)
        assertNull(second.nextCursor)
        assertTrue((first.items + second.items).all { it.mimeType == "image/png" && it.serverSeq == 0L })
        val selected = images.first()
        val path = requireNotNull(selected.attachment).path
        assertTrue(ctx.attachmentAccess.canRead(reader, path))
        val versionPath = ctx.fileStore.store(owner, "replacement.bin", "application/octet-stream", ByteArrayInputStream("replacement".encodeToByteArray()))
        val updated = ctx.groupFileService.addVersion(owner, id(), chat.chatId, selected.entryId,
            requireNotNull(ctx.fileStore.getAttachment(versionPath)), selected.revision)
        assertTrue(search.search(reader, request).items.none { it.targetId == selected.entryId })
        ctx.groupFileService.rename(owner, id(), chat.chatId, selected.entryId, "current-literal[?]*.bin", updated.revision)
        assertEquals(selected.entryId, search.search(reader, fileRequest("literal[?]*", chat.chatId)).items.single().targetId)
        val current = requireNotNull(ctx.groupFileRepo.find(selected.entryId))
        assertEquals(current.revision, search.search(reader, fileRequest("literal[?]*", chat.chatId)).items.single().revision)
        ctx.groupFileService.delete(owner, id(), chat.chatId, selected.entryId, current.revision)
        assertTrue(search.search(reader, fileRequest("literal[?]*", chat.chatId)).items.isEmpty())
        assertFalse(ctx.attachmentAccess.canRead(reader, path))
        ctx.chatService.removeMember(owner, chat.chatId, reader)
        assertTrue(search.search(reader, fileRequest("quarterly-")).items.isEmpty())
        assertFailsWith<ChatAccessDeniedException> { search.search(reader, request) }
        assertFalse(ctx.attachmentAccess.canRead(reader, requireNotNull(images.last().attachment).path))
    }

    @Test
    fun `keyset pages keep equal timestamps stable and bind cursor to actor and request`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("asset-page-owner"))
        val other = ctx.registerUser(uniqueUsername("asset-page-other"))
        val space = documents.createSpace(owner, "分页", null)
        val ids = (1..5).map { documents.createDocument(owner, space.spaceId, null, "分页 $it", "pagegem").documentId }
        transaction(ctx.database) { ids.forEach { nodeId -> DocumentNodes.update({ DocumentNodes.nodeId eq nodeId }) { it[DocumentNodes.updatedAt] = 10L } } }
        val request = documentRequest("pagegem", space.spaceId, 2)
        val first = search.search(owner, request)
        assertEquals(ids.sorted().take(2), first.items.map { it.targetId })
        val cursor = assertNotNull(first.nextCursor)
        assertFailsWith<IllegalArgumentException> { search.search(other, request.copy(cursor = cursor)) }
        assertFailsWith<IllegalArgumentException> { search.search(owner, request.copy(keyword = "different", cursor = cursor)) }
        documents.createDocument(owner, space.spaceId, null, "新插入", "pagegem")
        val second = search.search(owner, request.copy(cursor = cursor))
        val third = search.search(owner, request.copy(cursor = assertNotNull(second.nextCursor)))
        assertEquals(ids.sorted(), (first.items + second.items + third.items).map { it.targetId })
        assertNull(third.nextCursor)
    }

    @Test
    fun `dirty revisions coalesce and exact acknowledgement preserves newer writes`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("asset-queue-owner"))
        val space = documents.createSpace(owner, "事务", null)
        ctx.contentAssetIndex.catchUp()
        val nodeId = id()
        val key = ContentAssetKey(1, nodeId)
        val failing = DocumentService(ctx.documentRepo, ExposedPgUnitOfWork(ctx.database, onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { if (it == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) error("rollback fixture") }))
        assertFailsWith<IllegalStateException> { failing.createDocument(owner, nodeId, space.spaceId, null, "失败", "rollbackgem") }
        assertNull(source.projection(key))
        assertTrue(pending(key).isEmpty())
        val document = documents.createDocument(owner, nodeId, space.spaceId, null, "资料", "firstgem")
        val firstPending = pending(key).single()
        val firstProjection = assertNotNull(source.projection(key))
        documents.updateDocument(owner, space.spaceId, nodeId, "secondgem", document.revision)
        source.acknowledge(firstPending)
        assertEquals(listOf(2L), pending(key).map { it.revision })
        assertEquals(2L, search.search(owner, documentRequest("secondgem")).items.single().revision)
        assertTrue(pending(key).isEmpty())
        assertFalse(ctx.contentAssetIndex.applyProjection(firstProjection))
        documents.deleteNode(owner, space.spaceId, nodeId, 2, id())
        ctx.contentAssetIndex.catchUp()
        assertFalse(ctx.contentAssetIndex.applyProjection(firstProjection), "The durable deletion revision rejects older replays")
        assertTrue(search.search(owner, documentRequest("firstgem")).items.isEmpty())
    }

    @Test
    fun `commit before acknowledgement failure and missing index recover from current PostgreSQL`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("asset-recovery-owner"))
        val space = documents.createSpace(owner, "恢复", null)
        val document = documents.createDocument(owner, space.spaceId, null, "资料", "beforecrashgem")
        val key = ContentAssetKey(1, document.documentId)
        val directory = Files.createTempDirectory("teamtalk-asset-recovery-").toFile()
        try {
            val updated = documents.updateDocument(owner, space.spaceId, key.id, "aftercrashgem", document.revision)
            ContentAssetSearchIndex(directory, "isolated-test", source) { if (it.key == key) error("crash after commit") }.use { index ->
                index.start()
                assertFailsWith<IllegalStateException> { index.catchUp() }
            }
            assertEquals(listOf(updated.revision), pending(key).map { it.revision })
            ContentAssetSearchIndex(directory, "isolated-test", source).use { index ->
                index.start()
                index.recoverBeforeServing()
                assertTrue(pending(key).isEmpty())
                assertEquals(key, index.search(documentRequest("aftercrashgem"), setOf(space.spaceId), null, 10).single().key)
                assertTrue(index.search(documentRequest("beforecrashgem"), setOf(space.spaceId), null, 10).isEmpty())
            }
            assertTrue(directory.deleteRecursively())
            ContentAssetSearchIndex(directory, "isolated-test", source).use { index ->
                index.start()
                assertEquals(key, index.search(documentRequest("aftercrashgem"), setOf(space.spaceId), null, 10).single().key)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `startup repairs acknowledged missing rows and conflicting higher index revisions`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("asset-audit-owner"))
        val space = documents.createSpace(owner, "启动核对", null)
        val document = documents.createDocument(owner, space.spaceId, null, "正确资料", "authoritativegem")
        val key = ContentAssetKey(1, document.documentId)
        val directory = Files.createTempDirectory("teamtalk-asset-audit-").toFile()
        try {
            val current = assertNotNull(source.projection(key))
            ContentAssetSearchIndex(directory, "audit-test", source).use { index ->
                index.start()
                index.recoverBeforeServing()
                assertTrue(index.applyProjection(current.copy(revision = current.revision + 100, text = "incorrectgem")))
            }
            assertTrue(pending(key).isEmpty())
            ContentAssetSearchIndex(directory, "audit-test", source).use { index ->
                index.start()
                assertEquals(current.revision, index.search(documentRequest("authoritativegem"), setOf(space.spaceId), null, 10).single().revision)
                assertTrue(index.search(documentRequest("incorrectgem"), setOf(space.spaceId), null, 10).isEmpty())
            }
            org.apache.lucene.store.FSDirectory.open(directory.toPath()).use { dir ->
                org.wltea.analyzer.lucene.IKAnalyzer(true).use { analyzer ->
                    org.apache.lucene.index.IndexWriter(dir, org.apache.lucene.index.IndexWriterConfig(analyzer)).use { writer ->
                        writer.deleteDocuments(org.apache.lucene.index.Term("key", "1:${key.id}"))
                        writer.commit()
                    }
                }
            }
            ContentAssetSearchIndex(directory, "audit-test", source).use { index ->
                index.start()
                assertEquals(key, index.search(documentRequest("authoritativegem"), setOf(space.spaceId), null, 10).single().key)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `committed search reader does not wait for projector PostgreSQL work`() = runTest {
        val owner = ctx.registerUser(uniqueUsername("asset-concurrent-owner"))
        val space = documents.createSpace(owner, "并发检索", null)
        val document = documents.createDocument(owner, space.spaceId, null, "资料", "concurrentgem")
        val directory = Files.createTempDirectory("teamtalk-asset-concurrency-").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val realSource = source
        val blockedSource = object : com.virjar.tk.server.domain.search.ContentAssetRepository by realSource {
            override fun pending(limit: Int): List<ContentAssetPending> {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                return realSource.pending(limit)
            }
        }
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            ContentAssetSearchIndex(directory, "concurrency-test", blockedSource).use { index ->
                index.start()
                val projection = workers.submit { index.catchUp() }
                try {
                    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val result = workers.submit<List<com.virjar.tk.server.domain.search.ContentAssetMatch>> {
                        transaction(ctx.database) {
                            index.search(documentRequest("concurrentgem"), setOf(space.spaceId), null, 10)
                        }
                    }.get(3, java.util.concurrent.TimeUnit.SECONDS)
                    assertEquals(document.documentId, result.single().key.id)
                } finally {
                    release.countDown()
                    projection.get(10, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
            directory.deleteRecursively()
        }
    }

    private fun pending(key: ContentAssetKey): List<ContentAssetPending> = transaction(ctx.database) {
        ContentSearchPending.selectAll().where { (ContentSearchPending.kind eq key.kind) and (ContentSearchPending.resourceId eq key.id) }
            .map { ContentAssetPending(key, it[ContentSearchPending.revision]) }
    }
    private suspend fun createFile(owner: String, chatId: String, name: String, mime: String): GroupFileEntry {
        val path = ctx.fileStore.store(owner, name, mime, ByteArrayInputStream(id().encodeToByteArray()))
        return ctx.groupFileService.createFile(owner, id(), id(), chatId, null, name, requireNotNull(ctx.fileStore.getAttachment(path)))
    }
}

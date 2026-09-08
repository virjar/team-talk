package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.DocumentChangedPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.OrganizationChangedPayload
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.DocumentHomeItem
import com.virjar.tk.protocol.model.DocumentNode
import com.virjar.tk.protocol.model.DocumentSpace
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.DocumentRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wire 事件经过真实处理器、持久游标与 SQLite 投影；不依赖 UI 或在线服务器。 */
class DocumentChangeProjectionIntegrationTest {
    @Test
    fun `replayed changes retire older reads and deletion stays removed after restart`() = runBlocking {
        val directory = Files.createTempDirectory("teamtalk-document-events-").toFile()
        val database = directory.resolve("client.db")
        try {
            withCache(database.path, create = true) { cache ->
                cache.resetServerProjection(DATASET)
                seed(cache)
                val processor = EventProcessor(ImClient(), cache)
                try {
                    val bodyRead = cache.beginDocumentBodySnapshot(SPACE, "child")
                    val bodyWrite = cache.beginDocumentBodyMutationSnapshot(SPACE, "child")
                    val detailsRead = cache.beginDocumentSpaceDetailsSnapshot(SPACE)
                    val spacePage = cache.beginDocumentSpaceSnapshot()
                    val unrelatedRead = cache.beginDocumentBodySnapshot("another-space", "page")
                    processor.processBatch(listOf(event(1, DocumentChangedPayload.NODE_UPSERT, "child", 2)))
                    assertEquals(1L, cache.getSyncState()?.cursor)
                    assertEquals(1L, processor.documentChanges.value.sequence)
                    assertEquals(document("child", "parent"), cache.getDocumentBody(SPACE, "child"))
                    assertFalse(cache.applyDocumentBodySnapshot(bodyRead, document("child", "parent")))
                    assertTrue(cache.applyDocumentSpaceDetailsSnapshot(detailsRead, space()))
                    assertTrue(cache.applyDocumentSpaceRefreshPage(spacePage, listOf(space()), true, true),
                        "node changes must not cancel the visible spaces pagination cycle")
                    assertTrue(cache.applyDocumentBodySnapshot(unrelatedRead,
                        document("page", null).copy(spaceId = "another-space")))
                    assertTrue(cache.applyDocumentBodyMutation(bodyWrite, document("child", "parent").copy(revision = 2)))

                    val lateBody = cache.beginDocumentBodyMutationSnapshot(SPACE, "child")
                    val lateBranch = cache.beginDocumentBranchSnapshot(SPACE, null)
                    processor.processBatch(listOf(event(2, DocumentChangedPayload.NODE_DELETED, "parent", 2)))
                    assertNull(cache.getDocumentBody(SPACE, "child"))
                    assertTrue(cache.getDocumentNodes(SPACE, null).isEmpty())
                    assertTrue(cache.getDocumentHome(DocumentHomeCollection.RECENT).isEmpty())
                    assertFalse(cache.applyDocumentBodyMutation(lateBody, document("child", "parent")))
                    assertFalse(cache.applyDocumentBranchSnapshot(lateBranch, SPACE, null, listOf(node("parent", null))))
                    assertEquals(2L, cache.getSyncState()?.cursor)
                    processor.processNotify(event(2, DocumentChangedPayload.NODE_DELETED, "parent", 2))
                    assertEquals(2L, processor.documentChanges.value.sequence, "duplicate event must not repeat UI effects")
                } finally {
                    processor.stop()
                }
            }
            withCache(database.path, create = false) { cache ->
                assertEquals(2L, cache.getSyncState()?.cursor)
                assertNull(cache.getDocumentBody(SPACE, "child"))
                assertTrue(cache.getDocumentNodes(SPACE, null).isEmpty())
                assertTrue(cache.getDocumentHome(DocumentHomeCollection.RECENT).isEmpty())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `organization invalidation preserves offline content but revocation purges and fences late writes`() = runBlocking {
        withCache { cache ->
            cache.resetServerProjection(DATASET)
            seed(cache)
            val processor = EventProcessor(ImClient(), cache)
            try {
                val read = cache.beginDocumentSpaceDetailsSnapshot(SPACE)
                processor.processNotify(NotifyPayload(0, NotifyType.ORGANIZATION_CHANGED.code,
                    ProtoCodec.encode(OrganizationChangedPayload(10))))
                assertEquals(null, processor.documentChanges.value.change)
                assertEquals(1L, processor.documentChanges.value.sequence)
                assertFalse(cache.applyDocumentSpaceDetailsSnapshot(read, space()))
                assertEquals(document("child", "parent"), cache.getDocumentBody(SPACE, "child"))
                val write = cache.beginDocumentBodyMutationSnapshot(SPACE, "child")
                val spaceWrite = cache.beginDocumentSpaceMutationSnapshot(SPACE)
                processor.processNotify(event(1, DocumentChangedPayload.SPACE_REVOKED))
                assertTrue(cache.getDocumentSpaces().isEmpty())
                assertNull(cache.getDocumentBody(SPACE, "child"))
                assertFalse(cache.applyDocumentBodyMutation(write, document("child", "parent")))
                assertFalse(cache.applyDocumentSpaceMutation(spaceWrite, space()))
                assertEquals(1L, cache.getSyncState()?.cursor)
            } finally {
                processor.stop()
            }
        }
    }

    @Test
    fun `getSpace updates current role without consuming the visible space pagination cycle`() = runBlocking {
        withCache { cache ->
            cache.resetServerProjection(DATASET)
            seed(cache)
            val rpc = FakeRpcInvoker()
            val repo = DocumentRepository(rpc, cache)
            val pageLease = cache.beginDocumentSpaceSnapshot()
            assertTrue(cache.applyDocumentSpaceRefreshPage(pageLease, listOf(space()), true, false))
            val updated = space().copy(myRole = DocumentSpace.ROLE_VIEWER, name = "当前名称", policyRevision = 9)
            rpc.enqueueOk(ProtoCodec.encode(updated))
            assertEquals(updated, repo.getSpace(SPACE).getOrThrow())
            assertEquals(updated, cache.getDocumentSpaces().single())
            assertTrue(cache.applyDocumentSpaceRefreshPage(pageLease, emptyList(), false, true))
            val lateDetails = cache.beginDocumentSpaceDetailsSnapshot(SPACE)
            assertTrue(cache.applyDocumentSpaceMutation(cache.beginDocumentSpaceMutationSnapshot(SPACE), updated))
            assertFalse(cache.applyDocumentSpaceDetailsSnapshot(lateDetails, space()))
            rpc.throwOnInvoke = AppError.Network
            assertFailsWith<AppError.Network> { repo.getSpace(SPACE).getOrThrow() }
            assertEquals(updated, cache.getDocumentSpaces().single())
            rpc.throwOnInvoke = null
            rpc.enqueueError(403)
            assertFailsWith<AppError.Business> { repo.getSpace(SPACE).getOrThrow() }
            assertTrue(cache.getDocumentSpaces().isEmpty())
            assertNull(cache.getDocumentBody(SPACE, "child"))
        }
    }

    @Test
    fun `node update during a foreground open retries the fenced body exactly once`() = runBlocking {
        withCache { cache ->
            cache.resetServerProjection(DATASET)
            val processor = EventProcessor(ImClient(), cache)
            var calls = 0
            try {
                val latest = document("page", null).copy(revision = 2)
                val rpc = object : RpcInvoker {
                    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                        calls += 1
                        if (calls == 1) processor.processNotify(event(1, DocumentChangedPayload.NODE_UPSERT, "page", 2))
                        return ResponsePayload(calls, 0, ProtoCodec.encode(if (calls == 1) latest.copy(revision = 1) else latest))
                    }
                }
                assertEquals(latest, DocumentRepository(rpc, cache).getDocument(SPACE, "page").getOrThrow())
                assertEquals(2, calls)
            } finally {
                processor.stop()
            }
        }
    }

    @Test
    fun `revocation arriving during getSpace cannot republish its older successful response`() = runBlocking {
        withCache { cache ->
            cache.resetServerProjection(DATASET)
            seed(cache)
            val processor = EventProcessor(ImClient(), cache)
            try {
                val rpc = object : RpcInvoker {
                    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                        processor.processNotify(event(1, DocumentChangedPayload.SPACE_REVOKED))
                        return ResponsePayload(1, 0, ProtoCodec.encode(space()))
                    }
                }
                val result = DocumentRepository(rpc, cache).getSpace(SPACE)
                assertTrue(result is Outcome.Failure)
                assertTrue(cache.getDocumentSpaces().isEmpty())
                assertNull(cache.getDocumentBody(SPACE, "child"))
            } finally {
                processor.stop()
            }
        }
    }

    private fun event(id: Long, kind: Int, node: String? = null, revision: Long = 0) = NotifyPayload(
        id, NotifyType.DOCUMENT_CHANGED.code,
        ProtoCodec.encode(DocumentChangedPayload(SPACE, node, kind, revision, 9)),
    )

    private fun seed(cache: LocalCache) {
        check(cache.applyDocumentSpaceSnapshot(cache.beginDocumentSpaceSnapshot(), listOf(space())))
        check(cache.applyDocumentBranchSnapshot(cache.beginDocumentBranchSnapshot(SPACE, null), SPACE, null, listOf(node("parent", null))))
        check(cache.applyDocumentBranchSnapshot(cache.beginDocumentBranchSnapshot(SPACE, "parent"), SPACE, "parent", listOf(node("child", "parent"))))
        check(cache.applyDocumentBodySnapshot(cache.beginDocumentBodySnapshot(SPACE, "child"), document("child", "parent")))
        check(cache.applyDocumentHomeSnapshot(cache.beginDocumentHomeSnapshot(DocumentHomeCollection.RECENT),
            DocumentHomeCollection.RECENT, listOf(DocumentHomeItem("child", SPACE, "空间", "child", "摘要", "owner", "Owner", 1, 2, 3))))
    }

    private fun space() = DocumentSpace(SPACE, "空间", null, DocumentSpace.ROLE_EDITOR, "owner", 1, 2)
    private fun node(id: String, parent: String?) = DocumentNode(id, SPACE, parent, parent == null,
        id, "正文", 1, "owner", 1, "owner", 2)
    private fun document(id: String, parent: String?) = Document(id, SPACE, parent, id, "正文", 1,
        "owner", 1, "owner", 2, listOfNotNull(parent))

    private suspend fun withCache(path: String? = null, create: Boolean = true, block: suspend (LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver(path?.let { "jdbc:sqlite:$it" } ?: JdbcSqliteDriver.IN_MEMORY)
        if (create) AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try { block(cache) } finally { cache.close() }
    }

    private companion object {
        const val SPACE = "space"
        const val DATASET = "00000000-0000-4000-8000-000000000001"
    }
}

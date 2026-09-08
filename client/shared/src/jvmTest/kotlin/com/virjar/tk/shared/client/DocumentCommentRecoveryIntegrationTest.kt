package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.DocumentChangedPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.DocumentComment
import com.virjar.tk.protocol.model.DocumentCommentPage
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.DocumentCommentRpcContract
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.DocumentCommentRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 使用真实 SQLite 和生成的 RPC 编解码验证评论队列；不连接真实账号或服务端。 */
class DocumentCommentRecoveryIntegrationTest {
    @Test
    fun `ordinary close retains cached pages and every pending command kind across reopen`() = runBlocking {
        withDatabase { database ->
            val cached = DocumentCommentPage(listOf(comment(id(2), sequence = 2), comment(id(1))), 0)
            lateinit var pending: List<PendingDocumentComment>
            withCache(database, create = true) { cache ->
                val rpc = FakeRpcInvoker()
                var wakeCount = 0
                val repository = DocumentCommentRepository(rpc, cache.documentComments) { wakeCount += 1 }
                seedPage(cache, cached)
                repository.create(SPACE, DOCUMENT, "重启后发送的回复", id(1)).getOrThrow()
                repository.update(comment(id(3), revision = 5), "保留编辑正文").getOrThrow()
                repository.delete(comment(id(4), revision = 8)).getOrThrow()
                pending = cache.documentComments.pending()
                assertEquals(setOf(PendingDocumentComment.CREATE, PendingDocumentComment.UPDATE, PendingDocumentComment.DELETE), pending.map { it.kind }.toSet())
                assertEquals(3, wakeCount)
                assertTrue(rpc.calls.isEmpty(), "排队成功不依赖当前网络")
            }
            withCache(database) { cache ->
                assertEquals(pending, cache.documentComments.pending())
                assertEquals(cached, cache.documentComments.page(KEY))
                assertEquals(id(1), pending.single { it.kind == PendingDocumentComment.CREATE }.replyToId)
                assertEquals(5L, pending.single { it.kind == PendingDocumentComment.UPDATE }.expectedRevision)
                assertEquals(8L, pending.single { it.kind == PendingDocumentComment.DELETE }.expectedRevision)
            }
        }
    }

    @Test
    fun `an unknown send result replays identical bytes after restart and acknowledges the original identity`() = runBlocking {
        withDatabase { database ->
            lateinit var pending: PendingDocumentComment
            lateinit var firstPayload: ByteArray
            withCache(database, create = true) { cache ->
                seedPage(cache, DocumentCommentPage(emptyList(), 0))
                val rpc = FakeRpcInvoker().apply { throwOnInvoke = AppError.Network }
                val repository = DocumentCommentRepository(rpc, cache.documentComments)
                repository.create(SPACE, DOCUMENT, "网络中断也保留这条评论", id(9)).getOrThrow()
                pending = cache.documentComments.pending().single()
                assertEquals(Outcome.Failure(AppError.Network), repository.retryPending())
                assertEquals(pending, cache.documentComments.pending().single())
                assertNull(pending.failure)
                assertFailsWith<IllegalStateException> { repository.discardRejected(pending.commentId) }
                val call = rpc.calls.single()
                assertEquals(DocumentCommentRpcContract.SERVICE, call.first)
                assertEquals(DocumentCommentRpcContract.M_CREATE, call.second)
                firstPayload = checkNotNull(call.third)
            }
            withCache(database) { cache ->
                val accepted = comment(pending.commentId, body = pending.body, replyToId = pending.replyToId)
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(accepted)) }
                val repository = DocumentCommentRepository(rpc, cache.documentComments)
                assertEquals(pending, cache.documentComments.pending().single())
                repository.retryPending().getOrThrow()
                assertContentEquals(firstPayload, rpc.calls.single().third)
                assertTrue(cache.documentComments.pending().isEmpty())
                assertEquals(listOf(accepted), cache.documentComments.page(KEY)?.items)
            }
            withCache(database) { cache ->
                assertTrue(cache.documentComments.pending().isEmpty())
                assertEquals(pending.commentId, cache.documentComments.page(KEY)?.items?.single()?.commentId)
            }
        }
    }

    @Test
    fun `403 remains a visible retained failure across restart until an explicit retry`() = runBlocking {
        withDatabase { database ->
            lateinit var rejected: PendingDocumentComment
            withCache(database, create = true) { cache ->
                val rpc = FakeRpcInvoker().apply { enqueueError(403, "denied") }
                val repository = DocumentCommentRepository(rpc, cache.documentComments)
                repository.create(SPACE, DOCUMENT, "需要保留的原评论").getOrThrow()
                assertBusinessFailure(403, repository.retryPending())
                rejected = cache.documentComments.pending().single()
                assertFalse(rejected.failure.isNullOrBlank())
                assertEquals("需要保留的原评论", rejected.body)
                repository.retryPending().getOrThrow()
                assertEquals(1, rpc.calls.size, "确定拒绝不能被后台无休止重试")
            }
            withCache(database) { cache ->
                val rpc = FakeRpcInvoker()
                var wakeCount = 0
                val repository = DocumentCommentRepository(rpc, cache.documentComments) { wakeCount += 1 }
                assertEquals(rejected, cache.documentComments.pending().single())
                repository.retryPending().getOrThrow()
                assertTrue(rpc.calls.isEmpty())
                repository.retry(rejected.commentId)
                assertEquals(rejected.copy(failure = null), cache.documentComments.pending().single())
                assertEquals(1, wakeCount)
                rpc.enqueueOk(ProtoCodec.encode(comment(rejected.commentId, body = rejected.body)))
                repository.retryPending().getOrThrow()
                assertTrue(cache.documentComments.pending().isEmpty())
                assertContentEquals(
                    DocumentCommentRpcContract.encodeCreate(SPACE, DOCUMENT, rejected.commentId, rejected.replyToId, rejected.body),
                    rpc.calls.single().third,
                )
            }
        }
    }

    @Test
    fun `409 preserves the original edit and revision until the user explicitly discards it`() = runBlocking {
        withDatabase { database ->
            lateinit var rejected: PendingDocumentComment
            withCache(database, create = true) { cache ->
                val original = comment(id(10), revision = 4)
                val rpc = FakeRpcInvoker().apply { enqueueError(409, "changed") }
                val repository = DocumentCommentRepository(rpc, cache.documentComments)
                repository.update(original, "尚未被服务端接受的编辑").getOrThrow()
                assertBusinessFailure(409, repository.retryPending())
                rejected = cache.documentComments.pending().single()
                assertFalse(rejected.failure.isNullOrBlank())
                assertEquals(4L, rejected.expectedRevision)
                assertIs<Outcome.Failure>(repository.update(original.copy(revision = 5), "不能覆盖旧意图"))
                assertEquals(rejected, cache.documentComments.pending().single())
            }
            withCache(database) { cache ->
                val rpc = FakeRpcInvoker()
                val repository = DocumentCommentRepository(rpc, cache.documentComments)
                assertEquals(rejected, cache.documentComments.pending().single())
                repository.retryPending().getOrThrow()
                assertTrue(rpc.calls.isEmpty())
                repository.discardRejected(rejected.commentId)
                assertTrue(cache.documentComments.pending().isEmpty())
            }
            withCache(database) { cache -> assertTrue(cache.documentComments.pending().isEmpty()) }
        }
    }

    @Test
    fun `reset and revocation purge server pages but retain pending user content and retire old generations`() = runBlocking {
        for (reset in listOf(false, true)) {
            withCache { cache ->
                cache.resetServerProjection(DATASET)
                val page = DocumentCommentPage(listOf(comment(id(1))), 0)
                seedPage(cache, page)
                val otherKey = DocumentCommentPageKey("other-space", "other-document")
                val otherPage = DocumentCommentPage(listOf(comment(id(2)).copy(spaceId = otherKey.spaceId, documentId = otherKey.documentId)), 0)
                assertTrue(cache.documentComments.applyPage(otherKey, otherPage, cache.documentComments.generation()))
                val repository = DocumentCommentRepository(FakeRpcInvoker(), cache.documentComments)
                repository.create(SPACE, DOCUMENT, "未发送原文").getOrThrow()
                repository.create(otherKey.spaceId, otherKey.documentId, "另一空间原文").getOrThrow()
                val pending = cache.documentComments.pending()
                val generation = cache.documentComments.generation()
                if (reset) cache.resetServerProjection(NEXT_DATASET) else revoke(cache)
                assertNull(cache.documentComments.page(KEY))
                assertEquals(if (reset) null else otherPage, cache.documentComments.page(otherKey))
                assertEquals(pending, cache.documentComments.pending())
                assertFalse(cache.documentComments.applyPage(KEY, page, generation))
                assertNull(cache.documentComments.page(KEY))
            }
        }
    }

    @Test
    fun `a page response arriving after reset or revocation cannot repopulate removed content`() = runBlocking {
        for (reset in listOf(false, true)) {
            withCache { cache ->
                cache.resetServerProjection(DATASET)
                val page = DocumentCommentPage(listOf(comment(id(1))), 0)
                seedPage(cache, page)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val responses = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(page)) }
                val delayed = object : RpcInvoker {
                    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                        val response = responses.invoke(service, methodId, payload)
                        started.complete(Unit)
                        release.await()
                        return response
                    }
                }
                val repository = DocumentCommentRepository(delayed, cache.documentComments)
                withTimeout(5_000) {
                    coroutineScope {
                        val response = async { repository.refresh(KEY) }
                        started.await()
                        if (reset) cache.resetServerProjection(NEXT_DATASET) else revoke(cache)
                        release.complete(Unit)
                        assertFalse(response.await().getOrThrow())
                    }
                }
                assertEquals(DocumentCommentRpcContract.M_LIST, responses.calls.single().second)
                assertNull(cache.documentComments.page(KEY))
            }
        }
    }

    @Test
    fun `an accepted command arriving after revocation clears only its pending intent without resurrecting a page`() = runBlocking {
        withCache { cache ->
            cache.resetServerProjection(DATASET)
            seedPage(cache, DocumentCommentPage(emptyList(), 0))
            val responses = FakeRpcInvoker()
            val delayed = object : RpcInvoker {
                override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                    val response = responses.invoke(service, methodId, payload)
                    revoke(cache)
                    return response
                }
            }
            val repository = DocumentCommentRepository(delayed, cache.documentComments)
            val commentId = repository.create(SPACE, DOCUMENT, "已提交但响应晚到").getOrThrow()
            responses.enqueueOk(ProtoCodec.encode(comment(commentId, body = "已提交但响应晚到")))
            repository.retryPending().getOrThrow()
            assertTrue(cache.documentComments.pending().isEmpty())
            assertNull(cache.documentComments.page(KEY))
        }
    }

    @Test
    fun `403 and 404 page rejection purges only the matching scope and preserves pending edits`() = runBlocking {
        for (status in listOf(403, 404)) {
            withCache { cache ->
                val current = comment(id(1))
                seedPage(cache, DocumentCommentPage(listOf(current), 0))
                val sibling = DocumentCommentPageKey(SPACE, "sibling-document")
                val siblingPage = DocumentCommentPage(listOf(comment(id(2)).copy(documentId = sibling.documentId)), 0)
                assertTrue(cache.documentComments.applyPage(sibling, siblingPage, cache.documentComments.generation()))
                val rpc = FakeRpcInvoker().apply { enqueueError(status, "unavailable") }
                val repository = DocumentCommentRepository(rpc, cache.documentComments)
                repository.update(current, "本地编辑").getOrThrow()
                val pending = cache.documentComments.pending()
                assertBusinessFailure(status, repository.refresh(KEY))
                assertNull(cache.documentComments.page(KEY))
                assertEquals(if (status == 403) null else siblingPage, cache.documentComments.page(sibling))
                assertEquals(pending, cache.documentComments.pending())
            }
        }
    }

    @Test
    fun `closing the cache retires a late page response while keeping the last cached page on disk`() = runBlocking {
        withDatabase { database ->
            val cached = DocumentCommentPage(listOf(comment(id(1), body = "关闭前缓存")), 0)
            withCache(database, create = true) { cache ->
                seedPage(cache, cached)
                val responses = FakeRpcInvoker().apply {
                    enqueueOk(ProtoCodec.encode(DocumentCommentPage(listOf(comment(id(2), body = "关闭后晚到")), 0)))
                }
                val delayed = object : RpcInvoker {
                    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                        val response = responses.invoke(service, methodId, payload)
                        cache.close()
                        return response
                    }
                }
                assertFalse(DocumentCommentRepository(delayed, cache.documentComments).refresh(KEY).getOrThrow())
            }
            withCache(database) { cache -> assertEquals(cached, cache.documentComments.page(KEY)) }
        }
    }

    @Test
    fun `an acknowledgement after close leaves its durable command for the next cache owner to confirm`() = runBlocking {
        withDatabase { database ->
            lateinit var pending: PendingDocumentComment
            lateinit var accepted: DocumentComment
            withCache(database, create = true) { cache ->
                seedPage(cache, DocumentCommentPage(emptyList(), 0))
                val responses = FakeRpcInvoker()
                val delayed = object : RpcInvoker {
                    override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
                        val response = responses.invoke(service, methodId, payload)
                        cache.close()
                        return response
                    }
                }
                val repository = DocumentCommentRepository(delayed, cache.documentComments)
                repository.create(SPACE, DOCUMENT, "关闭时等待确认").getOrThrow()
                pending = cache.documentComments.pending().single()
                accepted = comment(pending.commentId, body = pending.body)
                responses.enqueueOk(ProtoCodec.encode(accepted))
                repository.retryPending().getOrThrow()
            }
            withCache(database) { cache ->
                assertEquals(listOf(pending), cache.documentComments.pending())
                assertEquals(emptyList(), cache.documentComments.page(KEY)?.items)
                val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encode(accepted)) }
                DocumentCommentRepository(rpc, cache.documentComments).retryPending().getOrThrow()
                assertContentEquals(
                    DocumentCommentRpcContract.encodeCreate(SPACE, DOCUMENT, pending.commentId, pending.replyToId, pending.body),
                    rpc.calls.single().third,
                )
                assertTrue(cache.documentComments.pending().isEmpty())
                assertEquals(listOf(accepted), cache.documentComments.page(KEY)?.items)
            }
        }
    }

    private fun seedPage(cache: LocalCache, page: DocumentCommentPage) {
        assertTrue(cache.documentComments.applyPage(KEY, page, cache.documentComments.generation()))
    }

    private suspend fun revoke(cache: LocalCache) {
        val processor = EventProcessor(ImClient(), cache)
        try {
            processor.processNotify(NotifyPayload(
                1,
                NotifyType.DOCUMENT_CHANGED.code,
                ProtoCodec.encode(DocumentChangedPayload(SPACE, null, DocumentChangedPayload.SPACE_REVOKED, 0, 2)),
            ))
        } finally {
            processor.stop()
        }
    }

    private fun assertBusinessFailure(status: Int, result: Outcome<*>) {
        val failure = assertIs<Outcome.Failure>(result)
        assertEquals(status, assertIs<AppError.Business>(failure.error).code)
    }

    private fun comment(
        commentId: String,
        body: String = "评论正文",
        revision: Long = 1,
        sequence: Long = 1,
        replyToId: String? = null,
    ) = DocumentComment(commentId, SPACE, DOCUMENT, sequence, "author", "作者", replyToId, body, revision, 1, 2, false)

    private suspend fun withDatabase(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("teamtalk-document-comments-").toFile()
        try { block(directory.resolve("client.db")) } finally { directory.deleteRecursively() }
    }

    private suspend fun withCache(database: File? = null, create: Boolean = database == null, block: suspend (LocalCacheImpl) -> Unit) {
        val driver = JdbcSqliteDriver(database?.let { "jdbc:sqlite:${it.path}" } ?: JdbcSqliteDriver.IN_MEMORY)
        if (create) AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        try { block(cache) } finally { cache.close() }
    }

    private fun id(value: Long) = UUID(0, value).toString()

    private companion object {
        const val SPACE = "space"
        const val DOCUMENT = "document"
        const val DATASET = "00000000-0000-4000-8000-000000000001"
        const val NEXT_DATASET = "00000000-0000-4000-8000-000000000002"
        val KEY = DocumentCommentPageKey(SPACE, DOCUMENT)
    }
}

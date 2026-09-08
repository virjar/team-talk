package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.protocol.DocumentChangedPayload
import com.virjar.tk.protocol.GroupFileChangedPayload
import com.virjar.tk.protocol.IProto
import com.virjar.tk.protocol.MessageType
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.OrganizationChangedPayload
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.body.FileBody
import com.virjar.tk.protocol.model.Attachment
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.ContentSearchHit
import com.virjar.tk.protocol.model.ContentSearchPage
import com.virjar.tk.protocol.model.ContentSearchRequest
import com.virjar.tk.protocol.model.Document
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.model.Message
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.ContentSearchInvalidatedException
import com.virjar.tk.shared.repository.ContentSearchRepository
import com.virjar.tk.shared.repository.DocumentRepository
import com.virjar.tk.shared.repository.GroupFileRepository
import com.virjar.tk.shared.repository.ResolvedContentSearchHit
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 真实 wire 事件及 SQLite 缓存与 RPC 完成交错，搜索摘要自身不写入这些持久投影。 */
class ContentSearchRepositoryIntegrationTest {
    @Test
    fun `durable events advance only relevant kind and duplicate replay has no extra effect`() = runBlocking {
        withFixture { _, processor ->
            processor.processNotify(event(1, NotifyType.DOCUMENT_CHANGED, DocumentChangedPayload(SCOPE, TARGET, 1, 2, 1)))
            assertEquals(ContentSearchInvalidation(1, 0, 0), processor.contentSearchChanges.value)
            processor.processNotify(event(2, NotifyType.DOCUMENT_CHANGED, DocumentChangedPayload(SCOPE, TARGET, 5, 0, 1)))
            assertEquals(ContentSearchInvalidation(1, 0, 0), processor.contentSearchChanges.value)
            val deleted = event(3, NotifyType.GROUP_FILE_CHANGED,
                GroupFileChangedPayload(SCOPE, 2, null, TARGET, 2))
            processor.processNotify(deleted)
            processor.processNotify(deleted)
            assertEquals(ContentSearchInvalidation(1, 1, 0), processor.contentSearchChanges.value)
            processor.processNotify(event(4, NotifyType.MESSAGE_RECV, message()))
            assertEquals(ContentSearchInvalidation(1, 1, 1), processor.contentSearchChanges.value)
            processor.processNotify(event(5, NotifyType.CHAT_DELETED, Chat(SCOPE, 2)))
            assertEquals(ContentSearchInvalidation(1, 2, 2), processor.contentSearchChanges.value)
            assertEquals(5L, processor.lastEventId.value)
        }
    }

    @Test
    fun `organization checkpoint and session retirement invalidate all current search owners`() = runBlocking {
        withFixture { _, processor ->
            processor.processNotify(event(0, NotifyType.ORGANIZATION_CHANGED, OrganizationChangedPayload(1)))
            assertEquals(ContentSearchInvalidation(1, 1, 1), processor.contentSearchChanges.value)
            processor.applyServerProjectionCheckpoint(DATASET)
            assertEquals(ContentSearchInvalidation(2, 2, 2), processor.contentSearchChanges.value)
            processor.stop()
            assertEquals(ContentSearchInvalidation(3, 3, 3), processor.contentSearchChanges.value)
        }
    }

    @Test
    fun `first page retries a revoked in flight snapshot and returns only current authorized results`() = runBlocking {
        withFixture { cache, processor ->
            var calls = 0
            val rpc = invoker {
                calls++
                if (calls == 1) {
                    processor.processNotify(event(1, NotifyType.DOCUMENT_CHANGED, DocumentChangedPayload(SCOPE, null, 4, 0, 2)))
                    ProtoCodec.encode(ContentSearchPage(listOf(documentHit()), null))
                } else ProtoCodec.encode(ContentSearchPage(emptyList(), null))
            }
            val result = repository(rpc, cache, processor).search(ContentSearchRequest(1, "query")).getOrThrow()
            assertTrue(result.items.isEmpty())
            assertEquals(2, calls)
            assertTrue(cache.getDocumentSpaces().isEmpty(), "search summaries must not populate document projections")
        }
    }

    @Test
    fun `unrelated messages do not cancel document searches and response scope is checked`() = runBlocking {
        withFixture { cache, processor ->
            var calls = 0
            val rpc = invoker {
                calls++
                processor.processNotify(event(calls.toLong(), NotifyType.MESSAGE_RECV, message().copy(serverSeq = calls.toLong())))
                ProtoCodec.encode(ContentSearchPage(listOf(documentHit()), null))
            }
            assertEquals(listOf(documentHit()), repository(rpc, cache, processor)
                .search(ContentSearchRequest(1, "query")).getOrThrow().items)
            assertEquals(1, calls)
            val wrongScope = repository(rpc, cache, processor).search(ContentSearchRequest(1, "query", scopeId = "another"))
            assertTrue(wrongScope is Outcome.Failure)
        }
    }

    @Test
    fun `invalidated continuation never retries its old cursor and repeated first page changes stay bounded`() = runBlocking {
        withFixture { cache, processor ->
            var calls = 0
            val rpc = invoker {
                calls++
                processor.processNotify(event(calls.toLong(), NotifyType.DOCUMENT_CHANGED,
                    DocumentChangedPayload(SCOPE, TARGET, 1, calls.toLong(), 1)))
                ProtoCodec.encode(ContentSearchPage(listOf(documentHit()), "next"))
            }
            val repo = repository(rpc, cache, processor)
            assertFailsWith<ContentSearchInvalidatedException> { repo.search(ContentSearchRequest(1, "query", cursor = "old")) }
            assertEquals(1, calls)
            assertFailsWith<ContentSearchInvalidatedException> { repo.search(ContentSearchRequest(1, "query")) }
            assertEquals(3, calls)
        }
    }

    @Test
    fun `resolve uses current document and file payload rather than stale search summary`() = runBlocking {
        withFixture { cache, processor ->
            val rpc = FakeRpcInvoker()
            val repo = repository(rpc, cache, processor)
            val document = Document(TARGET, SCOPE, null, "当前标题", "当前正文", 5, OWNER, 1, OWNER, 5)
            rpc.enqueueOk(ProtoCodec.encode(document))
            assertEquals(ResolvedContentSearchHit.Document(document), repo.resolve(documentHit()).getOrThrow())
            val currentAttachment = attachment().copy(path = "owner/version-two.txt", name = "新文件.txt")
            val file = GroupFileEntry(TARGET, SCOPE, null, GroupFileEntry.KIND_FILE, "新名称", currentAttachment, 2, 2, OWNER, 1, OWNER, 2)
            rpc.enqueueOk(ProtoCodec.encode(file))
            assertEquals(ResolvedContentSearchHit.GroupFile(file), repo.resolve(groupFileHit()).getOrThrow())
            assertEquals(listOf("document", "groupFile"), rpc.calls.map { it.first })
            rpc.enqueueError(403)
            assertEquals(403, (repo.resolve(groupFileHit()) as Outcome.Failure).error.let { (it as AppError.Business).code })
        }
    }

    @Test
    fun `attachment resolve verifies exact message identity and currently present main attachment`() = runBlocking {
        withFixture { cache, processor ->
            val rpc = FakeRpcInvoker()
            val repo = repository(rpc, cache, processor)
            val current = message()
            rpc.enqueueOk(messagePage(current))
            assertEquals(ResolvedContentSearchHit.ChatMessage(current), repo.resolve(attachmentHit()).getOrThrow())
            for (unavailable in listOf(current.copy(flags = Message.FLAG_REVOKED), current.copy(serverSeq = 2),
                current.copy(body = FileBody(attachment().copy(path = "owner/replacement.txt"))))) {
                rpc.enqueueOk(messagePage(unavailable))
                val result = repo.resolve(attachmentHit()) as Outcome.Failure
                assertEquals(404, (result.error as AppError.Business).code)
            }
        }
    }

    @Test
    fun `retired session and network failures never produce successful snapshots`() = runBlocking {
        withFixture { cache, processor ->
            var active = true
            var calls = 0
            val rpc = invoker {
                calls++
                active = false
                ProtoCodec.encode(ContentSearchPage(listOf(documentHit()), null))
            }
            val repo = repository(rpc, cache, processor) { check(active) }
            assertTrue(repo.search(ContentSearchRequest(1, "query")) is Outcome.Failure)
            assertEquals(1, calls)
            val offline = FakeRpcInvoker().apply { throwOnInvoke = AppError.Network }
            assertEquals(Outcome.Failure(AppError.Network), repository(offline, cache, processor).search(ContentSearchRequest(1, "query")))
        }
    }

    private fun repository(rpc: RpcInvoker, cache: LocalCache, processor: EventProcessor, ensureActive: () -> Unit = {}) =
        ContentSearchRepository(rpc, processor.contentSearchChanges, DocumentRepository(rpc, cache),
            GroupFileRepository(rpc, cache), ensureActive)

    private fun invoker(response: suspend () -> ByteArray) = object : RpcInvoker {
        override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?) = ResponsePayload(1, 0, response())
    }

    private fun event(id: Long, type: NotifyType, payload: IProto) = NotifyPayload(id, type.code, ProtoCodec.encode(payload))
    private fun documentHit() = ContentSearchHit(1, SCOPE, TARGET, "旧标题", "旧摘要", "空间", 1, 1)
    private fun groupFileHit() = ContentSearchHit(2, SCOPE, TARGET, "旧文件", "", "群", 1, 1, "text/plain", 1)
    private fun attachmentHit() = ContentSearchHit(3, SCOPE,
        MessageDigest.getInstance("SHA-256").digest(attachment().path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }, "文件", "", "群", 1, 1, "text/plain", 10, 1)
    private fun attachment() = Attachment("owner/file.txt", "file.txt", "text/plain", 10)
    private fun message() = Message(SCOPE, "00000000-0000-4000-8000-000000000003", 1, OWNER, MessageType.FILE.code, 1, body = FileBody(attachment()))
    private fun messagePage(message: Message) = ProtoCodec.encodePayload { writeVarInt(1); message.writeTo(this) }

    private suspend fun withFixture(block: suspend (LocalCacheImpl, EventProcessor) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        cache.resetServerProjection(DATASET)
        val processor = EventProcessor(ImClient(), cache, ownerUid = OWNER,
            checkpointLoader = ServerCheckpointLoader { dataset, _, _ ->
                ServerProjectionCheckpoint(dataset, 10, User(OWNER, "owner", "Owner"), emptyList(), emptyList(), emptyList())
            })
        try { block(cache, processor) } finally { processor.stop(); cache.close() }
    }

    private companion object {
        const val DATASET = "00000000-0000-4000-8000-000000000001"
        const val SCOPE = "00000000-0000-4000-8000-000000000002"
        const val TARGET = "00000000-0000-4000-8000-000000000004"
        const val OWNER = "owner"
    }
}

package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.virjar.tk.protocol.GroupFileChangedPayload
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.model.GroupFileEntry
import com.virjar.tk.protocol.payload.NotifyPayload
import com.virjar.tk.protocol.payload.ResponsePayload
import com.virjar.tk.protocol.rpc.RpcInvoker
import com.virjar.tk.protocol.rpc.gen.GroupFileRpcContract
import com.virjar.tk.shared.AppError
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.shared.repository.GroupFileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 真实 SQLite、Notify 与生成的 list RPC 编解码交错；不用产品 hook 改变写入顺序。 */
class GroupFileDirectoryRecoveryIntegrationTest {
    @Test
    fun `late first directory cannot undo delete or overwrite delta and retry fills missing siblings`() = withProjection { cache, events ->
        val rpc = DirectoryRpc()
        val repository = GroupFileRepository(rpc, cache)
        val deleted = entry("deleted")
        val before = entry("updated")
        val updated = before.copy(revision = 2, name = "updated-new")
        val sibling = entry("sibling")
        cache.observeGroupFileEntries(CHAT, null).test {
            assertEquals(emptyList(), awaitItem())
            val listing = async { repository.list(CHAT) }
            val oldResponse = rpc.requests.receive()
            events.processNotify(upsert(1, updated))
            assertEquals(listOf(updated), awaitItem())
            events.processNotify(NotifyPayload(2, NotifyType.GROUP_FILE_CHANGED.code,
                ProtoCodec.encode(GroupFileChangedPayload(CHAT, 2, null, deleted.entryId, 2))))
            oldResponse.complete(page(deleted, before, sibling))

            val refreshed = rpc.requests.receive()
            assertEquals(listOf(updated), cache.activeGroupFileEntries(CHAT, null))
            expectNoEvents()
            refreshed.complete(page(sibling, updated))
            val fullDirectory = listOf(sibling, updated)
            assertEquals(fullDirectory, listing.await().getOrThrow())
            assertEquals(fullDirectory, awaitItem())
            assertEquals(2, rpc.calls)
        }
    }

    @Test
    fun `repeated invalidation fails instead of claiming a partial first directory is complete`() = withProjection { cache, events ->
        val rpc = DirectoryRpc()
        val repository = GroupFileRepository(rpc, cache)
        val listing = async { repository.list(CHAT) }
        repeat(2) { attempt ->
            val response = rpc.requests.receive()
            events.processNotify(upsert(attempt + 1L, entry("delta").copy(revision = attempt + 1L)))
            response.complete(page(entry("old-page-only")))
        }
        val failure = assertIs<Outcome.Failure>(listing.await())
        assertEquals(503, assertIs<AppError.Business>(failure.error).code)
        assertEquals(listOf("delta"), cache.activeGroupFileEntries(CHAT, null).map { it.entryId })
        assertEquals(2, rpc.calls)
    }

    @Test
    fun `older concurrent list retries without replacing a newer accepted directory`() = withProjection { cache, _ ->
        val rpc = DirectoryRpc()
        val repository = GroupFileRepository(rpc, cache)
        val older = async { repository.list(CHAT) }
        val oldResponse = rpc.requests.receive()
        val newer = async { repository.list(CHAT) }
        rpc.requests.receive().complete(page(entry("current")))
        assertEquals(listOf(entry("current")), newer.await().getOrThrow())
        oldResponse.complete(page(entry("obsolete")))
        val retry = rpc.requests.receive()
        assertEquals(listOf(entry("current")), cache.activeGroupFileEntries(CHAT, null))
        retry.complete(page(entry("current")))
        assertEquals(listOf(entry("current")), older.await().getOrThrow())
    }

    @Test
    fun `purge and dataset reset retire a delayed page before another authority check`() = withProjection { cache, _ ->
        for (reset in listOf(false, true)) {
            cache.applyGroupFileUpsert(entry("cached"))
            val rpc = DirectoryRpc()
            val listing = async { GroupFileRepository(rpc, cache).list(CHAT) }
            val oldResponse = rpc.requests.receive()
            if (reset) cache.resetServerProjection(NEXT_DATASET) else cache.purgeGroupFileProjection(CHAT)
            oldResponse.complete(page(entry("obsolete")))
            val retry = rpc.requests.receive()
            assertTrue(cache.activeGroupFileEntries(CHAT, null).isEmpty())
            retry.complete(ResponsePayload(1, 403, "denied".encodeToByteArray()))
            assertIs<Outcome.Failure>(listing.await())
            assertTrue(cache.activeGroupFileEntries(CHAT, null).isEmpty())
        }
    }

    @Test
    fun `close rejects a delayed page and no second RPC uses the retired cache`() = withProjection { cache, _ ->
        val rpc = DirectoryRpc()
        val listing = async { GroupFileRepository(rpc, cache).list(CHAT) }
        val response = rpc.requests.receive()
        cache.close()
        response.complete(page(entry("late")))
        assertIs<Outcome.Failure>(listing.await())
        assertEquals(1, rpc.calls)
    }

    @Test
    fun `directory leases preserve sibling requests and reject wrong scope without cache pollution`() = withProjection { cache, _ ->
        val root = cache.beginGroupFileDirectorySnapshot(CHAT, null)
        val child = cache.beginGroupFileDirectorySnapshot(CHAT, "folder")
        assertTrue(cache.applyGroupFileDirectorySnapshot(root, CHAT, null, listOf(entry("root"))))
        assertTrue(cache.applyGroupFileDirectorySnapshot(child, CHAT, "folder", listOf(entry("child").copy(parentId = "folder"))))
        assertFalse(cache.applyGroupFileDirectorySnapshot(root, CHAT, null, listOf(entry("old"))))
        for (local in listOf<LocalCache?>(cache, null)) {
            val rpc = DirectoryRpc()
            val listing = async { GroupFileRepository(rpc, local).list(CHAT) }
            rpc.requests.receive().complete(page(entry("outside").copy(chatId = "other-chat")))
            assertIs<Outcome.Failure>(listing.await())
        }
        assertEquals(listOf(entry("root")), cache.activeGroupFileEntries(CHAT, null))
        assertTrue(cache.activeGroupFileEntries("other-chat", null).isEmpty())
    }

    private class DirectoryRpc : RpcInvoker {
        val requests = Channel<CompletableDeferred<ResponsePayload>>(Channel.UNLIMITED)
        var calls = 0
        override suspend fun invoke(service: String, methodId: Int, payload: ByteArray?): ResponsePayload {
            assertEquals(GroupFileRpcContract.M_LIST, methodId)
            calls++
            val response = CompletableDeferred<ResponsePayload>()
            requests.send(response)
            return response.await()
        }
    }

    private fun withProjection(block: suspend CoroutineScope.(LocalCacheImpl, EventProcessor) -> Unit) = runBlocking {
        withTimeout(10_000) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            AppDatabase.Schema.create(driver)
            val cache = LocalCacheImpl(driver)
            cache.resetServerProjection(DATASET)
            val client = ImClient()
            val events = EventProcessor(client, cache, ownerUid = "owner")
            try { coroutineScope { block(cache, events) } }
            finally { events.stop(); cache.close(); client.destroy() }
        }
    }

    private fun entry(id: String) = GroupFileEntry(
        entryId = id, chatId = CHAT, kind = GroupFileEntry.KIND_FOLDER, name = id,
        revision = 1, createdBy = "owner", createdAt = 1, updatedBy = "owner", updatedAt = 1,
    )
    private fun upsert(eventId: Long, entry: GroupFileEntry) = NotifyPayload(eventId, NotifyType.GROUP_FILE_CHANGED.code,
        ProtoCodec.encode(GroupFileChangedPayload(CHAT, 1, entry, entry.entryId, entry.revision)))
    private fun page(vararg entries: GroupFileEntry) = ResponsePayload(1, 0, ProtoCodec.encodeList(entries.toList()))

    private companion object {
        const val CHAT = "00000000-0000-4000-8000-000000000003"
        const val DATASET = "00000000-0000-4000-8000-000000000001"
        const val NEXT_DATASET = "00000000-0000-4000-8000-000000000002"
    }
}

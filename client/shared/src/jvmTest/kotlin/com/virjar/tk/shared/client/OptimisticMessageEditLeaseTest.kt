package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticMessageEditLeaseTest {
    @Test
    fun `optimistic edit is resident only and exact rollback restores durable authority`() = runTest {
        val cache = newCache()
        val previous = message("resident-only")
        cache.insertMessage(previous)
        val pager = cache.pager(previous.chatId)
        val optimistic = previous.copy(flags = Message.FLAG_EDITED)

        val lease = assertNotNull(cache.reserveOptimisticMessageEdit(optimistic))
        assertNull(
            cache.reserveOptimisticMessageEdit(optimistic),
            "one stable message identity must not admit concurrent edit RPCs",
        )
        assertTrue(cache.publishOptimisticMessageEdit(lease))

        assertEquals(Message.FLAG_EDITED, pager.messages.first().single().flags)
        assertEquals(
            0,
            cache.getMessages(previous.chatId).single().flags,
            "an unconfirmed edit must never survive a process restart in SQLite",
        )

        assertTrue(cache.rollbackOptimisticMessageEdit(lease))
        assertEquals(0, pager.messages.first().single().flags)
        assertFalse(cache.rollbackOptimisticMessageEdit(lease))
        pager.close()
        cache.close()
    }

    @Test
    fun `same-value server projection supersedes rollback by provenance`() = runTest {
        val cache = newCache()
        val previous = message("same-value-authority")
        cache.insertMessage(previous)
        val pager = cache.pager(previous.chatId)
        val optimistic = previous.copy(flags = Message.FLAG_EDITED)
        val lease = assertNotNull(cache.reserveOptimisticMessageEdit(optimistic))
        assertTrue(cache.publishOptimisticMessageEdit(lease))

        // 相等性无法标识来源。这次权威写入仍然必须把 rollback 挡在栅栏外。
        cache.insertMessage(optimistic)
        assertTrue(cache.rollbackOptimisticMessageEdit(lease))

        assertEquals(Message.FLAG_EDITED, pager.messages.first().single().flags)
        assertEquals(Message.FLAG_EDITED, cache.getMessages(previous.chatId).single().flags)
        pager.close()
        cache.close()
    }

    @Test
    fun `authority between reserve and publish cancels the overlay without clobbering authority`() = runTest {
        val cache = newCache()
        val previous = message("reserve-race")
        cache.insertMessage(previous)
        val firstPager = cache.pager(previous.chatId)
        val secondPager = cache.pager(previous.chatId)
        val authority = previous.copy(flags = Message.FLAG_EDITED)
        val lease = assertNotNull(cache.reserveOptimisticMessageEdit(authority))

        cache.insertMessage(authority)

        assertFalse(cache.publishOptimisticMessageEdit(lease))
        assertTrue(cache.rollbackOptimisticMessageEdit(lease))
        assertFalse(cache.rollbackOptimisticMessageEdit(lease))
        assertEquals(authority, firstPager.messages.first().single())
        assertEquals(authority, secondPager.messages.first().single())
        val nextLease = assertNotNull(cache.reserveOptimisticMessageEdit(authority))
        assertTrue(cache.rollbackOptimisticMessageEdit(nextLease))
        firstPager.close()
        secondPager.close()
        cache.close()
    }

    @Test
    fun `unrelated live message does not prevent exact rollback`() = runTest {
        val cache = newCache()
        val previous = message("edited-target", serverSeq = 7L)
        cache.insertMessage(previous)
        val pager = cache.pager(previous.chatId)
        val optimistic = previous.copy(flags = Message.FLAG_EDITED)
        val lease = assertNotNull(cache.reserveOptimisticMessageEdit(optimistic))
        assertTrue(cache.publishOptimisticMessageEdit(lease))

        cache.insertMessage(message("unrelated", serverSeq = 8L))
        assertTrue(cache.rollbackOptimisticMessageEdit(lease))

        assertEquals(
            0,
            pager.messages.first().single { it.clientMsgId == previous.clientMsgId }.flags,
        )
        pager.close()
        cache.close()
    }

    @Test
    fun `reentrant rollback cannot leave a later pager on the superseded overlay`() = runTest {
        val cache = newCache()
        val previous = message("reentrant")
        cache.insertMessage(previous)
        val firstPager = cache.pager(previous.chatId)
        val secondPager = cache.pager(previous.chatId)
        val optimistic = previous.copy(flags = Message.FLAG_EDITED)
        val lease = assertNotNull(cache.reserveOptimisticMessageEdit(optimistic))

        val collector = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
            start = CoroutineStart.UNDISPATCHED,
        ) {
            firstPager.messages.collect { messages ->
                if (messages.singleOrNull()?.flags == Message.FLAG_EDITED) {
                    cache.rollbackOptimisticMessageEdit(lease)
                }
            }
        }

        assertFalse(
            cache.publishOptimisticMessageEdit(lease),
            "the publication was synchronously retired by its first observer",
        )
        assertEquals(0, firstPager.messages.first().single().flags)
        assertEquals(0, secondPager.messages.first().single().flags)

        collector.cancel()
        firstPager.close()
        secondPager.close()
        cache.close()
    }

    @Test
    fun `reentrant same-value authority wins for every pager owner`() = runTest {
        val cache = newCache()
        val previous = message("reentrant-authority")
        cache.insertMessage(previous)
        val firstPager = cache.pager(previous.chatId)
        val secondPager = cache.pager(previous.chatId)
        val authority = previous.copy(flags = Message.FLAG_EDITED)
        val lease = assertNotNull(cache.reserveOptimisticMessageEdit(authority))
        var authorityInserted = false

        val collector = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
            start = CoroutineStart.UNDISPATCHED,
        ) {
            firstPager.messages.collect { messages ->
                if (!authorityInserted && messages.singleOrNull()?.flags == Message.FLAG_EDITED) {
                    authorityInserted = true
                    cache.insertMessage(authority)
                }
            }
        }

        assertFalse(cache.publishOptimisticMessageEdit(lease))
        assertTrue(cache.rollbackOptimisticMessageEdit(lease))
        assertEquals(authority, firstPager.messages.first().single())
        assertEquals(authority, secondPager.messages.first().single())

        collector.cancel()
        firstPager.close()
        secondPager.close()
        cache.close()
    }

    @Test
    fun `chat tombstone and global reset cannot be undone by edit rollback`() = runTest {
        val cache = newCache()
        val deleted = message("deleted")
        cache.insertMessage(deleted)
        val deletedPager = cache.pager(deleted.chatId)
        val deletedLease = assertNotNull(
            cache.reserveOptimisticMessageEdit(deleted.copy(flags = Message.FLAG_EDITED)),
        )
        assertTrue(cache.publishOptimisticMessageEdit(deletedLease))

        cache.deleteChat(deleted.chatId)

        assertTrue(cache.rollbackOptimisticMessageEdit(deletedLease))
        assertTrue(deletedPager.messages.first().isEmpty())

        val reset = message("reset")
        cache.insertMessage(reset)
        val resetLease = assertNotNull(
            cache.reserveOptimisticMessageEdit(reset.copy(flags = Message.FLAG_EDITED)),
        )
        assertTrue(cache.publishOptimisticMessageEdit(resetLease))

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)

        assertTrue(cache.rollbackOptimisticMessageEdit(resetLease))
        assertTrue(cache.getMessages(reset.chatId).isEmpty())
        deletedPager.close()
        cache.close()
    }

    @Test
    fun `caller cannot forge message identity flags status or transient progress`() = runTest {
        val cache = newCache()
        val previous = message("canonical-content").copy(flags = Message.FLAG_FORWARDED)
        cache.insertMessage(previous)
        val pager = cache.pager(previous.chatId)

        assertNull(cache.reserveOptimisticMessageEdit(previous.copy(serverSeq = 99L)))
        assertNull(cache.reserveOptimisticMessageEdit(previous.copy(senderUid = "forged")))
        assertNull(cache.reserveOptimisticMessageEdit(previous.copy(timestamp = 99L)))

        val lease = assertNotNull(
            cache.reserveOptimisticMessageEdit(
                previous.copy(
                    messageType = 2,
                    flags = 0,
                    sendStatus = Message.SEND_STATUS_FAILED,
                    uploadProgress = 0.75f,
                ),
            ),
        )
        assertTrue(cache.publishOptimisticMessageEdit(lease))
        val projection = pager.messages.first().single()
        assertEquals(Message.FLAG_FORWARDED or Message.FLAG_EDITED, projection.flags)
        assertEquals(Message.SEND_STATUS_SENT, projection.sendStatus)
        assertEquals(0f, projection.uploadProgress)
        assertTrue(cache.rollbackOptimisticMessageEdit(lease))

        cache.insertMessage(previous.copy(flags = previous.flags or Message.FLAG_REVOKED))
        assertNull(cache.reserveOptimisticMessageEdit(previous))
        pager.close()
        cache.close()
    }

    @Test
    fun `publish failure after resident overlay retains rollback capability`() = runTest {
        val cache = newCache()
        val previous = message("publish-failure")
        cache.insertMessage(previous)
        val pager = cache.pager(previous.chatId)
        val optimistic = previous.copy(flags = Message.FLAG_EDITED)
        val lease = assertNotNull(cache.reserveOptimisticMessageEdit(optimistic))
        cache.optimisticEditPublishedHookForTest = { error("after resident publish") }

        assertFailsWith<IllegalStateException> { cache.publishOptimisticMessageEdit(lease) }
        cache.optimisticEditPublishedHookForTest = null

        assertTrue(cache.rollbackOptimisticMessageEdit(lease))
        assertEquals(previous, pager.messages.first().single())
        pager.close()
        cache.close()
    }

    private fun newCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun message(clientMsgId: String, serverSeq: Long = 7L) = Message(
        chatId = "optimistic-chat",
        clientMsgId = clientMsgId,
        serverSeq = serverSeq,
        senderUid = "owner",
        messageType = 1,
        timestamp = serverSeq,
    )
}

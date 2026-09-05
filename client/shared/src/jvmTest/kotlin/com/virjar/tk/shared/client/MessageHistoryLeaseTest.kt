package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessageHistoryLeaseTest {
    private fun newCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun message(chatId: String, seq: Long, id: String = "m$seq") = Message(
        chatId = chatId,
        clientMsgId = id,
        serverSeq = seq,
        senderUid = "sender",
        messageType = 1,
        timestamp = seq,
    )

    @Test
    fun `late history page cannot resurrect a chat tombstone`() {
        val cache = newCache()
        val lease = cache.beginMessageHistoryLease("deleted", resetResidentWindow = true)

        cache.deleteChat("deleted")

        assertFalse(cache.applyMessageHistoryPage(lease, listOf(message("deleted", 10L))))
        assertTrue(cache.getMessages("deleted").isEmpty())
    }

    @Test
    fun `rebuilt chat accepts only its new lifecycle lease`() {
        val cache = newCache()
        val oldLease = cache.beginMessageHistoryLease("rebuilt", resetResidentWindow = true)
        cache.deleteChat("rebuilt")
        cache.upsertChat(Chat(chatId = "rebuilt", chatType = 1))
        val rebuiltLease = cache.beginMessageHistoryLease("rebuilt", resetResidentWindow = true)

        assertTrue(
            rebuiltLease.chatLifecycleGeneration > oldLease.chatLifecycleGeneration,
            "a rebuilt chat must receive a globally newer lifecycle token",
        )
        assertTrue(
            cache.applyMessageHistoryPage(
                rebuiltLease,
                listOf(message("rebuilt", 20L, "rebuilt-page")),
            ),
        )
        assertFalse(
            cache.applyMessageHistoryPage(
                oldLease,
                listOf(message("rebuilt", 10L, "pre-tombstone-page")),
            ),
        )
        assertEquals(listOf("rebuilt-page"), cache.getMessages("rebuilt").map(Message::clientMsgId))
    }

    @Test
    fun `newest history responses arriving in reverse order keep only the latest request`() {
        val cache = newCache()
        cache.pager("reverse-newest", LocalCache.DEFAULT_MESSAGE_WINDOW)
        val first = cache.beginMessageHistoryLease("reverse-newest", resetResidentWindow = true)
        val second = cache.beginMessageHistoryLease("reverse-newest", resetResidentWindow = true)

        assertTrue(
            cache.applyMessageHistoryPage(
                second,
                listOf(message("reverse-newest", 200L, "second-response")),
            ),
        )
        assertFalse(
            cache.applyMessageHistoryPage(
                first,
                listOf(message("reverse-newest", 100L, "first-response")),
            ),
        )
        assertEquals(
            listOf("second-response"),
            cache.getMessages("reverse-newest").map(Message::clientMsgId),
        )
    }

    @Test
    fun `older page cannot bind the first merely pending newest chain when older returns first`() {
        val cache = newCache()
        cache.pager("pending-without-anchor-first", LocalCache.DEFAULT_MESSAGE_WINDOW)
        val newest = cache.beginMessageHistoryLease(
            "pending-without-anchor-first",
            resetResidentWindow = true,
        )
        val older = cache.beginMessageHistoryLease(
            "pending-without-anchor-first",
            resetResidentWindow = false,
        )

        assertFalse(
            cache.applyMessageHistoryPage(
                older,
                listOf(message("pending-without-anchor-first", 90L, "unanchored-older")),
            ),
        )
        assertTrue(
            cache.applyMessageHistoryPage(
                newest,
                listOf(message("pending-without-anchor-first", 200L, "newest-anchor")),
            ),
        )
        assertEquals(
            listOf("newest-anchor"),
            cache.getMessages("pending-without-anchor-first").map(Message::clientMsgId),
        )
    }

    @Test
    fun `older page cannot bind the first merely pending newest chain when newest returns first`() {
        val cache = newCache()
        cache.pager("pending-without-anchor-newest", LocalCache.DEFAULT_MESSAGE_WINDOW)
        val newest = cache.beginMessageHistoryLease(
            "pending-without-anchor-newest",
            resetResidentWindow = true,
        )
        val older = cache.beginMessageHistoryLease(
            "pending-without-anchor-newest",
            resetResidentWindow = false,
        )

        assertTrue(
            cache.applyMessageHistoryPage(
                newest,
                listOf(message("pending-without-anchor-newest", 200L, "newest-anchor")),
            ),
        )
        assertFalse(
            cache.applyMessageHistoryPage(
                older,
                listOf(message("pending-without-anchor-newest", 90L, "unanchored-older")),
            ),
        )
        assertEquals(
            listOf("newest-anchor"),
            cache.getMessages("pending-without-anchor-newest").map(Message::clientMsgId),
        )
    }

    @Test
    fun `older page may finish on the committed anchor before pending newest resets it`() = runTest {
        val cache = newCache()
        val pager = cache.pager("older-wins", LocalCache.DEFAULT_MESSAGE_WINDOW)
        val anchor = cache.beginMessageHistoryLease("older-wins", resetResidentWindow = true)
        assertTrue(
            cache.applyMessageHistoryPage(
                anchor,
                listOf(message("older-wins", 100L, "old-anchor")),
            ),
        )
        val pendingNewest = cache.beginMessageHistoryLease("older-wins", resetResidentWindow = true)
        val older = cache.beginMessageHistoryLease("older-wins", resetResidentWindow = false)

        assertTrue(
            cache.applyMessageHistoryPage(
                older,
                listOf(message("older-wins", 90L, "old-chain-extension")),
            ),
        )
        assertEquals(
            listOf("old-anchor", "old-chain-extension"),
            pager.messages.first().map(Message::clientMsgId),
        )
        assertTrue(
            cache.applyMessageHistoryPage(
                pendingNewest,
                listOf(message("older-wins", 200L, "new-anchor")),
            ),
        )
        assertEquals(listOf("new-anchor"), pager.messages.first().map(Message::clientMsgId))
        pager.close()
    }

    @Test
    fun `older page cannot cross a newer newest-page reset`() = runTest {
        val cache = newCache()
        val pager = cache.pager("older-reset", LocalCache.DEFAULT_MESSAGE_WINDOW)
        val anchor = cache.beginMessageHistoryLease("older-reset", resetResidentWindow = true)
        assertTrue(cache.applyMessageHistoryPage(anchor, listOf(message("older-reset", 100L))))
        val older = cache.beginMessageHistoryLease("older-reset", resetResidentWindow = false)
        val reset = cache.beginMessageHistoryLease("older-reset", resetResidentWindow = true)

        assertTrue(
            cache.applyMessageHistoryPage(
                reset,
                listOf(message("older-reset", 200L, "reset-page")),
            ),
        )
        assertFalse(
            cache.applyMessageHistoryPage(
                older,
                listOf(message("older-reset", 90L, "old-chain-page")),
            ),
        )
        assertEquals(listOf("reset-page"), pager.messages.first().map(Message::clientMsgId))
        pager.close()
    }

    @Test
    fun `abandoning failed newest restores the last committed anchor`() {
        val cache = newCache()
        cache.pager("abandon-newest", LocalCache.DEFAULT_MESSAGE_WINDOW)
        val anchor = cache.beginMessageHistoryLease("abandon-newest", resetResidentWindow = true)
        assertTrue(
            cache.applyMessageHistoryPage(
                anchor,
                listOf(message("abandon-newest", 100L, "committed-anchor")),
            ),
        )
        val failedNewest = cache.beginMessageHistoryLease("abandon-newest", resetResidentWindow = true)

        assertTrue(cache.abandonMessageHistoryLease(failedNewest))
        assertFalse(
            cache.applyMessageHistoryPage(
                failedNewest,
                listOf(message("abandon-newest", 200L, "failed-page")),
            ),
        )
        val older = cache.beginMessageHistoryLease("abandon-newest", resetResidentWindow = false)
        assertTrue(
            cache.applyMessageHistoryPage(
                older,
                listOf(message("abandon-newest", 90L, "recovered-older")),
            ),
        )
        assertEquals(
            listOf("committed-anchor", "recovered-older"),
            cache.getMessages("abandon-newest").map(Message::clientMsgId),
        )
    }

    @Test
    fun `global projection reset invalidates every outstanding history lease`() {
        val cache = newCache()
        val lease = cache.beginMessageHistoryLease("global-reset", resetResidentWindow = true)

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)

        assertFalse(cache.applyMessageHistoryPage(lease, listOf(message("global-reset", 1L))))
        assertTrue(cache.getMessages("global-reset").isEmpty())
    }

    @Test
    fun `malformed row in a history page rolls back the whole SQLite page`() {
        val cache = newCache()
        val pager = cache.pager("rollback", LocalCache.DEFAULT_MESSAGE_WINDOW)
        val anchor = cache.beginMessageHistoryLease("rollback", resetResidentWindow = true)
        assertTrue(
            cache.applyMessageHistoryPage(
                anchor,
                listOf(message("rollback", 10L, "baseline")),
            ),
        )
        val lease = cache.beginMessageHistoryLease("rollback", resetResidentWindow = false)
        val validRow = message("rollback", 9L, "would-have-been-inserted")

        assertFailsWith<IllegalArgumentException> {
            cache.applyMessageHistoryPage(
                lease,
                listOf(validRow, message("another-chat", 8L, "invalid-row")),
            )
        }
        assertEquals(listOf("baseline"), cache.getMessages("rollback").map(Message::clientMsgId))

        pager.close()
        assertEquals(
            listOf("baseline"),
            cache.getMessages("rollback").map(Message::clientMsgId),
            "the first row must also have rolled back from SQLite",
        )
        assertTrue(cache.applyMessageHistoryPage(lease, listOf(validRow)))
        assertEquals(
            listOf("baseline", "would-have-been-inserted"),
            cache.getMessages("rollback").map(Message::clientMsgId),
        )
    }

    @Test
    fun `idle window eviction drains its admitted page then releases the history anchor`() {
        val cache = newCache()
        val pager = cache.pager("inactive", LocalCache.DEFAULT_MESSAGE_WINDOW)
        val anchor = cache.beginMessageHistoryLease("inactive", resetResidentWindow = true)
        assertTrue(
            cache.applyMessageHistoryPage(
                anchor,
                listOf(message("inactive", 10L, "anchor")),
            ),
        )
        val admittedOlder = cache.beginMessageHistoryLease("inactive", resetResidentWindow = false)

        pager.close()
        repeat(LocalCache.MAX_ACTIVE_CHATS) { index ->
            cache.pager("inactive-eviction-$index").close()
        }

        assertTrue(
            cache.applyMessageHistoryPage(
                admittedOlder,
                listOf(message("inactive", 9L, "admitted-older")),
            ),
            "window retirement must not split an already admitted atomic page",
        )
        assertEquals(
            listOf("anchor", "admitted-older"),
            cache.getMessages("inactive").map(Message::clientMsgId),
        )

        val detachedOlder = cache.beginMessageHistoryLease("inactive", resetResidentWindow = false)
        assertTrue(
            detachedOlder.chatLifecycleGeneration > admittedOlder.chatLifecycleGeneration,
            "release and recreation must allocate a fresh lifecycle token",
        )
        assertFalse(
            cache.applyMessageHistoryPage(
                detachedOlder,
                listOf(message("inactive", 8L, "detached-older")),
            ),
            "the deferred release must discard the old committed anchor after the request drains",
        )
        assertTrue(cache.abandonMessageHistoryLease(detachedOlder))
    }

    @Test
    fun `history anchors follow the resident chat capacity`() {
        val cache = newCache()
        val overflow = 4
        val total = LocalCache.MAX_ACTIVE_CHATS + overflow
        repeat(total) { index ->
            val chatId = "capacity-$index"
            val pager = cache.pager(chatId, LocalCache.DEFAULT_MESSAGE_WINDOW)
            val anchor = cache.beginMessageHistoryLease(chatId, resetResidentWindow = true)
            assertTrue(
                cache.applyMessageHistoryPage(
                    anchor,
                    listOf(message(chatId, 10L, "anchor-$index")),
                ),
            )
            pager.close()
        }

        repeat(overflow) { index ->
            val chatId = "capacity-$index"
            val older = cache.beginMessageHistoryLease(chatId, resetResidentWindow = false)
            assertFalse(
                cache.applyMessageHistoryPage(
                    older,
                    listOf(message(chatId, 9L, "evicted-$index")),
                ),
                "an evicted chat must not retain a detached history anchor",
            )
            assertTrue(cache.abandonMessageHistoryLease(older))
        }

        val retainedChatId = "capacity-${total - 1}"
        val retainedOlder = cache.beginMessageHistoryLease(
            retainedChatId,
            resetResidentWindow = false,
        )
        assertTrue(
            cache.applyMessageHistoryPage(
                retainedOlder,
                listOf(message(retainedChatId, 9L, "retained-older")),
            ),
            "a resident chat must keep its committed history anchor",
        )
    }

    @Test
    fun `realtime insert neither invalidates a history lease nor disappears behind its page`() {
        val cache = newCache()
        cache.pager("realtime", LocalCache.DEFAULT_MESSAGE_WINDOW)
        val lease = cache.beginMessageHistoryLease("realtime", resetResidentWindow = true)

        cache.insertMessage(message("realtime", 101L, "live-event"))

        assertTrue(
            cache.applyMessageHistoryPage(
                lease,
                listOf(message("realtime", 100L, "history-page")),
            ),
        )
        assertEquals(
            listOf("live-event", "history-page"),
            cache.getMessages("realtime").map(Message::clientMsgId),
        )
    }

    @Test
    fun `newest empty history response retains a different realtime message`() = runTest {
        val cache = newCache()
        val chatId = "realtime-absent-from-page"
        val pager = cache.pager(chatId, LocalCache.DEFAULT_MESSAGE_WINDOW)
        val lease = cache.beginMessageHistoryLease(chatId, resetResidentWindow = true)
        val live = message(chatId, 101L, "live-not-in-page")

        cache.insertMessage(live)

        assertTrue(cache.applyMessageHistoryPage(lease, emptyList()))
        assertEquals(listOf(live), cache.getMessages(chatId))
        assertEquals(listOf(live), pager.messages.first())
        pager.close()
    }

    @Test
    fun `newest history cannot overwrite a same-key realtime edit`() = runTest {
        val cache = newCache()
        val previous = message("same-key-newest", 100L, "target")
        cache.insertMessage(previous)
        val pager = cache.pager(previous.chatId)
        val lease = cache.beginMessageHistoryLease(previous.chatId, resetResidentWindow = true)
        val edited = previous.copy(flags = Message.FLAG_EDITED)

        cache.insertMessage(edited)

        assertTrue(cache.applyMessageHistoryPage(lease, listOf(previous)))
        assertEquals(edited, cache.getMessages(previous.chatId).single())
        assertEquals(edited, pager.messages.first().single())
        pager.close()
    }

    @Test
    fun `protected history row preserves a later optimistic overlay based on realtime authority`() = runTest {
        val cache = newCache()
        val previous = message("protected-overlay", 100L, "target")
        cache.insertMessage(previous)
        val pager = cache.pager(previous.chatId)
        val history = cache.beginMessageHistoryLease(previous.chatId, resetResidentWindow = true)
        val editedAuthority = previous.copy(flags = Message.FLAG_EDITED)
        cache.insertMessage(editedAuthority)
        val optimistic = editedAuthority.copy(messageType = 2)
        val editLease = assertNotNull(cache.reserveOptimisticMessageEdit(optimistic))
        assertTrue(cache.publishOptimisticMessageEdit(editLease))

        assertTrue(cache.applyMessageHistoryPage(history, listOf(previous)))

        assertEquals(editedAuthority, cache.getMessages(previous.chatId).single())
        assertEquals(2, pager.messages.first().single().messageType)
        assertTrue(cache.rollbackOptimisticMessageEdit(editLease))
        assertEquals(editedAuthority, pager.messages.first().single())
        pager.close()
    }

    @Test
    fun `older history reveals but cannot overwrite a below-cursor realtime edit`() = runTest {
        val cache = newCache()
        val chatId = "same-key-older"
        val pager = cache.pager(chatId)
        val anchor = cache.beginMessageHistoryLease(chatId, resetResidentWindow = true)
        assertTrue(cache.applyMessageHistoryPage(anchor, listOf(message(chatId, 100L, "anchor"))))
        val older = cache.beginMessageHistoryLease(chatId, resetResidentWindow = false)
        val stale = message(chatId, 90L, "older-target")
        val edited = stale.copy(flags = Message.FLAG_EDITED)

        cache.insertMessage(edited)
        assertTrue(cache.applyMessageHistoryPage(older, listOf(stale)))

        assertEquals(edited, cache.getMessages(chatId).first { it.clientMsgId == stale.clientMsgId })
        assertEquals(edited, pager.messages.first().first { it.clientMsgId == stale.clientMsgId })
        pager.close()
    }

    @Test
    fun `malformed page does not release same-key mutation provenance`() = runTest {
        val cache = newCache()
        val previous = message("malformed-fence", 100L, "target")
        cache.insertMessage(previous)
        val pager = cache.pager(previous.chatId)
        val lease = cache.beginMessageHistoryLease(previous.chatId, resetResidentWindow = true)
        val edited = previous.copy(flags = Message.FLAG_EDITED)
        cache.insertMessage(edited)

        assertFailsWith<IllegalArgumentException> {
            cache.applyMessageHistoryPage(
                lease,
                listOf(previous, message("another-chat", 99L, "invalid")),
            )
        }
        assertTrue(cache.applyMessageHistoryPage(lease, listOf(previous)))

        assertEquals(edited, cache.getMessages(previous.chatId).single())
        assertEquals(edited, pager.messages.first().single())
        pager.close()
    }

    @Test
    fun `blank history message id is rejected before any row is persisted`() {
        val cache = newCache()
        val chatId = "blank-id-atomicity"
        val pager = cache.pager(chatId, LocalCache.DEFAULT_MESSAGE_WINDOW)
        val anchor = cache.beginMessageHistoryLease(chatId, resetResidentWindow = true)
        assertTrue(cache.applyMessageHistoryPage(anchor, listOf(message(chatId, 10L, "baseline"))))
        val lease = cache.beginMessageHistoryLease(chatId, resetResidentWindow = false)
        val valid = message(chatId, 9L, "must-roll-back")

        assertFailsWith<IllegalArgumentException> {
            cache.applyMessageHistoryPage(lease, listOf(valid, message(chatId, 8L, "")))
        }
        pager.close()
        assertEquals(listOf("baseline"), cache.getMessages(chatId).map(Message::clientMsgId))

        assertTrue(cache.applyMessageHistoryPage(lease, listOf(valid)))
        assertEquals(
            listOf("baseline", "must-roll-back"),
            cache.getMessages(chatId).map(Message::clientMsgId),
        )
    }

    @Test
    fun `malformed history identity and oversized pages are rejected before persistence`() {
        val cache = newCache()
        val chatId = "history-page-contract"
        val pager = cache.pager(chatId, LocalCache.DEFAULT_MESSAGE_WINDOW)
        val anchor = cache.beginMessageHistoryLease(chatId, resetResidentWindow = true)
        assertTrue(cache.applyMessageHistoryPage(anchor, listOf(message(chatId, 20L, "baseline"))))
        val lease = cache.beginMessageHistoryLease(chatId, resetResidentWindow = false)
        val valid = message(chatId, 19L, "valid")

        assertFailsWith<IllegalArgumentException> {
            cache.applyMessageHistoryPage(lease, listOf(valid, message(chatId, 0L, "zero-seq")))
        }
        assertFailsWith<IllegalArgumentException> {
            cache.applyMessageHistoryPage(lease, listOf(valid, valid.copy(serverSeq = 18L)))
        }
        assertFailsWith<IllegalArgumentException> {
            cache.applyMessageHistoryPage(lease, listOf(valid, message(chatId, 19L, "duplicate-seq")))
        }
        assertFailsWith<IllegalArgumentException> {
            cache.applyMessageHistoryPage(
                lease,
                (1..(Message.MAX_QUERY_PAGE_SIZE + 1)).map { index ->
                    message(chatId, index.toLong(), "oversized-$index")
                },
            )
        }

        pager.close()
        assertEquals(listOf("baseline"), cache.getMessages(chatId).map(Message::clientMsgId))
        assertTrue(cache.applyMessageHistoryPage(lease, listOf(valid)))
        assertEquals(
            listOf("baseline", "valid"),
            cache.getMessages(chatId).map(Message::clientMsgId),
        )
    }

    @Test
    fun `accepted older row fences a pending newest response on the same key`() = runTest {
        val cache = newCache()
        val chatId = "cross-lane-mutation"
        val pager = cache.pager(chatId)
        val anchor = cache.beginMessageHistoryLease(chatId, resetResidentWindow = true)
        assertTrue(cache.applyMessageHistoryPage(anchor, listOf(message(chatId, 100L, "anchor"))))
        val newest = cache.beginMessageHistoryLease(chatId, resetResidentWindow = true)
        val older = cache.beginMessageHistoryLease(chatId, resetResidentWindow = false)
        val stale = message(chatId, 90L, "shared-row")
        val accepted = stale.copy(flags = Message.FLAG_EDITED)

        assertTrue(cache.applyMessageHistoryPage(older, listOf(accepted)))
        assertTrue(
            cache.applyMessageHistoryPage(
                newest,
                listOf(message(chatId, 200L, "new-anchor"), stale),
            ),
        )

        assertEquals(accepted, cache.getMessages(chatId).first { it.clientMsgId == stale.clientMsgId })
        assertEquals(accepted, pager.messages.first().first { it.clientMsgId == stale.clientMsgId })
        pager.close()
    }

    @Test
    fun `history mutation fence fails closed at its exact space bound`() {
        val gate = MessageHistoryLeaseGate("bounded history fence")
        val lease = gate.begin("bounded", resetResidentWindow = true)
        repeat(MessageHistoryLeaseGate.MAX_MUTATED_KEYS_PER_REQUEST + 1) { index ->
            gate.recordAuthoritativeMutation("bounded", "message-$index")
        }
        var applied = false

        assertFalse(
            gate.consumeIfCurrent(lease) { _, _, _, _ -> applied = true },
        )
        assertFalse(applied)
        assertTrue(gate.abandon(lease))

        val successor = gate.begin("bounded", resetResidentWindow = true)
        var successorApplied = false
        assertTrue(
            gate.consumeIfCurrent(successor) { _, _, mutations, liveMutations ->
                assertTrue(mutations.isEmpty())
                assertTrue(liveMutations.isEmpty())
                successorApplied = true
            },
        )
        assertTrue(successorApplied)
    }

    @Test
    fun `history mutation fence accepts exactly its documented bound`() {
        val gate = MessageHistoryLeaseGate("exact bounded history fence")
        val lease = gate.begin("exact-bounded", resetResidentWindow = true)
        repeat(MessageHistoryLeaseGate.MAX_MUTATED_KEYS_PER_REQUEST) { index ->
            gate.recordAuthoritativeMutation(
                "exact-bounded",
                "message-$index",
                retainIfAbsentFromNewestPage = index % 2 == 0,
            )
        }
        var applied = false

        assertTrue(
            gate.consumeIfCurrent(lease) { _, _, mutations, liveMutations ->
                assertEquals(MessageHistoryLeaseGate.MAX_MUTATED_KEYS_PER_REQUEST, mutations.size)
                assertEquals(MessageHistoryLeaseGate.MAX_MUTATED_KEYS_PER_REQUEST / 2, liveMutations.size)
                applied = true
            },
        )
        assertTrue(applied)
    }

    @Test
    fun `close invalidates outstanding leases without touching the closed driver`() {
        val cache = newCache()
        val lease = cache.beginMessageHistoryLease("closed", resetResidentWindow = true)

        cache.close()

        assertFalse(cache.applyMessageHistoryPage(lease, listOf(message("closed", 1L))))
        assertFailsWith<IllegalStateException> {
            cache.beginMessageHistoryLease("closed", resetResidentWindow = true)
        }
        cache.close()
    }
}

package com.virjar.tk.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.database.AppDatabase
import com.virjar.tk.model.Chat
import com.virjar.tk.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `older page may finish on the committed anchor before pending newest resets it`() {
        val cache = newCache()
        cache.pager("older-wins", LocalCache.DEFAULT_MESSAGE_WINDOW)
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
            cache.getMessages("older-wins").map(Message::clientMsgId),
        )
        assertTrue(
            cache.applyMessageHistoryPage(
                pendingNewest,
                listOf(message("older-wins", 200L, "new-anchor")),
            ),
        )
        assertEquals(listOf("new-anchor"), cache.getMessages("older-wins").map(Message::clientMsgId))
    }

    @Test
    fun `older page cannot cross a newer newest-page reset`() {
        val cache = newCache()
        cache.pager("older-reset", LocalCache.DEFAULT_MESSAGE_WINDOW)
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
        assertEquals(listOf("reset-page"), cache.getMessages("older-reset").map(Message::clientMsgId))
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

        cache.resetServerProjection()

        assertFalse(cache.applyMessageHistoryPage(lease, listOf(message("global-reset", 1L))))
        assertTrue(cache.getMessages("global-reset").isEmpty())
    }

    @Test
    fun `malformed row in a history page rolls back the whole SQLite page`() {
        val cache = newCache()
        cache.pager("rollback", LocalCache.DEFAULT_MESSAGE_WINDOW)
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

        cache.onChatInactive("rollback")
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

package com.virjar.tk.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseSchemaTest {
    private fun createInMemoryDb(): DatabaseQueries {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TeamTalkDatabase.Schema.create(driver)
        return TeamTalkDatabase(driver).databaseQueries
    }

    @Test
    fun testSchemaCreation() {
        val queries = createInMemoryDb()
        // messages
        queries.insertMessage("ch1", 1, "msg1", "u1", "Alice", 1, """{"text":"hello"}""", 0, 1000L)
        val msgs = queries.selectAllMessages("ch1", 10).executeAsList()
        assertEquals(1, msgs.size)

        // conversations
        queries.insertConversation("ch1", 1, "Test", "", "hello", 1000L, 1, 0, 0, 0, "", 0L, 0)
        val convs = queries.selectAllConversations().executeAsList()
        assertEquals(1, convs.size)

        // contacts
        queries.insertContact("u2", "Bob", "", "", 1, 0, 1000L)
        val contacts = queries.selectAllContacts().executeAsList()
        assertEquals(1, contacts.size)

        // channels
        queries.insertChannel("ch1", 2, "Group1", "", "u1", 5, 1000L, 1000L)
        val channels = queries.selectAllChannels().executeAsList()
        assertEquals(1, channels.size)

        // read_positions
        queries.insertReadPosition("ch1", 5)
        val rp = queries.selectReadPosition("ch1").executeAsOne()
        assertEquals(5L, rp.read_seq)
    }

    @Test
    fun testMessageInsertAndQuery() {
        val queries = createInMemoryDb()
        for (i in 1..3) {
            queries.insertMessage("ch1", i.toLong(), "msg$i", "u1", "", 1, """{"text":"msg$i"}""", 0, 1000L + i)
        }
        val all = queries.selectAllMessages("ch1", 10).executeAsList()
        assertEquals(3, all.size)
        assertEquals(1, all[0].seq)
        assertEquals(3, all[2].seq)
        val after = queries.selectMessagesAfterSeq("ch1", 2, 10).executeAsList()
        assertEquals(1, after.size)
        assertEquals(3, after[0].seq)
    }

    @Test
    fun testConversationOrdering() {
        val queries = createInMemoryDb()
        queries.insertConversation("ch1", 1, "Old", "", "hi", 1000L, 1, 0, 0, 0, "", 0L, 0)
        queries.insertConversation("ch2", 1, "Pinned", "", "hello", 2000L, 1, 0, 1, 0, "", 0L, 0)
        queries.insertConversation("ch3", 1, "Recent", "", "hey", 3000L, 1, 0, 0, 0, "", 0L, 0)
        val convs = queries.selectAllConversations().executeAsList()
        assertEquals("ch2", convs[0].channel_id) // pinned first
        assertEquals("ch3", convs[1].channel_id) // time 3000
        assertEquals("ch1", convs[2].channel_id) // time 1000
    }
}

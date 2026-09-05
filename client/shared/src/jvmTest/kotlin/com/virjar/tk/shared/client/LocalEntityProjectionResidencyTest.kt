package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.User
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalEntityProjectionResidencyTest {
    @Test
    fun `restart reconstructs joined contacts and one chat members without resident keys`() {
        val databaseFile = Files.createTempFile("teamtalk-lazy-entity-", ".db")
        try {
            openFileCache(databaseFile.toString(), createSchema = true).let { first ->
                val user = user("u1", "Persisted User")
                first.upsertContact(Contact(uid = "me", friendUid = user.uid, user = user))
                first.upsertChat(chat("g1", "Persisted Group"))
                first.upsertMember(Member(uid = user.uid, chatId = "g1", role = 0, user = user))
                first.close()
            }

            openFileCache(databaseFile.toString(), createSchema = false).let { rebuilt ->
                try {
                    assertEquals("Persisted User", rebuilt.getContacts().single().user?.name)
                    assertEquals("Persisted User", rebuilt.getMembers("g1").single().user?.name)
                    assertEquals("Persisted Group", rebuilt.getChat("g1")?.name)
                    assertEquals("Persisted User", rebuilt.getUser("u1")?.name)
                    assertEquals(
                        EntityProjectionResidentCounts(1, 0, 0, 0, 0, 0, 0),
                        rebuilt.residentEntityProjectionCountsForTest(),
                    )
                } finally {
                    rebuilt.close()
                }
            }
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }

    @Test
    fun `large persisted projection keeps only explicit contacts resident`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val queries = AppDatabase(driver).appDatabaseQueries
        queries.transaction {
            repeat(SCALE_ROWS) { index ->
                val user = user("u$index", "User $index")
                queries.upsertUser(
                    user.uid,
                    user.username,
                    user.name,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0L,
                    0L,
                    1L,
                    1L,
                )
                queries.upsertChat(
                    "g$index",
                    2L,
                    "Group $index",
                    null,
                    null,
                    1L,
                    0L,
                    null,
                    0L,
                )
                queries.upsertMember("g$index", user.uid, 0L, null, index.toLong())
                if (index < RESIDENT_CONTACT_ROWS) {
                    queries.upsertContact("me", user.uid, null, 1L)
                }
            }
        }

        val cache = LocalCacheImpl(driver)
        assertEquals(
            EntityProjectionResidentCounts(RESIDENT_CONTACT_ROWS, 0, 0, 0, 0, 0, 0),
            cache.residentEntityProjectionCountsForTest(),
        )

        assertEquals("User ${SCALE_ROWS - 1}", cache.getUser("u${SCALE_ROWS - 1}")?.name)
        assertEquals("Group ${SCALE_ROWS - 1}", cache.getChat("g${SCALE_ROWS - 1}")?.name)
        assertEquals("u${SCALE_ROWS - 1}", cache.getMembers("g${SCALE_ROWS - 1}").single().uid)
        repeat(SCALE_ROWS) { index ->
            cache.upsertUser(user("background-$index", "Background $index"))
            cache.upsertChat(chat("background-$index", "Background $index"))
            cache.upsertMember(
                Member(uid = "background-$index", chatId = "background-chat-$index", role = 0),
            )
        }

        assertEquals(
            EntityProjectionResidentCounts(RESIDENT_CONTACT_ROWS, 0, 0, 0, 0, 0, 0),
            cache.residentEntityProjectionCountsForTest(),
            "keyed reads and unobserved events must not allocate resident flows",
        )
        cache.close()
    }

    @Test
    fun `keyed observers share one resident and release it after the last collector`() = runBlocking {
        val cache = newCache()
        cache.upsertUser(user("u1", "User One"))
        cache.upsertChat(chat("g1", "Group One"))
        cache.upsertMember(Member(uid = "u1", chatId = "g1", role = 0))

        val firstUser = launch(start = CoroutineStart.UNDISPATCHED) { cache.observeUser("u1").collect { } }
        val secondUser = launch(start = CoroutineStart.UNDISPATCHED) { cache.observeUser("u1").collect { } }
        assertEquals(1, cache.residentEntityProjectionCountsForTest().users)
        assertEquals(2, cache.residentEntityProjectionCountsForTest().userObservers)
        firstUser.cancelAndJoin()
        assertEquals(1, cache.residentEntityProjectionCountsForTest().userObservers)
        secondUser.cancelAndJoin()
        assertEquals(0, cache.residentEntityProjectionCountsForTest().users)

        val chatObserver = launch(start = CoroutineStart.UNDISPATCHED) { cache.observeChat("g1").collect { } }
        val memberObserver = launch(start = CoroutineStart.UNDISPATCHED) { cache.observeMembers("g1").collect { } }
        assertEquals(1, cache.residentEntityProjectionCountsForTest().chats)
        assertEquals(1, cache.residentEntityProjectionCountsForTest().memberChats)
        chatObserver.cancelAndJoin()
        memberObserver.cancelAndJoin()
        assertEquals(0, cache.residentEntityProjectionCountsForTest().chats)
        assertEquals(0, cache.residentEntityProjectionCountsForTest().memberChats)

        val missing = launch(start = CoroutineStart.UNDISPATCHED) { cache.observeUser("missing").collect { } }
        assertEquals(1, cache.residentEntityProjectionCountsForTest().users)
        assertNull(cache.getUser("missing"))
        missing.cancelAndJoin()
        assertEquals(0, cache.residentEntityProjectionCountsForTest().users)
        cache.close()
    }

    @Test
    fun `user update refreshes contacts and only currently observed member chats`() = runBlocking {
        val cache = newCache()
        val original = user("u1", "Old Name")
        val updated = original.copy(name = "Updated Name", revision = original.revision + 1L)
        cache.upsertContact(Contact(uid = "me", friendUid = original.uid, user = original))
        listOf("g1", "g2", "unobserved").forEach { chatId ->
            cache.upsertMember(Member(uid = original.uid, chatId = chatId, role = 0, user = original))
        }

        val contactStates = mutableListOf<List<Contact>>()
        val firstMemberStates = mutableListOf<List<Member>>()
        val secondMemberStates = mutableListOf<List<Member>>()
        val contacts = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observeContacts().collect { contactStates += it }
        }
        val firstMembers = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observeMembers("g1").collect { firstMemberStates += it }
        }
        val secondMembers = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observeMembers("g2").collect { secondMemberStates += it }
        }
        assertEquals(2, cache.residentEntityProjectionCountsForTest().memberChats)

        cache.upsertUser(updated)
        awaitSize(contactStates, 2)
        awaitSize(firstMemberStates, 2)
        awaitSize(secondMemberStates, 2)

        assertEquals(updated, contactStates.last().single().user)
        assertEquals(updated, firstMemberStates.last().single().user)
        assertEquals(updated, secondMemberStates.last().single().user)
        assertEquals(updated, cache.getMembers("unobserved").single().user)
        assertEquals(2, cache.residentEntityProjectionCountsForTest().memberChats)

        contacts.cancelAndJoin()
        firstMembers.cancelAndJoin()
        secondMembers.cancelAndJoin()
        assertEquals(0, cache.residentEntityProjectionCountsForTest().memberChats)
        cache.close()
    }

    @Test
    fun `equal revision conflict neither overwrites nor publishes normalized user`() = runBlocking {
        val cache = newCache()
        val canonical = user("u1", "Canonical").copy(revision = 2L)
        cache.upsertContact(Contact(uid = "me", friendUid = canonical.uid, user = canonical))

        cache.observeUser(canonical.uid).test {
            assertEquals(canonical, awaitItem())
            cache.observeContacts().test {
                assertEquals(canonical, awaitItem().single().user)

                cache.upsertUser(canonical.copy(name = "Conflicting"))

                expectNoEvents()
                assertEquals(canonical, cache.getUser(canonical.uid))
                assertEquals(canonical, cache.getContacts().single().user)
                cancelAndIgnoreRemainingEvents()
            }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        cache.close()
    }

    @Test
    fun `chat tombstone empties active observers and rejects in flight snapshots`() = runBlocking {
        val cache = newCache()
        val chat = chat("deleted", "Deleted Group")
        val member = Member(
            uid = "u1",
            chatId = chat.chatId,
            role = 0,
            user = user("u1", "Member"),
        )
        cache.upsertChat(chat)
        cache.upsertMember(member)
        val staleChat = cache.beginChatSnapshot(chat.chatId)
        val staleMembers = cache.beginMemberSnapshot(chat.chatId)
        val chatStates = mutableListOf<Chat?>()
        val memberStates = mutableListOf<List<Member>>()
        val chatObserver = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observeChat(chat.chatId).collect { chatStates += it }
        }
        val memberObserver = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observeMembers(chat.chatId).collect { memberStates += it }
        }

        cache.deleteChat(chat.chatId)
        awaitSize(chatStates, 2)
        awaitSize(memberStates, 2)

        assertNull(chatStates.last())
        assertTrue(memberStates.last().isEmpty())
        assertNull(cache.getChat(chat.chatId))
        assertTrue(cache.getMembers(chat.chatId).isEmpty())
        assertFalse(cache.applyChatSnapshot(staleChat, chat.copy(name = "Stale")))
        assertFalse(cache.applyMemberSnapshot(staleMembers, listOf(member)))
        assertNull(cache.getChat(chat.chatId))
        assertTrue(cache.getMembers(chat.chatId).isEmpty())

        chatObserver.cancelAndJoin()
        memberObserver.cancelAndJoin()
        assertEquals(0, cache.residentEntityProjectionCountsForTest().chats)
        assertEquals(0, cache.residentEntityProjectionCountsForTest().memberChats)
        cache.close()
    }

    @Test
    fun `sync reset clears active residents and replay reuses the same collectors`() = runBlocking {
        val cache = newCache()
        val originalUser = user("u1", "Before Reset")
        val replayedUser = originalUser.copy(name = "After Replay")
        val originalChat = chat("g1", "Before Reset")
        val replayedChat = originalChat.copy(name = "After Replay")
        cache.upsertUser(originalUser)
        cache.upsertContact(Contact(uid = "me", friendUid = originalUser.uid, user = originalUser))
        cache.upsertChat(originalChat)
        cache.upsertMember(Member(uid = originalUser.uid, chatId = originalChat.chatId, role = 0))

        val users = mutableListOf<User?>()
        val contacts = mutableListOf<List<Contact>>()
        val chats = mutableListOf<Chat?>()
        val members = mutableListOf<List<Member>>()
        val jobs = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeUser("u1").collect { users += it } },
            launch(start = CoroutineStart.UNDISPATCHED) {
                cache.observeContacts().collect { contacts += it }
            },
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeChat("g1").collect { chats += it } },
            launch(start = CoroutineStart.UNDISPATCHED) {
                cache.observeMembers("g1").collect { members += it }
            },
        )

        cache.resetServerProjection(TEST_SYNC_DATASET_ID)
        awaitSize(users, 2)
        awaitSize(contacts, 2)
        awaitSize(chats, 2)
        awaitSize(members, 2)
        assertNull(users.last())
        assertTrue(contacts.last().isEmpty())
        assertNull(chats.last())
        assertTrue(members.last().isEmpty())

        cache.upsertUser(replayedUser)
        cache.upsertContact(Contact(uid = "me", friendUid = replayedUser.uid, user = replayedUser))
        cache.upsertChat(replayedChat)
        cache.upsertMember(Member(uid = replayedUser.uid, chatId = replayedChat.chatId, role = 0))
        awaitSize(users, 3)
        awaitSize(contacts, 3)
        awaitSize(chats, 3)
        awaitSize(members, 3)
        assertEquals(replayedUser, users.last())
        assertEquals(replayedUser, contacts.last().single().user)
        assertEquals(replayedChat, chats.last())
        assertEquals(replayedUser, members.last().single().user)

        jobs.forEach { it.cancelAndJoin() }
        assertEquals(0, cache.residentEntityProjectionCountsForTest().users)
        assertEquals(0, cache.residentEntityProjectionCountsForTest().chats)
        assertEquals(0, cache.residentEntityProjectionCountsForTest().memberChats)
        cache.close()
    }

    @Test
    fun `close completes every entity collector and rejects late cache work`() = runBlocking {
        val cache = newCache()
        val cachedUser = user("u1", "User")
        cache.upsertUser(cachedUser)
        cache.upsertChat(chat("g1", "Group"))
        cache.upsertMember(Member(uid = cachedUser.uid, chatId = "g1", role = 0))
        val observers = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeUser("u1").collect { } },
            // 这些已经是 null/empty 的状态证明 close 是一个独特的终局信号，
            // 而不是可能被 StateFlow 相等性抑制掉的哨兵发布尝试。
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeUser("missing").collect { } },
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeContacts().collect { } },
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeChat("g1").collect { } },
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeChat("missing").collect { } },
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeMembers("g1").collect { } },
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeMembers("empty").collect { } },
            launch(start = CoroutineStart.UNDISPATCHED) { cache.observeConversations().collect { } },
        )
        assertEquals(2, cache.residentEntityProjectionCountsForTest().userObservers)
        assertEquals(2, cache.residentEntityProjectionCountsForTest().chatObservers)
        assertEquals(2, cache.residentEntityProjectionCountsForTest().memberObservers)
        val userLease = cache.beginUserSnapshot("u1")
        val chatLease = cache.beginChatSnapshot("g1")
        val memberLease = cache.beginMemberSnapshot("g1")
        val contactGeneration = cache.contactProjectionGeneration()

        cache.close()
        withTimeout(2_000) { observers.forEach { it.join() } }

        assertFalse(cache.applyUserSnapshot(userLease, cachedUser.copy(name = "Late")))
        assertFalse(cache.applyChatSnapshot(chatLease, chat("g1", "Late")))
        assertFalse(cache.applyMemberSnapshot(memberLease, emptyList()))
        assertFalse(cache.applyContactSnapshot(contactGeneration, emptyList()))
        assertFailsWith<IllegalStateException> { cache.getUser("u1") }
        assertFailsWith<IllegalStateException> { cache.observeUser("u1") }
        Unit
    }

    private suspend fun awaitSize(values: List<*>, size: Int) {
        withTimeout(2_000) {
            while (values.size < size) yield()
        }
    }

    private fun newCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun openFileCache(path: String, createSchema: Boolean): LocalCacheImpl {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        if (createSchema) AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun user(uid: String, name: String) = User(uid = uid, username = uid, name = name)
    private fun chat(chatId: String, name: String) = Chat(chatId = chatId, chatType = 2, name = name)

    private companion object {
        const val SCALE_ROWS = 256
        const val RESIDENT_CONTACT_ROWS = 32
    }
}

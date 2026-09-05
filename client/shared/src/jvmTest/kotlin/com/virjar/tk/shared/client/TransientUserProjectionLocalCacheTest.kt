package com.virjar.tk.shared.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.virjar.tk.shared.Outcome
import com.virjar.tk.shared.database.AppDatabase
import com.virjar.tk.protocol.model.Contact
import com.virjar.tk.protocol.model.Conversation
import com.virjar.tk.protocol.model.Member
import com.virjar.tk.protocol.model.OrganizationMember
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.shared.repository.UserRepository
import com.virjar.tk.shared.testkit.FakeRpcInvoker
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransientUserProjectionLocalCacheTest {
    @Test
    fun `unknown transient user is not materialized`() {
        val cache = newMemoryCache()
        val unknown = user("unknown", "Unknown")

        assertFalse(cache.upsertTransientUserIfRelevant(unknown))
        assertNull(cache.getUser(unknown.uid))
        cache.close()
    }

    @Test
    fun `unknown transient revision bridges the first authoritative relationship`() {
        val cache = newMemoryCache()

        val profileA = user("profile", "Profile A").copy(revision = 1L)
        val profileB = profileA.copy(name = "Profile B", revision = 2L)
        val profileLease = cache.beginUserSnapshot(profileA.uid)
        assertTrue(cache.upsertTransientUserIfRelevant(profileB))
        assertEquals(profileB, cache.getUser(profileB.uid))
        assertFalse(cache.applyUserSnapshot(profileLease, profileA))
        assertEquals(profileB, cache.getUser(profileB.uid))

        val memberA = user("member", "Member A").copy(revision = 1L)
        val memberB = memberA.copy(name = "Member B", revision = 2L)
        assertFalse(cache.upsertTransientUserIfRelevant(memberB))
        assertNull(cache.getUser(memberB.uid))
        cache.upsertMember(Member(memberA.uid, "group", role = 0, user = memberA))
        assertEquals(memberB, cache.getMembers("group").single().user)

        val organizationA = user("organization", "Organization A").copy(revision = 1L)
        val organizationB = organizationA.copy(name = "Organization B", revision = 2L)
        assertFalse(cache.upsertTransientUserIfRelevant(organizationB))
        assertNull(cache.getUser(organizationB.uid))
        val organizationLease = cache.beginOrganizationMemberSnapshot("unit")
        assertTrue(
            cache.applyOrganizationMemberSnapshot(
                organizationLease,
                listOf(OrganizationMember("unit", organizationA.uid, user = organizationA)),
                revision = 1L,
            ),
        )
        assertEquals(organizationB, cache.getUser(organizationB.uid))
        assertEquals(
            organizationB,
            cache.getOrganizationMemberProjection("unit").members.single().user,
        )

        val snapshotA = user("snapshot-peer", "Snapshot A").copy(revision = 1L)
        val snapshotB = snapshotA.copy(name = "Snapshot B", revision = 2L)
        assertFalse(cache.upsertTransientUserIfRelevant(snapshotB))
        assertNull(cache.getUser(snapshotB.uid))
        val conversationLease = cache.beginConversationSnapshot()
        assertTrue(
            cache.applyConversationSnapshot(
                conversationLease,
                listOf(personalConversation("snapshot-chat", snapshotA)),
            ),
        )
        assertEquals(snapshotB, cache.getUser(snapshotB.uid))

        val upsertA = user("upsert-peer", "Upsert A").copy(revision = 1L)
        val upsertB = upsertA.copy(name = "Upsert B", revision = 2L)
        assertFalse(cache.upsertTransientUserIfRelevant(upsertB))
        assertNull(cache.getUser(upsertB.uid))
        cache.upsertConversation(personalConversation("upsert-chat", upsertA))
        assertEquals(upsertB, cache.getUser(upsertB.uid))
        cache.close()
    }

    @Test
    fun `active user snapshot keeps a transient update outside the bounded unknown bridge`() {
        val cache = newMemoryCache()
        val revisionOne = user("profile", "A").copy(revision = 1L)
        val revisionTwo = revisionOne.copy(name = "B", revision = 2L)
        val lease = cache.beginUserSnapshot(revisionOne.uid)

        assertTrue(cache.upsertTransientUserIfRelevant(revisionTwo))
        repeat(257) { index ->
            assertFalse(
                cache.upsertTransientUserIfRelevant(
                    user("noise-$index", "Noise $index").copy(revision = 2L),
                ),
            )
        }

        assertFalse(cache.applyUserSnapshot(lease, revisionOne))
        assertEquals(revisionTwo, cache.getUser(revisionOne.uid))
        assertEquals(256, cache.recentTransientUserCountForTest())
        cache.close()
    }

    @Test
    fun `active user resident materializes a transient update immediately`() = runBlocking {
        val cache = newMemoryCache()
        val updated = user("profile", "B").copy(revision = 2L)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observeUser(updated.uid).collect { }
        }

        assertTrue(cache.upsertTransientUserIfRelevant(updated))
        assertEquals(updated, cache.getUser(updated.uid))
        assertEquals(0, cache.recentTransientUserCountForTest())

        collector.cancelAndJoin()
        cache.close()
    }

    @Test
    fun `transient before an exact projection survives bounded bridge pressure`() = runBlocking {
        val cache = newMemoryCache()
        val revisionOne = user("profile", "A").copy(revision = 1L)
        val revisionTwo = revisionOne.copy(name = "B", revision = 2L)
        assertFalse(cache.upsertTransientUserIfRelevant(revisionTwo))

        val lease = cache.beginUserSnapshot(revisionOne.uid)
        assertEquals(revisionTwo, cache.getUser(revisionOne.uid))
        repeat(257) { index ->
            assertFalse(
                cache.upsertTransientUserIfRelevant(
                    user("snapshot-noise-$index", "Noise $index").copy(revision = 2L),
                ),
            )
        }
        assertFalse(cache.applyUserSnapshot(lease, revisionOne))
        assertEquals(revisionTwo, cache.getUser(revisionOne.uid))

        val residentRevisionOne = user("resident", "A").copy(revision = 1L)
        val residentRevisionTwo = residentRevisionOne.copy(name = "B", revision = 2L)
        assertFalse(cache.upsertTransientUserIfRelevant(residentRevisionTwo))
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.observeUser(residentRevisionOne.uid).collect { }
        }
        repeat(257) { index ->
            assertFalse(
                cache.upsertTransientUserIfRelevant(
                    user("resident-noise-$index", "Noise $index").copy(revision = 2L),
                ),
            )
        }
        cache.upsertUser(residentRevisionOne)
        assertEquals(residentRevisionTwo, cache.getUser(residentRevisionOne.uid))

        collector.cancelAndJoin()
        cache.close()
    }

    @Test
    fun `late search response returns the canonical transient revision`() = runBlocking {
        val cache = newMemoryCache()
        val stale = user("search-result", "A").copy(revision = 1L)
        val fresh = stale.copy(name = "B", revision = 2L)
        assertFalse(cache.upsertTransientUserIfRelevant(fresh))
        assertNull(cache.getUser(stale.uid))
        val rpc = FakeRpcInvoker().apply { enqueueOk(ProtoCodec.encodeList(listOf(stale))) }

        val result = assertIs<Outcome.Success<List<User>>>(UserRepository(rpc, cache).search("search"))

        assertEquals(listOf(fresh), result.value)
        assertEquals(fresh, cache.getUser(stale.uid))
        cache.close()
    }

    @Test
    fun `unknown transient bridge is bounded and evicts the least recently used uid`() {
        val cache = newMemoryCache()
        repeat(257) { index ->
            val transient = user("unknown-$index", "B$index").copy(revision = 2L)
            assertFalse(cache.upsertTransientUserIfRelevant(transient))
        }

        assertEquals(256, cache.recentTransientUserCountForTest())
        val evictedA = user("unknown-0", "A0").copy(revision = 1L)
        cache.upsertUser(evictedA)
        assertEquals(evictedA, cache.getUser(evictedA.uid))

        val retainedA = user("unknown-256", "A256").copy(revision = 1L)
        cache.upsertConversation(personalConversation("retained-chat", retainedA))
        assertEquals("B256", cache.getUser(retainedA.uid)?.name)
        assertEquals(256, cache.recentTransientUserCountForTest())
        cache.close()
    }

    @Test
    fun `failed peer materialization retains overlay and does not commit or publish stale conversation`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        val cache = LocalCacheImpl(driver)
        val stale = user("rollback-peer", "A").copy(revision = 1L)
        val fresh = stale.copy(name = "B", revision = 2L)
        val conversation = personalConversation("rollback-chat", stale)
        assertFalse(cache.upsertTransientUserIfRelevant(fresh))
        driver.execute(
            null,
            """
            CREATE TRIGGER reject_rollback_peer
            BEFORE INSERT ON user
            WHEN NEW.uid = 'rollback-peer'
            BEGIN
                SELECT RAISE(ABORT, 'injected user projection failure');
            END
            """.trimIndent(),
            0,
        )

        assertFailsWith<Exception> { cache.upsertConversation(conversation) }

        assertNull(cache.getUser(stale.uid))
        assertTrue(cache.getConversations().isEmpty())
        assertTrue(
            AppDatabase(driver).appDatabaseQueries.selectAllConversations().executeAsList().isEmpty(),
        )
        assertEquals(1, cache.recentTransientUserCountForTest())

        driver.execute(null, "DROP TRIGGER reject_rollback_peer", 0)
        cache.upsertConversation(conversation)

        assertEquals(fresh, cache.getUser(stale.uid))
        assertEquals(conversation, cache.getConversations().single())
        cache.close()
    }

    @Test
    fun `personal conversation peer admits a transient user`() {
        val cache = newMemoryCache()
        val peer = user("peer", "Fresh Peer")
        cache.upsertConversation(
            Conversation(
                chatId = "personal-chat",
                chatType = 1,
                chatName = "Cold snapshot",
                peerUid = peer.uid,
            ),
        )

        assertTrue(cache.upsertTransientUserIfRelevant(peer))
        assertEquals(peer, cache.getUser(peer.uid))
        cache.close()
    }

    @Test
    fun `chat member relation admits and hydrates a transient user`() {
        val cache = newMemoryCache()
        val member = Member(uid = "member", chatId = "group", role = 0)
        val updated = user(member.uid, "Fresh Member")
        cache.upsertMember(member)

        assertTrue(cache.upsertTransientUserIfRelevant(updated))
        assertEquals(updated, cache.getMembers(member.chatId).single().user)
        cache.close()
    }

    @Test
    fun `stale contact and member users cannot replace a newer normalized revision`() {
        val cache = newMemoryCache()
        val revisionOne = user("peer", "A").copy(revision = 1L)
        val revisionTwo = revisionOne.copy(name = "B", revision = 2L)
        val contact = Contact(uid = "me", friendUid = revisionOne.uid, user = revisionOne)
        cache.upsertContact(contact)
        assertTrue(cache.upsertTransientUserIfRelevant(revisionTwo))

        cache.upsertUser(revisionOne)
        assertEquals(revisionTwo, cache.getUser(revisionOne.uid))
        cache.upsertContact(contact)
        assertEquals(revisionTwo, cache.getContacts().single().user)

        val member = Member(uid = revisionOne.uid, chatId = "group", role = 0, user = revisionOne)
        cache.beginMemberSnapshot(member.chatId).also { lease ->
            assertTrue(cache.applyMemberSnapshot(lease, listOf(member.copy(user = revisionTwo))))
        }
        cache.beginMemberSnapshot(member.chatId).also { lease ->
            assertTrue(cache.applyMemberSnapshot(lease, listOf(member)))
        }
        assertEquals(revisionTwo, cache.getMembers(member.chatId).single().user)
        assertEquals(revisionTwo, cache.getUser(revisionOne.uid))
        cache.close()
    }

    private fun newMemoryCache(): LocalCacheImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return LocalCacheImpl(driver)
    }

    private fun user(uid: String, name: String) = User(uid = uid, username = uid, name = name)

    private fun personalConversation(chatId: String, peer: User) = Conversation(
        chatId = chatId,
        chatType = 1,
        peerUid = peer.uid,
        peerRevision = peer.revision,
        chatName = peer.name,
    )
}

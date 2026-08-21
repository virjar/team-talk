package com.virjar.tk.domain.chat

import com.virjar.tk.model.Chat
import com.virjar.tk.model.Member
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatStoreCacheConcurrencyTest {
    @Test
    fun `committed deactivation cannot be followed by an old chat load refilling cache`() {
        val repo = BlockingChatRepository()
        val store = ChatStore(repo, PassiveMemberRepository(), PassiveInviteRepository())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<Chat?> { store.getChat(CHAT_ID) }
            repo.loadStarted.await()
            val writerAttempted = CountDownLatch(1)
            repo.commitDeactivation()
            val invalidate = pool.submit {
                writerAttempted.countDown()
                store.invalidateCommittedDeactivation(CHAT_ID)
            }
            writerAttempted.await()

            // The database commit already happened; only cache publication is serialized behind
            // the old load, and must invalidate the snapshot after that load returns.
            assertFalse(invalidate.isDone)
            repo.releaseLoad.countDown()

            assertEquals(CHAT_ID, read.get(1, TimeUnit.SECONDS)?.chatId)
            invalidate.get(1, TimeUnit.SECONDS)
            assertNull(store.getChat(CHAT_ID))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `member removal cannot be undone by an old member snapshot`() {
        val repo = ImmediateChatRepository()
        val members = BlockingMemberRepository()
        val store = ChatStore(repo, members, PassiveInviteRepository())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<List<Member>> { store.getMembers(CHAT_ID) }
            members.loadStarted.await()
            val writerAttempted = CountDownLatch(1)
            members.commitRemoval()
            val invalidate = pool.submit {
                writerAttempted.countDown()
                store.invalidateCommittedMembershipChange(CHAT_ID)
            }
            writerAttempted.await()

            assertFalse(invalidate.isDone)
            members.releaseLoad.countDown()

            assertEquals(listOf(MEMBER_UID), read.get(1, TimeUnit.SECONDS).map(Member::uid))
            invalidate.get(1, TimeUnit.SECONDS)
            assertFalse(store.isMember(CHAT_ID, MEMBER_UID))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `member mute is reevaluated after its expiry`() {
        val clock = AtomicLong(100)
        val members = ExpiringMuteMemberRepository(clock, expiresAt = 101)
        val store = ChatStore(ImmediateChatRepository(), members, PassiveInviteRepository())

        assertEquals(true, store.isMuted(CHAT_ID, MEMBER_UID))
        clock.set(102)
        assertFalse(store.isMuted(CHAT_ID, MEMBER_UID))
    }

    private companion object {
        const val CHAT_ID = "chat-cache-race"
        const val MEMBER_UID = "member"
    }
}

private open class ImmediateChatRepository : ChatRepository {
    @Volatile
    protected var active = true

    override fun getChat(chatId: String): Chat? =
        if (active && chatId == "chat-cache-race") Chat(chatId, chatType = 2) else null

    fun commitDeactivation() {
        active = false
    }

    override fun getMemberUids(chatId: String): List<String> = emptyList()
    override fun listUserChats(uid: String): List<Chat> = emptyList()
    override fun joinByInvite(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        uid: String,
        token: String,
        nowMillis: Long,
    ): InviteJoinResult = error("unused")
    override fun findPersonalChatId(uid1: String, uid2: String): String? = null
    override fun getChatById(chatId: String): Chat? = getChat(chatId)
    override fun listGroups(query: String?, page: Int, size: Int): AdminPage<Chat> = AdminPage(0, emptyList())
    override fun countGroups(): Long = 0
    override fun countEventsSince(since: Long): Long = 0
}

private class BlockingChatRepository : ImmediateChatRepository() {
    val loadStarted = CountDownLatch(1)
    val releaseLoad = CountDownLatch(1)
    private val blockFirstLoad = AtomicBoolean(true)

    override fun getChat(chatId: String): Chat? {
        val snapshot = super.getChat(chatId)
        if (blockFirstLoad.compareAndSet(true, false)) {
            loadStarted.countDown()
            releaseLoad.await()
        }
        return snapshot
    }
}

private open class PassiveMemberRepository : ChatMemberRepository {
    override fun getMembers(chatId: String): List<Member> = emptyList()
    override fun getMember(chatId: String, uid: String): Member? = null
    override fun getMemberUids(chatId: String): List<String> = emptyList()
    override fun isMember(chatId: String, uid: String): Boolean = false
    override fun addMembers(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        chatId: String,
        operatorUid: String,
        uids: List<String>,
        requiredHumanUids: Set<String>,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition = error("unused")
    override fun removeMember(
        transaction: com.virjar.tk.domain.transaction.PgTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval = error("unused")
    override fun isMuted(chatId: String, uid: String): Boolean = false
    override fun getMutedMembers(chatId: String): List<String> = emptyList()
}

private class BlockingMemberRepository : PassiveMemberRepository() {
    val loadStarted = CountDownLatch(1)
    val releaseLoad = CountDownLatch(1)
    private val blockFirstLoad = AtomicBoolean(true)
    @Volatile
    private var active = true

    override fun getMembers(chatId: String): List<Member> {
        val snapshot = if (active) listOf(Member("member", chatId, role = 1)) else emptyList()
        if (blockFirstLoad.compareAndSet(true, false)) {
            loadStarted.countDown()
            releaseLoad.await()
        }
        return snapshot
    }

    fun commitRemoval() {
        active = false
    }
}

private class ExpiringMuteMemberRepository(
    private val clock: AtomicLong,
    private val expiresAt: Long,
) : PassiveMemberRepository() {
    override fun isMuted(chatId: String, uid: String): Boolean = clock.get() < expiresAt
}

private class PassiveInviteRepository : InviteLinkRepository {
    override fun listInviteLinks(chatId: String): List<InviteLinkRecord> = emptyList()
    override fun getInviteLink(token: String): InviteLinkRecord? = null
}

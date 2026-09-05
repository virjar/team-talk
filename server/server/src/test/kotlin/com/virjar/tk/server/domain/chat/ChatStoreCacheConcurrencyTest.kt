package com.virjar.tk.server.domain.chat

import com.virjar.tk.protocol.model.Chat
import com.virjar.tk.protocol.model.Member
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

            // 数据库提交已经发生；只有缓存发布被排在旧加载之后串行执行，
            // 并且必须在该加载返回之后使快照失效。
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

    @Test
    fun `cache retains a bounded aggregate snapshot instead of every accessed chat`() {
        val repo = CountingChatRepository()
        val store = ChatStore(
            repo = repo,
            memberRepo = PassiveMemberRepository(),
            inviteRepo = PassiveInviteRepository(),
            cacheStripeCount = 1,
            cacheEntriesPerStripe = 2,
        )

        assertEquals("chat-1", store.getChat("chat-1")?.chatId)
        assertEquals("chat-2", store.getChat("chat-2")?.chatId)
        assertEquals("chat-1", store.getChat("chat-1")?.chatId)
        assertEquals("chat-3", store.getChat("chat-3")?.chatId)

        assertEquals(2, store.cachedChatCountForTest())
        assertEquals(1, repo.loadCount("chat-1"), "recent access must refresh LRU order")
        assertEquals("chat-2", store.getChat("chat-2")?.chatId)
        assertEquals(2, repo.loadCount("chat-2"), "eldest entry must reload after eviction")
        assertEquals(2, store.cachedChatCountForTest())
    }

    @Test
    fun `oversized member list is returned but never retained by the hot cache`() {
        val members = CountingLargeMemberRepository(memberCount = 3)
        val store = ChatStore(
            repo = ImmediateChatRepository(),
            memberRepo = members,
            inviteRepo = PassiveInviteRepository(),
            cacheStripeCount = 1,
            cacheEntriesPerStripe = 1,
            maxCachedMemberRolesPerChat = 2,
        )

        assertEquals(3, store.getMembers(CHAT_ID).size)
        assertEquals(0, store.cachedMemberRoleCountForTest())

        assertEquals("member-2", store.getMember(CHAT_ID, "member-2")?.uid)
        assertEquals(1, members.memberListLoads, "point lookup must not reload the oversized list")
        assertEquals(1, members.memberPointLoads)
        assertEquals(0, store.cachedMemberRoleCountForTest())
    }

    private companion object {
        const val CHAT_ID = "chat-cache-race"
        const val MEMBER_UID = "member"
    }
}

private class CountingChatRepository : ImmediateChatRepository() {
    private val loads = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    override fun getChat(chatId: String): Chat {
        loads.computeIfAbsent(chatId) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        return Chat(chatId, chatType = 2)
    }

    fun loadCount(chatId: String): Int = loads[chatId]?.get() ?: 0
}

private open class ImmediateChatRepository : ChatRepository {
    @Volatile
    protected var active = true

    override fun getChat(chatId: String): Chat? =
        if (active && chatId == "chat-cache-race") Chat(chatId, chatType = 2) else null

    override fun getOrCreateSavedChat(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        uid: String,
    ): com.virjar.tk.server.domain.chat.ChatCreation = throw UnsupportedOperationException("saved chat is out of scope here")

    fun commitDeactivation() {
        active = false
    }

    override fun createPersonalChat(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        uid1: String,
        uid2: String,
    ): ChatCreation = error("unused")

    override fun createGroupChat(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        command: GroupCreationCommand,
    ): ChatCreation = error("unused")

    override fun getMemberUids(chatId: String): List<String> = emptyList()
    override fun listUserChats(uid: String): List<Chat> = emptyList()
    override fun joinByInvite(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        uid: String,
        token: String,
        nowMillis: Long,
    ): InviteJoinResult = error("unused")
    override fun updateGroup(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        name: String?,
        avatar: String?,
        notice: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("unused")
    override fun lockForDeactivation(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        operatorUid: String?,
        authorize: (GroupCommandFacts) -> Unit,
    ): Chat = error("unused")
    override fun deactivateChat(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
    ): ChatDeactivation = error("unused")
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
    override fun getActiveChatIds(uid: String): Set<String> = emptySet()
    override fun getActiveChatIds(
        transaction: com.virjar.tk.server.domain.transaction.PgReadTransactionContext,
        uid: String,
    ): Set<String> = emptySet()
    override fun getProjectedChatIds(uid: String): Set<String> = emptySet()
    override fun getProjectedChatIds(
        transaction: com.virjar.tk.server.domain.transaction.PgReadTransactionContext,
        uid: String,
    ): Set<String> = emptySet()
    override fun isMember(chatId: String, uid: String): Boolean = false
    override fun getActiveMemberUids(
        transaction: com.virjar.tk.server.domain.transaction.PgReadTransactionContext,
        chatId: String,
    ): List<String> = emptyList()
    override fun lockChats(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatIds: Collection<String>,
        requireActive: Boolean,
    ): Map<String, LockedChat> = error("unused")
    override fun getActiveMember(
        transaction: com.virjar.tk.server.domain.transaction.PgReadTransactionContext,
        chatId: String,
        uid: String,
    ): Member? = null
    override fun admitMessage(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        senderUid: String,
        nowMillis: Long,
        afterChatLocked: () -> Unit,
        authorize: (MessageAdmissionFacts) -> Unit,
    ): MessageAdmission = error("unused")
    override fun addMembers(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        uids: List<String>,
        requiredHumanUids: Set<String>,
        authorize: (GroupMemberAdditionFacts) -> Unit,
    ): GroupMemberAddition = error("unused")
    override fun removeMember(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval = error("unused")
    override fun removeMemberIfPresent(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        requireActiveChat: Boolean,
        authorize: (GroupMemberRemovalFacts) -> Unit,
    ): GroupMemberRemoval? = error("unused")
    override fun cleanupServiceMemberProjection(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        uid: String,
        lockedChat: LockedChat?,
    ): ServiceMemberProjectionCleanup? = error("unused")
    override fun transferOwner(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        oldOwnerUid: String,
        newOwnerUid: String,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("unused")
    override fun setRole(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        role: Int,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("unused")
    override fun setMemberMute(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        operatorUid: String,
        targetUid: String,
        expiresAt: Long?,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("unused")
    override fun isMuted(chatId: String, uid: String): Boolean = false
    override fun setMuteAll(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        chatId: String,
        operatorUid: String?,
        mutedAll: Boolean,
        authorize: (GroupCommandFacts) -> Unit,
    ): ChatMutation = error("unused")
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

private class CountingLargeMemberRepository(
    private val memberCount: Int,
) : PassiveMemberRepository() {
    var memberListLoads = 0
        private set
    var memberPointLoads = 0
        private set

    override fun getMembers(chatId: String): List<Member> {
        memberListLoads += 1
        return List(memberCount) { index -> Member("member-$index", chatId, role = 0) }
    }

    override fun getMember(chatId: String, uid: String): Member? {
        memberPointLoads += 1
        val index = uid.removePrefix("member-").toIntOrNull() ?: return null
        return if (index in 0 until memberCount) Member(uid, chatId, role = 0) else null
    }
}

private class PassiveInviteRepository : InviteLinkRepository {
    override fun createInviteLink(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        command: InviteLinkCreationCommand,
        authorize: (GroupCommandFacts) -> Unit,
    ): String = error("unused")
    override fun listInviteLinks(chatId: String): List<InviteLinkRecord> = emptyList()
    override fun revokeInviteLink(
        transaction: com.virjar.tk.server.domain.transaction.PgWriteTransactionContext,
        expectedChatId: String,
        operatorUid: String,
        token: String,
        nowMillis: Long,
        authorize: (GroupCommandFacts) -> Unit,
    ): InviteLinkRecord = error("unused")
    override fun getInviteLink(token: String): InviteLinkRecord? = null
}

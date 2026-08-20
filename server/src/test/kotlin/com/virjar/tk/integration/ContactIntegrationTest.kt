package com.virjar.tk.integration

import com.virjar.tk.infra.db.FriendApplies
import com.virjar.tk.model.ContactApplyRecord
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactIntegrationTest {

    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `apply and accept friend request`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val apply = ctx.contactService(uid1).apply(uid2, "Hi")
        assertNotNull(apply)
        assertEquals(0, apply.status)
        assertNull(apply.token, "apply 响应不能向发件人暴露收件人处理 token")
        val accepted = ctx.contactService(uid2).accept(pendingToken(uid2, uid1))
        assertEquals(1, accepted.status)
    }

    @Test
    fun `reject friend request`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid1).apply(uid2, "Hi")
        val rejected = ctx.contactService(uid2).reject(pendingToken(uid2, uid1))
        assertEquals(2, rejected.status)
    }

    @Test
    fun `only the request recipient can accept or reject`() = runTest {
        val sender = ctx.registerUser()
        val recipient = ctx.registerUser()
        val outsider = ctx.registerUser()
        ctx.contactService(sender).apply(recipient, null)
        val token = pendingToken(recipient, sender)

        assertFailsWith<IllegalArgumentException> {
            ctx.contactService(outsider).accept(token)
        }
        val accepted = ctx.contactService(recipient).accept(token)
        assertEquals(1, accepted.status)
    }

    @Test
    fun `list friends after accept`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(pendingToken(uid2, uid1))
        val friends = ctx.contactService(uid1).list()
        assertTrue(friends.any { it.friendUid == uid2 })
        val friendsOf2 = ctx.contactService(uid2).list()
        assertTrue(friendsOf2.any { it.friendUid == uid1 })
    }

    @Test
    fun `delete friend`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(pendingToken(uid2, uid1))
        ctx.contactService(uid1).delete(uid2)
        val friends = ctx.contactService(uid1).list()
        assertTrue(friends.none { it.friendUid == uid2 })
    }

    @Test
    fun `set friend remark`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(pendingToken(uid2, uid1))
        ctx.contactService(uid1).setRemark(uid2, "Buddy")
        val friends = ctx.contactService(uid1).list()
        val friend = friends.first { it.friendUid == uid2 }
        assertEquals("Buddy", friend.remark)
    }

    @Test
    fun `blacklist user`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid1).blacklist(uid2)
        val blacklist = ctx.contactService(uid1).listBlacklist()
        assertTrue(blacklist.any { it.friendUid == uid2 })
    }

    @Test
    fun `blacklist terminates friendship and blocks new relationship`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(pendingToken(uid2, uid1))

        ctx.contactService(uid1).blacklist(uid2)

        assertTrue(ctx.contactService(uid1).list().none { it.friendUid == uid2 })
        assertTrue(ctx.contactService(uid2).list().none { it.friendUid == uid1 })
        assertFailsWith<IllegalArgumentException> { ctx.contactService(uid2).apply(uid1, null) }
        assertFailsWith<IllegalArgumentException> { ctx.chatService.createPersonalChat(uid1, uid2) }
    }

    @Test
    fun `remove from blacklist`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid1).blacklist(uid2)
        ctx.contactService(uid1).removeFromBlacklist(uid2)
        val blacklist = ctx.contactService(uid1).listBlacklist()
        assertTrue(blacklist.none { it.friendUid == uid2 })
    }

    @Test
    fun `accept restores friendship rows after blacklist is removed`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(pendingToken(uid2, uid1))

        ctx.contactService(uid1).blacklist(uid2)
        ctx.contactService(uid1).removeFromBlacklist(uid2)

        ctx.contactService(uid2).apply(uid1, "again")
        ctx.contactService(uid1).accept(pendingToken(uid1, uid2))

        assertTrue(ctx.contactService(uid1).list().any { it.friendUid == uid2 })
        assertTrue(ctx.contactService(uid2).list().any { it.friendUid == uid1 })
    }

    @Test
    fun `mutual blacklist keeps both independent blocks`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid2).blacklist(uid1)
        ctx.contactService(uid1).blacklist(uid2)

        assertTrue(ctx.contactService(uid1).listBlacklist().any { it.friendUid == uid2 })
        assertTrue(ctx.contactService(uid2).listBlacklist().any { it.friendUid == uid1 })
    }

    @Test
    fun `list pending applies`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        ctx.contactService(uid1).apply(uid2, "Hello")
        val pending = ctx.contactService(uid2).listApplies()
        assertTrue(pending.any { it.fromUid == uid1 })
    }

    @Test
    fun `records distinguish incoming outgoing and redact recipient token`() = runTest {
        val sender1 = ctx.registerUser(uniqueUsername("record-sender-1"))
        val sender2 = ctx.registerUser(uniqueUsername("record-sender-2"))
        val recipient = ctx.registerUser(uniqueUsername("record-recipient"))

        val sent = ctx.contactService(sender1).apply(recipient, "first")
        assertNull(sent.token)
        val incomingToken = pendingToken(recipient, sender1)

        val senderPending = ctx.contactService(sender1).getPendingApply(recipient).record!!
        assertEquals(ContactApplyRecord.DIRECTION_OUTGOING, senderPending.direction)
        assertEquals(recipient, senderPending.peerUser?.uid)
        assertNull(senderPending.token)

        val recipientPending = ctx.contactService(recipient).getPendingApply(sender1).record!!
        assertEquals(ContactApplyRecord.DIRECTION_INCOMING, recipientPending.direction)
        assertEquals(sender1, recipientPending.peerUser?.uid)
        assertEquals(incomingToken, recipientPending.token)

        ctx.contactService(recipient).reject(incomingToken)
        ctx.contactService(sender2).apply(recipient, "second")

        val newest = ctx.contactService(recipient).listApplyRecords(0, 1).single()
        assertEquals(sender2, newest.fromUid)
        assertEquals(ContactApplyRecord.STATUS_PENDING, newest.status)
        assertNotNull(newest.token)

        val older = ctx.contactService(recipient).listApplyRecords(newest.id, 10)
            .single { it.fromUid == sender1 }
        assertEquals(ContactApplyRecord.STATUS_REJECTED, older.status)
        assertNull(older.token)

        val senderHistory = ctx.contactService(sender1).listApplyRecords(0, 10).single()
        assertEquals(ContactApplyRecord.DIRECTION_OUTGOING, senderHistory.direction)
        assertEquals(ContactApplyRecord.STATUS_REJECTED, senderHistory.status)
        assertNull(senderHistory.token)

        ctx.contactService(recipient).accept(pendingToken(recipient, sender2))
        assertNull(ctx.contactService(recipient).getPendingApply(sender2).record)
        assertNull(ctx.contactService(sender2).getPendingApply(recipient).record)
    }

    @Test
    fun `same direction concurrent apply reuses one row and emits one event`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("dedupe-sender"))
        val recipient = ctx.registerUser(uniqueUsername("dedupe-recipient"))
        val beforeEvents = ctx.syncEventReader.getEventsAfter(recipient, 0, 1_000)
            .count { it.notifyType == NotifyType.CONTACT_APPLY.code }

        val results = coroutineScope {
            List(8) {
                async(Dispatchers.Default) {
                    ctx.contactService(sender).apply(recipient, "一次即可")
                }
            }.awaitAll()
        }

        assertEquals(1, results.map { it.id }.toSet().size)
        assertTrue(results.all { it.token == null })
        assertEquals(1, ctx.contactService(recipient).listApplies().count { it.fromUid == sender })
        val afterEvents = ctx.syncEventReader.getEventsAfter(recipient, 0, 1_000)
            .count { it.notifyType == NotifyType.CONTACT_APPLY.code }
        assertEquals(beforeEvents + 1, afterEvents)
        assertFailsWith<IllegalArgumentException> {
            ctx.contactService(recipient).apply(sender, "反向重复")
        }
        assertEquals(ContactApplyRecord.DIRECTION_INCOMING, ctx.contactService(recipient)
            .getPendingApply(sender).record?.direction)
    }

    @Test
    fun `accept racing reverse apply cannot leave a pending request after friendship`() = runTest {
        // 多设备可能在一端处理收到的申请时，另一端仍按旧资料状态发起反向申请。
        // 最终允许的线性化结果只有“接受成功、反向申请失败”，不能同时留下 friend + pending。
        repeat(8) { index ->
            val sender = ctx.registerUser(uniqueUsername("accept-race-sender-$index"))
            val recipient = ctx.registerUser(uniqueUsername("accept-race-recipient-$index"))
            ctx.contactService(sender).apply(recipient, "race")
            val token = pendingToken(recipient, sender)

            val (acceptResult, reverseApplyResult) = coroutineScope {
                val accept = async(Dispatchers.Default) {
                    runCatching { ctx.contactService(recipient).accept(token) }
                }
                val reverseApply = async(Dispatchers.Default) {
                    runCatching { ctx.contactService(recipient).apply(sender, "reverse") }
                }
                accept.await() to reverseApply.await()
            }

            assertTrue(acceptResult.isSuccess)
            assertTrue(reverseApplyResult.isFailure)
            assertTrue(ctx.contactService(sender).list().any { it.friendUid == recipient })
            assertTrue(ctx.contactService(recipient).list().any { it.friendUid == sender })
            assertNull(ctx.contactService(sender).getPendingApply(recipient).record)
            assertNull(ctx.contactService(recipient).getPendingApply(sender).record)
            assertTrue(ctx.contactService(sender).listApplies().none { it.fromUid == recipient })
            assertTrue(ctx.contactService(recipient).listApplies().none { it.fromUid == sender })
        }
    }

    @Test
    fun `blacklist closes an existing pending request and prevents it from reviving`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("block-pending-sender"))
        val recipient = ctx.registerUser(uniqueUsername("block-pending-recipient"))
        val first = ctx.contactService(sender).apply(recipient, "before block")

        ctx.contactService(recipient).blacklist(sender)

        assertTrue(ctx.contactService(recipient).listApplies().none { it.fromUid == sender })
        assertNull(ctx.contactService(sender).getPendingApply(recipient).record)
        assertNull(ctx.contactService(recipient).getPendingApply(sender).record)
        val closed = ctx.contactService(sender).listApplyRecords(0, 10).single { it.id == first.id }
        assertEquals(ContactApplyRecord.STATUS_REJECTED, closed.status)
        assertNull(closed.token)
        assertFailsWith<IllegalArgumentException> {
            ctx.contactService(sender).apply(recipient, "while blocked")
        }

        ctx.contactService(recipient).removeFromBlacklist(sender)
        val second = ctx.contactService(sender).apply(recipient, "after unblock")
        assertTrue(second.id != first.id)
        assertEquals(ContactApplyRecord.STATUS_PENDING, second.status)
    }

    @Test
    fun `apply rejects missing and service identities while pending lookup is empty for service profile`() = runTest {
        val sender = ctx.registerUser(uniqueUsername("human-sender"))
        val bot = ctx.userService.createServiceAccount("不可加好友机器人")

        assertFailsWith<IllegalArgumentException> {
            ctx.contactService(sender).apply("missing-user", null)
        }
        assertFailsWith<IllegalArgumentException> {
            ctx.contactService(sender).apply(bot.uid, null)
        }
        assertNull(ctx.contactService(sender).getPendingApply(bot.uid).record)
    }

    @Test
    fun `legacy pending inbox is bounded to newest one hundred rows`() = runTest {
        val recipient = ctx.registerUser(uniqueUsername("bounded-recipient"))
        transaction {
            repeat(101) { index ->
                FriendApplies.insert {
                    it[FriendApplies.fromUid] = "legacy-sender-$index"
                    it[FriendApplies.toUid] = recipient
                    it[FriendApplies.token] = UUID.randomUUID().toString()
                    it[FriendApplies.remark] = null
                    it[FriendApplies.status] = ContactApplyRecord.STATUS_PENDING
                    it[FriendApplies.createdAt] = index.toLong()
                    it[FriendApplies.updatedAt] = index.toLong()
                }
            }
        }

        val pending = ctx.contactService(recipient).listApplies()
        assertEquals(100, pending.size)
        assertEquals("legacy-sender-100", pending.first().fromUid)
        assertTrue(pending.none { it.fromUid == "legacy-sender-0" })
    }

    private suspend fun pendingToken(recipientUid: String, senderUid: String): String =
        ctx.contactService(recipientUid).listApplies()
            .single { it.fromUid == senderUid }
            .token!!
}

package com.virjar.tk.integration

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
        val accepted = ctx.contactService(uid2).accept(apply.token!!)
        assertEquals(1, accepted.status)
    }

    @Test
    fun `reject friend request`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val apply = ctx.contactService(uid1).apply(uid2, "Hi")
        val rejected = ctx.contactService(uid2).reject(apply.token!!)
        assertEquals(2, rejected.status)
    }

    @Test
    fun `only the request recipient can accept or reject`() = runTest {
        val sender = ctx.registerUser()
        val recipient = ctx.registerUser()
        val outsider = ctx.registerUser()
        val apply = ctx.contactService(sender).apply(recipient, null)

        assertFailsWith<IllegalArgumentException> {
            ctx.contactService(outsider).accept(apply.token!!)
        }
        val accepted = ctx.contactService(recipient).accept(apply.token!!)
        assertEquals(1, accepted.status)
    }

    @Test
    fun `list friends after accept`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val apply = ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(apply.token!!)
        val friends = ctx.contactService(uid1).list()
        assertTrue(friends.any { it.friendUid == uid2 })
        val friendsOf2 = ctx.contactService(uid2).list()
        assertTrue(friendsOf2.any { it.friendUid == uid1 })
    }

    @Test
    fun `delete friend`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val apply = ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(apply.token!!)
        ctx.contactService(uid1).delete(uid2)
        val friends = ctx.contactService(uid1).list()
        assertTrue(friends.none { it.friendUid == uid2 })
    }

    @Test
    fun `set friend remark`() = runTest {
        val uid1 = ctx.registerUser()
        val uid2 = ctx.registerUser()
        val apply = ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(apply.token!!)
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
        val apply = ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(apply.token!!)

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
        val initial = ctx.contactService(uid1).apply(uid2, null)
        ctx.contactService(uid2).accept(initial.token!!)

        ctx.contactService(uid1).blacklist(uid2)
        ctx.contactService(uid1).removeFromBlacklist(uid2)

        val reapplied = ctx.contactService(uid2).apply(uid1, "again")
        ctx.contactService(uid1).accept(reapplied.token!!)

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
}

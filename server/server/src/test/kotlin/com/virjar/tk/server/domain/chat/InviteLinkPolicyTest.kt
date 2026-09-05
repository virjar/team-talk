package com.virjar.tk.server.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class InviteLinkPolicyTest {
    @Test
    fun `canonical personal key is independent of caller order`() {
        assertEquals("5:alice3:bob", personalChatKey("alice", "bob"))
        assertEquals("5:alice3:bob", personalChatKey("bob", "alice"))
        assertEquals(personalChatKey("a:b", "c"), personalChatKey("c", "a:b"))
        assertNotEquals(personalChatKey("a:b", "c"), personalChatKey("a", "b:c"))
    }

    @Test
    fun `active invite accepts expiry boundary and unlimited quota`() {
        invite(maxUses = 0, useCount = 999, expiresAt = 100).requireJoinable(nowMillis = 100)
    }

    @Test
    fun `revoked exhausted and expired invites are rejected deterministically`() {
        assertEquals(
            "邀请链接已失效",
            assertFailsWith<IllegalArgumentException> {
                invite(revokedAt = 1).requireJoinable(nowMillis = 10)
            }.message,
        )
        assertEquals(
            "邀请链接已用完",
            assertFailsWith<IllegalArgumentException> {
                invite(maxUses = 2, useCount = 2).requireJoinable(nowMillis = 10)
            }.message,
        )
        assertEquals(
            "邀请链接已过期",
            assertFailsWith<IllegalArgumentException> {
                invite(expiresAt = 9).requireJoinable(nowMillis = 10)
            }.message,
        )
    }

    @Test
    fun `negative limits are not unlimited sentinels`() {
        assertFailsWith<IllegalArgumentException> {
            invite(maxUses = -1).requireJoinable(nowMillis = 10)
        }
        assertFailsWith<IllegalArgumentException> {
            invite(expiresAt = -1).requireJoinable(nowMillis = 10)
        }
    }

    private fun invite(
        maxUses: Int = 10,
        useCount: Int = 0,
        expiresAt: Long = 0,
        revokedAt: Long = 0,
    ) = InviteLinkRecord(
        token = "token",
        chatId = "chat",
        creatorUid = "owner",
        name = "invite",
        maxUses = maxUses,
        useCount = useCount,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
        createdAt = 1,
    )
}

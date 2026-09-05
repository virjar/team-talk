package com.virjar.tk.protocol.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GroupPolicyTest {
    @Test
    fun `raw input and uid validation fail with fixed non-identifying reasons`() {
        val oversized = List(GroupPolicy.MAX_MEMBER_UIDS_PER_COMMAND + 1) { "duplicate" }
        assertEquals(
            GroupPolicy.INPUT_LIMIT_REASON,
            assertFailsWith<IllegalArgumentException> {
                GroupPolicy.canonicalTargetMemberUids(oversized)
            }.message,
        )
        listOf(
            "",
            "contains space",
            "line\nbreak",
            "c1\u0085control",
            "x".repeat(GroupPolicy.MAX_MEMBER_UID_LENGTH + 1),
        ).forEach {
            assertEquals(
                GroupPolicy.INVALID_MEMBER_REASON,
                assertFailsWith<IllegalArgumentException> {
                    GroupPolicy.canonicalTargetMemberUids(listOf(it))
                }.message,
            )
        }
        assertEquals(
            GroupPolicy.INVALID_MEMBER_REASON,
            assertFailsWith<IllegalArgumentException> {
                GroupPolicy.canonicalTargetMemberUids(emptyList())
            }.message,
        )
    }
}

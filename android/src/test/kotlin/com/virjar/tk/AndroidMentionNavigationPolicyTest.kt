package com.virjar.tk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidMentionNavigationPolicyTest {

    @Test
    fun `allows current and legacy uid shapes`() {
        assertEquals("user_profile/dQ3KUFf7", safeMentionProfileRouteOrNull("dQ3KUFf7"))
        assertEquals(
            "user_profile/550e8400-e29b-41d4-a716-446655440000",
            safeMentionProfileRouteOrNull("550e8400-e29b-41d4-a716-446655440000"),
        )
        assertEquals("user_profile/zd-uidesign2_a93c0d16", safeMentionProfileRouteOrNull("zd-uidesign2_a93c0d16"))
    }

    @Test
    fun `rejects path navigation and control characters`() {
        listOf(
            "",
            "foo/bar",
            "foo\\bar",
            ".",
            "..",
            "foo?tab=devices",
            "foo#devices",
            "foo%2Fbar",
            "foo bar",
            "foo\nbar",
            "用户",
            "a".repeat(37),
        ).forEach { uid ->
            assertNull(safeMentionProfileRouteOrNull(uid), "should reject uid=${uid.replace("\n", "\\n")}")
        }
    }
}

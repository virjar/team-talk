package com.virjar.tk.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthenticatedSessionRetirementTest {
    @Test
    fun `platform hooks bracket local session retirement`() {
        val calls = mutableListOf<String>()

        withAuthenticatedSessionRetirementBoundary(
            before = { calls += "before" },
            retirement = { calls += "session" },
            after = { calls += "after" },
        )

        assertEquals(listOf("before", "session", "after"), calls)
    }

    @Test
    fun `after hook runs when local retirement throws`() {
        val calls = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            withAuthenticatedSessionRetirementBoundary(
                before = { calls += "before" },
                retirement = {
                    calls += "session"
                    error("session failed")
                },
                after = { calls += "after" },
            )
        }

        assertEquals(listOf("before", "session", "after"), calls)
    }

    @Test
    fun `hook failures are diagnostic and cannot skip session retirement`() {
        val calls = mutableListOf<String>()

        withAuthenticatedSessionRetirementBoundary(
            before = {
                calls += "before"
                error("before failed")
            },
            retirement = { calls += "session" },
            after = {
                calls += "after"
                error("after failed")
            },
            onHookFailure = { stage, _ -> calls += "$stage-failure" },
        )

        assertEquals(
            listOf("before", "before-failure", "session", "after", "after-failure"),
            calls,
        )
    }
}

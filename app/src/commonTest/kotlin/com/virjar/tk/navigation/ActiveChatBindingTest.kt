package com.virjar.tk.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveChatBindingTest {

    @Test
    fun `restored route prepares when session has no chat ViewModel`() {
        val binding = ActiveChatBinding()

        assertTrue(binding.needsPreparation("chat-a", hasViewModel = false))
        assertFalse(binding.matches("chat-a", hasViewModel = false))
    }

    @Test
    fun `matching route reuses live ViewModel idempotently`() {
        val binding = ActiveChatBinding()
        binding.markPrepared("chat-a")

        assertFalse(binding.needsPreparation("chat-a", hasViewModel = true))
        assertTrue(binding.matches("chat-a", hasViewModel = true))
    }

    @Test
    fun `route transition hides stale ViewModel until replacement is prepared`() {
        val binding = ActiveChatBinding()
        binding.markPrepared("chat-a")

        assertTrue(binding.needsPreparation("chat-b", hasViewModel = true))
        assertFalse(binding.matches("chat-b", hasViewModel = true))

        binding.markPrepared("chat-b")
        assertTrue(binding.matches("chat-b", hasViewModel = true))

        binding.clear()
        assertFalse(binding.matches("chat-b", hasViewModel = true))
    }
}

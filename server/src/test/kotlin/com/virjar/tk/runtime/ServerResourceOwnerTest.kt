package com.virjar.tk.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerResourceOwnerTest {
    @Test
    fun `resources close once in reverse acquisition order`() {
        val closed = mutableListOf<String>()
        val owner = ServerResourceOwner { _, error -> throw error }
        owner.own("database") { closed += "database" }
        owner.own("registry") { closed += "registry" }
        owner.own("tcp") { closed += "tcp" }

        owner.close()
        owner.close()

        assertEquals(listOf("tcp", "registry", "database"), closed)
    }

    @Test
    fun `one close failure does not strand older resources`() {
        val closed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val owner = ServerResourceOwner { name, _ -> failures += name }
        owner.own("database") { closed += "database" }
        owner.own("broken") {
            closed += "broken"
            error("close failed")
        }
        owner.own("tcp") { closed += "tcp" }

        owner.close()

        assertEquals(listOf("tcp", "broken", "database"), closed)
        assertEquals(listOf("broken"), failures)
    }

    @Test
    fun `closed owner rejects newly acquired resources`() {
        val owner = ServerResourceOwner { _, error -> throw error }
        owner.close()

        assertFailsWith<IllegalStateException> {
            owner.own("late") {}
        }
    }
}

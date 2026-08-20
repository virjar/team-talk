package com.virjar.tk.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `sync dispatcher is closed before the client registry it delivers through`() {
        val closed = mutableListOf<String>()
        var dispatcherClosed = false
        val owner = ServerResourceOwner { _, error -> throw error }
        // Application acquires these in dependency order; ServerResourceOwner closes in reverse.
        owner.own("client registry") {
            assertTrue(dispatcherClosed, "dispatcher must not retain work after registry shutdown")
            closed += "client registry"
        }
        owner.own("sync event dispatcher") {
            dispatcherClosed = true
            closed += "sync event dispatcher"
        }
        owner.own("tcp server") { closed += "tcp server" }

        owner.close()

        assertEquals(listOf("tcp server", "sync event dispatcher", "client registry"), closed)
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

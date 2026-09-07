package com.virjar.tk.protocol.rpc.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WireSchemaTest {
    private val history = WireSchemaEntry("rpc", "message/1", AvailabilityModel(0, null), "history(chatId:String):List<Message>")

    @Test
    fun `same major permits only registered additive signatures`() {
        val previous = WireSchema(0, 0, listOf(history))
        val added = history.copy(key = "message/2", availability = AvailabilityModel(1, null), signature = "search(text:String):List<Message>")
        val errors = mutableListOf<String>()
        val merged = mergeWireSchema(previous, WireSchema(0, 1, listOf(history, added)), 0, emptySet(), errors::add)
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(merged, WireSchema.parse(merged.text()))

        mergeWireSchema(previous, WireSchema(0, 1, listOf(history.copy(signature = "history(chatId:Long):List<Message>"))), 0, emptySet(), errors::add)
        assertTrue(errors.any { "Existing wire signature cannot change" in it })
        errors.clear()
        mergeWireSchema(previous, WireSchema(0, 1, listOf(history, added.copy(availability = AvailabilityModel(0, null)))), 0, emptySet(), errors::add)
        assertTrue(errors.any { "must declare @SinceProtocol" in it })
    }

    @Test
    fun `retirement requires a previously declared removal floor and an ID tombstone`() {
        val removed = history.copy(availability = AvailabilityModel(0, 2))
        val previous = WireSchema(0, 2, listOf(removed))
        val errors = mutableListOf<String>()
        mergeWireSchema(previous, WireSchema(0, 3, emptyList()), 1, setOf("message/1"), errors::add)
        assertTrue(errors.any { "cannot disappear" in it })

        errors.clear()
        val retired = mergeWireSchema(previous, WireSchema(0, 3, emptyList()), 2, setOf("message/1"), errors::add)
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(listOf(removed.copy(retired = true)), retired.entries)
        val later = mergeWireSchema(retired, WireSchema(0, 4, emptyList()), 2, setOf("message/1"), errors::add)
        assertEquals(retired.entries, later.entries)
        assertTrue(errors.isEmpty(), errors.toString())
        mergeWireSchema(retired, WireSchema(0, 4, listOf(history)), 2, emptySet(), errors::add)
        assertTrue(errors.any { "tombstone cannot be reused" in it })

        errors.clear()
        mergeWireSchema(previous, WireSchema(0, 3, emptyList()), 2, emptySet(), errors::add)
        assertTrue(errors.any { "must retain @RpcReservedMethodIds" in it })
    }

    @Test
    fun `new major explicitly permits a fresh identity space`() {
        val previous = WireSchema(0, 3, listOf(history.copy(retired = true)))
        val current = WireSchema(1, 0, listOf(history.copy(signature = "replacement():Unit")))
        val errors = mutableListOf<String>()
        assertEquals(current, mergeWireSchema(previous, current, 0, emptySet(), errors::add))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `development changes share one pending minor until the release freezes them`() {
        val published = WireSchema(0, 0, listOf(history))
        val added = history.copy(key = "message/2", availability = AvailabilityModel(1, null))
        val first = WireSchema(0, 1, listOf(history, added))
        val changed = first.copy(entries = listOf(history, added.copy(signature = "search(text:String):String")))
        val removed = first.copy(entries = listOf(history))
        for (current in listOf(first, changed, removed)) {
            val errors = mutableListOf<String>()
            assertEquals(current, reconcileWireSchema(first, published, current, 0, emptySet(), errors::add))
            assertTrue(errors.isEmpty(), errors.toString())
        }
        // After publication, the same modification is a compatibility violation.
        val errors = mutableListOf<String>()
        reconcileWireSchema(first, first, changed, 0, emptySet(), errors::add)
        assertTrue(errors.any { "Existing wire signature cannot change" in it })
    }

    @Test
    fun `temporary development counters can consolidate without changing the published contract`() {
        val published = WireSchema(0, 0, listOf(history))
        val experiment = history.copy(key = "message/2", availability = AvailabilityModel(4, null))
        val development = WireSchema(0, 4, listOf(history, experiment))
        val compacted = WireSchema(0, 1, listOf(history, experiment.copy(availability = AvailabilityModel(1, null))))
        val errors = mutableListOf<String>()
        assertEquals(compacted, reconcileWireSchema(development, published, compacted, 0, emptySet(), errors::add))
        assertTrue(errors.isEmpty(), errors.toString())
        // The generator still rejects an unregistered TSV mismatch until writeProtocolBaseline.
        assertTrue(compacted.text() != development.text())
    }

    @Test
    fun `rewriting the development baseline cannot hide a published signature change`() {
        val published = WireSchema(0, 0, listOf(history))
        val corrupted = WireSchema(0, 1, listOf(history.copy(signature = "history(chatId:Long):List<Message>")))
        val errors = mutableListOf<String>()
        reconcileWireSchema(corrupted, published, corrupted, 0, emptySet(), errors::add)
        assertTrue(errors.any { "Existing wire signature cannot change" in it }, errors.toString())
    }

    @Test
    fun `another development commit does not start another protocol version`() {
        val published = WireSchema(0, 0, listOf(history))
        val added = history.copy(key = "message/2", availability = AvailabilityModel(2, null))
        val errors = mutableListOf<String>()
        reconcileWireSchema(null, published, WireSchema(0, 2, listOf(history, added)), 0, emptySet(), errors::add)
        assertTrue(errors.any { "share pending minor=1" in it }, errors.toString())
    }
}

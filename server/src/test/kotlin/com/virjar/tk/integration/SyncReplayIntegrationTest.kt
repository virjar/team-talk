package com.virjar.tk.integration

import com.virjar.tk.domain.event.SyncBatchResult
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.model.User
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncReplayIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `more than one hundred events are drained through bounded acknowledged pages`() = runTest {
        val uid = "sync-pagination-user"
        val now = System.currentTimeMillis()
        transaction {
            repeat(150) { index ->
                SyncEvents.insert {
                    it[SyncEvents.uid] = uid
                    it[SyncEvents.eventType] = NotifyType.GENERIC.code
                    it[SyncEvents.payload] = byteArrayOf(index.toByte())
                    it[SyncEvents.createdAt] = now
                }
            }
        }

        var cursor = 0L
        val replayed = mutableListOf<Long>()
        var activated = false
        while (!activated) {
            when (val result = ctx.syncEventReader.nextBatchOrActivate(uid, cursor, 64) { true }) {
                is SyncBatchResult.Events -> {
                    assertTrue(result.events.size in 1..64)
                    assertTrue(result.events.zipWithNext().all { (left, right) -> left.eventId < right.eventId })
                    replayed += result.events.map { it.eventId }
                    // This cursor update represents the client ACK after the whole page is durable.
                    cursor = result.events.last().eventId
                }
                SyncBatchResult.Activated -> activated = true
                SyncBatchResult.ConnectionClosed -> error("test connection unexpectedly closed")
                SyncBatchResult.InvalidCursor -> error("server rejected its own acknowledged cursor")
            }
        }

        assertEquals(150, replayed.size)
        assertEquals(replayed.distinct(), replayed)
    }

    @Test
    fun `live persistence cannot cross the final empty check and activation barrier`() = runTest {
        val uid = "sync-race-user"
        val activationEntered = CompletableDeferred<Unit>()
        val allowActivation = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val activation = async {
            ctx.syncEventReader.nextBatchOrActivate(uid, 0L, 64) {
                order += "ready"
                activationEntered.complete(Unit)
                allowActivation.await()
                true
            }
        }
        activationEntered.await()

        val live = async {
            ctx.eventPublisher.emitEvent(
                uid,
                NotifyType.USER_UPDATED,
                User(uid = uid, username = "sync-user", name = "Sync User"),
            )
            order += "live"
        }
        yield()
        assertFalse(live.isCompleted, "live persist/push must wait behind final activation")

        allowActivation.complete(Unit)
        assertIs<SyncBatchResult.Activated>(activation.await())
        live.await()
        assertEquals(listOf("ready", "live"), order)

        val replay = ctx.syncEventReader.nextBatchOrActivate(uid, 0L, 64) { false }
        assertIs<SyncBatchResult.Events>(replay)
        assertEquals(1, replay.events.size)
    }

    @Test
    fun `development baseline keeps old sync events until full resync protocol exists`() = runTest {
        val uid = "sync-retention-user"
        transaction {
            SyncEvents.insert {
                it[SyncEvents.uid] = uid
                it[SyncEvents.eventType] = NotifyType.GENERIC.code
                it[SyncEvents.payload] = byteArrayOf(7)
                it[SyncEvents.createdAt] = 0L
            }
        }
        val service = assertIs<SyncEventService>(ctx.syncEventReader)

        assertEquals(0, service.cleanupExpiredEvents(), "cleanup must remain an explicit no-op")
        val replay = service.getEventsAfter(uid, 0L, 64)

        assertEquals(1, replay.size)
        assertEquals(byteArrayOf(7).toList(), replay.single().payload?.toList())
    }

    @Test
    fun `cursor must be zero or a durable event owned by the authenticated user`() = runTest {
        val ownerUid = "sync-cursor-owner"
        val otherUid = "sync-cursor-other"
        val ownedId = transaction {
            SyncEvents.insert {
                it[SyncEvents.uid] = ownerUid
                it[SyncEvents.eventType] = NotifyType.GENERIC.code
                it[SyncEvents.payload] = byteArrayOf(1)
                it[SyncEvents.createdAt] = System.currentTimeMillis()
            } get SyncEvents.id
        }.value
        val otherId = transaction {
            SyncEvents.insert {
                it[SyncEvents.uid] = otherUid
                it[SyncEvents.eventType] = NotifyType.GENERIC.code
                it[SyncEvents.payload] = byteArrayOf(2)
                it[SyncEvents.createdAt] = System.currentTimeMillis()
            } get SyncEvents.id
        }.value

        assertIs<SyncBatchResult.Activated>(
            ctx.syncEventReader.nextBatchOrActivate(ownerUid, ownedId, 64) { true },
        )
        var invalidCursorActivated = false
        assertIs<SyncBatchResult.InvalidCursor>(
            ctx.syncEventReader.nextBatchOrActivate(ownerUid, otherId, 64) {
                invalidCursorActivated = true
                true
            },
        )
        assertIs<SyncBatchResult.InvalidCursor>(
            ctx.syncEventReader.nextBatchOrActivate(ownerUid, Long.MAX_VALUE, 64) {
                invalidCursorActivated = true
                true
            },
        )
        assertFalse(invalidCursorActivated, "invalid cursors must never cross the live activation barrier")
    }
}

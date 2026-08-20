package com.virjar.tk.integration

import com.virjar.tk.domain.event.SyncBatchResult
import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.sync.LiveEventSink
import com.virjar.tk.infra.sync.SyncEventDispatcher
import com.virjar.tk.infra.sync.SyncEventReadHooks
import com.virjar.tk.infra.sync.SyncEventService
import com.virjar.tk.model.User
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
        val uid = ctx.registerUser(uniqueUsername("sync-pagination"))
        ctx.pgUnitOfWork.write {
            repeat(150) { index ->
                appendEvent(
                    uid,
                    NotifyType.USER_UPDATED,
                    User(uid = uid, username = "sync-$index", name = "Sync $index"),
                )
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
        val uid = ctx.registerUser(uniqueUsername("sync-race"))
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
    fun `live dispatch before final gate is still returned by the second durable read`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("sync-live-first"))
        val firstReadWasEmpty = CompletableDeferred<Unit>()
        val allowFinalGate = CompletableDeferred<Unit>()
        val liveDeliveries = mutableListOf<Long>()
        val dispatcher = SyncEventDispatcher(
            sink = LiveEventSink { deliveredUid, notify ->
                if (deliveredUid == uid) liveDeliveries += notify.eventId
            },
            scanIntervalMillis = 60_000L,
        )
        val unitOfWork = ExposedPgUnitOfWork(dispatcher::signal)
        val service = SyncEventService(
            unitOfWork = unitOfWork,
            dispatcher = dispatcher,
            readHooks = SyncEventReadHooks { hookUid, _ ->
                if (hookUid == uid) {
                    firstReadWasEmpty.complete(Unit)
                    allowFinalGate.await()
                }
            },
        )
        var activated = false
        try {
            val replay = async {
                service.nextBatchOrActivate(uid, 0L, 64) {
                    activated = true
                    true
                }
            }
            firstReadWasEmpty.await()

            unitOfWork.write {
                appendEvent(uid, NotifyType.USER_UPDATED, User(uid, "live-first", "Live First"))
            }
            assertEquals(1, dispatcher.dispatchPendingForUid(uid))
            assertEquals(listOf(1L), liveDeliveries)

            allowFinalGate.complete(Unit)
            val result = assertIs<SyncBatchResult.Events>(replay.await())
            assertEquals(listOf(1L), result.events.map { it.eventId })
            assertFalse(activated, "the second durable read must win over SYNC_READY")
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `development baseline keeps old sync events until full resync protocol exists`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("sync-retention"))
        val payload = User(uid = uid, username = "retention", name = "Retention")
        ctx.pgUnitOfWork.write {
            appendEvent(uid, NotifyType.USER_UPDATED, payload)
        }
        val service = assertIs<SyncEventService>(ctx.syncEventReader)

        assertEquals(0, service.cleanupExpiredEvents(), "cleanup must remain an explicit no-op")
        val replay = service.getEventsAfter(uid, 0L, 64)

        assertEquals(1, replay.size)
        assertEquals(com.virjar.tk.protocol.ProtoCodec.encode(payload).toList(), replay.single().payload?.toList())
    }

    @Test
    fun `cursor must be inside the authenticated user stream`() = runTest {
        val ownerUid = ctx.registerUser(uniqueUsername("sync-cursor-owner"))
        val otherUid = ctx.registerUser(uniqueUsername("sync-cursor-other"))
        val emptyUid = ctx.registerUser(uniqueUsername("sync-cursor-empty"))
        ctx.pgUnitOfWork.write {
            appendEvent(ownerUid, NotifyType.USER_UPDATED, User(ownerUid, "owner-1", "Owner 1"))
            appendEvent(otherUid, NotifyType.USER_UPDATED, User(otherUid, "other-1", "Other 1"))
            appendEvent(otherUid, NotifyType.USER_UPDATED, User(otherUid, "other-2", "Other 2"))
        }

        assertIs<SyncBatchResult.Activated>(
            ctx.syncEventReader.nextBatchOrActivate(ownerUid, 1L, 64) { true },
        )
        assertIs<SyncBatchResult.Activated>(
            ctx.syncEventReader.nextBatchOrActivate(emptyUid, 0L, 64) { true },
        )
        assertIs<SyncBatchResult.InvalidCursor>(
            ctx.syncEventReader.nextBatchOrActivate(emptyUid, 1L, 64) { true },
        )
        // Per-user event IDs intentionally overlap. Numeric cursor 1 is valid for both accounts.
        assertIs<SyncBatchResult.Events>(
            ctx.syncEventReader.nextBatchOrActivate(otherUid, 1L, 64) { true },
        )
        var invalidCursorActivated = false
        assertIs<SyncBatchResult.InvalidCursor>(
            ctx.syncEventReader.nextBatchOrActivate(ownerUid, 2L, 64) {
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

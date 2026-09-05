package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.event.SyncBatchResult
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.SyncStreams
import com.virjar.tk.server.infra.sync.SyncEventRetentionConfig
import com.virjar.tk.server.infra.sync.SyncEventService
import com.virjar.tk.server.infra.sync.SyncReplayLeaseRegistry
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.NotifyType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncEventCompactionIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()

        private const val NOW = 10_000L
        private const val OLD = 100L
    }

    private val ctx get() = ext.env

    @Test
    fun `cleanup stops at the first undispatched or fresh row`() = runTest {
        val uid = appendEvents("compact-prefix", 3)
        markEvents(uid, dispatchedThrough = 3L)
        transaction(ctx.database) {
            SyncEvents.update({ (SyncEvents.uid eq uid) and (SyncEvents.streamSeq eq 2L) }) {
                it[dispatchedAt] = null
            }
        }
        val service = retentionService()

        assertEquals(1, service.cleanupExpiredEvents().deletedEvents)
        assertStream(uid, floor = 1L, remaining = listOf(2L, 3L))

        transaction(ctx.database) {
            SyncEvents.update({ (SyncEvents.uid eq uid) and (SyncEvents.streamSeq eq 2L) }) {
                it[dispatchedAt] = OLD
                it[createdAt] = NOW
            }
        }
        assertEquals(0, service.cleanupExpiredEvents().deletedEvents)
        assertStream(uid, floor = 1L, remaining = listOf(2L, 3L))

        transaction(ctx.database) {
            SyncEvents.update({ (SyncEvents.uid eq uid) and (SyncEvents.streamSeq eq 2L) }) {
                it[createdAt] = OLD
            }
        }
        assertEquals(2, service.cleanupExpiredEvents().deletedEvents)
        assertStream(uid, floor = 3L, remaining = emptyList())
    }

    @Test
    fun `replay lease protects the acknowledged cursor until disconnect`() = runTest {
        val uid = appendEvents("compact-replay", 3)
        markEvents(uid, dispatchedThrough = 3L)
        val service = retentionService()
        val sessionId = "retention-replay-session"

        val page = assertIs<SyncBatchResult.Events>(
            service.nextBatchOrActivate(
                uid = uid,
                sessionId = sessionId,
                claimedDatasetId = service.datasetId,
                afterEventId = 1L,
                limit = 64,
            ) { false },
        )
        assertEquals(listOf(2L, 3L), page.events.map { it.eventId })

        assertEquals(1, service.cleanupExpiredEvents().deletedEvents)
        assertStream(uid, floor = 1L, remaining = listOf(2L, 3L))

        service.releaseSession(uid, sessionId)
        assertEquals(2, service.cleanupExpiredEvents().deletedEvents)
        assertStream(uid, floor = 3L, remaining = emptyList())
    }

    @Test
    fun `checkpoint lease permits its base prefix but protects the tail`() = runTest {
        val uid = appendEvents("compact-checkpoint", 3)
        val sessionId = "retention-checkpoint-session"
        val header = ctx.syncCheckpointService.beginCheckpoint(
            uid = uid,
            sessionId = sessionId,
            claimedDatasetId = ctx.syncEventReader.datasetId,
        )
        assertEquals(3L, header.baseEventId)
        ctx.pgUnitOfWork.write {
            repeat(2) { index ->
                appendEvent(uid, NotifyType.USER_UPDATED, user(uid, "tail-$index"))
            }
        }
        markEvents(uid, dispatchedThrough = 5L)
        val service = retentionService()

        assertEquals(3, service.cleanupExpiredEvents().deletedEvents)
        assertStream(uid, floor = 3L, remaining = listOf(4L, 5L))

        service.releaseSession(uid, sessionId)
        assertEquals(2, service.cleanupExpiredEvents().deletedEvents)
        assertStream(uid, floor = 5L, remaining = emptyList())
    }

    @Test
    fun `cleanup waits for the delivery gate and observes a cursor published inside it`() = runTest {
        val uid = appendEvents("compact-gate", 3)
        markEvents(uid, dispatchedThrough = 3L)
        val service = retentionService()
        val gateEntered = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val holder = async {
            ctx.syncEventDispatcher.withDeliveryGate(uid) {
                ctx.syncReplayLeaseRegistry.reserveReplay(uid, "gate-session")
                assertTrue(ctx.syncReplayLeaseRegistry.advanceReplay(uid, "gate-session", 1L))
                gateEntered.complete(Unit)
                releaseGate.await()
            }
        }
        gateEntered.await()

        val cleanup = async { service.cleanupExpiredEvents() }
        yield()
        assertFalse(cleanup.isCompleted, "cleanup must wait behind the per-user delivery gate")

        releaseGate.complete(Unit)
        holder.await()
        assertEquals(1, cleanup.await().deletedEvents)
        assertStream(uid, floor = 1L, remaining = listOf(2L, 3L))
        service.releaseSession(uid, "gate-session")
        assertEquals(2, service.cleanupExpiredEvents().deletedEvents)
        assertStream(uid, floor = 3L, remaining = emptyList())
    }

    @Test
    fun `durable floor survives service restart and rejects an older cursor`() = runTest {
        val uid = appendEvents("compact-restart", 3)
        markEvents(uid, dispatchedThrough = 3L)
        val service = retentionService()
        assertEquals(3, service.cleanupExpiredEvents().deletedEvents)

        val restarted = retentionService(leases = SyncReplayLeaseRegistry())
        assertIs<SyncBatchResult.InvalidCursor>(
            restarted.nextBatchOrActivate(
                uid,
                "restart-old-session",
                restarted.datasetId,
                0L,
                64,
            ) { error("an older cursor must not activate") },
        )
        assertIs<SyncBatchResult.Activated>(
            restarted.nextBatchOrActivate(
                uid,
                "restart-floor-session",
                restarted.datasetId,
                3L,
                64,
            ) { true },
        )
    }

    @Test
    fun `bounded cleanup reports catch up only while an eligible prefix remains`() = runTest {
        val uid = appendEvents("compact-bounded", 3)
        markEvents(uid, dispatchedThrough = 3L)
        val service = retentionService(maxEventsPerUser = 2)

        val first = service.cleanupExpiredEvents()
        assertEquals(2, first.deletedEvents)
        assertTrue(first.backlogMayRemain)
        assertStream(uid, floor = 2L, remaining = listOf(3L))

        val second = service.cleanupExpiredEvents()
        assertEquals(1, second.deletedEvents)
        assertFalse(second.backlogMayRemain)
        assertStream(uid, floor = 3L, remaining = emptyList())
    }

    @Test
    fun `lease pinned low uid does not starve the next cleanup candidate`() = runTest {
        val firstCreated = appendEvents("compact-fair-a", 1)
        val secondCreated = appendEvents("compact-fair-b", 1)
        val (pinnedUid, eligibleUid) = listOf(firstCreated, secondCreated).sorted()
        markEvents(pinnedUid, dispatchedThrough = 1L)
        markEvents(eligibleUid, dispatchedThrough = 1L)
        val service = retentionService(maxUsersPerRun = 1)
        ctx.syncEventDispatcher.withDeliveryGate(pinnedUid) {
            ctx.syncReplayLeaseRegistry.reserveReplay(pinnedUid, "fair-session")
            assertTrue(ctx.syncReplayLeaseRegistry.advanceReplay(pinnedUid, "fair-session", 0L))
        }

        val pinnedPass = service.cleanupExpiredEvents()
        assertEquals(0, pinnedPass.deletedEvents)
        assertTrue(pinnedPass.backlogMayRemain, "an unvisited candidate page must be scheduled promptly")
        assertStream(pinnedUid, floor = 0L, remaining = listOf(1L))

        val eligiblePass = service.cleanupExpiredEvents()
        assertEquals(1, eligiblePass.deletedEvents)
        assertFalse(eligiblePass.backlogMayRemain, "the final keyset page must end catch-up scheduling")
        assertStream(eligibleUid, floor = 1L, remaining = emptyList())

        service.releaseSession(pinnedUid, "fair-session")
        assertEquals(1, service.cleanupExpiredEvents().deletedEvents)
        assertStream(pinnedUid, floor = 1L, remaining = emptyList())
    }

    private suspend fun appendEvents(prefix: String, count: Int): String {
        val uid = ctx.registerUser(uniqueUsername(prefix))
        ctx.pgUnitOfWork.write {
            repeat(count) { index ->
                appendEvent(uid, NotifyType.USER_UPDATED, user(uid, "$prefix-$index"))
            }
        }
        return uid
    }

    private fun markEvents(uid: String, dispatchedThrough: Long) {
        transaction(ctx.database) {
            SyncEvents.update({ SyncEvents.uid eq uid }) {
                it[createdAt] = OLD
                it[dispatchedAt] = OLD
            }
            SyncEvents.update({
                (SyncEvents.uid eq uid) and (SyncEvents.streamSeq greater dispatchedThrough)
            }) {
                it[dispatchedAt] = null
            }
        }
    }

    private fun retentionService(
        leases: SyncReplayLeaseRegistry = ctx.syncReplayLeaseRegistry,
        maxUsersPerRun: Int = 16,
        maxEventsPerUser: Int = 32,
    ): SyncEventService = SyncEventService(
        database = ctx.database,
        dispatcher = ctx.syncEventDispatcher,
        datasetId = ctx.syncEventReader.datasetId,
        leases = leases,
        retention = SyncEventRetentionConfig(
            retentionMillis = 1_000L,
            maxUsersPerRun = maxUsersPerRun,
            maxEventsPerUser = maxEventsPerUser,
        ),
        clock = { NOW },
    )

    private fun assertStream(uid: String, floor: Long, remaining: List<Long>) {
        transaction(ctx.database) {
            val stream = SyncStreams.selectAll().where { SyncStreams.uid eq uid }.single()
            assertEquals(floor, stream[SyncStreams.compactedThrough])
            assertEquals(
                remaining,
                SyncEvents.selectAll()
                    .where { SyncEvents.uid eq uid }
                    .orderBy(SyncEvents.streamSeq to SortOrder.ASC)
                    .map { it[SyncEvents.streamSeq] },
            )
        }
    }

    private fun user(uid: String, label: String) = User(uid = uid, username = label, name = label)
}

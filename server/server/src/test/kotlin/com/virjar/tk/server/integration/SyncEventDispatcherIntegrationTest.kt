package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.sync.ExposedPendingSyncUidPageRepository
import com.virjar.tk.server.infra.sync.LiveEventSink
import com.virjar.tk.server.infra.sync.SyncEventDispatcher
import com.virjar.tk.server.infra.sync.SyncEventDispatcherPhase
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.payload.NotifyPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SyncEventDispatcherIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `pending uid repository applies due filters before stable bounded keyset pages`() = runTest {
        val now = 5_000L
        val uids = (0 until 6).map { index ->
            ctx.registerUser(uniqueUsername("dispatch-page-$index"))
        }.sorted()
        uids.forEach { uid -> appendEventsWithoutWake(uid, count = 1, now = now) }
        val dispatchedUid = uids[1]
        val deferredUid = uids[4]
        transaction(ctx.database) {
            SyncEvents.update({ SyncEvents.uid eq dispatchedUid }) {
                it[SyncEvents.dispatchedAt] = now
            }
            SyncEvents.update({ SyncEvents.uid eq deferredUid }) {
                it[SyncEvents.nextAttemptAt] = now + 1L
            }
        }
        val repository = ExposedPendingSyncUidPageRepository(ctx.database)
        fun scanPages(): List<List<String>> {
            val pages = mutableListOf<List<String>>()
            var after: String? = null
            var page: List<String>
            do {
                page = repository.loadPage(nowMillis = now, afterUidExclusive = after, limit = 2)
                pages += page
                after = page.lastOrNull()
            } while (page.size == 2)
            return pages
        }

        val pages = scanPages()
        val flattened = pages.flatten()
        val expected = uids.filterNot { uid -> uid == dispatchedUid || uid == deferredUid }.toSet()

        assertEquals(expected, flattened.toSet())
        assertEquals(flattened.size, flattened.distinct().size)
        assertEquals(listOf(2, 2, 0), pages.map { page -> page.size })
        assertEquals(pages, scanPages(), "a fresh cursor must observe the same database-owned order")
    }

    @Test
    fun `failed head event blocks later sequence until ordered retry succeeds`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("dispatch-order"))
        var now = 10_000L
        val attempts = Collections.synchronizedList(mutableListOf<Long>())
        var failFirstAttempt = true
        val dispatcher = SyncEventDispatcher(
            database = ctx.database,
            sink = LiveEventSink { _, notify ->
                attempts += notify.eventId
                if (notify.eventId == 1L && failFirstAttempt) {
                    failFirstAttempt = false
                    error("injected live delivery failure")
                }
            },
            clock = { now },
            scanIntervalMillis = 60_000L,
        )
        try {
            appendEventsWithoutWake(uid, 2, now)

            assertEquals(0, dispatcher.dispatchPendingForUid(uid))
            assertEquals(listOf(1L), attempts.toList())
            assertEquals(0, dispatcher.dispatchPendingForUid(uid))
            assertEquals(listOf(1L), attempts.toList(), "seq 2 must not overtake a backed-off seq 1")

            now += SyncEventDispatcher.BASE_RETRY_MILLIS
            assertEquals(2, dispatcher.dispatchPendingForUid(uid))
            assertEquals(listOf(1L, 1L, 2L), attempts.toList())

            val rows = transaction(ctx.database) {
                SyncEvents.selectAll()
                    .where { SyncEvents.uid eq uid }
                    .orderBy(SyncEvents.streamSeq to SortOrder.ASC)
                    .toList()
            }
            assertEquals(listOf(1, 0), rows.map { it[SyncEvents.dispatchAttempts] })
            rows.forEach { assertNotNull(it[SyncEvents.dispatchedAt]) }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `caller cancellation is propagated without recording a delivery attempt`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("dispatch-cancel"))
        val now = 30_000L
        val expected = CancellationException("request owner retired")
        appendEventsWithoutWake(uid, 1, now)
        val dispatcher = SyncEventDispatcher(
            database = ctx.database,
            sink = LiveEventSink { _, _ -> throw expected },
            clock = { now },
            scanIntervalMillis = 60_000L,
        )

        try {
            val actual = assertFailsWith<CancellationException> {
                dispatcher.dispatchPendingForUid(uid)
            }
            assertSame(expected, actual)
            assertPendingUntouched(uid)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `fatal delivery failure is propagated without recording a delivery attempt`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("dispatch-fatal"))
        val now = 40_000L
        val expected = FatalDispatchProbe("fatal delivery boundary")
        appendEventsWithoutWake(uid, 1, now)
        val dispatcher = SyncEventDispatcher(
            database = ctx.database,
            sink = LiveEventSink { _, _ -> throw expected },
            clock = { now },
            scanIntervalMillis = 60_000L,
        )

        try {
            assertSame(
                expected,
                runCatching { dispatcher.dispatchPendingForUid(uid) }.exceptionOrNull(),
            )
            assertPendingUntouched(uid)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `startup scan dispatches an event committed before process wake`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("dispatch-restart"))
        val now = 20_000L
        val wakeCalled = AtomicBoolean(false)
        val callbackCalled = AtomicBoolean(false)
        val crashAfterCommit = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = { wakeCalled.set(true) },
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_COMMIT_BEFORE_CALLBACKS) throw InjectedPostCommitCrash
            },
            clock = { now },
        )
        assertIs<InjectedPostCommitCrashException>(runCatching {
            crashAfterCommit.write {
                appendEvent(uid, NotifyType.USER_UPDATED, User(uid, "restart", "Restart"))
                afterCommit { callbackCalled.set(true) }
            }
        }.exceptionOrNull())
        assertFalse(wakeCalled.get())
        assertFalse(callbackCalled.get())
        assertNull(transaction(ctx.database) {
            SyncEvents.selectAll().where { SyncEvents.uid eq uid }.single()[SyncEvents.dispatchedAt]
        })

        val delivered = Collections.synchronizedList(mutableListOf<NotifyPayload>())
        val restarted = SyncEventDispatcher(
            database = ctx.database,
            sink = LiveEventSink { deliveredUid, notify ->
                if (deliveredUid == uid) delivered += notify
            },
            clock = { now },
            scanIntervalMillis = 60_000L,
        )
        try {
            restarted.start()
            withContext(Dispatchers.IO) {
                withTimeout(5_000) { restarted.awaitStartupScan() }
            }
            assertEquals(SyncEventDispatcherPhase.READY, restarted.snapshot().phase)
            assertEquals(true, restarted.snapshot().live)
            assertEquals(true, restarted.snapshot().ready)
            assertEquals(listOf(1L), delivered.map { it.eventId })
            assertNotNull(transaction(ctx.database) {
                SyncEvents.selectAll().where { SyncEvents.uid eq uid }.single()[SyncEvents.dispatchedAt]
            })
        } finally {
            restarted.close()
        }
    }

    private suspend fun appendEventsWithoutWake(uid: String, count: Int, now: Long) {
        ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {}, clock = { now }).write {
            repeat(count) { index ->
                appendEvent(
                    uid,
                    NotifyType.USER_UPDATED,
                    User(uid, "dispatch-$index", "Dispatch $index"),
                )
            }
        }
    }

    private fun assertPendingUntouched(uid: String) {
        val (attempts, dispatchedAt) = transaction(ctx.database) {
            val row = SyncEvents.selectAll().where { SyncEvents.uid eq uid }.single()
            row[SyncEvents.dispatchAttempts] to row[SyncEvents.dispatchedAt]
        }
        assertEquals(0, attempts)
        assertNull(dispatchedAt)
    }

    private object InjectedPostCommitCrash : InjectedPostCommitCrashException()
    private open class InjectedPostCommitCrashException : RuntimeException("injected post-commit crash")
    private class FatalDispatchProbe(message: String) : Error(message)
}

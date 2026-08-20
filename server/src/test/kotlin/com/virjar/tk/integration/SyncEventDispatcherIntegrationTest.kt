package com.virjar.tk.integration

import com.virjar.tk.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.infra.db.PgUnitOfWorkStage
import com.virjar.tk.infra.db.SyncEvents
import com.virjar.tk.infra.sync.LiveEventSink
import com.virjar.tk.infra.sync.SyncEventDispatcher
import com.virjar.tk.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.payload.NotifyPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncEventDispatcherIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `failed head event blocks later sequence until ordered retry succeeds`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("dispatch-order"))
        var now = 10_000L
        val attempts = Collections.synchronizedList(mutableListOf<Long>())
        var failFirstAttempt = true
        val dispatcher = SyncEventDispatcher(
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

            val rows = transaction {
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
    fun `startup scan dispatches an event committed before process wake`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("dispatch-restart"))
        val now = 20_000L
        val wakeCalled = AtomicBoolean(false)
        val callbackCalled = AtomicBoolean(false)
        val crashAfterCommit = ExposedPgUnitOfWork(
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
        assertNull(transaction {
            SyncEvents.selectAll().where { SyncEvents.uid eq uid }.single()[SyncEvents.dispatchedAt]
        })

        val delivered = Collections.synchronizedList(mutableListOf<NotifyPayload>())
        val restarted = SyncEventDispatcher(
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
            assertEquals(listOf(1L), delivered.map { it.eventId })
            assertNotNull(transaction {
                SyncEvents.selectAll().where { SyncEvents.uid eq uid }.single()[SyncEvents.dispatchedAt]
            })
        } finally {
            restarted.close()
        }
    }

    private suspend fun appendEventsWithoutWake(uid: String, count: Int, now: Long) {
        ExposedPgUnitOfWork(onEventsCommitted = {}, clock = { now }).write {
            repeat(count) { index ->
                appendEvent(
                    uid,
                    NotifyType.USER_UPDATED,
                    User(uid, "dispatch-$index", "Dispatch $index"),
                )
            }
        }
    }

    private object InjectedPostCommitCrash : InjectedPostCommitCrashException()
    private open class InjectedPostCommitCrashException : RuntimeException("injected post-commit crash")
}

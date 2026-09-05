package com.virjar.tk.server.integration

import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.PgUnitOfWorkHooks
import com.virjar.tk.server.infra.db.PgUnitOfWorkStage
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.SyncStreams
import com.virjar.tk.server.infra.db.Users
import com.virjar.tk.server.infra.db.requireExposedTransaction
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.protocol.ProtoCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.Connection
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PgUnitOfWorkIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `write transactions pin read committed for post-fence visibility`() = runTest {
        val isolation = ctx.pgUnitOfWork.write {
            transaction.requireExposedTransaction().connection.transactionIsolation
        }

        assertEquals(Connection.TRANSACTION_READ_COMMITTED, isolation)
    }

    @Test
    fun `event flush failure rolls back domain mutation streams and callbacks`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("uow-rollback"))
        val beforeName = transaction(ctx.database) {
            Users.selectAll().where { Users.uid eq uid }.single()[Users.name]
        }
        val commitWakeCalled = AtomicBoolean(false)
        val afterCommitCalled = AtomicBoolean(false)
        val unitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = { commitWakeCalled.set(true) },
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                    throw InjectedRollback
                }
            },
        )

        val failure = runCatching {
            unitOfWork.write {
                Users.update({ Users.uid eq uid }) { it[Users.name] = "must-roll-back" }
                appendEvent(uid, NotifyType.USER_UPDATED, userPayload(uid, "rollback"))
                afterCommit { afterCommitCalled.set(true) }
            }
        }.exceptionOrNull()

        assertIs<InjectedRollbackException>(failure)
        assertEquals(beforeName, transaction(ctx.database) {
            Users.selectAll().where { Users.uid eq uid }.single()[Users.name]
        })
        assertNull(transaction(ctx.database) {
            SyncStreams.selectAll().where { SyncStreams.uid eq uid }.singleOrNull()
        })
        assertTrue(transaction(ctx.database) {
            SyncEvents.selectAll().where { SyncEvents.uid eq uid }.empty()
        })
        assertFalse(commitWakeCalled.get())
        assertFalse(afterCommitCalled.get())
    }

    @Test
    fun `local visibility callbacks run before durable event dispatch is signalled`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("uow-callback-order"))
        val publicationOrder = mutableListOf<String>()
        val unitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = { committedUids ->
                assertEquals(setOf(uid), committedUids)
                assertEquals(listOf("cache-visible"), publicationOrder)
                publicationOrder += "dispatcher-wake"
            },
        )

        unitOfWork.write {
            appendEvent(uid, NotifyType.USER_UPDATED, userPayload(uid, "callback-order"))
            afterCommit { publicationOrder += "cache-visible" }
        }

        assertEquals(listOf("cache-visible", "dispatcher-wake"), publicationOrder)
    }

    @Test
    fun `fatal post commit callback escapes only after later visibility and dispatcher wake`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("uow-fatal-callback"))
        val fatal = AssertionError("fatal cache invariant")
        val publicationOrder = mutableListOf<String>()
        val unitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {
                publicationOrder += "dispatcher-wake"
            },
        )

        val observed = try {
            unitOfWork.write {
                appendEvent(uid, NotifyType.USER_UPDATED, userPayload(uid, "fatal-callback"))
                afterCommit {
                    publicationOrder += "fatal-callback"
                    throw fatal
                }
                afterCommit { publicationOrder += "later-cache-visible" }
            }
            null
        } catch (error: AssertionError) {
            error
        }

        assertSame(fatal, observed)
        assertEquals(
            listOf("fatal-callback", "later-cache-visible", "dispatcher-wake"),
            publicationOrder,
        )
        assertEquals(listOf(1L), streamSequences(uid))
    }

    @Test
    fun `post commit cancellation is not swallowed and escapes after publication drain`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("uow-cancelled-callback"))
        val cancellation = CancellationException("cancelled cache publication")
        val publicationOrder = mutableListOf<String>()
        val unitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = { publicationOrder += "dispatcher-wake" },
        )

        val observed = try {
            unitOfWork.write {
                appendEvent(uid, NotifyType.USER_UPDATED, userPayload(uid, "cancelled-callback"))
                afterCommit {
                    publicationOrder += "cancelled-callback"
                    throw cancellation
                }
                afterCommit { publicationOrder += "later-cache-visible" }
            }
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, observed)
        assertEquals(
            listOf("cancelled-callback", "later-cache-visible", "dispatcher-wake"),
            publicationOrder,
        )
        assertEquals(listOf(1L), streamSequences(uid))
    }

    @Test
    fun `caller cancellation after commit cannot split callbacks from dispatcher wake`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("uow-post-commit-cancel"))
        val afterCommitReached = CompletableDeferred<Unit>()
        val releaseAfterCommit = CompletableDeferred<Unit>()
        val cacheVisible = AtomicBoolean(false)
        val dispatcherSignalled = AtomicBoolean(false)
        val unitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = { dispatcherSignalled.set(true) },
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_COMMIT_BEFORE_CALLBACKS) {
                    afterCommitReached.complete(Unit)
                    releaseAfterCommit.await()
                }
            },
        )

        val writer = async(Dispatchers.Default) {
            unitOfWork.write {
                appendEvent(uid, NotifyType.USER_UPDATED, userPayload(uid, "post-commit-cancel"))
                afterCommit { cacheVisible.set(true) }
            }
        }
        afterCommitReached.await()
        writer.cancel()
        releaseAfterCommit.complete(Unit)
        writer.join()

        assertTrue(writer.isCancelled)
        assertTrue(cacheVisible.get(), "committed mutation must still publish local visibility")
        assertTrue(dispatcherSignalled.get(), "committed event must still wake the dispatcher")
        assertEquals(listOf(1L), streamSequences(uid))
    }

    @Test
    fun `already cancelled caller cannot begin an aggregate transaction`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("uow-pre-cancel"))
        val enteredWriteBlock = AtomicBoolean(false)
        val callerStarted = CompletableDeferred<Unit>()
        val observedFailure = CompletableDeferred<Throwable?>()
        val unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {})

        val caller = launch {
            try {
                callerStarted.complete(Unit)
                awaitCancellation()
            } catch (_: CancellationException) {
                observedFailure.complete(
                    runCatching {
                        unitOfWork.write {
                            enteredWriteBlock.set(true)
                            appendEvent(uid, NotifyType.USER_UPDATED, userPayload(uid, "must-not-run"))
                        }
                    }.exceptionOrNull(),
                )
            }
        }
        callerStarted.await()
        caller.cancel()
        caller.join()

        assertIs<CancellationException>(observedFailure.await())
        assertFalse(enteredWriteBlock.get())
        assertTrue(streamSequences(uid).isEmpty())
    }

    @Test
    fun `same uid sequence allocation serializes transaction commit order`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("uow-order"))
        val firstFlushed = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondBeforeFlush = CountDownLatch(1)
        val secondFlushed = CountDownLatch(1)
        val completionOrder = Collections.synchronizedList(mutableListOf<String>())

        val firstUnitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) {
                    firstFlushed.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS)) { "test did not release first transaction" }
                }
            },
        )
        val secondUnitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                when (stage) {
                    PgUnitOfWorkStage.BEFORE_EVENT_FLUSH -> secondBeforeFlush.countDown()
                    PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT -> secondFlushed.countDown()
                    PgUnitOfWorkStage.AFTER_COMMIT_BEFORE_CALLBACKS -> Unit
                }
            },
        )

        val first = async(Dispatchers.Default) {
            firstUnitOfWork.write {
                appendEvent(uid, NotifyType.USER_UPDATED, userPayload(uid, "first"))
            }
            completionOrder += "first"
        }
        assertTrue(firstFlushed.await(5, TimeUnit.SECONDS))

        val second = async(Dispatchers.Default) {
            secondUnitOfWork.write {
                appendEvent(uid, NotifyType.USER_UPDATED, userPayload(uid, "second"))
            }
            completionOrder += "second"
        }
        assertTrue(secondBeforeFlush.await(5, TimeUnit.SECONDS))
        assertFalse(
            secondFlushed.await(250, TimeUnit.MILLISECONDS),
            "second transaction must wait for the first stream-row owner to commit",
        )

        releaseFirst.countDown()
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                first.await()
                second.await()
            }
        }

        assertEquals(listOf("first", "second"), completionOrder.toList())
        assertEquals(listOf(1L, 2L), transaction(ctx.database) {
            SyncEvents.selectAll()
                .where { SyncEvents.uid eq uid }
                .orderBy(SyncEvents.streamSeq to SortOrder.ASC)
                .map { it[SyncEvents.streamSeq] }
        })
        assertEquals(2L, transaction(ctx.database) {
            SyncStreams.selectAll().where { SyncStreams.uid eq uid }.single()[SyncStreams.lastSeq]
        })
    }

    @Test
    fun `inverse recipient order uses sorted locks and multi recipient rollback is atomic`() = runTest {
        val uidA = ctx.registerUser(uniqueUsername("uow-multi-a"))
        val uidB = ctx.registerUser(uniqueUsername("uow-multi-b"))
        val flushBarrier = CyclicBarrier(2)
        fun concurrentUnitOfWork() = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.BEFORE_EVENT_FLUSH) {
                    flushBarrier.await(5, TimeUnit.SECONDS)
                }
            },
        )

        val concurrentWrites = listOf(
            async(Dispatchers.Default) {
                concurrentUnitOfWork().write {
                    appendEvent(uidA, NotifyType.USER_UPDATED, userPayload(uidA, "a-first"))
                    appendEvent(uidB, NotifyType.USER_UPDATED, userPayload(uidB, "a-second"))
                }
            },
            async(Dispatchers.Default) {
                concurrentUnitOfWork().write {
                    appendEvent(uidB, NotifyType.USER_UPDATED, userPayload(uidB, "b-first"))
                    appendEvent(uidA, NotifyType.USER_UPDATED, userPayload(uidA, "b-second"))
                }
            },
        )
        withContext(Dispatchers.IO) {
            withTimeout(10_000) { concurrentWrites.awaitAll() }
        }

        assertEquals(listOf(1L, 2L), streamSequences(uidA))
        assertEquals(listOf(1L, 2L), streamSequences(uidB))

        val rollbackA = ctx.registerUser(uniqueUsername("uow-partial-a"))
        val rollbackB = ctx.registerUser(uniqueUsername("uow-partial-b"))
        val failingUnitOfWork = ExposedPgUnitOfWork(
            database = ctx.database,
            onEventsCommitted = {},
            hooks = PgUnitOfWorkHooks { stage ->
                if (stage == PgUnitOfWorkStage.AFTER_EVENT_FLUSH_BEFORE_COMMIT) throw InjectedRollback
            },
        )
        assertIs<InjectedRollbackException>(runCatching {
            failingUnitOfWork.write {
                appendEvent(rollbackB, NotifyType.USER_UPDATED, userPayload(rollbackB, "rollback-b"))
                appendEvent(rollbackA, NotifyType.USER_UPDATED, userPayload(rollbackA, "rollback-a"))
            }
        }.exceptionOrNull())
        assertTrue(streamSequences(rollbackA).isEmpty())
        assertTrue(streamSequences(rollbackB).isEmpty())
    }

    @Test
    fun `batched event flush appends every intent in order`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("uow-batch-all-intents"))
        val unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = {})

        unitOfWork.write {
            appendEvent(
                uid,
                NotifyType.USER_UPDATED,
                userPayload(uid, "first"),
            )
            appendEvent(
                uid,
                NotifyType.USER_UPDATED,
                userPayload(uid, "second"),
            )
            appendEvent(
                uid,
                NotifyType.USER_UPDATED,
                userPayload(uid, "third"),
            )
        }
        unitOfWork.write {
            appendEvent(
                uid,
                NotifyType.USER_UPDATED,
                userPayload(uid, "fourth"),
            )
            appendEvent(
                uid,
                NotifyType.USER_UPDATED,
                userPayload(uid, "fifth"),
            )
        }

        val rows = transaction(ctx.database) {
            SyncEvents.selectAll()
                .where { SyncEvents.uid eq uid }
                .orderBy(SyncEvents.streamSeq to SortOrder.ASC)
                .toList()
        }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), rows.map { it[SyncEvents.streamSeq] })
        assertEquals(
            listOf("UoW first", "UoW second", "UoW third", "UoW fourth", "UoW fifth"),
            rows.map { row -> ProtoCodec.decode(User, row[SyncEvents.payload]).name },
        )
        assertEquals(5L, transaction(ctx.database) {
            SyncStreams.selectAll().where { SyncStreams.uid eq uid }.single()[SyncStreams.lastSeq]
        })
    }

    private fun streamSequences(uid: String): List<Long> = transaction(ctx.database) {
        SyncEvents.selectAll()
            .where { SyncEvents.uid eq uid }
            .orderBy(SyncEvents.streamSeq to SortOrder.ASC)
            .map { it[SyncEvents.streamSeq] }
    }

    private fun userPayload(uid: String, suffix: String) = User(
        uid = uid,
        username = "uow-$suffix",
        name = "UoW $suffix",
    )

    private object InjectedRollback : InjectedRollbackException()
    private open class InjectedRollbackException : RuntimeException("injected rollback")
}

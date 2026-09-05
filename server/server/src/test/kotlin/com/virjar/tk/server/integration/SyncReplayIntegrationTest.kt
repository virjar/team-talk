package com.virjar.tk.server.integration

import com.virjar.tk.server.domain.event.SyncBatchResult
import com.virjar.tk.server.infra.db.ExposedPgUnitOfWork
import com.virjar.tk.server.infra.db.Friends
import com.virjar.tk.server.infra.db.SyncEvents
import com.virjar.tk.server.infra.db.SyncStreams
import com.virjar.tk.server.infra.sync.LiveEventSink
import com.virjar.tk.server.infra.sync.SyncEventDispatcher
import com.virjar.tk.server.infra.sync.SyncEventReadHooks
import com.virjar.tk.server.infra.sync.SyncEventService
import com.virjar.tk.protocol.model.User
import com.virjar.tk.protocol.model.SyncCheckpointPageRequest
import com.virjar.tk.protocol.model.SyncCheckpointHeader
import com.virjar.tk.protocol.model.SyncCheckpointContactPage
import com.virjar.tk.protocol.ProtoCodec
import com.virjar.tk.protocol.NotifyType
import com.virjar.tk.server.protocol.rpc.RpcSessionContext
import com.virjar.tk.protocol.rpc.gen.SyncRpcContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SyncReplayIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env

    @Test
    fun `same numeric cursor from a different dataset is rejected before replay`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("sync-dataset"))
        ctx.pgUnitOfWork.write {
            appendEvent(
                uid,
                NotifyType.USER_UPDATED,
                User(uid = uid, username = "dataset-user", name = "Dataset User"),
            )
        }

        val result = ctx.syncEventReader.nextBatchOrActivate(
            uid = uid,
            sessionId = "dataset-session",
            claimedDatasetId = "00000000-0000-4000-8000-000000000001",
            afterEventId = 1L,
            limit = 64,
        ) { error("a mismatched dataset must never activate") }

        val mismatch = assertIs<SyncBatchResult.DatasetMismatch>(result)
        assertEquals(ctx.syncEventReader.datasetId, mismatch.datasetId)
    }

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
            when (val result = ctx.syncEventReader.nextBatchOrActivate(
                uid,
                "pagination-session",
                ctx.syncEventReader.datasetId,
                cursor,
                64,
            ) { true }) {
                is SyncBatchResult.Events -> {
                    assertTrue(result.events.size in 1..64)
                    assertTrue(result.events.zipWithNext().all { (left, right) -> left.eventId < right.eventId })
                    replayed += result.events.map { it.eventId }
                    // 这个 cursor 更新代表整页事件持久化之后客户端发出的 ACK。
                    cursor = result.events.last().eventId
                }
                SyncBatchResult.Activated -> activated = true
                SyncBatchResult.ConnectionClosed -> error("test connection unexpectedly closed")
                is SyncBatchResult.DatasetMismatch -> error("server rejected its own dataset identity")
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
            ctx.syncEventReader.nextBatchOrActivate(uid, "race-session", ctx.syncEventReader.datasetId, 0L, 64) {
                order += "ready"
                activationEntered.complete(Unit)
                allowActivation.await()
                true
            }
        }
        activationEntered.await()

        val live = async {
            ctx.pgUnitOfWork.write {
                appendEvent(
                    uid,
                    NotifyType.USER_UPDATED,
                    User(uid = uid, username = "sync-user", name = "Sync User"),
                )
            }
            // 持久事件只能在它们所属的聚合事务内部创建。
            // 即时投递是独立的投影步骤，受到 SYNC_READY 竞态保护。
            ctx.syncEventDispatcher.dispatchPendingForUid(uid)
            order += "live"
        }
        yield()
        assertFalse(live.isCompleted, "live persist/push must wait behind final activation")

        allowActivation.complete(Unit)
        assertIs<SyncBatchResult.Activated>(activation.await())
        live.await()
        assertEquals(listOf("ready", "live"), order)

        val replay = ctx.syncEventReader.nextBatchOrActivate(
            uid,
            "race-replay-session",
            ctx.syncEventReader.datasetId,
            0L,
            64,
        ) { false }
        assertIs<SyncBatchResult.Events>(replay)
        assertEquals(1, replay.events.size)
    }

    @Test
    fun `persistence during the gated empty check is returned before queued live dispatch`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("sync-live-first"))
        val firstReadWasEmpty = CompletableDeferred<Unit>()
        val allowFinalGate = CompletableDeferred<Unit>()
        val liveDeliveries = mutableListOf<Long>()
        val dispatcher = SyncEventDispatcher(
            database = ctx.database,
            sink = LiveEventSink { deliveredUid, notify ->
                if (deliveredUid == uid) liveDeliveries += notify.eventId
            },
            scanIntervalMillis = 60_000L,
        )
        val unitOfWork = ExposedPgUnitOfWork(database = ctx.database, onEventsCommitted = dispatcher::signal)
        val service = SyncEventService(
            database = ctx.database,
            dispatcher = dispatcher,
            datasetId = ctx.syncEventReader.datasetId,
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
                service.nextBatchOrActivate(uid, "live-first-session", service.datasetId, 0L, 64) {
                    activated = true
                    true
                }
            }
            firstReadWasEmpty.await()

            unitOfWork.write {
                appendEvent(uid, NotifyType.USER_UPDATED, User(uid, "live-first", "Live First"))
            }
            val liveDispatch = async { dispatcher.dispatchPendingForUid(uid) }
            yield()
            assertFalse(liveDispatch.isCompleted, "live dispatch must wait behind replay's delivery gate")

            allowFinalGate.complete(Unit)
            val result = assertIs<SyncBatchResult.Events>(replay.await())
            assertEquals(listOf(1L), result.events.map { it.eventId })
            assertFalse(activated, "the second durable read must win over SYNC_READY")
            assertEquals(1, liveDispatch.await())
            assertEquals(listOf(1L), liveDeliveries)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun `retention keeps fresh sync events`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("sync-retention"))
        val payload = User(uid = uid, username = "retention", name = "Retention")
        ctx.pgUnitOfWork.write {
            appendEvent(uid, NotifyType.USER_UPDATED, payload)
        }
        val service = assertIs<SyncEventService>(ctx.syncEventReader)

        val cleanup = service.cleanupExpiredEvents()
        assertEquals(0, cleanup.deletedEvents)
        assertFalse(cleanup.backlogMayRemain)
        val replay = service.getEventsAfter(uid, 0L, 64)

        assertEquals(1, replay.size)
        assertEquals(com.virjar.tk.protocol.ProtoCodec.encode(payload).toList(), replay.single().payload?.toList())
    }

    @Test
    fun `checkpoint anchors the tail and returns complete typed projections`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("checkpoint-owner"))
        val friendUid = ctx.registerUser(uniqueUsername("checkpoint-friend"))
        transaction(ctx.database) {
            Friends.insert {
                it[Friends.uid] = uid
                it[Friends.friendUid] = friendUid
                it[Friends.status] = 1
                it[Friends.createdAt] = System.currentTimeMillis()
            }
        }
        val chat = ctx.chatService.createPersonalChat(uid, friendUid)
        val sessionId = "checkpoint-session"

        val header = ctx.syncCheckpointService.beginCheckpoint(
            uid = uid,
            sessionId = sessionId,
            claimedDatasetId = ctx.syncEventReader.datasetId,
        )

        assertEquals(uid, header.currentUser.uid)
        assertEquals(ctx.syncEventReader.datasetId, header.datasetId)
        assertTrue(header.baseEventId > 0L)
        val request = SyncCheckpointPageRequest(header.checkpointId)
        val contacts = ctx.syncCheckpointService.listContacts(uid, sessionId, request)
        val chats = ctx.syncCheckpointService.listChats(uid, sessionId, request)
        val conversations = ctx.syncCheckpointService.listConversations(uid, sessionId, request)
        assertEquals(listOf(friendUid), contacts.items.map { it.friendUid })
        assertEquals(friendUid, contacts.items.single().user?.uid)
        assertTrue(chats.items.any { it.chatId == chat.chatId })
        assertTrue(conversations.items.any { it.chatId == chat.chatId })
        assertFailsWith<IllegalArgumentException> {
            ctx.syncCheckpointService.listContacts(uid, "different-session", request)
        }
        assertIs<SyncBatchResult.Activated>(
            ctx.syncEventReader.nextBatchOrActivate(
                uid = uid,
                sessionId = sessionId,
                claimedDatasetId = header.datasetId,
                afterEventId = header.baseEventId,
                limit = 64,
            ) { true },
        )
        assertNull(ctx.syncReplayLeaseRegistry.leaseFor(uid, sessionId))
    }

    @Test
    fun `sync rpc registry binds checkpoint ownership to the authenticated session`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("checkpoint-rpc"))
        val sessionId = "checkpoint-rpc-session"
        val session = RpcSessionContext(
            uid = uid,
            deviceId = "checkpoint-device",
            deviceCredentialEpoch = 1L,
            sessionId = sessionId,
        )

        val encoded = ctx.rpcStubRegistry.dispatchSuspend(
            session = session,
            service = SyncRpcContract.SERVICE,
            methodId = SyncRpcContract.M_BEGIN_CHECKPOINT,
            payload = SyncRpcContract.encodeBeginCheckpoint(ctx.syncEventReader.datasetId),
        )
        val header = ProtoCodec.decode(SyncCheckpointHeader, requireNotNull(encoded))

        assertEquals(uid, header.currentUser.uid)
        val pageRequest = SyncCheckpointPageRequest(header.checkpointId)
        val pageBytes = ctx.rpcStubRegistry.dispatchSuspend(
            session = session,
            service = SyncRpcContract.SERVICE,
            methodId = SyncRpcContract.M_LIST_CHECKPOINT_CONTACTS,
            payload = SyncRpcContract.encodeListCheckpointContacts(pageRequest),
        )
        assertTrue(ProtoCodec.decode(SyncCheckpointContactPage, requireNotNull(pageBytes)).items.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            ctx.rpcStubRegistry.dispatchSuspend(
                session = session.copy(sessionId = "different-rpc-session"),
                service = SyncRpcContract.SERVICE,
                methodId = SyncRpcContract.M_LIST_CHECKPOINT_CONTACTS,
                payload = SyncRpcContract.encodeListCheckpointContacts(pageRequest),
            )
        }
        ctx.syncEventReader.releaseSession(uid, sessionId)
    }

    @Test
    fun `checkpoint tail anchor is published behind the per user delivery gate`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("checkpoint-gate"))
        val previousTail = ctx.syncEventReader.getEventsAfter(uid, 0L, 64).lastOrNull()?.eventId ?: 0L
        val gateHeld = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val holder = async {
            ctx.syncEventDispatcher.withDeliveryGate(uid) {
                gateHeld.complete(Unit)
                releaseGate.await()
            }
        }
        gateHeld.await()
        val checkpoint = async {
            ctx.syncCheckpointService.beginCheckpoint(
                uid = uid,
                sessionId = "checkpoint-gate-session",
                claimedDatasetId = ctx.syncEventReader.datasetId,
            )
        }
        yield()
        assertFalse(checkpoint.isCompleted, "checkpoint must wait behind the per-user gate")

        ctx.pgUnitOfWork.write {
            appendEvent(uid, NotifyType.USER_UPDATED, User(uid, "checkpoint-gate", "Checkpoint Gate"))
        }
        releaseGate.complete(Unit)
        holder.await()

        assertEquals(previousTail + 1L, checkpoint.await().baseEventId)
        ctx.syncEventReader.releaseSession(uid, "checkpoint-gate-session")
    }

    @Test
    fun `replay rejects cursors below the declared compacted floor`() = runTest {
        val uid = ctx.registerUser(uniqueUsername("sync-floor"))
        ctx.pgUnitOfWork.write {
            repeat(3) { index ->
                appendEvent(uid, NotifyType.USER_UPDATED, User(uid, "floor-$index", "Floor $index"))
            }
        }
        transaction(ctx.database) {
            SyncEvents.deleteWhere {
                (SyncEvents.uid eq uid) and (SyncEvents.streamSeq lessEq 1L)
            }
            SyncStreams.update({ SyncStreams.uid eq uid }) {
                it[SyncStreams.compactedThrough] = 1L
            }
        }

        assertIs<SyncBatchResult.InvalidCursor>(
            ctx.syncEventReader.nextBatchOrActivate(
                uid,
                "floor-invalid-session",
                ctx.syncEventReader.datasetId,
                0L,
                64,
            ) { error("a compacted cursor must not activate") },
        )
        val retained = assertIs<SyncBatchResult.Events>(
            ctx.syncEventReader.nextBatchOrActivate(
                uid,
                "floor-valid-session",
                ctx.syncEventReader.datasetId,
                1L,
                64,
            ) { false },
        )
        assertEquals(listOf(2L, 3L), retained.events.map { it.eventId })
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
            ctx.syncEventReader.nextBatchOrActivate(
                ownerUid,
                "owner-valid-session",
                ctx.syncEventReader.datasetId,
                1L,
                64,
            ) { true },
        )
        assertIs<SyncBatchResult.Activated>(
            ctx.syncEventReader.nextBatchOrActivate(
                emptyUid,
                "empty-valid-session",
                ctx.syncEventReader.datasetId,
                0L,
                64,
            ) { true },
        )
        assertIs<SyncBatchResult.InvalidCursor>(
            ctx.syncEventReader.nextBatchOrActivate(
                emptyUid,
                "empty-invalid-session",
                ctx.syncEventReader.datasetId,
                1L,
                64,
            ) { true },
        )
        // 各用户的 event ID 有意重叠。数字 cursor 1 对两个账号都有效。
        assertIs<SyncBatchResult.Events>(
            ctx.syncEventReader.nextBatchOrActivate(
                otherUid,
                "other-valid-session",
                ctx.syncEventReader.datasetId,
                1L,
                64,
            ) { true },
        )
        var invalidCursorActivated = false
        assertIs<SyncBatchResult.InvalidCursor>(
            ctx.syncEventReader.nextBatchOrActivate(
                ownerUid,
                "owner-invalid-session",
                ctx.syncEventReader.datasetId,
                2L,
                64,
            ) {
                invalidCursorActivated = true
                true
            },
        )
        assertIs<SyncBatchResult.InvalidCursor>(
            ctx.syncEventReader.nextBatchOrActivate(
                ownerUid,
                "owner-max-session",
                ctx.syncEventReader.datasetId,
                Long.MAX_VALUE,
                64,
            ) {
                invalidCursorActivated = true
                true
            },
        )
        assertFalse(invalidCursorActivated, "invalid cursors must never cross the live activation barrier")
    }
}

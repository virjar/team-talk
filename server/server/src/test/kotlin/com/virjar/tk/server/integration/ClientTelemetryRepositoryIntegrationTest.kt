package com.virjar.tk.server.integration

import com.virjar.tk.server.application.admin.AdminPageRequest
import com.virjar.tk.server.application.admin.ClientTelemetryAdminService
import com.virjar.tk.server.domain.auth.CredentialDevice
import com.virjar.tk.protocol.payload.AuthRequestPayload
import com.virjar.tk.server.domain.telemetry.TelemetryBatchConflictException
import com.virjar.tk.server.domain.telemetry.TelemetryBatchDraft
import com.virjar.tk.server.domain.telemetry.TelemetryCollectionMode
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceAuthority
import com.virjar.tk.server.domain.telemetry.TelemetryDeviceIdentity
import com.virjar.tk.server.domain.telemetry.TelemetryEventDraft
import com.virjar.tk.server.domain.telemetry.TelemetryIngestStatus
import com.virjar.tk.server.domain.telemetry.TelemetryNumericRange
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueMetrics
import com.virjar.tk.server.domain.telemetry.TelemetryOutgoingQueueQuery
import com.virjar.tk.server.domain.telemetry.TelemetryRuntimeSnapshot
import com.virjar.tk.server.domain.telemetry.TelemetrySearchQuery
import com.virjar.tk.server.domain.telemetry.TelemetryStoragePolicy
import com.virjar.tk.server.domain.telemetry.TelemetryStoreCapacityException
import com.virjar.tk.server.domain.telemetry.OUTGOING_QUEUE_STORED_MESSAGE
import com.virjar.tk.server.domain.telemetry.OUTGOING_QUEUE_STORED_SEARCH_TEXT
import com.virjar.tk.server.infra.search.ClientTelemetrySearchIndex
import com.virjar.tk.protocol.telemetry.TELEMETRY_OUTGOING_QUEUE_EVENT_NAME
import com.virjar.tk.protocol.telemetry.TelemetryEventKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.LongPoint
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.FSDirectory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientTelemetryArchitectureIntegrationTest {
    companion object {
        @JvmField
        @RegisterExtension
        val ext = IntegrationTestExtension()
    }

    private val ctx get() = ext.env
    private val control get() = ctx.clientTelemetryControl
    private val events get() = ctx.clientTelemetryEvents

    @Test
    fun `event receipt and all events commit atomically and retry idempotently`() = runTest {
        val uid = ctx.registerHuman(
            uniqueUsername("telemetry-idempotent"),
            "pass123",
            "Telemetry Idempotent",
        ).uid
        val now = System.currentTimeMillis()
        val batch = batch(event(7L))

        val accepted = events.ingest(uid, "device-authoritative", batch, now, 1_024)
        assertEquals(TelemetryIngestStatus.ACCEPTED, accepted.status)
        assertEquals(7L, accepted.acceptedThroughSequence)
        assertNotNull(events.findBatchReceipt(uid, "device-authoritative", batch.batchId))

        val duplicate = events.ingest(uid, "device-authoritative", batch, now + 1L, 1_024)
        assertEquals(TelemetryIngestStatus.DUPLICATE, duplicate.status)
        assertEquals(now, duplicate.receivedAt)
        assertFailsWith<TelemetryBatchConflictException> {
            events.ingest(
                uid,
                "device-authoritative",
                batch.copy(payloadSha256 = "f".repeat(64)),
                now + 2L,
                1_024,
            )
        }
    }

    @Test
    fun `writer groups concurrent batches into one durable commit`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-group-").toFile()
        val store = ClientTelemetrySearchIndex(root, groupCommitDelayMillis = 50L)
        try {
            assertTrue(store.start())
            val before = store.durableCommitCount
            coroutineScope {
                val first = async { store.ingest("uid-a", "device-a", batch(event(1L)), 100L, 1_024) }
                val second = async { store.ingest("uid-b", "device-b", batch(event(2L)), 101L, 1_024) }
                first.await()
                second.await()
            }
            assertEquals(before + 1L, store.durableCommitCount)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `new batch cannot replace a committed event and rejects all of its events atomically`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-event-immutable-").toFile()
        val store = ClientTelemetrySearchIndex(root)
        val uid = "immutable-uid"
        val deviceId = "immutable-device"
        val sharedEventId = "event-${UUID.randomUUID()}"
        val originalEvent = event(1L).copy(
            eventId = sharedEventId,
            message = "original immutable event",
            searchText = "original immutable event",
        )
        val originalBatch = batch(originalEvent)
        val uniqueRejectedEvent = event(3L).copy(
            runId = originalEvent.runId,
            message = "must not be committed",
        )
        val conflictingBatch = batch(
            originalEvent.copy(
                sequence = 2L,
                message = "attempted replacement",
                searchText = "attempted replacement",
            ),
            uniqueRejectedEvent,
        )
        try {
            assertTrue(store.start())
            store.ingest(uid, deviceId, originalBatch, 100L, 1_024)

            assertFailsWith<TelemetryBatchConflictException> {
                store.ingest(uid, deviceId, conflictingBatch, 101L, 1_024)
            }

            assertNotNull(store.findBatchReceipt(uid, deviceId, originalBatch.batchId))
            assertNull(store.findBatchReceipt(uid, deviceId, conflictingBatch.batchId))
            val stored = store.search(
                TelemetrySearchQuery(
                    uid = uid,
                    deviceId = deviceId,
                    receivedAtFrom = 0L,
                    receivedAtUntil = Long.MAX_VALUE,
                ),
                0,
                20,
            )
            assertEquals(1L, stored.total, "the non-conflicting event in a rejected batch must not leak")
            assertEquals(sharedEventId, stored.hits.single().event.event.eventId)
            assertEquals("original immutable event", stored.hits.single().event.event.message)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `earlier batch in one writer group exclusively claims an event id`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-group-conflict-").toFile()
        val store = ClientTelemetrySearchIndex(root, groupCommitDelayMillis = 100L)
        val uid = "group-conflict-uid"
        val deviceId = "group-conflict-device"
        val sharedEventId = "event-${UUID.randomUUID()}"
        val firstBatch = batch(event(1L).copy(eventId = sharedEventId, message = "first event wins"))
        val secondBatch = batch(event(2L).copy(eventId = sharedEventId, message = "second event loses"))
        try {
            assertTrue(store.start())
            supervisorScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    store.ingest(uid, deviceId, firstBatch, 100L, 1_024)
                }
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    store.ingest(uid, deviceId, secondBatch, 101L, 1_024)
                }
                assertEquals(TelemetryIngestStatus.ACCEPTED, first.await().status)
                assertFailsWith<TelemetryBatchConflictException> { second.await() }
            }

            assertNotNull(store.findBatchReceipt(uid, deviceId, firstBatch.batchId))
            assertNull(store.findBatchReceipt(uid, deviceId, secondBatch.batchId))
            val stored = store.search(
                TelemetrySearchQuery(
                    uid = uid,
                    deviceId = deviceId,
                    receivedAtFrom = 0L,
                    receivedAtUntil = Long.MAX_VALUE,
                ),
                0,
                20,
            )
            assertEquals(1L, stored.total)
            assertEquals("first event wins", stored.hits.single().event.event.message)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `retention physically erases expired stored text and preserves the cutoff`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-retention-").toFile()
        val store = ClientTelemetrySearchIndex(root)
        val sentinel = "expired-physical-sentinel-${UUID.randomUUID()}"
        val old = batch(event(1L).copy(message = sentinel, searchText = sentinel))
        val fresh = batch(event(2L))
        try {
            assertTrue(store.start())
            store.ingest("retention-uid", "retention-device", old, 1L, 1_024)
            store.ingest("retention-uid", "retention-device", fresh, 2L, 1_024)

            assertTrue(store.deleteBefore(2L))
            assertNull(store.findBatchReceipt("retention-uid", "retention-device", old.batchId))
            assertNotNull(store.findBatchReceipt("retention-uid", "retention-device", fresh.batchId))
        } finally {
            store.close()
        }
        try {
            FSDirectory.open(root.toPath()).use { directory ->
                DirectoryReader.open(directory).use { reader ->
                    assertFalse(reader.hasDeletions(), "retention must not leave Lucene tombstones")
                    val storedFields = reader.storedFields()
                    for (docId in 0 until reader.maxDoc()) {
                        val containsExpiredText = storedFields.document(docId).fields.any { field ->
                            field.stringValue()?.contains(sentinel) == true
                        }
                        assertFalse(containsExpiredText, "expired stored text remained physically readable")
                    }
                }
            }
            ClientTelemetrySearchIndex(root).useStore { reopened ->
                assertNull(reopened.findBatchReceipt("retention-uid", "retention-device", old.batchId))
                assertNotNull(reopened.findBatchReceipt("retention-uid", "retention-device", fresh.batchId))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retention cannot unlink an index generation while an admin search still owns it`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-generation-gate-").toFile()
        val store = ClientTelemetrySearchIndex(root)
        val searchAcquired = CountDownLatch(1)
        val releaseSearch = CountDownLatch(1)
        try {
            assertTrue(store.start())
            store.ingest("gate-uid", "gate-device", batch(event(1L)), 100L, 1_024)
            store.searchLeaseHookForTest = {
                searchAcquired.countDown()
                check(releaseSearch.await(5L, TimeUnit.SECONDS))
            }
            val search = async(Dispatchers.Default) {
                store.search(
                    TelemetrySearchQuery(
                        uid = "gate-uid",
                        receivedAtFrom = 0L,
                        receivedAtUntil = Long.MAX_VALUE,
                    ),
                    0,
                    20,
                )
            }
            assertTrue(searchAcquired.await(5L, TimeUnit.SECONDS))
            val retention = async { store.deleteBefore(101L) }
            delay(100L)
            assertFalse(retention.isCompleted, "retention must wait for the pinned search generation")
            releaseSearch.countDown()
            assertEquals(1L, search.await().total)
            assertTrue(retention.await())
        } finally {
            releaseSearch.countDown()
            store.searchLeaseHookForTest = null
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `completed index reopens and invalid commit marker resets disposable events`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-reopen-").toFile()
        val batch = batch(event(1L))
        ClientTelemetrySearchIndex(root).useStore { store ->
            store.ingest("reopen-uid", "reopen-device", batch, 100L, 1_024)
        }
        ClientTelemetrySearchIndex(root).useStore { reopened ->
            assertNotNull(reopened.findBatchReceipt("reopen-uid", "reopen-device", batch.batchId))
        }

        FSDirectory.open(root.toPath()).use { directory ->
            StandardAnalyzer().use { analyzer ->
                IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                    writer.setLiveCommitData(mapOf("invalid" to "marker").entries)
                    writer.commit()
                }
            }
        }
        ClientTelemetrySearchIndex(root).useStore { reset ->
            assertNull(reset.findBatchReceipt("reopen-uid", "reopen-device", batch.batchId))
        }
        root.deleteRecursively()
    }

    @Test
    fun `valid marker with inconsistent live document count resets disposable events`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-marker-mismatch-").toFile()
        val batch = batch(event(1L))
        ClientTelemetrySearchIndex(root).useStore { store ->
            store.ingest("marker-uid", "marker-device", batch, 100L, 1_024)
        }
        FSDirectory.open(root.toPath()).use { directory ->
            val metadata = DirectoryReader.listCommits(directory).maxBy { it.generation }.userData.toMutableMap()
            metadata["teamtalk.telemetry.documentCount"] =
                (checkNotNull(metadata["teamtalk.telemetry.documentCount"]).toLong() + 1L).toString()
            StandardAnalyzer().use { analyzer ->
                IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                    writer.setLiveCommitData(metadata.entries)
                    writer.commit()
                }
            }
        }

        ClientTelemetrySearchIndex(root).useStore { reset ->
            assertNull(reset.findBatchReceipt("marker-uid", "marker-device", batch.batchId))
        }
        root.deleteRecursively()
    }

    @Test
    fun `startup validates every segment projection shape instead of trusting the merged schema`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-segment-schema-").toFile()
        val malformedRoot = Files.createTempDirectory("teamtalk-telemetry-malformed-segment-").toFile()
        val batch = batch(event(1L))
        ClientTelemetrySearchIndex(root).useStore { store ->
            store.ingest("segment-uid", "segment-device", batch, 100L, 1_024)
        }
        FSDirectory.open(malformedRoot.toPath()).use { malformedDirectory ->
            StandardAnalyzer().use { analyzer ->
                IndexWriter(
                    malformedDirectory,
                    IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE),
                ).use { writer ->
                    writer.addDocument(Document().apply {
                        add(StringField("docKey", "malformed", Field.Store.YES))
                    })
                    writer.commit()
                }
            }
        }
        FSDirectory.open(root.toPath()).use { directory ->
            FSDirectory.open(malformedRoot.toPath()).use { malformedDirectory ->
                val metadata = DirectoryReader.listCommits(directory).maxBy { it.generation }.userData.toMutableMap()
                StandardAnalyzer().use { analyzer ->
                    IndexWriter(
                        directory,
                        IndexWriterConfig(analyzer).setMergePolicy(org.apache.lucene.index.NoMergePolicy.INSTANCE),
                    ).use { writer ->
                        writer.addIndexes(malformedDirectory)
                        metadata["teamtalk.telemetry.documentCount"] =
                            (checkNotNull(metadata["teamtalk.telemetry.documentCount"]).toLong() + 1L).toString()
                        writer.setLiveCommitData(metadata.entries)
                        writer.commit()
                    }
                }
            }
            DirectoryReader.open(directory).use { reader ->
                assertEquals(
                    org.apache.lucene.index.IndexOptions.DOCS,
                    org.apache.lucene.index.FieldInfos.getMergedFieldInfos(reader)
                        .fieldInfo("docKey")
                        .indexOptions,
                )
                assertTrue(reader.leaves().any { leaf ->
                    leaf.reader().fieldInfos.size() == 1 &&
                        leaf.reader().fieldInfos.fieldInfo("docKey") != null
                })
            }
        }

        ClientTelemetrySearchIndex(root).useStore { reset ->
            assertNull(reset.findBatchReceipt("segment-uid", "segment-device", batch.batchId))
        }
        root.deleteRecursively()
        malformedRoot.deleteRecursively()
    }

    @Test
    fun `startup rejects a commit that still contains tombstoned telemetry`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-orphan-receipt-").toFile()
        val uid = "orphan-uid"
        val deviceId = "orphan-device"
        val event = event(7L)
        val batch = batch(event)
        ClientTelemetrySearchIndex(root).useStore { store ->
            store.ingest(uid, deviceId, batch, 100L, 1_024)
        }

        FSDirectory.open(root.toPath()).use { directory ->
            val metadata = DirectoryReader.listCommits(directory).maxBy { it.generation }.userData.toMutableMap()
            val eventBytes = DirectoryReader.open(directory).use { reader ->
                reader.leaves().asSequence().flatMap { leaf ->
                    val liveDocs = leaf.reader().liveDocs
                    (0 until leaf.reader().maxDoc()).asSequence()
                        .filter { liveDocs == null || liveDocs.get(it) }
                        .map { leaf.reader().storedFields().document(it) }
                }.single { it.get("docType") == "event" }
                    .getField("accountedBytes").numericValue().toLong()
            }
            StandardAnalyzer().use { analyzer ->
                IndexWriter(
                    directory,
                    IndexWriterConfig(analyzer).setMergePolicy(org.apache.lucene.index.NoMergePolicy.INSTANCE),
                ).use { writer ->
                    writer.deleteDocuments(Term("docKey", "event\u0000$uid\u0000$deviceId\u0000${event.eventId}"))
                    metadata["teamtalk.telemetry.documentCount"] =
                        (checkNotNull(metadata["teamtalk.telemetry.documentCount"]).toLong() - 1L).toString()
                    metadata["teamtalk.telemetry.accountedBytes"] =
                        (checkNotNull(metadata["teamtalk.telemetry.accountedBytes"]).toLong() - eventBytes).toString()
                    writer.setLiveCommitData(metadata.entries)
                    writer.commit()
                }
            }
        }

        ClientTelemetrySearchIndex(root).useStore { reset ->
            assertNull(reset.findBatchReceipt(uid, deviceId, batch.batchId))
        }
        root.deleteRecursively()
    }

    @Test
    fun `startup resets an index containing a field outside the fixed schema`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-unknown-field-").toFile()
        val uid = "unknown-field-uid"
        val deviceId = "unknown-field-device"
        val batch = batch(event(4L))
        ClientTelemetrySearchIndex(root).useStore { store ->
            store.ingest(uid, deviceId, batch, 100L, 1_024)
        }

        FSDirectory.open(root.toPath()).use { directory ->
            val metadata = DirectoryReader.listCommits(directory).maxBy { it.generation }.userData.toMutableMap()
            StandardAnalyzer().use { analyzer ->
                IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                    writer.addDocument(Document().apply {
                        add(StringField("unexpectedField", "unexpected", Field.Store.NO))
                    })
                    metadata["teamtalk.telemetry.documentCount"] =
                        (checkNotNull(metadata["teamtalk.telemetry.documentCount"]).toLong() + 1L).toString()
                    writer.setLiveCommitData(metadata.entries)
                    writer.commit()
                }
            }
        }

        ClientTelemetrySearchIndex(root).useStore { reset ->
            assertNull(reset.findBatchReceipt(uid, deviceId, batch.batchId))
        }
        root.deleteRecursively()
    }

    @Test
    fun `hard document capacity rejects before consuming the filesystem`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-capacity-").toFile()
        ClientTelemetrySearchIndex(root, maxDocuments = 2L).useStore { store ->
            store.ingest("capacity-uid", "capacity-device", batch(event(1L)), 100L, 1_024)
        }
        val store = ClientTelemetrySearchIndex(root, maxDocuments = 2L)
        try {
            assertTrue(store.start())
            assertFailsWith<TelemetryStoreCapacityException> {
                store.ingest("capacity-uid", "capacity-device", batch(event(2L)), 101L, 1_024)
            }
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `physical quota is remeasured and pressure clears disposable events without tombstones`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-physical-capacity-").toFile()
        val oldBatch = batch(event(1L))
        val freshBatch = batch(event(2L))
        ClientTelemetrySearchIndex(root, groupCommitDelayMillis = 100L).useStore { store ->
            supervisorScope {
                val old = async(start = CoroutineStart.UNDISPATCHED) {
                    store.ingest("physical-uid", "physical-device", oldBatch, 100L, 1_024)
                }
                val fresh = async(start = CoroutineStart.UNDISPATCHED) {
                    store.ingest("physical-uid", "physical-device", freshBatch, 200L, 1_024)
                }
                old.await()
                fresh.await()
            }
        }
        val committedPhysicalBytes = physicalBytes(root)
        assertTrue(committedPhysicalBytes > 0L)
        val physicalLimit = Math.multiplyExact(committedPhysicalBytes, 2L)
        val store = ClientTelemetrySearchIndex(root, maxPhysicalBytes = physicalLimit)
        try {
            assertTrue(store.start())
            val pressureBatch = batch(event(3L))
            assertEquals(
                TelemetryIngestStatus.ACCEPTED,
                store.ingest("physical-uid", "physical-device", pressureBatch, 201L, 1_024).status,
            )
            assertNull(store.findBatchReceipt("physical-uid", "physical-device", oldBatch.batchId))
            assertNull(store.findBatchReceipt("physical-uid", "physical-device", freshBatch.batchId))
            assertNotNull(store.findBatchReceipt("physical-uid", "physical-device", pressureBatch.batchId))
            assertTrue(store.deleteBefore(150L))
            FSDirectory.open(root.toPath()).use { directory ->
                DirectoryReader.open(directory).use { reader ->
                    assertFalse(reader.hasDeletions(), "retention must reclaim deleted segments physically")
                }
            }
            val recoveredBatch = batch(event(4L))
            assertEquals(
                TelemetryIngestStatus.ACCEPTED,
                store.ingest("physical-uid", "physical-device", recoveredBatch, 202L, 1_024).status,
            )
            assertNull(store.findBatchReceipt("physical-uid", "physical-device", oldBatch.batchId))
            assertNull(store.findBatchReceipt("physical-uid", "physical-device", freshBatch.batchId))
            assertNotNull(store.findBatchReceipt("physical-uid", "physical-device", recoveredBatch.batchId))
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `ingest rejects a batch whose event sequence is not strictly increasing`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-sequence-admission-").toFile()
        val runId = "run-${UUID.randomUUID()}"
        val invalid = batch(
            event(2L).copy(runId = runId),
            event(1L).copy(runId = runId),
        )
        ClientTelemetrySearchIndex(root).useStore { store ->
            assertFailsWith<IllegalArgumentException> {
                store.ingest("sequence-uid", "sequence-device", invalid, 100L, 1_024)
            }
            assertNull(store.findBatchReceipt("sequence-uid", "sequence-device", invalid.batchId))
        }
        root.deleteRecursively()
    }

    @Test
    fun `ingest rejects a batch containing multiple run ids`() = runTest {
        val root = Files.createTempDirectory("teamtalk-telemetry-run-admission-").toFile()
        val invalid = batch(event(1L), event(2L))
        ClientTelemetrySearchIndex(root).useStore { store ->
            assertFailsWith<IllegalArgumentException> {
                store.ingest("run-uid", "run-device", invalid, 100L, 1_024)
            }
            assertNull(store.findBatchReceipt("run-uid", "run-device", invalid.batchId))
        }
        root.deleteRecursively()
    }

    @Test
    fun `uid and exact device policies resolve in one batch and expire to baseline`() = runTest {
        val owner = registerTelemetryOwner("telemetry-policy", "policy-device-a")
        val uid = owner.uid
        val secondAuthority = issueTelemetryAuthority(owner, "policy-device-b")
        val now = System.currentTimeMillis()
        control.refreshDevice(owner.authority, runtime(), now, null)
        control.refreshDevice(secondAuthority, runtime(), now, null)
        val wide = control.enableDiagnosticPolicy(
            uid,
            null,
            "investigate a reproducible client fault",
            now + 60_000L,
            "test-admin",
            now,
        )
        assertEquals(TelemetryCollectionMode.DIAGNOSTIC, wide.mode)

        val identities = setOf(
            TelemetryDeviceIdentity(uid, "policy-device-a"),
            TelemetryDeviceIdentity(uid, "policy-device-b"),
        )
        assertTrue(control.effectivePolicies(identities, now + 1L).values.all {
            it.mode == TelemetryCollectionMode.DIAGNOSTIC
        })
        val exact = control.enableDiagnosticPolicy(
            uid,
            "policy-device-a",
            "temporarily inspect only one installation",
            now + 60_000L,
            "test-admin",
            now + 2L,
        )
        val exactBaseline = assertNotNull(
            control.disablePolicy(checkNotNull(exact.policyId), "test-admin", now + 3L),
        )
        val exactTerminal = control.effectivePolicies(identities, now + 4L)
        assertEquals(
            TelemetryCollectionMode.BASELINE,
            exactTerminal.getValue(TelemetryDeviceIdentity(uid, "policy-device-a")).mode,
        )
        assertEquals(
            TelemetryCollectionMode.DIAGNOSTIC,
            exactTerminal.getValue(TelemetryDeviceIdentity(uid, "policy-device-b")).mode,
        )
        val laterWide = control.enableDiagnosticPolicy(
            uid,
            null,
            "broad collection changed after exact baseline terminal",
            now + 60_000L,
            "test-admin",
            now + 3L,
        )
        assertTrue(laterWide.revision > exactBaseline.revision)
        val exactAfterLaterWide = control.effectivePolicy(uid, "policy-device-a", now + 5L)
        assertEquals(TelemetryCollectionMode.BASELINE, exactAfterLaterWide.mode)
        assertTrue(exactAfterLaterWide.updatedAt >= laterWide.revision)
        assertEquals(1, control.expirePolicies(now + 60_001L, 100))
        assertTrue(control.effectivePolicies(identities, now + 60_001L).values.all {
            it.mode == TelemetryCollectionMode.BASELINE
        })
    }

    @Test
    fun `Chinese search returns complete safe event context directly from Lucene`() = runTest {
        val uid = "search-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val batch = batch(event(2L))
        events.ingest(uid, "search-device", batch, now, 1_024)
        val result = events.search(
            TelemetrySearchQuery(
                keyword = "客户端故障",
                uid = uid,
                deviceId = "search-device",
                platform = "DESKTOP",
                category = "FAULT",
                eventName = "fault.reported",
                receivedAtFrom = now,
                receivedAtUntil = now,
            ),
            0,
            20,
        )
        assertEquals(1L, result.total)
        assertEquals(batch.events.single().eventId, result.hits.single().event.event.eventId)
        assertEquals("客户端故障，可安全展示", result.hits.single().event.event.message)
    }

    @Test
    fun `administrative queue query retains all typed metrics and filters numerically`() = runTest {
        val uid = UUID.randomUUID().toString()
        val deviceId = "queue-admin-device"
        val now = System.currentTimeMillis()
        val expected = TelemetryOutgoingQueueMetrics(0, 1, 3, 60_000L, 9L)
        val queueEvent = TelemetryEventDraft(
            eventId = "event-${UUID.randomUUID()}",
            runId = "run-${UUID.randomUUID()}",
            sequence = 1L,
            occurredAt = now,
            category = TelemetryEventKind.OUTGOING_QUEUE.name,
            eventName = TELEMETRY_OUTGOING_QUEUE_EVENT_NAME,
            message = OUTGOING_QUEUE_STORED_MESSAGE,
            searchText = OUTGOING_QUEUE_STORED_SEARCH_TEXT,
            outgoingQueue = expected,
        )
        events.ingest(uid, deviceId, batch(queueEvent), now, 1_024)

        val page = ClientTelemetryAdminService(control, events, ctx.userRepo, clock = { now }).searchEvents(
            actor = "test-admin",
            keyword = null,
            uid = uid,
            deviceId = deviceId,
            phone = null,
            platform = null,
            osName = null,
            osVersion = null,
            appVersion = null,
            gitCommit = null,
            category = TelemetryEventKind.OUTGOING_QUEUE.name,
            eventName = TELEMETRY_OUTGOING_QUEUE_EVENT_NAME,
            start = now,
            end = now,
            pagination = AdminPageRequest(page = 1, size = 20),
            outgoingQueue = TelemetryOutgoingQueueQuery(
                pendingCount = TelemetryNumericRange(0L, 0L),
                retryWaitCount = TelemetryNumericRange(1L, 1L),
                terminalFailedCount = TelemetryNumericRange(3L, 3L),
                oldestActiveAgeMillis = TelemetryNumericRange(60_000L, 60_000L),
                maxAttemptCount = TelemetryNumericRange(9L, 9L),
            ),
        )

        assertEquals(1L, page.total)
        val item = page.items.single()
        assertEquals(ClientTelemetryAdminService.OutgoingQueueItem(0, 1, 3, 60_000L, 9L), item.outgoingQueue)
        assertEquals(OUTGOING_QUEUE_STORED_MESSAGE, item.message)
    }

    @Test
    fun `literal em remains plain text while HTTP DTO exposes validated highlight spans`() = runTest {
        val uid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val literalText = "literal <em> marker stays plain"
        val batch = batch(
            event(3L).copy(
                message = literalText,
                searchText = literalText,
            ),
        )
        events.ingest(uid, "highlight-device", batch, now, 1_024)

        val search = events.search(
            TelemetrySearchQuery(
                keyword = "marker",
                uid = uid,
                receivedAtFrom = now,
                receivedAtUntil = now,
            ),
            0,
            20,
        )
        val highlight = assertNotNull(search.hits.single().highlight)
        assertEquals(literalText, highlight.text)
        assertTrue(highlight.spans.any { span ->
            highlight.text.substring(span.start, span.end) == "marker"
        })
        val literalTag = highlight.text.indexOf("<em>") until highlight.text.indexOf("<em>") + 4
        assertTrue(highlight.spans.none { span -> span.start < literalTag.last + 1 && span.end > literalTag.first })

        val page = ClientTelemetryAdminService(control, events, ctx.userRepo, clock = { now }).searchEvents(
            actor = "test-admin",
            keyword = "marker",
            uid = uid,
            deviceId = null,
            phone = null,
            platform = null,
            osName = null,
            osVersion = null,
            appVersion = null,
            gitCommit = null,
            category = null,
            eventName = null,
            start = now,
            end = now,
            pagination = AdminPageRequest(page = 1, size = 20),
        )
        val encoded = Json.encodeToString(page)
        assertTrue(encoded.contains("\"highlight\":{\"text\":"))
        assertFalse(encoded.contains("\"highlight\":\""))
        assertEquals(highlight.text, page.items.single().highlight?.text)
    }

    @Test
    fun `device administration resolves phone without persisting it in telemetry`() = runTest {
        val phone = "1390000${(10_000..99_999).random()}"
        val owner = registerTelemetryOwner("telemetry-phone", "phone-device", phone)
        val uid = owner.uid
        control.refreshDevice(owner.authority, runtime(), System.currentTimeMillis(), null)
        val other = registerTelemetryOwner("telemetry-phone-collision", "contains-$uid")
        control.refreshDevice(other.authority, runtime(), System.currentTimeMillis(), null)
        val service = ClientTelemetryAdminService(control, events, ctx.userRepo)

        val page = service.pageDevices(null, phone, AdminPageRequest(page = 1, size = 20))
        assertEquals(1L, page.total)
        assertEquals(uid, page.items.single().uid)
        assertEquals("phone-device", page.items.single().deviceId)

        val unknown = service.pageDevices(
            query = null,
            phone = "unknown-$phone",
            pagination = AdminPageRequest(page = 1, size = 20),
        )
        assertEquals(0L, unknown.total)
        assertTrue(unknown.items.isEmpty())
    }

    @Test
    fun `late exact retry updates last seen without regressing latest runtime`() = runTest {
        val deviceId = "runtime-order-device"
        val owner = registerTelemetryOwner("telemetry-runtime-order", deviceId)
        val uid = owner.uid
        val firstObservedAt = System.currentTimeMillis()
        val oldRuntime = runtime().copy(appVersion = "1.0.0", buildIdentity = "old-runtime")
        val latestRuntime = runtime().copy(appVersion = "2.0.0", buildIdentity = "latest-runtime")

        control.refreshDevice(owner.authority, oldRuntime, firstObservedAt, firstObservedAt)
        control.refreshDevice(
            owner.authority,
            latestRuntime,
            firstObservedAt + 100L,
            firstObservedAt + 100L,
        )
        control.refreshDevice(
            authority = owner.authority,
            runtime = oldRuntime,
            receivedAt = firstObservedAt + 200L,
            acceptedEventAt = firstObservedAt,
            runtimeObservedAt = firstObservedAt + 100L,
        )

        val profile = checkNotNull(control.findDevice(uid, deviceId))
        assertEquals("2.0.0", profile.runtime.appVersion)
        assertEquals("latest-runtime", profile.runtime.buildIdentity)
        assertEquals(firstObservedAt + 200L, profile.lastSeenAt)
        assertEquals(firstObservedAt + 100L, profile.lastEventAt)
    }

    @Test
    fun `administrative event ranges outside retention return an empty page without inversion errors`() {
        val now = TelemetryStoragePolicy.RETENTION_MILLIS + 1_000L
        val service = ClientTelemetryAdminService(control, events, ctx.userRepo, clock = { now })

        fun search(start: Long?, end: Long?) = service.searchEvents(
            actor = "test-admin",
            keyword = null,
            uid = null,
            deviceId = null,
            phone = null,
            platform = null,
            osName = null,
            osVersion = null,
            appVersion = null,
            gitCommit = null,
            category = null,
            eventName = null,
            start = start,
            end = end,
            pagination = AdminPageRequest(page = 1, size = 20),
        )

        assertEquals(0L, search(start = 0L, end = 999L).total)
        assertEquals(0L, search(start = now + 1L, end = now + 1_000L).total)
        assertFailsWith<IllegalArgumentException> { search(start = 10L, end = 9L) }
    }

    private fun batch(vararg events: TelemetryEventDraft): TelemetryBatchDraft = TelemetryBatchDraft(
        batchId = "batch-${UUID.randomUUID()}",
        payloadSha256 = UUID.randomUUID().toString().replace("-", "").padEnd(64, '0'),
        createdAt = System.currentTimeMillis(),
        runtime = runtime(),
        events = events.toList(),
    )

    private fun runtime() = TelemetryRuntimeSnapshot(
        platform = "DESKTOP",
        osName = "macOS",
        osVersion = "15.6",
        architecture = "arm64",
        deviceModel = "Mac",
        appVersion = "0.1.0",
        buildNumber = "1",
        gitCommit = "abcdef0",
        buildIdentity = "desktop-test",
        buildTime = "2026-08-27T00:00:00Z",
        protocolVersion = 1,
        distribution = "test",
    )

    private fun event(sequence: Long) = TelemetryEventDraft(
        eventId = "event-${UUID.randomUUID()}",
        runId = "run-${UUID.randomUUID()}",
        sequence = sequence,
        occurredAt = System.currentTimeMillis(),
        category = "FAULT",
        eventName = "fault.reported",
        message = "客户端故障，可安全展示",
        searchText = "客户端 故障 client fault",
    )

    private suspend fun registerTelemetryOwner(
        prefix: String,
        deviceId: String,
        phone: String? = null,
    ): TelemetryTestOwner {
        val username = uniqueUsername(prefix)
        val password = "pass123"
        val registration = ctx.registrationService.register(
            username = username,
            password = password,
            name = prefix,
            phone = phone,
            device = CredentialDevice(deviceId, "Telemetry test", null, 0),
        )
        return TelemetryTestOwner(
            uid = registration.user.uid,
            username = username,
            password = password,
            authority = registration.credentials.principal.toTelemetryAuthority(),
        )
    }

    private suspend fun issueTelemetryAuthority(
        owner: TelemetryTestOwner,
        deviceId: String,
    ): TelemetryDeviceAuthority {
        val result = ctx.authService.authenticate(
            AuthRequestPayload(
                authType = 0,
                username = owner.username,
                password = owner.password,
                deviceId = deviceId,
                correlationId = "telemetry-authority-auth-0001",
                connectionGeneration = 1L,
            ),
        )
        return checkNotNull(result.principal).toTelemetryAuthority()
    }

    private fun com.virjar.tk.server.domain.auth.TokenInfo.toTelemetryAuthority() = TelemetryDeviceAuthority(
        uid = uid,
        deviceId = deviceId,
        userCredentialEpoch = userCredentialEpoch,
        deviceCredentialEpoch = deviceCredentialEpoch,
    )

    private suspend fun <T> ClientTelemetrySearchIndex.useStore(
        block: suspend (ClientTelemetrySearchIndex) -> T,
    ): T {
        assertTrue(start())
        return try {
            block(this)
        } finally {
            close()
        }
    }

    private fun physicalBytes(root: java.io.File): Long = root.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    private data class TelemetryTestOwner(
        val uid: String,
        val username: String,
        val password: String,
        val authority: TelemetryDeviceAuthority,
    )
}

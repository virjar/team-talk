package com.virjar.tk.desktop

import com.virjar.tk.shared.client.DeploymentIdentity
import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import com.virjar.tk.app.navigation.feature.document.DocumentDraftOwnerKey
import com.virjar.tk.app.navigation.feature.document.DocumentDraftPayload
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadRetryableException
import com.virjar.tk.app.navigation.feature.document.DocumentDraftReadStatus
import com.virjar.tk.app.navigation.feature.document.DocumentDraftRecord
import com.virjar.tk.app.navigation.feature.document.DocumentDraftRecordSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDocumentDraftPersistenceTest {
    private val deployment = DeploymentIdentity.from(
        tcpHost = "im.test.example",
        tcpPort = 5100,
        serverUrl = "https://files.test.example/api",
    )

    private fun ownerKey(
        uid: String = "user-a",
        identity: DeploymentIdentity = deployment,
        datasetId: String = DATASET_A,
    ) = DocumentDraftOwnerKey(identity.fingerprint, datasetId, uid)

    @Test
    fun `manifest and independent records survive recreation in a private owner namespace`() =
        withPrivateDataDirectory("desktop-document-draft-round-trip") { directory ->
            val uid = "../../other-user/账号@example.com"
            val ownerKey = ownerKey(uid)
            val first = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            val payload = draftPayload(
                manifest = "workspace-manifest",
                records = linkedMapOf(
                    "tab-first" to "first body",
                    "tab-second" to "第二份正文🙂",
                ),
                activeRecoveryKeys = setOf("tab-first", "tab-second", "space-command-pending"),
            )

            assertTrue(first.write(ownerKey) { payload })
            assertTrue(first.flush())

            val reopened = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            var callbackCount = 0
            var escapedSource: DocumentDraftRecordSource? = null
            assertEquals(
                DocumentDraftReadStatus.AVAILABLE,
                reopened.read(ownerKey) { source ->
                    callbackCount += 1
                    escapedSource = source
                    assertEquals("workspace-manifest", source.manifest)
                    assertEquals(emptySet(), source.tombstones)
                    assertEquals("first body".toByteArray().size.toLong(), source.recordByteCount("tab-first"))
                    assertEquals(
                        "第二份正文🙂".toByteArray().size.toLong(),
                        source.recordByteCount("tab-second"),
                    )
                    assertEquals("first body", source.readRecord("tab-first"))
                    assertEquals("第二份正文🙂", source.readRecord("tab-second"))
                    assertNull(source.readRecord("tab-absent"))
                },
            )
            assertEquals(1, callbackCount)
            assertFailsWith<IllegalStateException> { escapedSource!!.manifest }

            val namespace = desktopDocumentDraftOwnerNamespace(ownerKey)
            assertTrue(namespace.matches(Regex("u-[0-9a-f]{64}")))
            val storageDirectory = directory.resolve(
                "document-drafts/v3/deployments/${deployment.fingerprint}/owners/$namespace",
            )
            val persistedManifest = storageDirectory.resolve("manifest")
            assertTrue(Files.isRegularFile(persistedManifest, LinkOption.NOFOLLOW_LINKS))
            assertEquals(2L, countRecordFiles(storageDirectory))
            val relativePath = directory.relativize(persistedManifest).toString()
            assertFalse("other-user" in relativePath)
            assertFalse("账号" in relativePath)
            if (Files.getFileAttributeView(
                    persistedManifest,
                    PosixFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ) != null
            ) {
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(persistedManifest),
                )
            }
            assertTrue(first.retirePreservingDraft())
            first.sealPreservedDraft()
            assertTrue(reopened.retireAndDelete())
        }

    @Test
    fun `deployment dataset and authenticated owner cannot read each other's records`() =
        withPrivateDataDirectory("desktop-document-draft-isolation") { directory ->
            val deploymentB = DeploymentIdentity.from(
                tcpHost = deployment.tcpHost,
                tcpPort = deployment.tcpPort,
                serverUrl = "https://other-files.test.example/api",
            )
            val ownerAKey = ownerKey()
            val ownerA = DesktopDocumentDraftPersistence(directory.toFile(), ownerAKey)
            assertTrue(ownerA.write(ownerAKey) { draftPayload("owner-a", "tab-a" to "body") })
            assertTrue(ownerA.retirePreservingDraft())
            ownerA.sealPreservedDraft()

            val ownerBKey = ownerKey("user-b")
            val ownerB = DesktopDocumentDraftPersistence(directory.toFile(), ownerBKey)
            assertEquals(DocumentDraftReadStatus.ABSENT, ownerB.read(ownerBKey) { error("unexpected") })
            val deploymentBKey = ownerKey(identity = deploymentB)
            val otherDeployment = DesktopDocumentDraftPersistence(directory.toFile(), deploymentBKey)
            assertEquals(
                DocumentDraftReadStatus.ABSENT,
                otherDeployment.read(deploymentBKey) { error("unexpected") },
            )
            val datasetBKey = ownerKey(datasetId = DATASET_B)
            val otherDataset = DesktopDocumentDraftPersistence(directory.toFile(), datasetBKey)
            assertEquals(
                DocumentDraftReadStatus.ABSENT,
                otherDataset.read(datasetBKey) { error("unexpected") },
            )
            assertNotEquals(
                desktopDocumentDraftOwnerNamespace(ownerAKey),
                desktopDocumentDraftOwnerNamespace(deploymentBKey),
            )
            assertNotEquals(
                desktopDocumentDraftOwnerNamespace(ownerAKey),
                desktopDocumentDraftOwnerNamespace(datasetBKey),
            )
            assertTrue(ownerB.retirePreservingDraft())
            ownerB.sealPreservedDraft()
            assertTrue(otherDeployment.retirePreservingDraft())
            otherDeployment.sealPreservedDraft()
            assertTrue(otherDataset.retirePreservingDraft())
            otherDataset.sealPreservedDraft()
        }

    @Test
    fun `session bound persistence rejects every foreign owner operation before payload evaluation`() =
        withPrivateDataDirectory("desktop-document-draft-owner-bound") { directory ->
            val ownerKey = ownerKey()
            val persistence = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            val foreignOwnerKey = ownerKey("user-b")
            var evaluated = false

            assertFailsWith<IllegalArgumentException> { persistence.read(foreignOwnerKey) {} }
            assertFailsWith<IllegalArgumentException> {
                persistence.write(foreignOwnerKey) {
                    evaluated = true
                    draftPayload("foreign", "tab-a" to "foreign")
                }
            }
            assertFailsWith<IllegalArgumentException> {
                persistence.tombstone(foreignOwnerKey, setOf("tab-a"))
            }
            assertFailsWith<IllegalArgumentException> { persistence.delete(foreignOwnerKey) }
            assertFalse(evaluated)
            assertTrue(persistence.retireAndDelete())
        }

    @Test
    fun `failed oversized record preserves the last published manifest`() =
        withPrivateDataDirectory("desktop-document-draft-bounds") { directory ->
            val ownerKey = ownerKey()
            val persistence = DesktopDocumentDraftPersistence(
                dataDir = directory.toFile(),
                ownerKey = ownerKey,
                maxRecordBytes = 32,
                maxTotalRecordBytes = 32,
            )
            assertTrue(persistence.write(ownerKey) { draftPayload("old", "tab-a" to "old body") })
            assertTrue(persistence.flush())

            assertTrue(persistence.write(ownerKey) { draftPayload("oversized", "tab-a" to "x".repeat(33)) })
            assertFalse(persistence.flush())
            assertRead(persistence, ownerKey, "old", mapOf("tab-a" to "old body"))
            assertFalse(persistence.flush())

            val reopened = DesktopDocumentDraftPersistence(
                dataDir = directory.toFile(),
                ownerKey = ownerKey,
                maxRecordBytes = 32,
                maxTotalRecordBytes = 32,
            )
            assertRead(reopened, ownerKey, "old", mapOf("tab-a" to "old body"))
            assertTrue(reopened.write(ownerKey) { draftPayload("recovered", "tab-a" to "new body") })
            assertTrue(reopened.flush())
            assertRead(reopened, ownerKey, "recovered", mapOf("tab-a" to "new body"))
            assertFalse(persistence.retirePreservingDraft())
            persistence.sealPreservedDraft()
            assertTrue(reopened.retireAndDelete())
        }

    @Test
    fun `records within individual bounds but over total budget preserve last known good`() =
        withPrivateDataDirectory("desktop-document-draft-total-bounds") { directory ->
            val ownerKey = ownerKey()
            val persistence = DesktopDocumentDraftPersistence(
                dataDir = directory.toFile(),
                ownerKey = ownerKey,
                maxRecordBytes = 8,
                maxTotalRecordBytes = 10,
            )
            assertTrue(persistence.write(ownerKey) { draftPayload("old", "tab-old" to "lkg") })
            assertTrue(persistence.flush())

            assertTrue(
                persistence.write(ownerKey) {
                    draftPayload(
                        "over total",
                        linkedMapOf("tab-a" to "123456", "tab-b" to "abcdef"),
                    )
                },
            )
            assertFalse(persistence.flush())
            assertEquals(1L, countRecordFiles(draftStorageDirectory(directory, ownerKey)))

            val reopened = DesktopDocumentDraftPersistence(
                dataDir = directory.toFile(),
                ownerKey = ownerKey,
                maxRecordBytes = 8,
                maxTotalRecordBytes = 10,
            )
            assertRead(reopened, ownerKey, "old", mapOf("tab-old" to "lkg"))
            assertFalse(persistence.retirePreservingDraft())
            persistence.sealPreservedDraft()
            assertTrue(reopened.retireAndDelete())
        }

    @Test
    fun `preserve retirement rejects late frames and can still be monotonically upgraded to discard`() =
        withPrivateDataDirectory("desktop-document-draft-retirement") { directory ->
            val ownerKey = ownerKey()
            val persistence = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertTrue(persistence.write(ownerKey) { draftPayload("before dispose", "tab-a" to "draft") })
            assertTrue(persistence.retirePreservingDraft())
            var latePayloadEvaluated = false

            assertFalse(
                persistence.write(ownerKey) {
                    latePayloadEvaluated = true
                    draftPayload("late", "tab-a" to "stale")
                },
            )
            assertFalse(latePayloadEvaluated)
            assertTrue(persistence.retireAndDelete())

            val reopened = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertEquals(DocumentDraftReadStatus.ABSENT, reopened.read(ownerKey) { error("unexpected") })
            assertTrue(reopened.retireAndDelete())
        }

    @Test
    fun `writes retain one pending payload and encode only the latest pending records`() {
        val storage = InMemoryDraftStorage()
        val writeStarted = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        storage.beforeReplace = { index ->
            if (index == 1) {
                writeStarted.countDown()
                assertTrue(allowWrite.await(5, TimeUnit.SECONDS))
            }
        }
        val ownerKey = ownerKey()
        val persistence = DesktopDocumentDraftPersistence(storage, ownerKey)
        var obsoletePayloadEvaluated = false
        var obsoleteRecordEvaluated = false

        assertTrue(persistence.write(ownerKey) { draftPayload("first", "tab-a" to "first") })
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
        assertTrue(
            persistence.write(ownerKey) {
                obsoletePayloadEvaluated = true
                DocumentDraftPayload(
                    manifest = "obsolete",
                    records = listOf(DocumentDraftRecord("tab-a") {
                        obsoleteRecordEvaluated = true
                        "obsolete"
                    }),
                    activeRecoveryKeys = setOf("tab-a"),
                )
            },
        )
        assertTrue(persistence.write(ownerKey) { draftPayload("latest", "tab-a" to "latest") })

        val flush = CompletableFuture.supplyAsync(persistence::flush)
        assertFalse(flush.isDone)
        allowWrite.countDown()

        assertTrue(flush.get(5, TimeUnit.SECONDS))
        assertFalse(obsoletePayloadEvaluated)
        assertFalse(obsoleteRecordEvaluated)
        assertEquals(listOf("first", "latest"), storage.publishedManifests.toList())
        assertRead(persistence, ownerKey, "latest", mapOf("tab-a" to "latest"))
        assertTrue(persistence.retireAndDelete())
    }

    @Test
    fun `logout deletion linearizes after an active writer and drops queued resurrection`() {
        val storage = InMemoryDraftStorage()
        val writeStarted = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        storage.beforeReplace = { index ->
            if (index == 1) {
                writeStarted.countDown()
                allowWrite.await()
            }
        }
        val ownerKey = ownerKey()
        val persistence = DesktopDocumentDraftPersistence(storage, ownerKey)
        var latePayloadEvaluated = false

        assertTrue(persistence.write(ownerKey) { draftPayload("active", "tab-a" to "active") })
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
        assertTrue(
            persistence.write(ownerKey) {
                latePayloadEvaluated = true
                draftPayload("queued", "tab-a" to "queued")
            },
        )
        val retirement = CompletableFuture.supplyAsync(persistence::retireAndDelete)
        val retirementGateObserved = CompletableFuture.supplyAsync {
            eventually {
                !persistence.write(ownerKey) { draftPayload("race", "tab-a" to "race") }
            }
        }
        try {
            assertTrue(retirementGateObserved.get(5, TimeUnit.SECONDS))
        } finally {
            // 始终释放 storage worker，这样编排失败会由测试线程报告，而不是从守护写线程泄漏断言。
            allowWrite.countDown()
        }

        assertTrue(retirement.get(5, TimeUnit.SECONDS))
        assertFalse(latePayloadEvaluated)
        assertNull(storage.snapshot)
    }

    @Test
    fun `timed out logout still drops process pending so a successor cannot resurrect it`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val occupyingReadStarted = CountDownLatch(1)
        val releaseOccupyingRead = CountDownLatch(1)
        storage.beforeRead = {
            occupyingReadStarted.countDown()
            assertTrue(releaseOccupyingRead.await(5, TimeUnit.SECONDS))
        }
        val blocker = DesktopDocumentDraftPersistence(storage, ownerKey)
        val occupyingRead = CompletableFuture.supplyAsync {
            blocker.read(ownerKey) { error("unexpected") }
        }
        assertTrue(occupyingReadStarted.await(5, TimeUnit.SECONDS))

        var discardedPayloadEvaluated = false
        val retiring = DesktopDocumentDraftPersistence(
            storage = storage,
            ownerKey = ownerKey,
            flushTimeoutMillis = 25,
        )
        assertTrue(
            retiring.write(ownerKey) {
                discardedPayloadEvaluated = true
                draftPayload("must not resurrect", "tab-a" to "secret")
            },
        )
        assertFalse(retiring.retireAndDelete())
        val successor = DesktopDocumentDraftPersistence(storage, ownerKey)
        releaseOccupyingRead.countDown()

        assertEquals(DocumentDraftReadStatus.ABSENT, occupyingRead.get(5, TimeUnit.SECONDS))
        assertEquals(
            DocumentDraftReadStatus.ABSENT,
            successor.read(ownerKey) { error("unexpected") },
        )
        assertFalse(discardedPayloadEvaluated)
        assertTrue(blocker.retirePreservingDraft())
        blocker.sealPreservedDraft()
        assertTrue(successor.retireAndDelete())
    }

    @Test
    fun `tombstone is retained for an active identity and compacted only after a newer manifest drops it`() =
        withPrivateDataDirectory("desktop-document-draft-tombstones") { directory ->
            val ownerKey = ownerKey()
            val persistence = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertTrue(
                persistence.write(ownerKey) {
                    draftPayload("both", linkedMapOf("tab-a" to "a", "tab-b" to "b"))
                },
            )
            assertTrue(persistence.flush())
            assertTrue(persistence.tombstone(ownerKey, setOf("tab-a")))
            assertEquals(setOf("tab-a"), readTombstones(persistence, ownerKey))

            assertTrue(
                persistence.write(ownerKey) {
                    draftPayload("still active", linkedMapOf("tab-a" to "a2", "tab-b" to "b2"))
                },
            )
            assertTrue(persistence.flush())
            assertEquals(setOf("tab-a"), readTombstones(persistence, ownerKey))

            assertTrue(persistence.write(ownerKey) { draftPayload("dropped", "tab-b" to "b3") })
            assertTrue(persistence.flush())
            assertEquals(emptySet(), readTombstones(persistence, ownerKey))
            assertTrue(persistence.retireAndDelete())
        }

    @Test
    fun `manifest publication failure before tombstone compaction remains fail closed`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val first = DesktopDocumentDraftPersistence(storage, ownerKey)
        assertTrue(first.write(ownerKey) { draftPayload("old", "tab-a" to "old") })
        assertTrue(first.flush())
        assertTrue(first.tombstone(ownerKey, setOf("tab-a")))

        // worker 与 flush 的 process-pending 交接重试都必须命中这个模拟的崩溃窗口，把墓碑压缩留给后继实例完成。
        storage.failAfterManifestPublicationCount.set(2)
        assertTrue(
            first.write(ownerKey) {
                draftPayload("new manifest", linkedMapOf("tab-a" to "new-a", "tab-b" to "new-b"))
            },
        )
        assertFalse(first.flush())

        val successor = DesktopDocumentDraftPersistence(storage, ownerKey)
        assertRead(
            successor,
            ownerKey,
            "new manifest",
            mapOf("tab-a" to "new-a", "tab-b" to "new-b"),
            tombstones = setOf("tab-a"),
        )
        assertTrue(successor.write(ownerKey) { draftPayload("without a", "tab-b" to "newer-b") })
        assertTrue(successor.flush())
        assertEquals(emptySet(), readTombstones(successor, ownerKey))
        assertFalse(first.retirePreservingDraft())
        first.sealPreservedDraft()
        assertTrue(successor.retireAndDelete())
    }

    @Test
    fun `same session read retries a retained payload after flush also sees a transient failure`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val persistence = DesktopDocumentDraftPersistence(storage, ownerKey)
        assertTrue(persistence.write(ownerKey) { draftPayload("old", "tab-a" to "old") })
        assertTrue(persistence.flush())

        storage.failBeforeManifestPublicationCount.set(2)
        assertTrue(persistence.write(ownerKey) { draftPayload("latest", "tab-a" to "latest") })
        assertFalse(persistence.flush())

        assertRead(persistence, ownerKey, "latest", mapOf("tab-a" to "latest"))
        assertTrue(persistence.flush())
        assertEquals(listOf("old", "latest"), storage.publishedManifests.toList())
        assertTrue(persistence.retireAndDelete())
    }

    @Test
    fun `failed tombstone retains hot pending and a write replay cannot hide the control failure`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val persistence = DesktopDocumentDraftPersistence(storage, ownerKey)
        assertTrue(persistence.write(ownerKey) { draftPayload("old", "tab-a" to "old") })
        assertTrue(persistence.flush())

        storage.failBeforeManifestPublicationCount.set(2)
        assertTrue(persistence.write(ownerKey) { draftPayload("latest", "tab-a" to "latest") })
        assertFalse(persistence.flush())
        storage.failNextTombstone.set(true)
        assertFalse(persistence.tombstone(ownerKey, setOf("tab-a")))

        assertRead(persistence, ownerKey, "latest", mapOf("tab-a" to "latest"))
        assertFalse(persistence.flush())
        assertTrue(persistence.tombstone(ownerKey, setOf("tab-a")))
        assertTrue(persistence.flush())
        assertEquals(setOf("tab-a"), readTombstones(persistence, ownerKey))
        assertTrue(persistence.retireAndDelete())
    }

    @Test
    fun `successful tombstone publishes pending snapshot so unrelated edits remain durable`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val persistence = DesktopDocumentDraftPersistence(storage, ownerKey)
        val failedAttemptStarted = CountDownLatch(1)
        storage.beforeReplace = { index -> if (index == 1) failedAttemptStarted.countDown() }
        storage.failBeforeNextManifestPublication.set(true)
        assertTrue(
            persistence.write(ownerKey) {
                draftPayload(
                    "hot snapshot",
                    linkedMapOf("tab-cancelled" to "cancel me", "tab-kept" to "latest edit"),
                )
            },
        )
        assertTrue(failedAttemptStarted.await(5, TimeUnit.SECONDS))

        assertTrue(persistence.tombstone(ownerKey, setOf("tab-cancelled")))
        assertRead(
            persistence,
            ownerKey,
            "hot snapshot",
            mapOf("tab-cancelled" to "cancel me", "tab-kept" to "latest edit"),
            tombstones = setOf("tab-cancelled"),
        )
        assertTrue(persistence.flush())
        assertTrue(persistence.retireAndDelete())
    }

    @Test
    fun `durable tombstone stays successful when post tombstone hot publication fails`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val persistence = DesktopDocumentDraftPersistence(storage, ownerKey)
        assertTrue(persistence.write(ownerKey) { draftPayload("old", "tab-a" to "old") })
        assertTrue(persistence.flush())

        // worker 与显式 flush 先失败；墓碑账本随后成功，而它自己尝试发布保留的热快照时才消耗掉第三次注入的失败。
        storage.failBeforeManifestPublicationCount.set(3)
        assertTrue(persistence.write(ownerKey) { draftPayload("latest", "tab-a" to "latest") })
        assertFalse(persistence.flush())
        assertTrue(persistence.tombstone(ownerKey, setOf("tab-a")))

        assertEquals(setOf("tab-a"), readTombstones(persistence, ownerKey))
        assertTrue(persistence.flush())
        assertTrue(persistence.retireAndDelete())
    }

    @Test
    fun `temporary manifest and record read failures stay retryable`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val persistence = DesktopDocumentDraftPersistence(storage, ownerKey)
        assertTrue(persistence.write(ownerKey) { draftPayload("manifest", "tab-a" to "body") })
        assertTrue(persistence.flush())

        storage.failManifestRead.set(true)
        var consumed = false
        assertEquals(
            DocumentDraftReadStatus.RETRYABLE,
            persistence.read(ownerKey) { consumed = true },
        )
        assertFalse(consumed)

        storage.failRecordRead.set(true)
        assertFailsWith<DocumentDraftReadRetryableException> {
            persistence.read(ownerKey) { source -> source.readRecord("tab-a") }
        }
        assertTrue(persistence.retireAndDelete())
    }

    @Test
    fun `missing or digest damaged record is skipped without blocking other recovery records`() =
        withPrivateDataDirectory("desktop-document-draft-damaged-record") { directory ->
            val ownerKey = ownerKey()
            val first = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            val records = linkedMapOf("tab-a" to "body-a", "tab-b" to "body-b")
            assertTrue(first.write(ownerKey) { draftPayload("manifest", records) })
            assertTrue(first.retirePreservingDraft())
            first.sealPreservedDraft()

            val storageDirectory = draftStorageDirectory(directory, ownerKey)
            Files.delete(recordPath(storageDirectory, "tab-a"))
            val afterMissing = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertEquals(
                DocumentDraftReadStatus.AVAILABLE,
                afterMissing.read(ownerKey) { source ->
                    assertEquals("body-a".toByteArray().size.toLong(), source.recordByteCount("tab-a"))
                    assertNull(source.readRecord("tab-a"))
                    assertEquals("body-b", source.readRecord("tab-b"))
                },
            )

            assertTrue(afterMissing.write(ownerKey) { draftPayload("repaired", records) })
            assertTrue(afterMissing.retirePreservingDraft())
            afterMissing.sealPreservedDraft()
            Files.writeString(recordPath(storageDirectory, "tab-a"), "digest-damaged")

            val afterDamage = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertEquals(
                DocumentDraftReadStatus.AVAILABLE,
                afterDamage.read(ownerKey) { source ->
                    assertEquals("body-a".toByteArray().size.toLong(), source.recordByteCount("tab-a"))
                    assertNull(source.readRecord("tab-a"))
                    assertEquals("body-b", source.readRecord("tab-b"))
                },
            )
            assertTrue(afterDamage.retireAndDelete())
        }

    @Test
    fun `physically oversized damaged record is isolated instead of blocking workspace recovery`() =
        withPrivateDataDirectory("desktop-document-draft-oversized-damage") { directory ->
            val ownerKey = ownerKey()
            val first = DesktopDocumentDraftPersistence(
                dataDir = directory.toFile(),
                ownerKey = ownerKey,
                maxRecordBytes = 8,
                maxTotalRecordBytes = 8,
            )
            assertTrue(first.write(ownerKey) { draftPayload("manifest", "tab-a" to "body") })
            assertTrue(first.retirePreservingDraft())
            first.sealPreservedDraft()
            Files.writeString(recordPath(draftStorageDirectory(directory, ownerKey), "tab-a"), "x".repeat(9))

            val reopened = DesktopDocumentDraftPersistence(
                dataDir = directory.toFile(),
                ownerKey = ownerKey,
                maxRecordBytes = 8,
                maxTotalRecordBytes = 8,
            )
            assertEquals(
                DocumentDraftReadStatus.AVAILABLE,
                reopened.read(ownerKey) { source ->
                    assertEquals(4L, source.recordByteCount("tab-a"))
                    assertNull(source.readRecord("tab-a"))
                },
            )
            assertTrue(reopened.retireAndDelete())
        }

    @Test
    fun `damaged tombstone ledger fails closed for reads and newer publications`() =
        withPrivateDataDirectory("desktop-document-draft-damaged-tombstone") { directory ->
            val ownerKey = ownerKey()
            val first = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertTrue(first.write(ownerKey) { draftPayload("old", "tab-a" to "body") })
            assertTrue(first.flush())
            assertTrue(first.tombstone(ownerKey, setOf("tab-a")))
            assertTrue(first.retirePreservingDraft())
            first.sealPreservedDraft()

            val tombstoneFile = draftStorageDirectory(directory, ownerKey).resolve("tombstones")
            Files.writeString(tombstoneFile, "damaged tombstones\n")
            val reopened = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            var consumed = false
            assertEquals(
                DocumentDraftReadStatus.RETRYABLE,
                reopened.read(ownerKey) { consumed = true },
            )
            assertFalse(consumed)
            assertTrue(reopened.write(ownerKey) { draftPayload("must not publish", "tab-a" to "new") })
            assertFalse(reopened.flush())
            assertTrue(reopened.retireAndDelete())
            val afterDelete = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertEquals(
                DocumentDraftReadStatus.ABSENT,
                afterDelete.read(ownerKey) { error("unexpected") },
            )
            assertTrue(afterDelete.retireAndDelete())
        }

    @Test
    fun `successor restores process owned hot snapshot after old preserve timeout before any edit`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val occupyingReadStarted = CountDownLatch(1)
        val releaseOccupyingRead = CountDownLatch(1)
        val readCount = AtomicInteger(0)
        storage.beforeRead = {
            if (readCount.incrementAndGet() == 1) {
                occupyingReadStarted.countDown()
                assertTrue(releaseOccupyingRead.await(5, TimeUnit.SECONDS))
            }
        }
        val blocker = DesktopDocumentDraftPersistence(storage, ownerKey)
        val occupyingRead = CompletableFuture.supplyAsync {
            blocker.read(ownerKey) { error("unexpected") }
        }
        assertTrue(occupyingReadStarted.await(5, TimeUnit.SECONDS))

        val payloadEvaluations = AtomicInteger(0)
        val old = DesktopDocumentDraftPersistence(
            storage = storage,
            ownerKey = ownerKey,
            flushTimeoutMillis = 25,
        )
        assertTrue(
            old.write(ownerKey) {
                payloadEvaluations.incrementAndGet()
                draftPayload("latest hot snapshot", "tab-a" to "latest body")
            },
        )
        assertFalse(old.retirePreservingDraft())
        old.sealPreservedDraft()

        val successor = DesktopDocumentDraftPersistence(storage, ownerKey)
        val restored = CompletableFuture.supplyAsync {
            var restoredBody: String? = null
            val status = successor.read(ownerKey) { source ->
                assertEquals("latest hot snapshot", source.manifest)
                restoredBody = source.readRecord("tab-a")
            }
            status to restoredBody
        }
        assertFalse(restored.isDone)
        releaseOccupyingRead.countDown()

        assertEquals(DocumentDraftReadStatus.ABSENT, occupyingRead.get(5, TimeUnit.SECONDS))
        assertEquals(
            DocumentDraftReadStatus.AVAILABLE to "latest body",
            restored.get(5, TimeUnit.SECONDS),
        )
        assertEquals(1, payloadEvaluations.get())
        assertEquals(listOf("latest hot snapshot"), storage.publishedManifests.toList())
        assertTrue(blocker.retirePreservingDraft())
        blocker.sealPreservedDraft()
        assertTrue(successor.retireAndDelete())
    }

    @Test
    fun `successor preserve retirement drains inherited hot snapshot without requiring a read`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val occupyingReadStarted = CountDownLatch(1)
        val releaseOccupyingRead = CountDownLatch(1)
        val readCount = AtomicInteger(0)
        storage.beforeRead = {
            if (readCount.incrementAndGet() == 1) {
                occupyingReadStarted.countDown()
                assertTrue(releaseOccupyingRead.await(5, TimeUnit.SECONDS))
            }
        }
        val blocker = DesktopDocumentDraftPersistence(storage, ownerKey)
        val occupyingRead = CompletableFuture.supplyAsync {
            blocker.read(ownerKey) { error("unexpected") }
        }
        assertTrue(occupyingReadStarted.await(5, TimeUnit.SECONDS))

        val old = DesktopDocumentDraftPersistence(
            storage = storage,
            ownerKey = ownerKey,
            flushTimeoutMillis = 25,
        )
        assertTrue(old.write(ownerKey) { draftPayload("inherited", "tab-a" to "hot body") })
        assertFalse(old.retirePreservingDraft())
        old.sealPreservedDraft()

        val successor = DesktopDocumentDraftPersistence(storage, ownerKey)
        val preserved = CompletableFuture.supplyAsync(successor::retirePreservingDraft)
        assertFalse(preserved.isDone)
        releaseOccupyingRead.countDown()

        assertEquals(DocumentDraftReadStatus.ABSENT, occupyingRead.get(5, TimeUnit.SECONDS))
        assertTrue(preserved.get(5, TimeUnit.SECONDS))
        successor.sealPreservedDraft()
        assertEquals(listOf("inherited"), storage.publishedManifests.toList())
        val reopened = DesktopDocumentDraftPersistence(storage, ownerKey)
        assertRead(reopened, ownerKey, "inherited", mapOf("tab-a" to "hot body"))
        assertTrue(blocker.retirePreservingDraft())
        blocker.sealPreservedDraft()
        assertTrue(reopened.retireAndDelete())
    }

    @Test
    fun `successor publication stays final when predecessor was already inside replace`() {
        val storage = InMemoryDraftStorage()
        val ownerKey = ownerKey()
        val predecessorReplaceStarted = CountDownLatch(1)
        val releasePredecessorReplace = CountDownLatch(1)
        storage.beforeReplace = { index ->
            if (index == 1) {
                predecessorReplaceStarted.countDown()
                assertTrue(releasePredecessorReplace.await(5, TimeUnit.SECONDS))
            }
        }
        val predecessor = DesktopDocumentDraftPersistence(storage, ownerKey)
        assertTrue(predecessor.write(ownerKey) { draftPayload("predecessor", "tab-a" to "old") })
        assertTrue(predecessorReplaceStarted.await(5, TimeUnit.SECONDS))

        val successor = DesktopDocumentDraftPersistence(storage, ownerKey)
        assertTrue(successor.write(ownerKey) { draftPayload("successor", "tab-a" to "new") })
        val successorFlush = CompletableFuture.supplyAsync(successor::flush)
        assertFalse(successorFlush.isDone)
        releasePredecessorReplace.countDown()

        assertTrue(successorFlush.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("predecessor", "successor"), storage.publishedManifests.toList())
        assertRead(successor, ownerKey, "successor", mapOf("tab-a" to "new"))
        assertTrue(predecessor.retirePreservingDraft())
        predecessor.sealPreservedDraft()
        assertTrue(successor.retireAndDelete())
    }

    @Test
    fun `large body is one bounded record while the atomic manifest stays small`() =
        withPrivateDataDirectory("desktop-document-draft-large-record") { directory ->
            val ownerKey = ownerKey()
            val persistence = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            val body = buildString {
                append("开头\n")
                repeat(1_200_000) { append(('a'.code + it % 23).toChar()) }
                append("\n结尾🙂")
            }

            assertTrue(persistence.write(ownerKey) { draftPayload("small manifest", "tab-large" to body) })
            assertTrue(persistence.flush())
            assertRead(persistence, ownerKey, "small manifest", mapOf("tab-large" to body))

            val namespace = desktopDocumentDraftOwnerNamespace(ownerKey)
            val storageDirectory = directory.resolve(
                "document-drafts/v3/deployments/${deployment.fingerprint}/owners/$namespace",
            )
            assertEquals(1L, countRecordFiles(storageDirectory))
            assertTrue(Files.size(storageDirectory.resolve("manifest")) < 4_096L)
            assertTrue(persistence.retireAndDelete())
        }

    @Test
    fun `logical delete remains durable and removes records before a restart`() =
        withPrivateDataDirectory("desktop-document-draft-delete") { directory ->
            val ownerKey = ownerKey()
            val persistence = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertTrue(persistence.write(ownerKey) { draftPayload("secret manifest", "tab-a" to "secret body") })
            assertTrue(persistence.flush())
            assertTrue(persistence.tombstone(ownerKey, setOf("tab-a")))
            assertTrue(persistence.delete(ownerKey))
            assertTrue(persistence.delete(ownerKey))

            val namespace = desktopDocumentDraftOwnerNamespace(ownerKey)
            val storageDirectory = directory.resolve(
                "document-drafts/v3/deployments/${deployment.fingerprint}/owners/$namespace",
            )
            assertEquals(0L, countRecordFiles(storageDirectory))
            assertFalse(Files.exists(storageDirectory.resolve("tombstones"), LinkOption.NOFOLLOW_LINKS))
            val deletionManifest = Files.readString(storageDirectory.resolve("manifest"))
            assertTrue("DELETED" in deletionManifest)
            assertFalse("secret" in deletionManifest)

            val reopened = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertEquals(DocumentDraftReadStatus.ABSENT, reopened.read(ownerKey) { error("unexpected") })
            assertTrue(persistence.retirePreservingDraft())
            persistence.sealPreservedDraft()
            assertTrue(reopened.retireAndDelete())
        }

    @Test
    fun `record orphaned before first manifest publication is removed on absent read`() =
        withPrivateDataDirectory("desktop-document-draft-absent-orphan") { directory ->
            val ownerKey = ownerKey()
            val namespace = listOf(
                DesktopDocumentDraftPersistence.DRAFTS_DIRECTORY,
                DesktopDocumentDraftPersistence.STORAGE_VERSION_DIRECTORY,
                DesktopDocumentDraftPersistence.DEPLOYMENTS_DIRECTORY,
                ownerKey.deploymentFingerprint,
                DesktopDocumentDraftPersistence.OWNERS_DIRECTORY,
                desktopDocumentDraftOwnerNamespace(ownerKey),
            )
            JvmPrivateDataDirectory.openExisting(directory.toFile())
                .atomicTextFile(namespace, "record-orphan.json")
                .replaceText("sensitive orphan")
            val storageDirectory = draftStorageDirectory(directory, ownerKey)
            assertTrue(Files.exists(storageDirectory.resolve("record-orphan.json")))

            val persistence = DesktopDocumentDraftPersistence(directory.toFile(), ownerKey)
            assertEquals(
                DocumentDraftReadStatus.ABSENT,
                persistence.read(ownerKey) { error("unexpected") },
            )
            assertEquals(0L, Files.list(storageDirectory).use { it.count() })
            assertTrue(persistence.retireAndDelete())
        }

    private fun draftPayload(
        manifest: String,
        vararg records: Pair<String, String>,
    ): DocumentDraftPayload = draftPayload(manifest, linkedMapOf(*records))

    private fun draftPayload(
        manifest: String,
        records: Map<String, String>,
        activeRecoveryKeys: Set<String> = records.keys,
    ): DocumentDraftPayload = DocumentDraftPayload(
        manifest = manifest,
        records = records.map { (key, value) -> DocumentDraftRecord(key) { value } },
        activeRecoveryKeys = activeRecoveryKeys,
    )

    private fun assertRead(
        persistence: DesktopDocumentDraftPersistence,
        ownerKey: DocumentDraftOwnerKey,
        manifest: String,
        records: Map<String, String>,
        tombstones: Set<String> = emptySet(),
    ) {
        var consumed = 0
        assertEquals(
            DocumentDraftReadStatus.AVAILABLE,
            persistence.read(ownerKey) { source ->
                consumed += 1
                assertEquals(manifest, source.manifest)
                assertEquals(tombstones, source.tombstones)
                records.forEach { (key, value) -> assertEquals(value, source.readRecord(key)) }
            },
        )
        assertEquals(1, consumed)
    }

    private fun readTombstones(
        persistence: DesktopDocumentDraftPersistence,
        ownerKey: DocumentDraftOwnerKey,
    ): Set<String> {
        var tombstones: Set<String>? = null
        assertEquals(
            DocumentDraftReadStatus.AVAILABLE,
            persistence.read(ownerKey) { source -> tombstones = source.tombstones },
        )
        return checkNotNull(tombstones)
    }

    private fun countRecordFiles(directory: Path): Long = Files.list(directory).use { files ->
        files.filter { path -> path.fileName.toString().startsWith("record-") }.count()
    }

    private fun draftStorageDirectory(directory: Path, ownerKey: DocumentDraftOwnerKey): Path =
        directory.resolve(
            "document-drafts/v3/deployments/${ownerKey.deploymentFingerprint}/owners/" +
                desktopDocumentDraftOwnerNamespace(ownerKey),
        )

    private fun recordPath(directory: Path, key: String): Path {
        val keyDigest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return Files.list(directory).use { files ->
            files.filter { path -> path.fileName.toString().startsWith("record-$keyDigest-") }
                .findFirst()
                .orElseThrow { AssertionError("Missing test record for $key") }
        }
    }

    private companion object {
        const val DATASET_A = "00000000-0000-4000-8000-000000000001"
        const val DATASET_B = "00000000-0000-4000-8000-000000000002"
    }

    private fun eventually(action: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (action()) return true
            Thread.yield()
        }
        return action()
    }

    private inline fun withPrivateDataDirectory(name: String, block: (Path) -> Unit) {
        val directory = Files.createTempDirectory(name)
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class InMemoryDraftStorage : DesktopDocumentDraftStorage {
        private val identity = Any()
        override val coordinationIdentity: Any
            get() = identity
        val publishedManifests = Collections.synchronizedList(mutableListOf<String>())
        val failManifestRead = AtomicBoolean(false)
        val failRecordRead = AtomicBoolean(false)
        val failBeforeNextManifestPublication = AtomicBoolean(false)
        val failBeforeManifestPublicationCount = AtomicInteger(0)
        val failAfterManifestPublicationCount = AtomicInteger(0)
        val failNextTombstone = AtomicBoolean(false)
        private val replaceCount = AtomicInteger(0)

        @Volatile
        var snapshot: Snapshot? = null

        @Volatile
        private var storedTombstones: Set<String> = emptySet()

        @Volatile
        var beforeReplace: ((Int) -> Unit)? = null

        @Volatile
        var beforeRead: (() -> Unit)? = null

        override fun read(
            limits: DesktopDocumentDraftLimits,
            consume: (DesktopDocumentDraftStoredRecordSource) -> Unit,
        ): DesktopDocumentDraftStorageReadStatus {
            beforeRead?.invoke()
            if (failManifestRead.getAndSet(false)) throw IOException("temporary manifest read failure")
            val current = snapshot ?: return DesktopDocumentDraftStorageReadStatus.ABSENT
            consume(
                object : DesktopDocumentDraftStoredRecordSource {
                    override val manifest: String = current.manifest
                    override val tombstones: Set<String> = storedTombstones

                    override fun recordByteCount(key: String): Long? =
                        current.records[key]?.toByteArray()?.size?.toLong()

                    override fun readRecord(key: String): String? {
                        if (failRecordRead.getAndSet(false)) {
                            throw IOException("temporary record read failure")
                        }
                        return current.records[key]
                    }
                },
            )
            return DesktopDocumentDraftStorageReadStatus.AVAILABLE
        }

        override fun replace(payload: DocumentDraftPayload, limits: DesktopDocumentDraftLimits) {
            beforeReplace?.invoke(replaceCount.incrementAndGet())
            if (failBeforeNextManifestPublication.getAndSet(false) ||
                failBeforeManifestPublicationCount.getAndUpdate { remaining ->
                    (remaining - 1).coerceAtLeast(0)
                } > 0
            ) {
                throw IOException("simulated transient failure before manifest publication")
            }
            val records = linkedMapOf<String, String>()
            payload.records.forEach { record -> records[record.key] = record.payload() }
            snapshot = Snapshot(payload.manifest, records)
            publishedManifests += payload.manifest
            if (failAfterManifestPublicationCount.getAndUpdate { remaining ->
                    (remaining - 1).coerceAtLeast(0)
                } > 0
            ) {
                throw IOException("simulated crash before tombstone compaction")
            }
            storedTombstones = storedTombstones.intersect(payload.activeRecoveryKeys)
        }

        override fun tombstone(recoveryKeys: Set<String>, limits: DesktopDocumentDraftLimits) {
            if (failNextTombstone.getAndSet(false)) {
                throw IOException("simulated transient tombstone failure")
            }
            storedTombstones = storedTombstones + recoveryKeys
        }

        override fun delete(limits: DesktopDocumentDraftLimits) {
            snapshot = null
            storedTombstones = emptySet()
        }

        data class Snapshot(val manifest: String, val records: Map<String, String>)
    }
}

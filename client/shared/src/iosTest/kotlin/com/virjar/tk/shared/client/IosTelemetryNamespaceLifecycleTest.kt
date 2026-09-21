@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.virjar.tk.shared.client

import com.virjar.tk.shared.platform.*
import platform.posix.symlink
import kotlin.test.*

class IosTelemetryNamespaceLifecycleTest {
    @Test
    fun boundedPagesResumeAfterReopeningAndRetireEverySiblingWithoutTouchingCurrentOrUnregisteredData() = withTelemetryRoot { root ->
        val identities = listOf(
            listOf("1-a-a", "1-a-a", "1-a-a"),
            listOf("1-a-a", "1-a-a", "1-b-b"),
            listOf("1-a-a", "1-a-a", "1-c-c"),
            listOf("1-a-a", "1-b-b", "1-a-a"),
            listOf("1-b-b", "1-a-a", "1-a-a"),
        )
        val stores = identities.map { identity ->
            createClientTelemetrySegmentStore(root, directories(identity)).also { it.writeNew(OLD_SEGMENT, "committed") }
        }
        identities.forEach { expireLeaf(root, it) }
        val unregistered = listOf("1-c-c", "1-a-a", "1-a-a")
        val unknownLeaf = iosPrivateDirectory(root, directories(unregistered))
        PlatformFile(unknownLeaf, CLIENT_TELEMETRY_MARKER_FILE).writeText(CLIENT_TELEMETRY_MARKER_CONTENT)
        PlatformFile(unknownLeaf, OLD_SEGMENT).writeText("unregistered evidence")
        expireLeaf(root, unregistered)

        identities.indices.forEach { index ->
            val before = readRegistry(root)
            val reopened = createClientTelemetrySegmentStore(root, directories(identities.first()))
            assertEquals(before, readRegistry(root), "reopening must preserve the current maintenance page")
            val result = maintain(reopened, maxVisitedNodes = 600)
            assertTrue(result.visitedNodes <= 600)
            assertEquals(index < identities.lastIndex, result.truncated)
            assertEquals(identities[index].takeIf { result.truncated }, readRegistry(root).cursor)
            if (index > 0) assertFalse(leaf(root, identities[index]).exists(), "later siblings must not starve")
        }

        assertEquals("committed", stores.first().read(OLD_SEGMENT))
        assertEquals("unregistered evidence", PlatformFile(unknownLeaf, OLD_SEGMENT).readText())
        assertEquals(listOf(identities.first()), readRegistry(root).identities)
        assertNull(readRegistry(root).cycleDeadlineEpochMs)

        // A still-live owner can publish again after another owner has retired its old namespace.
        val revived = segmentName(NOW, "revived")
        stores[1].writeNew(revived, "revived")
        assertEquals("revived", stores[1].read(revived))
        assertTrue(identities[1] in readRegistry(root).identities)
        assertEquals(CLIENT_TELEMETRY_MARKER_CONTENT, PlatformFile(leaf(root, identities[1]), CLIENT_TELEMETRY_MARKER_FILE).readText())
    }

    @Test
    fun fixedPendingRecoveryPreservesUnknownFilesLinksAndInvalidMarkers() = withTelemetryRoot { root ->
        val identities = (1..6).map(::identity)
        identities.forEach { createClientTelemetrySegmentStore(root, directories(it)).writeNew(OLD_SEGMENT, "committed") }
        val fixedPending = PlatformFile(leaf(root, identities[0]), CLIENT_TELEMETRY_ATOMIC_PENDING_FILE)
        fixedPending.writeText("unpublished")
        val unknownPending = PlatformFile(leaf(root, identities[1]), "$CLIENT_TELEMETRY_ATOMIC_PENDING_FILE.keep")
        unknownPending.writeText("unknown evidence")
        val pendingBesideUnknown = PlatformFile(leaf(root, identities[1]), CLIENT_TELEMETRY_ATOMIC_PENDING_FILE)
        pendingBesideUnknown.writeText("preserve with unknown contents")
        val external = PlatformFile(root, "link-target").also { it.writeText("external evidence") }
        val linkedPending = PlatformFile(leaf(root, identities[2]), CLIENT_TELEMETRY_ATOMIC_PENDING_FILE)
        assertEquals(0, symlink(external.path, linkedPending.path))
        val invalidMarker = PlatformFile(leaf(root, identities[3]), CLIENT_TELEMETRY_MARKER_FILE)
        invalidMarker.writeText("unsupported-version")
        val missingMarker = PlatformFile(leaf(root, identities[4]), CLIENT_TELEMETRY_MARKER_FILE)
        check(missingMarker.delete())
        val emptyLeaf = leaf(root, identities[5])
        check(PlatformFile(emptyLeaf, CLIENT_TELEMETRY_MARKER_FILE).delete())
        check(PlatformFile(emptyLeaf, OLD_SEGMENT).delete())
        identities.forEach { expireLeaf(root, it) }
        val current = createClientTelemetrySegmentStore(root, directories(identity(7)))

        maintain(current, cutoffEpochMs = 0L)
        assertFalse(fixedPending.exists())
        assertEquals("committed", PlatformFile(leaf(root, identities[0]), OLD_SEGMENT).readText())
        maintain(current)

        assertFalse(emptyLeaf.exists())
        assertEquals("unknown evidence", unknownPending.readText())
        assertEquals("preserve with unknown contents", pendingBesideUnknown.readText())
        assertTrue(linkedPending.isSymbolicLink())
        assertEquals("external evidence", external.readText())
        assertEquals("unsupported-version", invalidMarker.readText())
        assertFalse(missingMarker.exists())
        identities.subList(1, 5).forEach {
            assertEquals("committed", PlatformFile(leaf(root, it), OLD_SEGMENT).readText())
            assertTrue(it in readRegistry(root).identities)
        }
    }

    @Test
    fun deletionLimitSchedulesAnotherCycleAndReopeningRetainsCommittedRegistry() = withTelemetryRoot { root ->
        val old = (1..3).map(::identity)
        old.forEach {
            createClientTelemetrySegmentStore(root, directories(it)).writeNew(OLD_SEGMENT, "committed")
            expireLeaf(root, it)
        }
        val currentIdentity = identity(4)
        val current = createClientTelemetrySegmentStore(root, directories(currentIdentity))
        val rootPending = PlatformFile(root, "$CLIENT_TELEMETRY_ROOT_DIRECTORY/$CLIENT_TELEMETRY_ATOMIC_PENDING_FILE")
        rootPending.writeText("interrupted registry replacement")
        val leafPending = PlatformFile(leaf(root, currentIdentity), CLIENT_TELEMETRY_ATOMIC_PENDING_FILE)
        leafPending.writeText("interrupted segment replacement")
        val before = readRegistry(root)
        createClientTelemetrySegmentStore(root, directories(currentIdentity))
        assertEquals(before, readRegistry(root))
        assertFalse(rootPending.exists())
        assertFalse(leafPending.exists())

        repeat(3) { index ->
            val result = maintain(current, maxDeletes = 1)
            assertEquals(2 - index, old.count { leaf(root, it).exists() })
            assertFalse(result.truncated)
            if (index < 2) assertEquals(NOW, result.nextMaintenanceEpochMs)
            else assertTrue(result.nextMaintenanceEpochMs > NOW)
        }
        assertEquals(listOf(currentIdentity), readRegistry(root).identities)
    }

    @Test
    fun malformedRegistryIsRejectedWithoutReplacingItsBytesOrDeletingNamespaces() = withTelemetryRoot { root ->
        val currentIdentity = identity(1)
        val store = createClientTelemetrySegmentStore(root, directories(currentIdentity))
        store.writeNew(OLD_SEGMENT, "committed")
        val registry = registryFile(root)
        registry.writeText("unsupported registry evidence")

        assertFailsWith<IllegalArgumentException> { maintain(store) }
        assertFailsWith<IllegalArgumentException> { createClientTelemetrySegmentStore(root, directories(identity(2))) }
        assertEquals("unsupported registry evidence", registry.readText())
        assertEquals("committed", store.read(OLD_SEGMENT))
        assertFalse(leaf(root, identity(2)).exists())
    }
}

private const val NOW = 2_000_000_000_000L
private const val EXPIRED_AT = 1_900_000_000_000L
private val OLD_SEGMENT = segmentName(EXPIRED_AT, "old")

private fun identity(number: Int): List<String> = listOf("1-a-a", "1-a-a", "1-$number-$number")
private fun directories(identity: List<String>): List<String> = listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY) + identity
private fun leaf(root: PlatformFile, identity: List<String>): PlatformFile =
    directories(identity).fold(root) { parent, name -> PlatformFile(parent, name) }
private fun registryFile(root: PlatformFile): PlatformFile =
    PlatformFile(root, "$CLIENT_TELEMETRY_ROOT_DIRECTORY/$CLIENT_TELEMETRY_REGISTRY_FILE")
private fun readRegistry(root: PlatformFile): ClientTelemetryNamespaceRegistry =
    decodeClientTelemetryNamespaceRegistry(registryFile(root).readText())
private fun segmentName(createdAtEpochMs: Long, batchId: String): String = "telemetry-$createdAtEpochMs-$batchId.json"

private fun expireLeaf(root: PlatformFile, identity: List<String>) {
    val directory = leaf(root, identity)
    directory.listFiles()?.filter { !it.isSymbolicLink() }?.forEach { check(it.setLastModified(EXPIRED_AT)) }
    check(directory.setLastModified(EXPIRED_AT))
}

private fun maintain(
    store: ClientTelemetrySegmentStore,
    maxVisitedNodes: Int = ClientTelemetrySpool.MAX_ROOT_SCAN_NODES,
    maxDeletes: Int = ClientTelemetrySpool.MAX_ROOT_DELETIONS_PER_PASS,
    cutoffEpochMs: Long = NOW - ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS,
): TelemetryNamespaceMaintenanceResult = store.maintainNamespaces(
    nowEpochMs = NOW,
    cutoffEpochMs = cutoffEpochMs,
    retentionMillis = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS,
    maxVisitedNodes = maxVisitedNodes,
    maxDeletes = maxDeletes,
)

private inline fun withTelemetryRoot(block: (PlatformFile) -> Unit) {
    val root = PlatformFile(platformDataDir(), "native-telemetry-test-${platformRandomUuid()}")
    check(root.mkdir())
    try { block(root) } finally { check(root.deleteRecursively()) }
}

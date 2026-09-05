package com.virjar.tk.shared.client

import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NioClientTelemetryNamespaceScannerTest {
    @Test
    fun `registry cursor progresses past a missing identity without starving the tail`() =
        withScannerHarness { harness ->
            repeat(3) { index -> harness.register(scannerIdentity("page-$index")) }
            val ordered = harness.registry.identities
            harness.createNamespace(ordered[0])
            harness.createNamespace(ordered[2])

            val first = harness.scanner.scan(ONE_IDENTITY_SCAN_BUDGET)
            val second = harness.scanner.scan(ONE_IDENTITY_SCAN_BUDGET)
            val third = harness.scanner.scan(ONE_IDENTITY_SCAN_BUDGET)

            assertEquals(listOf(ordered[0]), first.namespaces.map(StoredTelemetryNamespace::identityDirectories))
            assertTrue(first.truncated)
            assertTrue(second.namespaces.isEmpty())
            assertTrue(second.truncated)
            assertFalse(ordered[1] in harness.registry.identities, "only the SDS-confirmed missing leaf is retired")
            assertEquals(listOf(ordered[2]), third.namespaces.map(StoredTelemetryNamespace::identityDirectories))
            assertFalse(third.truncated)
            assertNull(harness.registry.cursor)
        }

    @Test
    fun `unsupported provider neither traverses nor advances the registry cursor`() =
        withScannerHarness(useSecureTestHandle = false) { harness ->
            repeat(2) { index ->
                scannerIdentity("unsupported-$index").also {
                    harness.register(it)
                    harness.createNamespace(it)
                }
            }
            val before = harness.registry

            val scan = harness.scanner.scan(ONE_IDENTITY_SCAN_BUDGET)

            assertTrue(scan.namespaces.isEmpty())
            assertEquals(0, scan.visitedNodes)
            assertFalse(scan.truncated)
            assertEquals(before, harness.registry)
        }

    @Test
    fun `persisted cycle deadline survives short sessions and an overdue tail clamps to now`() =
        withScannerHarness { harness ->
            val now = 2_000_000_000_000L
            val retention = 1_000L
            repeat(2) { index -> harness.register(scannerIdentity("deadline-$index")) }
            val ordered = harness.registry.identities
            val early = harness.createNamespace(ordered[0])
            val tail = harness.createNamespace(ordered[1])
            setModified(early.resolve(CLIENT_TELEMETRY_MARKER_FILE), now - 500L)
            setModified(early, now - 500L)
            setModified(tail.resolve(CLIENT_TELEMETRY_MARKER_FILE), now - 100L)
            setModified(tail, now - 100L)

            val first = harness.newScanner().maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = now,
                cutoffEpochMs = now - retention,
                retentionMillis = retention,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 32,
            )

            assertTrue(first.truncated)
            assertEquals(now, first.nextMaintenanceEpochMs)
            assertEquals(now + 501L, harness.registry.cycleDeadlineEpochMs)
            assertEquals(ordered[0], harness.registry.cursor)

            val resumedAt = now + 600L
            val tailResult = harness.newScanner().maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = resumedAt,
                cutoffEpochMs = resumedAt - retention,
                retentionMillis = retention,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 32,
            )

            assertFalse(tailResult.truncated)
            assertEquals(resumedAt, tailResult.nextMaintenanceEpochMs)
            assertNull(harness.registry.cursor)
            assertNull(harness.registry.cycleDeadlineEpochMs)
            assertFalse(harness.registry.cycleNeedsImmediateRetry)
        }

    @Test
    fun `failed cleanup retry survives a new scanner until the tail consumes the cycle`() =
        withScannerHarness { harness ->
            val now = 2_000_000_000_000L
            val retention = 1_000L
            val expiredAt = now - retention - 1L
            repeat(2) { index -> harness.register(scannerIdentity("retry-$index")) }
            val ordered = harness.registry.identities
            val oldLeaf = harness.createNamespace(ordered[0])
            val oldSegment = "telemetry-${expiredAt.toString().padStart(13, '0')}-retry.json"
            harness.privateData.atomicTextFile(
                listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY) + ordered[0],
                oldSegment,
            ).replaceText("old")
            setModified(oldLeaf.resolve(oldSegment), expiredAt)
            setModified(oldLeaf.resolve(CLIENT_TELEMETRY_MARKER_FILE), expiredAt)
            setModified(oldLeaf, expiredAt)
            harness.createNamespace(ordered[1])
            var changed = false
            harness.afterSnapshotComparisonBeforeDelete = { leaf ->
                if (!changed) {
                    changed = true
                    setModified(leaf.resolve(CLIENT_TELEMETRY_MARKER_FILE), now)
                }
            }

            val first = harness.newScanner().maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = now,
                cutoffEpochMs = now - retention,
                retentionMillis = retention,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 32,
            )

            assertTrue(changed)
            assertTrue(first.truncated)
            assertTrue(harness.registry.cycleNeedsImmediateRetry)
            harness.afterSnapshotComparisonBeforeDelete = {}
            val persisted = harness.registry
            val availableRoot = harness.openRoot
            harness.openRoot = { throw IOException("temporary root access failure") }
            val inaccessible = harness.newScanner().maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = now,
                cutoffEpochMs = now - retention,
                retentionMillis = retention,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 32,
            )
            assertEquals(now, inaccessible.nextMaintenanceEpochMs)
            assertEquals(persisted, harness.registry)
            harness.openRoot = availableRoot

            val tailResult = harness.newScanner().maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = now,
                cutoffEpochMs = now - retention,
                retentionMillis = retention,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 32,
            )

            assertFalse(tailResult.truncated)
            assertEquals(now, tailResult.nextMaintenanceEpochMs)
            assertNull(harness.registry.cursor)
            assertNull(harness.registry.cycleDeadlineEpochMs)
            assertFalse(harness.registry.cycleNeedsImmediateRetry)
        }

    @Test
    fun `failed parent force keeps a deleted leaf registered until durable missing is proven`() =
        withScannerHarness { harness ->
            val now = 2_000_000_000_000L
            val retention = 1_000L
            val expiredAt = now - retention - 1L
            val identity = scannerIdentity("durable-tombstone")
            harness.register(identity)
            val leaf = harness.createNamespace(identity)
            val dataset = checkNotNull(leaf.parent)
            setModified(leaf.resolve(CLIENT_TELEMETRY_MARKER_FILE), expiredAt)
            setModified(leaf, expiredAt)
            val durableForce = harness.forceDirectory
            harness.forceDirectory = { path, expectedStorageIdentity ->
                if (path == dataset && !leaf.exists(LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("simulated directory force failure")
                }
                durableForce(path, expectedStorageIdentity)
            }

            val failed = harness.newScanner().maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = now,
                cutoffEpochMs = now - retention,
                retentionMillis = retention,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 32,
            )

            assertFalse(leaf.exists(LinkOption.NOFOLLOW_LINKS))
            assertTrue(identity in harness.registry.identities)
            assertEquals(now, failed.nextMaintenanceEpochMs)

            harness.forceDirectory = durableForce
            harness.newScanner().maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = now,
                cutoffEpochMs = now - retention,
                retentionMillis = retention,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 32,
            )

            assertFalse(identity in harness.registry.identities)
        }

    @Test
    fun `provider without directory force retains namespaces without immediate rescan`() =
        withScannerHarness { harness ->
            val now = 2_000_000_000_000L
            val retention = 1_000L
            val expiredAt = now - retention - 1L
            val identity = scannerIdentity("no-directory-force")
            harness.register(identity)
            val leaf = harness.createNamespace(identity)
            setModified(leaf.resolve(CLIENT_TELEMETRY_MARKER_FILE), expiredAt)
            setModified(leaf, expiredAt)
            harness.forceDirectory = { _, _ ->
                throw UnsupportedOperationException("directory force unavailable")
            }

            val result = harness.newScanner().maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = now,
                cutoffEpochMs = now - retention,
                retentionMillis = retention,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 32,
            )

            assertTrue(leaf.exists(LinkOption.NOFOLLOW_LINKS))
            assertTrue(identity in harness.registry.identities)
            assertEquals(now + retention, result.nextMaintenanceEpochMs)
            assertFalse(harness.registry.cycleNeedsImmediateRetry)
        }

    @Test
    fun `provider without file keys retains namespaces without immediate rescan`() =
        withScannerHarness { harness ->
            val now = 2_000_000_000_000L
            val retention = 1_000L
            val expiredAt = now - retention - 1L
            val identity = scannerIdentity("no-file-key")
            harness.register(identity)
            val leaf = harness.createNamespace(identity)
            setModified(leaf.resolve(CLIENT_TELEMETRY_MARKER_FILE), expiredAt)
            setModified(leaf, expiredAt)
            var forceCalls = 0
            harness.storageIdentity = { null }
            harness.forceDirectory = { _, _ -> forceCalls++ }

            val result = harness.newScanner().maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = now,
                cutoffEpochMs = now - retention,
                retentionMillis = retention,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 32,
            )

            assertEquals(0, forceCalls)
            assertTrue(leaf.exists(LinkOption.NOFOLLOW_LINKS))
            assertTrue(identity in harness.registry.identities)
            assertEquals(now + retention, result.nextMaintenanceEpochMs)
            assertFalse(harness.registry.cycleNeedsImmediateRetry)
        }

    @Test
    fun `static identity symlink is quarantined without unregistering it`() = withScannerHarness { harness ->
        val identity = scannerIdentity("static-link")
        harness.register(identity)
        val dataset = harness.createIdentityPrefix(identity, depth = 2)
        val outside = harness.createOutsideNamespace("static-outside")
        val link = dataset.resolve(identity[2])
        Files.createSymbolicLink(link, outside)

        val scan = harness.scanner.scan(ONE_IDENTITY_SCAN_BUDGET)

        assertTrue(scan.namespaces.isEmpty())
        assertTrue(identity in harness.registry.identities)
        assertTrue(link.exists(LinkOption.NOFOLLOW_LINKS))
        assertEquals(CLIENT_TELEMETRY_MARKER_CONTENT, Files.readString(outside.resolve(CLIENT_TELEMETRY_MARKER_FILE)))
    }

    @Test
    fun `leaf replacement after snapshot comparison cannot delete through a symlink`() =
        withScannerHarness { harness ->
            val now = 2_000_000_000_000L
            val expiredAt = now - ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS - 1L
            val identity = scannerIdentity("racing-link")
            harness.register(identity)
            val leaf = harness.createNamespace(identity)
            val segment = "telemetry-${expiredAt.toString().padStart(13, '0')}-race.json"
            harness.privateData.atomicTextFile(
                listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY) + identity,
                segment,
            ).replaceText("old")
            setModified(leaf.resolve(segment), expiredAt)
            setModified(leaf.resolve(CLIENT_TELEMETRY_MARKER_FILE), expiredAt)
            setModified(leaf, expiredAt)
            val outside = harness.createOutsideNamespace("race-outside")
            val outsideSegment = outside.resolve(segment)
            harness.privateData.atomicTextFile(
                listOf("outside-race-outside"),
                segment,
            ).replaceText("outside")
            val retiredLeaf = leaf.resolveSibling("${leaf.fileName}.retired")
            var hookCalls = 0
            harness.afterSnapshotComparisonBeforeDelete = {
                hookCalls++
                Files.move(leaf, retiredLeaf, StandardCopyOption.ATOMIC_MOVE)
                Files.createSymbolicLink(leaf, outside)
            }

            val maintenance = harness.scanner.maintain(
                currentIdentityDirectories = scannerIdentity("current"),
                nowEpochMs = now,
                cutoffEpochMs = now - ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS,
                retentionMillis = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS,
                maxVisitedNodes = ONE_IDENTITY_SCAN_BUDGET,
                maxDeletes = 1,
            )

            assertEquals(now, maintenance.nextMaintenanceEpochMs)
            assertEquals(1, hookCalls)
            assertTrue(outsideSegment.exists())
            assertEquals("outside", Files.readString(outsideSegment))
            assertTrue(identity in harness.registry.identities)
        }

    internal class ScannerHarness(
        val root: Path,
        private val useSecureTestHandle: Boolean,
    ) {
        val privateData = JvmPrivateDataDirectory.openExisting(root.toFile())
        var registry = ClientTelemetryNamespaceRegistry.empty()
        var afterSnapshotComparisonBeforeDelete: (Path) -> Unit = {}
        var forceDirectory: (Path, Any) -> Unit = privateData.security()::forceDirectoryDurably
        var storageIdentity: (BasicFileAttributes) -> Any? = { attributes -> attributes.fileKey() }
        var openRoot: (Path) -> DirectoryStream<Path> = if (useSecureTestHandle) {
            { path -> TestSecureDirectoryStream(path) }
        } else {
            { path -> basicDirectoryStream(path) }
        }
        val scanner = newScanner()

        init {
            privateData.ensureDirectory(CLIENT_TELEMETRY_ROOT_DIRECTORY)
        }

        fun register(identity: List<String>) {
            registry = registry.register(identity)
        }

        fun newScanner(): NioClientTelemetryNamespaceScanner = NioClientTelemetryNamespaceScanner(
            dataRoot = root,
            requirePrivateDirectory = { path, _ ->
                privateData.security().requirePrivateDirectory(path)
                basicAttributes(path)
            },
            requirePrivateFile = { path, _ -> privateData.security().requirePrivateFile(path) },
            loadRegistry = { registry },
            replaceRegistry = { registry = it },
            forceDirectory = { path, expectedStorageIdentity ->
                this@ScannerHarness.forceDirectory(path, expectedStorageIdentity)
            },
            storageIdentity = { attributes -> this@ScannerHarness.storageIdentity(attributes) },
            openRoot = { path -> this@ScannerHarness.openRoot(path) },
            afterSnapshotComparisonBeforeDelete = { path ->
                this@ScannerHarness.afterSnapshotComparisonBeforeDelete(path)
            },
        )

        fun createNamespace(identity: List<String>): Path {
            val directories = listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY) + identity
            val leaf = privateData.ensureDirectory(*directories.toTypedArray()).toPath()
            privateData.atomicTextFile(directories, CLIENT_TELEMETRY_MARKER_FILE)
                .replaceText(CLIENT_TELEMETRY_MARKER_CONTENT)
            return leaf
        }

        fun createIdentityPrefix(identity: List<String>, depth: Int): Path =
            privateData.ensureDirectory(
                *(listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY) + identity.take(depth)).toTypedArray(),
            ).toPath()

        fun createOutsideNamespace(name: String): Path {
            val directory = privateData.ensureDirectory("outside-$name").toPath()
            privateData.atomicTextFile(listOf("outside-$name"), CLIENT_TELEMETRY_MARKER_FILE)
                .replaceText(CLIENT_TELEMETRY_MARKER_CONTENT)
            return directory
        }
    }

    private companion object {
        const val ONE_IDENTITY_SCAN_BUDGET = 261
    }
}

private fun scannerIdentity(value: String): List<String> = listOf(
    stableTelemetryNamespace("deployment-$value"),
    stableTelemetryNamespace("dataset-$value"),
    stableTelemetryNamespace("uid-$value"),
)

private fun basicDirectoryStream(path: Path): DirectoryStream<Path> {
    val delegate = Files.newDirectoryStream(path)
    return object : DirectoryStream<Path> {
        override fun iterator(): MutableIterator<Path> = delegate.iterator()
        override fun close() = delegate.close()
    }
}

private fun setModified(path: Path, epochMs: Long) {
    Files.setLastModifiedTime(path, FileTime.fromMillis(epochMs))
}

private inline fun withScannerHarness(
    useSecureTestHandle: Boolean = true,
    block: (NioClientTelemetryNamespaceScannerTest.ScannerHarness) -> Unit,
) {
    val root = Files.createTempDirectory(
        "teamtalk-secure-scanner-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    try {
        block(NioClientTelemetryNamespaceScannerTest.ScannerHarness(root, useSecureTestHandle))
    } finally {
        root.toFile().deleteRecursively()
    }
}

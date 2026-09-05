package com.virjar.tk.shared.client

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientTelemetryNamespaceLifecycleTest {
    @Test
    fun `second store retires an old leaf and the live first store republishes its full identity`() =
        withPrivateDataDirectory { dataDir ->
            val now = 2_000_000_000_000L
            val expiredAt = now - ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS - 1L
            val firstDirectories = telemetryDirectories("live-first")
            val secondDirectories = telemetryDirectories("maintainer")
            val first = createClientTelemetrySegmentStore(dataDir.toFile(), firstDirectories)
            val oldSegment = segmentFileName(expiredAt, "old")
            first.writeNew(oldSegment, "old")
            val firstLeaf = resolve(dataDir, firstDirectories)
            setModified(firstLeaf.resolve(oldSegment), expiredAt)
            setModified(firstLeaf.resolve(CLIENT_TELEMETRY_MARKER_FILE), expiredAt)
            setModified(firstLeaf, expiredAt)

            val second = createClientTelemetrySegmentStore(dataDir.toFile(), secondDirectories)
            val privateData = JvmPrivateDataDirectory.openExisting(dataDir.toFile())
            val registryFile = privateData.atomicTextFile(
                listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY),
                CLIENT_TELEMETRY_REGISTRY_FILE,
            )
            val scanner = testScanner(privateData, registryFile)
            withTelemetryRootLock(privateData) {
                scanner.maintain(
                    currentIdentityDirectories = second.identityDirectories,
                    nowEpochMs = now,
                    cutoffEpochMs = now - ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS,
                    retentionMillis = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS,
                    maxVisitedNodes = ClientTelemetrySpool.MAX_ROOT_SCAN_NODES,
                    maxDeletes = ClientTelemetrySpool.MAX_ROOT_DELETIONS_PER_PASS,
                )
            }

            assertFalse(firstLeaf.exists())
            assertFalse(first.identityDirectories in readRegistry(registryFile).identities)

            val revivedSegment = segmentFileName(now, "revived")
            first.writeNew(revivedSegment, "revived")

            assertEquals("revived", first.read(revivedSegment))
            assertEquals(
                CLIENT_TELEMETRY_MARKER_CONTENT,
                privateData.atomicTextFile(firstDirectories, CLIENT_TELEMETRY_MARKER_FILE).readText(32L),
            )
            assertTrue(first.identityDirectories in readRegistry(registryFile).identities)
        }

    @Test
    fun `empty markerless crash leaf is retired but markerless content is quarantined`() =
        withPrivateDataDirectory { dataDir ->
            val emptyDirectories = telemetryDirectories("empty-crash")
            val contentDirectories = telemetryDirectories("markerless-content")
            createClientTelemetrySegmentStore(dataDir.toFile(), emptyDirectories)
            val contentStore = createClientTelemetrySegmentStore(dataDir.toFile(), contentDirectories)
            val contentSegment = segmentFileName(1_900_000_000_000L, "preserved")
            contentStore.writeNew(contentSegment, "preserved")
            Files.delete(resolve(dataDir, emptyDirectories).resolve(CLIENT_TELEMETRY_MARKER_FILE))
            Files.delete(resolve(dataDir, contentDirectories).resolve(CLIENT_TELEMETRY_MARKER_FILE))
            val current = createClientTelemetrySegmentStore(dataDir.toFile(), telemetryDirectories("current"))
            val privateData = JvmPrivateDataDirectory.openExisting(dataDir.toFile())
            val registryFile = privateData.atomicTextFile(
                listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY),
                CLIENT_TELEMETRY_REGISTRY_FILE,
            )

            withTelemetryRootLock(privateData) {
                testScanner(privateData, registryFile).maintain(
                    currentIdentityDirectories = current.identityDirectories,
                    nowEpochMs = 2_000_000_000_000L,
                    cutoffEpochMs = 1_999_000_000_000L,
                    retentionMillis = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS,
                    maxVisitedNodes = ClientTelemetrySpool.MAX_ROOT_SCAN_NODES,
                    maxDeletes = ClientTelemetrySpool.MAX_ROOT_DELETIONS_PER_PASS,
                )
            }

            assertFalse(resolve(dataDir, emptyDirectories).exists())
            assertTrue(resolve(dataDir, contentDirectories).resolve(contentSegment).exists())
            assertFalse(resolve(dataDir, contentDirectories).resolve(CLIENT_TELEMETRY_MARKER_FILE).exists())
            assertEquals("preserved", Files.readString(resolve(dataDir, contentDirectories).resolve(contentSegment)))
            val registry = readRegistry(registryFile)
            assertFalse(emptyDirectories.drop(1) in registry.identities)
            assertTrue(contentDirectories.drop(1) in registry.identities)
        }

    @Test
    fun `store restart removes bounded atomic replacement orphans before listing`() =
        withPrivateDataDirectory { dataDir ->
            val directories = telemetryDirectories("orphan-recovery")
            val original = createClientTelemetrySegmentStore(dataDir.toFile(), directories)
            val committed = segmentFileName(1_900_000_000_000L, "committed")
            original.writeNew(committed, "committed")
            val privateData = JvmPrivateDataDirectory.openExisting(dataDir.toFile())
            val leafOrphanName = CLIENT_TELEMETRY_ATOMIC_PENDING_FILE
            val leafOrphan = privateData.preparePrivateFile(directories, leafOrphanName).toPath()
            Files.writeString(leafOrphan, "unpublished")
            val rootDirectories = listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY)
            val rootOrphanName = CLIENT_TELEMETRY_ATOMIC_PENDING_FILE
            val rootOrphan = privateData.preparePrivateFile(rootDirectories, rootOrphanName).toPath()
            Files.writeString(rootOrphan, "partial-registry")

            val restarted = createClientTelemetrySegmentStore(dataDir.toFile(), directories)

            assertFalse(leafOrphan.exists())
            assertFalse(rootOrphan.exists())
            assertEquals(listOf(committed), restarted.list().map(StoredTelemetrySegmentFile::fileName))
            assertEquals("committed", restarted.read(committed))
        }

    @Test
    fun `store reconstruction preserves a file backed cursor and short sessions reach the tail`() =
        withPrivateDataDirectory { dataDir ->
            val directories = (0 until 4).map { index -> telemetryDirectories("cursor-$index") }
            directories.forEach { identity -> createClientTelemetrySegmentStore(dataDir.toFile(), identity) }
            val privateData = JvmPrivateDataDirectory.openExisting(dataDir.toFile())
            val registryFile = privateData.atomicTextFile(
                listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY),
                CLIENT_TELEMETRY_REGISTRY_FILE,
            )
            val ordered = readRegistry(registryFile).identities
            val now = System.currentTimeMillis()
            var persisted = readRegistry(registryFile).copy(
                cursor = ordered.first(),
                cycleDeadlineEpochMs = now + 60_000L,
                cycleNeedsImmediateRetry = true,
            )
            registryFile.replaceText(
                encodeClientTelemetryNamespaceRegistry(persisted),
                MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES.toLong(),
            )

            val expectedCursors = listOf(ordered[1], ordered[2], null)
            expectedCursors.forEachIndexed { index, expectedCursor ->
                val reconstructed = createClientTelemetrySegmentStore(dataDir.toFile(), directories.first())
                assertEquals(persisted, readRegistry(registryFile), "store reconstruction reset maintenance state")

                withTelemetryRootLock(privateData) {
                    testScanner(privateData, registryFile).maintain(
                        currentIdentityDirectories = reconstructed.identityDirectories,
                        nowEpochMs = now + index,
                        cutoffEpochMs = 0L,
                        retentionMillis = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS,
                        maxVisitedNodes = 261,
                        maxDeletes = ClientTelemetrySpool.MAX_ROOT_DELETIONS_PER_PASS,
                    )
                }

                persisted = readRegistry(registryFile)
                assertEquals(expectedCursor, persisted.cursor)
                if (expectedCursor == null) {
                    assertEquals(null, persisted.cycleDeadlineEpochMs)
                    assertFalse(persisted.cycleNeedsImmediateRetry)
                } else {
                    assertEquals(now + 60_000L, persisted.cycleDeadlineEpochMs)
                    assertTrue(persisted.cycleNeedsImmediateRetry)
                }
            }
        }

    @Test
    fun `scanner removes only the fixed pending file from a registered old namespace`() =
        withPrivateDataDirectory { dataDir ->
            val pendingDirectories = telemetryDirectories("old-pending")
            val nearNameDirectories = telemetryDirectories("near-name")
            createClientTelemetrySegmentStore(dataDir.toFile(), pendingDirectories)
            createClientTelemetrySegmentStore(dataDir.toFile(), nearNameDirectories)
            val current = createClientTelemetrySegmentStore(dataDir.toFile(), telemetryDirectories("pending-current"))
            val privateData = JvmPrivateDataDirectory.openExisting(dataDir.toFile())
            val fixedPending = privateData.preparePrivateFile(
                pendingDirectories,
                CLIENT_TELEMETRY_ATOMIC_PENDING_FILE,
            ).toPath()
            val nearName = privateData.preparePrivateFile(
                nearNameDirectories,
                "$CLIENT_TELEMETRY_ATOMIC_PENDING_FILE.keep",
            ).toPath()
            val registryFile = privateData.atomicTextFile(
                listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY),
                CLIENT_TELEMETRY_REGISTRY_FILE,
            )

            withTelemetryRootLock(privateData) {
                testScanner(privateData, registryFile).maintain(
                    currentIdentityDirectories = current.identityDirectories,
                    nowEpochMs = System.currentTimeMillis(),
                    cutoffEpochMs = 0L,
                    retentionMillis = ClientTelemetrySpool.DEFAULT_RETENTION_MILLIS,
                    maxVisitedNodes = ClientTelemetrySpool.MAX_ROOT_SCAN_NODES,
                    maxDeletes = ClientTelemetrySpool.MAX_ROOT_DELETIONS_PER_PASS,
                )
            }

            assertFalse(fixedPending.exists())
            assertTrue(resolve(dataDir, pendingDirectories).resolve(CLIENT_TELEMETRY_MARKER_FILE).exists())
            assertTrue(nearName.exists(), "a near-name sentinel must never match the fixed pending file")
        }
}

private fun testScanner(
    privateData: JvmPrivateDataDirectory,
    registryFile: JvmPrivateAtomicTextFile,
): NioClientTelemetryNamespaceScanner = NioClientTelemetryNamespaceScanner(
    dataRoot = privateData.root,
    requirePrivateDirectory = { path, _ ->
        privateData.security().requirePrivateDirectory(path)
        basicAttributes(path)
    },
    requirePrivateFile = { path, _ -> privateData.security().requirePrivateFile(path) },
    loadRegistry = { readRegistry(registryFile) },
    replaceRegistry = { registry ->
        registryFile.replaceText(
            encodeClientTelemetryNamespaceRegistry(registry),
            MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES.toLong(),
        )
    },
    forceDirectory = privateData.security()::forceDirectoryDurably,
    openRoot = { path -> TestSecureDirectoryStream(path) },
)

private fun readRegistry(registryFile: JvmPrivateAtomicTextFile): ClientTelemetryNamespaceRegistry =
    decodeClientTelemetryNamespaceRegistry(
        checkNotNull(registryFile.readText(MAX_TELEMETRY_NAMESPACE_REGISTRY_BYTES.toLong())),
    )

private fun <T> withTelemetryRootLock(
    privateData: JvmPrivateDataDirectory,
    action: () -> T,
): T {
    val root = privateData.root.resolve(CLIENT_TELEMETRY_ROOT_DIRECTORY)
    val processLock = ClientTelemetryProcessRootLocks.forRoot(root)
    val lockFile = privateData.requirePrivateFile(
        listOf(CLIENT_TELEMETRY_ROOT_DIRECTORY),
        ".telemetry-lifecycle.lock",
    ).toPath()
    return synchronized(processLock) {
        FileChannel.open(lockFile, TEST_LOCK_OPTIONS).use { channel ->
            channel.lock().use { action() }
        }
    }
}

private fun telemetryDirectories(value: String): List<String> = listOf(
    CLIENT_TELEMETRY_ROOT_DIRECTORY,
    stableTelemetryNamespace("deployment-$value"),
    stableTelemetryNamespace("dataset-$value"),
    stableTelemetryNamespace("uid-$value"),
)

private fun segmentFileName(createdAtEpochMs: Long, batchId: String): String =
    "telemetry-${createdAtEpochMs.toString().padStart(13, '0')}-$batchId.json"

private fun resolve(root: Path, directories: List<String>): Path =
    directories.fold(root) { parent, component -> parent.resolve(component) }

private fun setModified(path: Path, epochMs: Long) {
    Files.setLastModifiedTime(path, FileTime.fromMillis(epochMs))
}

private inline fun withPrivateDataDirectory(block: (Path) -> Unit) {
    val dataDir = Files.createTempDirectory(
        "teamtalk-telemetry-root-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )
    try {
        block(dataDir)
    } finally {
        dataDir.toFile().deleteRecursively()
    }
}

private val TEST_LOCK_OPTIONS: Set<OpenOption> = setOf(
    StandardOpenOption.WRITE,
    LinkOption.NOFOLLOW_LINKS,
)

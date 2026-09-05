package com.virjar.tk.server.e2e

import com.virjar.tk.shared.client.JvmPrivateDataDirectory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DocumentFixtureToolTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @Test
    fun `deterministic plan has 150 unified document nodes and content-bearing parents`() {
        val fixtureId = "12345678-1234-4234-8234-1234567890ab"
        val first = documentFixturePlan(fixtureId)
        val second = documentFixturePlan(fixtureId)

        assertEquals(first, second)
        assertEquals(DOCUMENT_FIXTURE_DOCUMENT_COUNT, first.nodes.size)
        assertEquals(6, first.rootCount)
        assertEquals(24, first.middleCount)
        assertEquals(120, first.leafCount)
        assertEquals(first.nodes.size, first.nodes.mapTo(hashSetOf()) { it.documentId }.size)
        assertTrue(first.nodes.all { UUID.fromString(it.documentId).toString() == it.documentId })

        val children = first.nodes.groupBy { it.parentId }
        val roots = children[null].orEmpty()
        assertEquals(6, roots.size)
        roots.forEach { root ->
            assertTrue(root.markdown.isNotBlank())
            val middle = children[root.documentId].orEmpty()
            assertEquals(4, middle.size)
            middle.forEach { parent ->
                assertTrue(parent.markdown.isNotBlank())
                assertEquals(5, children[parent.documentId].orEmpty().size)
            }
        }
        assertTrue(first.nodes.all { it.title.isNotBlank() && it.markdown.contains(it.title) })
        assertEquals(first.nodes.last().documentId, first.representatives.longTitleLeafId)
        assertNotEquals(
            first.representatives.longTitleLeafId,
            first.representatives.offlineMissingLeafId,
        )
    }

    @Test
    fun `different fixture identities produce disjoint caller-owned ids`() {
        val first = documentFixturePlan("12345678-1234-4234-8234-1234567890ab")
        val second = documentFixturePlan("87654321-4321-4321-8321-ba0987654321")

        assertNotEquals(first.spaceId, second.spaceId)
        assertNotEquals(first.archiveOperationId, second.archiveOperationId)
        assertTrue(
            first.nodes.mapTo(hashSetOf()) { it.documentId }
                .intersect(second.nodes.mapTo(hashSetOf()) { it.documentId })
                .isEmpty(),
        )
    }

    @Test
    fun `plan fingerprint covers space metadata every node field and representatives`() {
        val plan = documentFixturePlan("12345678-1234-4234-8234-1234567890ab")
        val fingerprint = documentFixturePlanFingerprint(plan)
        val firstNode = plan.nodes.first()
        val changedPlans = listOf(
            plan.copy(spaceName = "${plan.spaceName} changed"),
            plan.copy(spaceDescription = "${plan.spaceDescription} changed"),
            plan.copy(spaceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            plan.copy(archiveOperationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            plan.copy(nodes = plan.nodes.updatedFirst(firstNode.copy(documentId = "changed-id"))),
            plan.copy(nodes = plan.nodes.updatedFirst(firstNode.copy(parentId = "changed-parent"))),
            plan.copy(nodes = plan.nodes.updatedFirst(firstNode.copy(level = firstNode.level + 1))),
            plan.copy(nodes = plan.nodes.updatedFirst(firstNode.copy(title = "${firstNode.title} changed"))),
            plan.copy(nodes = plan.nodes.updatedFirst(firstNode.copy(markdown = "${firstNode.markdown}\nchanged"))),
            plan.copy(
                representatives = plan.representatives.copy(
                    offlineMissingLeafId = plan.representatives.longTitleLeafId,
                ),
            ),
        )

        assertEquals(fingerprint, documentFixturePlanFingerprint(documentFixturePlan(plan.fixtureId)))
        assertTrue(fingerprint.matches(Regex("sha256:[0-9a-f]{64}")))
        changedPlans.forEach { changed ->
            assertNotEquals(fingerprint, documentFixturePlanFingerprint(changed))
        }
    }

    @Test
    fun `credential parser is strict and never renders secret values`() {
        val credentials = parseDocumentFixtureCredentials(
            "username=fixture-user\npassword=high=entropy=value\n",
        )

        assertEquals("fixture-user", credentials.username)
        assertEquals("high=entropy=value", credentials.password)
        assertFalse(credentials.toString().contains("fixture-user"))
        assertFalse(credentials.toString().contains("high=entropy=value"))

        assertFailsWith<IllegalArgumentException> {
            parseDocumentFixtureCredentials("username=fixture-user\npassword=secret123\ntoken=forbidden\n")
        }
        assertFailsWith<IllegalArgumentException> {
            parseDocumentFixtureCredentials("username=fixture-user\nusername=other-user\npassword=secret123\n")
        }
        assertFailsWith<IllegalArgumentException> {
            parseDocumentFixtureCredentials("username=fixture-user\n")
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            parseDocumentFixtureCredentials("username=fixture-user\npassword=short\nunknown=never-log-this\n")
        }
        assertFalse(failure.message.orEmpty().contains("never-log-this"))
    }

    @Test
    fun `manifest round trip retains deterministic topology and no credential field`() {
        val fixtureId = "12345678-1234-4234-8234-1234567890ab"
        val plan = documentFixturePlan(fixtureId)
        val manifest = plannedManifest(plan)

        val encoded = encodeDocumentFixtureManifest(manifest)
        assertFalse(encoded.contains("password", ignoreCase = true))
        assertFalse(encoded.contains("username", ignoreCase = true))
        assertEquals(manifest, decodeDocumentFixtureManifest(encoded))
        assertEquals(plan, validateDocumentFixtureManifest(manifest, TEST_TARGET))
        assertFailsWith<IllegalArgumentException> {
            validateDocumentFixtureManifest(
                manifest.copy(planFingerprint = "sha256:${"0".repeat(64)}"),
                TEST_TARGET,
            )
        }

        val withUnknownKey = encoded.replaceFirst("{", "{\n  \"unknown\": 1,")
        assertFailsWith<IllegalArgumentException> {
            decodeDocumentFixtureManifest(withUnknownKey)
        }
    }

    @Test
    fun `manifest distinguishes ready completion from a partially seeded archive`() {
        val plan = documentFixturePlan("12345678-1234-4234-8234-1234567890ab")
        val partialArchive = plannedManifest(plan).copy(
            ownerUid = "fixture-owner",
            datasetId = "fixture-dataset",
            status = DocumentFixtureStatus.ARCHIVED,
            createdDocuments = 17,
            archivedAtEpochMs = 2L,
        )

        assertEquals(plan, validateDocumentFixtureManifest(partialArchive, TEST_TARGET))
        assertFailsWith<IllegalArgumentException> {
            validateDocumentFixtureManifest(
                partialArchive.copy(
                    status = DocumentFixtureStatus.READY,
                    archivedAtEpochMs = null,
                ),
                TEST_TARGET,
            )
        }
    }

    @Test
    fun `authority reconciliation fences a changed dataset before any mutation`() {
        val plan = documentFixturePlan("12345678-1234-4234-8234-1234567890ab")
        val admitted = plannedManifest(plan).copy(
            ownerUid = "original-owner",
            datasetId = "original-dataset",
            status = DocumentFixtureStatus.SEEDING,
        )

        val obsolete = reconcileDocumentFixtureAuthority(
            admitted,
            ownerUid = "replacement-owner",
            datasetId = "replacement-dataset",
        )
        assertEquals(DocumentFixtureStatus.OBSOLETE_DATASET, obsolete.status)
        assertEquals("original-owner", obsolete.ownerUid)
        assertEquals("original-dataset", obsolete.datasetId)
        assertEquals(plan, validateDocumentFixtureManifest(obsolete, TEST_TARGET))

        assertFailsWith<IllegalArgumentException> {
            reconcileDocumentFixtureAuthority(
                admitted,
                ownerUid = "wrong-owner",
                datasetId = "original-dataset",
            )
        }
    }

    @Test
    fun `target confirmation is exact`() {
        assertEquals(TEST_TARGET, confirmedDocumentFixtureTarget(TEST_TARGET, "im.virjar.com", 5100))
        assertFailsWith<IllegalArgumentException> {
            confirmedDocumentFixtureTarget("im.virjar.com:5101", "im.virjar.com", 5100)
        }
        assertFailsWith<IllegalArgumentException> {
            confirmedDocumentFixtureTarget(null, "im.virjar.com", 5100)
        }
    }

    @Test
    fun `private state accepts only owner-only account file and writes manifest first`() {
        val fixture = newPrivateFixtureTree("valid")
        fixture.storage.atomicTextFile(fileName = DOCUMENT_FIXTURE_ACCOUNT_FILE).replaceText(
            "username=fixture-user\npassword=secret123\n",
        )

        val files = DocumentFixtureFiles.open(fixture.projectRoot, fixture.stateDir)
        files.acquireLifecycleLock().use {
            val credentials = files.readCredentials()
            val manifest = files.loadOrCreateManifest(TEST_TARGET, nowEpochMs = 1L)

            assertEquals("fixture-user", credentials.username)
            assertEquals(DocumentFixtureStatus.PLANNED, manifest.status)
            assertEquals(150, manifest.documentCount)
            assertEquals(documentFixturePlanFingerprint(documentFixturePlan(manifest.fixtureId)), manifest.planFingerprint)
            assertTrue(Files.exists(files.manifestPath, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.readString(files.manifestPath).contains("secret123"))
        }
    }

    @Test
    fun `private state rejects broad account permissions`() {
        assumePosix()
        val fixture = newPrivateFixtureTree("broad")
        val account = fixture.storage.atomicTextFile(fileName = DOCUMENT_FIXTURE_ACCOUNT_FILE)
        account.replaceText("username=fixture-user\npassword=secret123\n")
        Files.setPosixFilePermissions(
            fixture.stateDir.resolve(DOCUMENT_FIXTURE_ACCOUNT_FILE),
            PosixFilePermissions.fromString("rw-r--r--"),
        )

        val files = DocumentFixtureFiles.open(fixture.projectRoot, fixture.stateDir)
        assertFailsWith<IllegalArgumentException> { files.acquireLifecycleLock() }
    }

    @Test
    fun `private state rejects symlink and hard-linked credential files`() {
        assumePosix()
        val symlinkFixture = newPrivateFixtureTree("symlink")
        val external = temporaryRoot.resolve("external-account.properties")
        Files.writeString(external, "username=fixture-user\npassword=secret123\n")
        Files.setPosixFilePermissions(external, PosixFilePermissions.fromString("rw-------"))
        Files.createSymbolicLink(
            symlinkFixture.stateDir.resolve(DOCUMENT_FIXTURE_ACCOUNT_FILE),
            external,
        )
        assertFails {
            DocumentFixtureFiles.open(symlinkFixture.projectRoot, symlinkFixture.stateDir).acquireLifecycleLock()
        }

        val hardLinkFixture = newPrivateFixtureTree("hard-link")
        val source = hardLinkFixture.storage.atomicTextFile(fileName = "credential-source.properties")
        source.replaceText("username=fixture-user\npassword=secret123\n")
        Files.createLink(
            hardLinkFixture.stateDir.resolve(DOCUMENT_FIXTURE_ACCOUNT_FILE),
            hardLinkFixture.stateDir.resolve("credential-source.properties"),
        )
        assertFails {
            DocumentFixtureFiles.open(hardLinkFixture.projectRoot, hardLinkFixture.stateDir).acquireLifecycleLock()
        }
    }

    @Test
    fun `invocation rejects a second holder before credential or manifest admission`() {
        val fixture = newPrivateFixtureTree("invocation-lock-order")
        val hiddenValue = "fixture-value-that-must-not-appear"
        fixture.storage.atomicTextFile(fileName = DOCUMENT_FIXTURE_ACCOUNT_FILE).replaceText(
            "username=fixture-user\npassword=secret123\nunknown=$hiddenValue\n",
        )
        val files = DocumentFixtureFiles.open(fixture.projectRoot, fixture.stateDir)

        files.acquireLifecycleLock().use {
            val failure = assertFailsWith<DocumentFixtureLockUnavailableException> {
                loadDocumentFixtureInvocation(
                    environment = fixtureEnvironment(fixture.stateDir),
                    systemProperties = fixtureSystemProperties(fixture.projectRoot),
                )
            }
            assertFalse(failure.message.orEmpty().contains(hiddenValue))
            assertFalse(Files.exists(files.manifestPath, LinkOption.NOFOLLOW_LINKS))
        }

        assertFailsWith<IllegalArgumentException> {
            loadDocumentFixtureInvocation(
                environment = fixtureEnvironment(fixture.stateDir),
                systemProperties = fixtureSystemProperties(fixture.projectRoot),
            )
        }
        files.acquireLifecycleLock().use { }
    }

    @Test
    fun `cross-process holder makes lifecycle lock fail closed without waiting`() {
        val fixture = newPrivateFixtureTree("cross-process-lock")
        val files = DocumentFixtureFiles.open(fixture.projectRoot, fixture.stateDir)
        files.acquireLifecycleLock().use { }
        val lockPath = fixture.stateDir.resolve(DOCUMENT_FIXTURE_LOCK_FILE)
        val holderSource = temporaryRoot.resolve("DocumentFixtureExternalLockHolder.java")
        Files.writeString(holderSource, externalLockHolderSource())
        val process = ProcessBuilder(
            javaExecutable(),
            holderSource.toString(),
            lockPath.toString(),
        ).redirectErrorStream(true).start()
        val signalExecutor = Executors.newSingleThreadExecutor()
        val signal = signalExecutor.submit<String?> { process.inputReader().readLine() }
        val release = process.outputWriter()
        try {
            assertEquals("LOCKED", signal.get(20, TimeUnit.SECONDS))
            assertFailsWith<DocumentFixtureLockUnavailableException> {
                files.acquireLifecycleLock()
            }
            release.write("\n")
            release.flush()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            runCatching {
                release.write("\n")
                release.flush()
                release.close()
            }
            if (process.isAlive) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            signalExecutor.shutdownNow()
        }
        files.acquireLifecycleLock().use { }
    }

    @Test
    fun `lifecycle lock rejects broad symlink and hard-linked paths`() {
        assumePosix()
        val broadFixture = newPrivateFixtureTree("broad-lock")
        val broadFiles = DocumentFixtureFiles.open(broadFixture.projectRoot, broadFixture.stateDir)
        broadFiles.acquireLifecycleLock().use { }
        Files.setPosixFilePermissions(
            broadFixture.stateDir.resolve(DOCUMENT_FIXTURE_LOCK_FILE),
            PosixFilePermissions.fromString("rw-r--r--"),
        )
        assertFailsWith<IllegalArgumentException> { broadFiles.acquireLifecycleLock() }

        val symlinkFixture = newPrivateFixtureTree("symlink-lock")
        val external = temporaryRoot.resolve("external-document-fixture.lock")
        Files.writeString(external, "")
        Files.setPosixFilePermissions(external, PosixFilePermissions.fromString("rw-------"))
        Files.createSymbolicLink(
            symlinkFixture.stateDir.resolve(DOCUMENT_FIXTURE_LOCK_FILE),
            external,
        )
        assertFails {
            DocumentFixtureFiles.open(symlinkFixture.projectRoot, symlinkFixture.stateDir).acquireLifecycleLock()
        }

        val hardLinkFixture = newPrivateFixtureTree("hard-link-lock")
        val source = hardLinkFixture.storage.preparePrivateFile(emptyList(), "lock-source")
        Files.createLink(
            hardLinkFixture.stateDir.resolve(DOCUMENT_FIXTURE_LOCK_FILE),
            source.toPath(),
        )
        assertFails {
            DocumentFixtureFiles.open(hardLinkFixture.projectRoot, hardLinkFixture.stateDir).acquireLifecycleLock()
        }
    }

    @Test
    fun `state directory inside repository is rejected`() {
        assumePosix()
        secureTempRoot()
        val project = temporaryRoot.resolve("repository")
        Files.createDirectory(project)
        Files.setPosixFilePermissions(project, PosixFilePermissions.fromString("rwx------"))
        val storage = JvmPrivateDataDirectory.createNew(project.resolve("fixture-state").toFile(), project.toFile())
        storage.atomicTextFile(fileName = DOCUMENT_FIXTURE_ACCOUNT_FILE).replaceText(
            "username=fixture-user\npassword=secret123\n",
        )

        assertFailsWith<IllegalArgumentException> {
            DocumentFixtureFiles.open(project, storage.root)
        }
    }

    private fun plannedManifest(plan: DocumentFixturePlan): DocumentFixtureManifest = DocumentFixtureManifest(
        schemaVersion = 2,
        generatorVersion = 1,
        fixtureId = plan.fixtureId,
        target = TEST_TARGET,
        spaceId = plan.spaceId,
        archiveOperationId = plan.archiveOperationId,
        planFingerprint = documentFixturePlanFingerprint(plan),
        documentCount = plan.nodes.size,
        rootCount = plan.rootCount,
        middleCount = plan.middleCount,
        leafCount = plan.leafCount,
        ownerUid = null,
        datasetId = null,
        status = DocumentFixtureStatus.PLANNED,
        createdDocuments = 0,
        createdAtEpochMs = 1L,
        completedAtEpochMs = null,
        archivedAtEpochMs = null,
        representatives = plan.representatives,
    )

    private fun <T> List<T>.updatedFirst(value: T): List<T> = listOf(value) + drop(1)

    private fun fixtureEnvironment(stateDir: Path): Map<String, String> = mapOf(
        "TK_E2E_FIXTURE_ACTION" to "seed",
        "TK_E2E_FIXTURE_STATE_DIR" to stateDir.toString(),
        "TK_E2E_CONFIRM_TARGET" to TEST_TARGET,
    )

    private fun fixtureSystemProperties(projectRoot: Path): Map<String, String> = mapOf(
        "tk.e2e.host" to "im.virjar.com",
        "tk.e2e.port" to "5100",
        "tk.e2e.projectRoot" to projectRoot.toString(),
    )

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
        return Path.of(System.getProperty("java.home"), "bin", executable).toString()
    }

    private fun externalLockHolderSource(): String = """
        import java.nio.channels.FileChannel;
        import java.nio.channels.FileLock;
        import java.nio.file.LinkOption;
        import java.nio.file.Path;
        import java.nio.file.StandardOpenOption;

        public final class DocumentFixtureExternalLockHolder {
            public static void main(String[] args) throws Exception {
                try (
                    FileChannel channel = FileChannel.open(
                        Path.of(args[0]),
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS
                    );
                    FileLock ignored = channel.lock()
                ) {
                    System.out.println("LOCKED");
                    System.out.flush();
                    System.in.read();
                }
            }
        }
    """.trimIndent()

    private fun newPrivateFixtureTree(name: String): PrivateFixtureTree {
        assumePosix()
        secureTempRoot()
        val project = temporaryRoot.resolve("project-$name")
        Files.createDirectory(project)
        val storage = JvmPrivateDataDirectory.createNew(
            temporaryRoot.resolve("state-$name").toFile(),
            temporaryRoot.toFile(),
        )
        return PrivateFixtureTree(project, storage.root, storage)
    }

    private fun secureTempRoot() {
        Files.setPosixFilePermissions(temporaryRoot, PosixFilePermissions.fromString("rwx------"))
    }

    private fun assumePosix() {
        assumeTrue(Files.getFileStore(temporaryRoot).supportsFileAttributeView("posix"))
    }

    private data class PrivateFixtureTree(
        val projectRoot: Path,
        val stateDir: Path,
        val storage: JvmPrivateDataDirectory,
    )

    companion object {
        private const val TEST_TARGET = "im.virjar.com:5100"
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.virjar.tk.ios

import com.virjar.tk.app.navigation.feature.document.*
import com.virjar.tk.shared.platform.*
import kotlinx.coroutines.*
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.*

/** Real Native files plus a held encoder exercise admission, commit and lifecycle ordering. */
class IosDocumentDraftPersistenceTest {
    private val owner = DocumentDraftOwnerKey("a".repeat(64), "12345678-1234-4234-8234-123456789abc", "alice")

    @Test
    fun editorFramesCoalesceOffCallerAndBarrierIncludesTheLatestFrame() = withStorage { storage, scope ->
        val writer = IosDocumentDraftPersistence(storage, scope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val encoded = PlatformAtomicLong(0)
        val caller = platformCurrentThreadId()
        assertTrue(writer.write(owner) {
            assertNotEquals(caller, platformCurrentThreadId())
            entered.complete(Unit)
            runBlocking { release.await() }
            payload("old")
        })
        entered.await()
        try {
            repeat(100) { index ->
                assertTrue(writer.write(owner) { encoded.incrementAndGet(); payload("frame-$index") })
            }
            val barrier = writer.requestFlush()
            assertFalse(barrier.isCompleted)
            assertEquals(0L, encoded.get())
            release.complete(Unit)
            assertTrue(barrier.await())
            assertEquals(1L, encoded.get())
            assertStored(storage, "frame-99")
        } finally { release.complete(Unit) }
    }

    @Test
    fun deleteWaitsForAdmittedEncodingAndCannotBeOverwrittenByItsLateResult() = withStorage { storage, scope ->
        val writer = IosDocumentDraftPersistence(storage, scope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        assertTrue(writer.write(owner) {
            entered.complete(Unit)
            runBlocking { release.await() }
            payload("must-not-return")
        })
        entered.await()
        val deleting = async(Dispatchers.Default) { writer.delete(owner) }
        try {
            release.complete(Unit)
            assertTrue(deleting.await())
            assertTrue(writer.awaitDurability())
            assertEquals(DocumentDraftReadStatus.ABSENT, storage.read(owner) { error("Deleted draft returned") })
            // A later authenticated workspace is allowed to create a fresh draft for this account.
            assertTrue(writer.write(owner) { payload("new-session") })
            assertTrue(writer.awaitDurability())
            assertStored(storage, "new-session")
        } finally { release.complete(Unit) }
    }

    @Test
    fun aTimedOutDeleteCannotLetAccountCleanupOvertakeTheStillRunningWriter() = withStorage { storage, scope ->
        val writer = IosDocumentDraftPersistence(storage, scope, barrierTimeoutMillis = 150)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        assertTrue(writer.write(owner) {
            entered.complete(Unit)
            runBlocking { release.await() }
            payload("late")
        })
        entered.await()
        val writing = writer.requestFlush()
        val deleting = async(Dispatchers.Default) { writer.delete(owner) }
        try {
            while (writer.requestFlush() === writing) yield()
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { writer.awaitQuiescence() }
            assertFalse(deleting.await())
            assertFalse(cleanup.await(), "A failed control barrier is not proof that disk work has exited")
            release.complete(Unit)
            assertTrue(writer.awaitQuiescence())
            assertTrue(withContext(Dispatchers.Default) { writer.delete(owner) })
            assertEquals(DocumentDraftReadStatus.ABSENT, storage.read(owner) { error("Deleted draft returned") })
        } finally { release.complete(Unit) }
    }

    @Test
    fun lifecycleBarrierAlsoWaitsForAnAdmittedDurableControl() = withStorage { storage, scope ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controlled = object : DocumentDraftPersistence by storage {
            override fun tombstone(ownerKey: DocumentDraftOwnerKey, recoveryKeys: Set<String>): Boolean {
                entered.complete(Unit)
                runBlocking { release.await() }
                return storage.tombstone(ownerKey, recoveryKeys)
            }
        }
        val writer = IosDocumentDraftPersistence(controlled, scope)
        assertTrue(writer.write(owner) { payload("preserved") })
        assertTrue(writer.awaitDurability())
        val retiring = async(Dispatchers.Default) { writer.tombstone(owner, setOf("tab-one")) }
        try {
            entered.await()
            val barrier = writer.requestFlush()
            assertFalse(barrier.isCompleted, "A lifecycle barrier cannot overtake a durable control")
            release.complete(Unit)
            assertTrue(retiring.await())
            assertTrue(barrier.await())
            assertEquals(DocumentDraftReadStatus.AVAILABLE, storage.read(owner) {
                assertEquals(setOf("tab-one"), it.tombstones)
            })
        } finally { release.complete(Unit) }
    }

    @Test
    fun durableTombstoneSurvivesLaterSnapshotsAndFailedReplacementKeepsOldRecords() = withStorage { storage, scope ->
        val writer = IosDocumentDraftPersistence(storage, scope)
        assertTrue(writer.write(owner) { payload("stable") })
        assertTrue(writer.awaitDurability())
        assertTrue(withContext(Dispatchers.Default) { writer.tombstone(owner, setOf("tab-one")) })
        assertTrue(writer.write(owner) { payload("after-cancel") })
        assertTrue(writer.awaitDurability())
        assertEquals(DocumentDraftReadStatus.AVAILABLE, storage.read(owner) {
            assertEquals(setOf("tab-one"), it.tombstones)
        })
        assertTrue(writer.write(owner) {
            DocumentDraftPayload("failed", listOf(
                DocumentDraftRecord("tab-one") { "new orphan" },
                DocumentDraftRecord("tab-two") { error("Injected record encoding failure") },
            ), setOf("tab-one", "tab-two"))
        })
        assertFalse(writer.awaitDurability())
        assertTrue(writer.awaitQuiescence(), "A failed, settled write must not prevent account cleanup")
        assertStored(storage, "after-cancel")
        assertTrue(writer.write(owner) { payload("recovered") })
        assertTrue(writer.awaitDurability())
        assertStored(storage, "recovered")
    }

    private fun payload(value: String) = DocumentDraftPayload(value,
        listOf(DocumentDraftRecord("tab-one") { value }), setOf("tab-one"))

    private fun assertStored(storage: DocumentDraftPersistence, expected: String) {
        assertEquals(DocumentDraftReadStatus.AVAILABLE, storage.read(owner) {
            assertEquals(expected, it.manifest)
            assertEquals(expected, it.readRecord("tab-one"))
        })
    }

    private fun withStorage(block: suspend CoroutineScope.(DocumentDraftPersistence, CoroutineScope) -> Unit) = runBlocking {
        val root = PlatformFile(NSTemporaryDirectory()).resolve("teamtalk-drafts-${platformRandomUuid()}")
        check(root.mkdirs())
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { withTimeout(20_000) { block(IosDocumentDraftStorage(root), writerScope) } }
        finally {
            writerScope.coroutineContext.job.cancelAndJoin()
            root.deleteRecursively()
        }
    }
}
